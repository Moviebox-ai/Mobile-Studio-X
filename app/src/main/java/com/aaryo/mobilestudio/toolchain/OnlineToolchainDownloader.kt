package com.aaryo.mobilestudio.toolchain

import android.content.Context
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long?,
    val message: String
) {
    val fraction: Float? get() = totalBytes?.takeIf { it > 0L }
        ?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
}

private class FatalDownloadException(message: String) : RuntimeException(message)

/**
 * Downloads every component of [ToolchainCatalog], verifies SHA-256, extracts,
 * reuses already installed components, and activates the toolchain.
 */
class OnlineToolchainDownloader(
    private val context: Context,
    private val catalog: List<ToolchainComponent> = ToolchainCatalog.components
) {
    suspend fun downloadAndInstallAll(
        installer: RuntimeInstaller,
        onProgress: (DownloadProgress) -> Unit
    ): InstallReport = withContext(Dispatchers.IO) {
        val problems = ToolchainCatalog.configurationProblems(catalog)
        require(problems.isEmpty()) { "Toolchain sources are not configured:\n" + problems.joinToString("\n") }
        val freeBytes = runCatching { StatFs(context.filesDir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)
        require(freeBytes >= MIN_FREE_SPACE) {
            "At least 500 MB free app storage is required for the toolchain."
        }

        val ctx = currentCoroutineContext()
        val cancel = { ctx.ensureActive() }
        val cacheDir = File(context.cacheDir, "toolchain-downloads").apply {
            check(mkdirs() || isDirectory) { "Cannot create download cache." }
        }
        val staging = File(context.filesDir, "toolchain-staging")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Cannot create toolchain staging directory." }

        val destination = File(context.filesDir, "toolchain")
        val overallTotal = catalog.sumOf { it.sizeBytes }
        var completed = 0L
        var entries = 0
        var expanded = 0L
        try {
            catalog.forEachIndexed { index, c ->
                cancel()
                val label = "[${index + 1}/${catalog.size}] ${c.title}"
                if (isComponentInstalled(c, destination)) {
                    onProgress(DownloadProgress(completed + c.sizeBytes, overallTotal, "$label: already installed, reusing files..."))
                    val (cEntries, cBytes) = copyExistingComponent(c, destination, staging)
                    entries += cEntries
                    expanded += cBytes
                    completed += c.sizeBytes
                    return@forEachIndexed
                }
                val archive = fetch(c, cacheDir, cancel) { copied, _ ->
                    onProgress(DownloadProgress(completed + copied.coerceAtMost(c.sizeBytes), overallTotal, "$label: downloading..."))
                }
                onProgress(DownloadProgress(completed + c.sizeBytes, overallTotal, "$label: extracting..."))
                System.gc()
                val result = try {
                    ArchiveExtractor(
                        maxBytes = MAX_EXPANDED_BYTES - expanded,
                        checkCancelled = cancel,
                        onBytes = { onProgress(DownloadProgress(completed + c.sizeBytes, overallTotal, "$label: extracting ${it / (1024 * 1024)} MB...")) }
                    ).extract(archive, c.format, staging, c.targetPath, c.stripComponents)
                } catch (oom: OutOfMemoryError) {
                    System.gc()
                    throw IOException(
                        "Memory limit reached while extracting ${c.title}. The downloaded archive is saved in cache. Close other apps and retry.",
                        oom
                    )
                }
                entries += result.entries
                expanded += result.bytes
                completed += c.sizeBytes
                System.gc()
            }
            onProgress(DownloadProgress(overallTotal, overallTotal, "Validating toolchain..."))
            val report = installer.activateStaging(staging, entries, expanded)
            cacheDir.deleteRecursively()
            onProgress(DownloadProgress(overallTotal, overallTotal, "Toolchain archives checksum-verified and extracted; executable smoke tests are pending."))
            report
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    private fun fetch(
        c: ToolchainComponent,
        cacheDir: File,
        cancel: () -> Unit,
        report: (Long, Long?) -> Unit
    ): File {
        val file = File(cacheDir, "${c.id}.bin")
        val part = File(cacheDir, "${c.id}.part")
        if (file.isFile) {
            if (c.sha256 != null && sha256(file, cancel) == c.sha256) return file
            file.delete()
        }
        var lastError: IOException? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            cancel()
            try {
                transfer(c, part, cancel, report)
                lastError = null
                break
            } catch (e: IOException) {
                lastError = e
                if (attempt < MAX_ATTEMPTS) {
                    Thread.sleep(1_500L * attempt)
                    cancel()
                }
            }
        }
        lastError?.let { throw IOException("${c.title}: ${it.message ?: "network error"} (tap the button again to resume).", it) }

        c.sha256?.let { expected ->
            val actual = sha256(part, cancel)
            if (actual != expected) {
                part.delete()
                throw FatalDownloadException("${c.title}: checksum mismatch (expected $expected, got $actual). File discarded.")
            }
        }
        if (!part.renameTo(file)) {
            part.copyTo(file, overwrite = true)
            part.delete()
        }
        return file
    }

    private fun transfer(c: ToolchainComponent, part: File, cancel: () -> Unit, report: (Long, Long?) -> Unit) {
        val existing = if (part.isFile) part.length() else 0L
        var currentUrl = c.url
        var connection: HttpURLConnection? = null
        var redirects = 0

        while (redirects < 5) {
            connection?.disconnect()
            val urlObj = URL(currentUrl)
            require(urlObj.protocol.equals("https", ignoreCase = true)) {
                "Download redirect downgraded HTTPS; refusing insecure redirect."
            }
            connection = (urlObj.openConnection() as HttpURLConnection).apply {
                connectTimeout = 25_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept", "application/octet-stream, */*")
                setRequestProperty("User-Agent", "Mobile Studio X-ToolchainInstaller/2.0")
                if (existing > 0L) setRequestProperty("Range", "bytes=$existing-")
            }
            val code = connection.responseCode
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                if (!location.isNullOrBlank()) {
                    currentUrl = if (location.startsWith("http://") || location.startsWith("https://")) {
                        location
                    } else {
                        URL(urlObj, location).toString()
                    }
                    redirects++
                    continue
                }
            }
            if (existing > 0L && code == 200) {
                part.delete()
                transfer(c, part, cancel, report)
                return
            }
            if (existing > 0L && code != 206) {
                part.delete()
                transfer(c, part, cancel, report)
                return
            }
            if (existing == 0L && code != 200) {
                throw IOException("Server returned HTTP $code")
            }
            break
        }

        val conn = connection ?: throw IOException("Cannot connect to server.")
        val append = existing > 0L && conn.responseCode == 206
        val totalLength = conn.contentLengthLong.takeIf { it > 0 }
            ?.let { if (append) it + existing else it }

        part.parentFile?.mkdirs()
        try {
            var copied = existing
            conn.inputStream.buffered(64 * 1024).use { input ->
                FileOutputStream(part, append).buffered(64 * 1024).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        copied += n
                        require(copied <= MAX_DOWNLOAD_BYTES) { "Download exceeds the 8 GiB limit." }
                        output.write(buffer, 0, n)
                        report(copied, totalLength)
                        cancel()
                    }
                }
            }
            if (copied == 0L) throw IOException("The server returned an empty file.")
            if (totalLength != null && copied != totalLength) {
                throw IOException("Download incomplete ($copied of $totalLength bytes).")
            }
        } finally {
            connection?.disconnect()
        }
    }

    private fun sha256(file: File, cancel: () -> Unit): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
                cancel()
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isComponentInstalled(c: ToolchainComponent, dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        return when (c.id) {
            "jdk17" -> File(dir, "runtime/bin/java").isFile
            "gradle" -> File(dir, "gradle/lib").listFiles()?.any { it.name.startsWith("gradle-launcher-") && it.extension == "jar" } == true
            "build-tools" -> File(dir, "sdk/build-tools/34.0.4/aapt2").isFile && File(dir, "sdk/build-tools/34.0.4/lib/d8.jar").isFile
            "kotlin" -> File(dir, "kotlin/lib/kotlin-compiler.jar").isFile &&
                File(dir, "kotlin/lib/kotlin-stdlib.jar").isFile
            "r8" -> File(dir, "sdk/build-tools/34.0.4/lib/r8.jar").isFile
            "platform-36" -> File(dir, "sdk/platforms/android-36/android.jar").isFile
            "cmake-ninja" -> (File(dir, "cmake/bin/cmake").isFile || File(dir, "cmake/cmake").isFile) &&
                             (File(dir, "cmake/bin/ninja").isFile || File(dir, "cmake/ninja").isFile)
            "ndk-r30" -> File(dir, "ndk/30.0/build/cmake/android.toolchain.cmake").isFile ||
                         File(dir, "ndk/build/cmake/android.toolchain.cmake").isFile
            else -> false
        }
    }

    private fun copyExistingComponent(c: ToolchainComponent, sourceDir: File, staging: File): Pair<Int, Long> {
        val src = when (c.id) {
            "jdk17" -> File(sourceDir, "runtime")
            "gradle" -> File(sourceDir, "gradle")
            "build-tools" -> File(sourceDir, "sdk/build-tools/34.0.4")
            "kotlin" -> File(sourceDir, "kotlin")
            "r8" -> File(sourceDir, "sdk/build-tools/34.0.4/lib/r8.jar")
            "platform-36" -> File(sourceDir, "sdk/platforms/android-36/android.jar")
            "cmake-ninja" -> File(sourceDir, "cmake")
            "ndk-r30" -> if (File(sourceDir, "ndk/30.0").isDirectory) File(sourceDir, "ndk/30.0") else File(sourceDir, "ndk")
            else -> File(sourceDir, c.targetPath)
        }
        val dst = when (c.id) {
            "jdk17" -> File(staging, "runtime")
            "gradle" -> File(staging, "gradle")
            "build-tools" -> File(staging, "sdk/build-tools/34.0.4")
            "kotlin" -> File(staging, "kotlin")
            "r8" -> File(staging, "sdk/build-tools/34.0.4/lib/r8.jar")
            "platform-36" -> File(staging, "sdk/platforms/android-36/android.jar")
            "cmake-ninja" -> File(staging, "cmake")
            "ndk-r30" -> File(staging, "ndk/30.0")
            else -> File(staging, c.targetPath)
        }
        if (src.isFile) {
            dst.parentFile?.mkdirs()
            src.copyTo(dst, overwrite = true)
            return 1 to src.length()
        } else if (src.isDirectory) {
            dst.mkdirs()
            src.copyRecursively(dst, overwrite = true)
            var count = 0
            var bytes = 0L
            dst.walkTopDown().forEach {
                count++
                if (it.isFile) bytes += it.length()
            }
            return count to bytes
        }
        return 0 to 0L
    }

    companion object {
        private const val MAX_DOWNLOAD_BYTES = 8L * 1024 * 1024 * 1024
        private const val MAX_EXPANDED_BYTES = 8L * 1024 * 1024 * 1024
        private const val MIN_FREE_SPACE = 500L * 1024 * 1024
        private const val MAX_ATTEMPTS = 3
    }
}
