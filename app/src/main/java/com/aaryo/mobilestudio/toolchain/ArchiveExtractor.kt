package com.aaryo.mobilestudio.toolchain

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.ZipFile

data class ExtractResult(val entries: Int, val bytes: Long)

/**
 * Safe, bounded extractor for ZIP, TAR.XZ, and single FILE toolchain archives.
 */
class ArchiveExtractor(
    private val maxEntries: Int = 100_000,
    private val maxBytes: Long = 8L * 1024 * 1024 * 1024,
    private val checkCancelled: () -> Unit = {},
    private val onBytes: (Long) -> Unit = {}
) {
    private var entries = 0
    private var bytes = 0L
    private var nextReport = REPORT_STEP
    private val seen = HashSet<String>()

    fun extract(archive: File, format: ArchiveFormat, root: File, targetPath: String, strip: Int): ExtractResult {
        val rootCanonical = root.canonicalFile
        val target = resolve(rootCanonical, targetPath.trim('/'))
        when (format) {
            ArchiveFormat.FILE -> {
                countEntry(targetPath)
                target.parentFile?.let { mkdirs(it) }
                archive.inputStream().use { writeFile(it, target) }
            }
            ArchiveFormat.ZIP -> { mkdirs(target); extractZip(archive, target, strip) }
            ArchiveFormat.TAR_XZ -> { mkdirs(target); extractTarXz(archive, target, strip) }
            ArchiveFormat.TAR_GZ -> { mkdirs(target); extractTarGz(archive, target, strip) }
        }
        return ExtractResult(entries, bytes)
    }

    private fun extractZip(archive: File, base: File, strip: Int) {
        ZipFile(archive).use { zip ->
            val all = zip.entries()
            while (all.hasMoreElements()) {
                checkCancelled()
                val e = all.nextElement()
                val rel = relative(e.name, strip) ?: continue
                countEntry(rel)
                val target = resolve(base, rel)
                if (e.isDirectory) {
                    mkdirs(target)
                } else {
                    target.parentFile?.let { mkdirs(it) }
                    zip.getInputStream(e).use { writeFile(it, target) }
                    if (target.name in setOf("cmake", "ninja", "java", "aapt2", "zipalign", "apksigner") ||
                        target.parentFile?.name == "bin") {
                        target.setExecutable(true, false)
                    }
                }
            }
        }
    }

    private fun extractTarXz(archive: File, base: File, strip: Int) {
        System.gc()
        val tarStream = try {
            val raw = archive.inputStream().buffered(64 * 1024)
            TarArchiveInputStream(XZCompressorInputStream(raw))
        } catch (oom: OutOfMemoryError) {
            System.gc()
            throw IOException(
                "Decompressing ${archive.name} exceeded memory. Ensure largeHeap is enabled and close background apps.",
                oom
            )
        }
        tarStream.use { tar ->
            extractTarEntries(tar, base, strip)
        }
    }

    private fun extractTarGz(archive: File, base: File, strip: Int) {
        archive.inputStream().buffered(64 * 1024).use { raw ->
            TarArchiveInputStream(java.util.zip.GZIPInputStream(raw, 64 * 1024)).use { tar ->
                extractTarEntries(tar, base, strip)
            }
        }
    }

    private fun extractTarEntries(tar: TarArchiveInputStream, base: File, strip: Int) {
        while (true) {
            checkCancelled()
            val e = (tar.nextEntry ?: break) as TarArchiveEntry
            val rel = relative(e.name, strip) ?: continue
            countEntry(rel)
            val target = resolve(base, rel)
            when {
                e.isDirectory -> mkdirs(target)
                e.isSymbolicLink -> makeSymlink(base, target, e.linkName)
                e.isLink -> {
                    val srcRel = relative(e.linkName, strip)
                        ?: throw IOException("Bad hard link target: ${e.linkName}")
                    val src = resolve(base, srcRel)
                    check(src.isFile) { "Hard link source missing: ${e.linkName}" }
                    target.parentFile?.let { mkdirs(it) }
                    src.inputStream().use { writeFile(it, target) }
                    if (e.mode and EXEC_BITS != 0) target.setExecutable(true, false)
                }
                e.isFile -> {
                    target.parentFile?.let { mkdirs(it) }
                    writeFile(tar, target)
                    if (e.mode and EXEC_BITS != 0) target.setExecutable(true, false)
                }
                else -> Unit
            }
        }
    }

    private fun makeSymlink(base: File, link: File, linkName: String) {
        if (linkName.isBlank() || linkName.startsWith("/") || linkName.contains('\\')) {
            return
        }
        link.parentFile?.let { mkdirs(it) }
        val resolved = runCatching { link.parentFile!!.canonicalFile.toPath().resolve(linkName).normalize() }.getOrNull()
            ?: return
        if (!resolved.startsWith(base.canonicalFile.toPath())) {
            return
        }
        try {
            link.delete()
            Files.createSymbolicLink(link.toPath(), Paths.get(linkName))
        } catch (_: Throwable) {
            runCatching {
                val targetFile = resolved.toFile()
                if (targetFile.isFile) {
                    targetFile.copyTo(link, overwrite = true)
                }
            }
        }
    }

    /** Drops [strip] leading segments; null when nothing is left (the stripped folder itself). */
    private fun relative(raw: String, strip: Int): String? {
        val name = raw.replace('\\', '/')
        require(name.isNotBlank() && !name.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(name)) {
            "Unsafe archive path rejected: $raw"
        }
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        require(parts.none { it == ".." }) { "Unsafe archive path rejected: $raw" }
        val effectiveStrip = if (strip > 0 && parts.isNotEmpty() && parts[0] in setOf("bin", "lib", "include", "share", "build")) 0 else strip
        if (parts.size <= effectiveStrip) return null
        return parts.drop(effectiveStrip).joinToString("/")
    }

    private fun resolve(base: File, rel: String): File {
        val target = File(base, rel).canonicalFile
        require(target.toPath().startsWith(base.canonicalFile.toPath())) { "Archive path escapes target directory: $rel" }
        return target
    }

    private fun countEntry(rel: String) {
        entries++
        require(entries <= maxEntries) { "Archive contains too many entries." }
        require(seen.add(rel)) { "Duplicate archive entry rejected: $rel" }
    }

    private fun writeFile(input: InputStream, target: File) {
        target.outputStream().buffered(64 * 1024).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                bytes += n
                require(bytes <= maxBytes) { "Archive exceeds the expanded-size limit." }
                out.write(buffer, 0, n)
                if (bytes >= nextReport) {
                    nextReport = bytes + REPORT_STEP
                    onBytes(bytes)
                    checkCancelled()
                }
            }
        }
    }

    private fun mkdirs(dir: File) {
        check(dir.mkdirs() || dir.isDirectory) { "Cannot create directory: $dir" }
    }

    companion object {
        private const val REPORT_STEP = 5L * 1024 * 1024
        private const val EXEC_BITS = 0b001_001_001
    }
}
