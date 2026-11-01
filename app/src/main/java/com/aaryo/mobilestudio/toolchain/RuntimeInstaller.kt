package com.aaryo.mobilestudio.toolchain

import android.content.Context
import android.net.Uri
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

data class InstallReport(
    val installedRoot: File,
    val filesInstalled: Int,
    val expandedBytes: Long,
    val detectedComponents: List<String>
)

/** Bounded, staged installation of toolchain components. */
class RuntimeInstaller(private val context: Context) {
    private val destination = File(context.filesDir, "toolchain")

    suspend fun installFromZip(uri: Uri): InstallReport = withContext(Dispatchers.IO) {
        val staging = File(context.filesDir, "toolchain-staging")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Cannot create toolchain staging directory." }
        var entries = 0
        var expandedBytes = 0L
        val seen = HashSet<String>()
        try {
            require(StatFs(context.filesDir.absolutePath).availableBytes >= MIN_FREE_SPACE) {
                "At least 512 MB free app storage is required."
            }
            val source = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot open selected toolchain ZIP.")
            ZipInputStream(BufferedInputStream(source)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    require(entries <= MAX_ENTRIES) { "Archive contains too many entries." }
                    val raw = entry.name.replace('\\', '/')
                    require(raw.isNotBlank() && !raw.startsWith("/") &&
                        !Regex("^[A-Za-z]:").containsMatchIn(raw) &&
                        raw.split('/').none { it == ".." || it == "." }) {
                        "Unsafe archive path rejected: $raw"
                    }
                    val normalized = raw.trimEnd('/')
                    val relative = when {
                        normalized == "toolchain" -> ""
                        normalized.startsWith("toolchain/") -> normalized.removePrefix("toolchain/")
                        else -> normalized
                    }
                    if (relative.isNotBlank()) {
                        require(seen.add(relative)) { "Duplicate archive entry rejected: $relative" }
                        val target = File(staging, relative).canonicalFile
                        require(target.toPath().startsWith(staging.canonicalFile.toPath())) {
                            "Archive path escapes staging directory."
                        }
                        if (entry.isDirectory) {
                            check(target.mkdirs() || target.isDirectory) { "Cannot create $relative" }
                        } else {
                            target.parentFile?.let { check(it.mkdirs() || it.isDirectory) }
                            target.outputStream().buffered().use { output ->
                                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                                while (true) {
                                    val n = zip.read(buffer)
                                    if (n < 0) break
                                    expandedBytes += n
                                    require(expandedBytes <= MAX_EXPANDED_BYTES) {
                                        "Toolchain archive exceeds 8 GiB expanded-size limit."
                                    }
                                    output.write(buffer, 0, n)
                                }
                            }
                        }
                    }
                    zip.closeEntry()
                }
            }
            activateStaging(staging, entries, expandedBytes)
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    /**
     * Validates an extracted toolchain tree and atomically activates it.
     */
    fun activateStaging(staging: File, entries: Int, expandedBytes: Long): InstallReport {
        try {
            require(entries > 0 && staging.walkTopDown().any { it.isFile }) { "Toolchain is empty." }
            markExecutables(staging)
            validateMandatoryComponents(staging)
            installExecutableRuntime(staging)

            val backup = File(context.filesDir, "toolchain-backup")
            backup.deleteRecursively()
            val hadOld = destination.exists()
            if (hadOld && !destination.renameTo(backup)) throw IOException("Cannot backup existing toolchain.")
            if (!staging.renameTo(destination)) {
                if (hadOld && backup.exists()) backup.renameTo(destination)
                throw IOException("Cannot activate new toolchain; previous toolchain restored.")
            }
            backup.deleteRecursively()
            return InstallReport(destination, entries, expandedBytes, detectComponents(destination))
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    private fun markExecutables(root: File) {
        val exact = setOf(
            "java", "aapt2", "apksigner", "zipalign", "cmake", "ninja", "clang", "clang++",
            "ld.lld", "lld", "make", "llvm-ar", "llvm-ranlib", "llvm-strip", "llvm-objcopy",
            "llvm-nm", "llvm-readelf", "llvm-as", "llvm-link", "llvm-config", "llvm-addr2line",
            "llvm-strings", "llvm-size", "llvm-symbolizer", "llvm-profdata", "llvm-cov"
        )
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            if (file.name in exact || file.parentFile?.name == "bin" ||
                Regex("^(clang|clang\\+\\+)-[0-9]+(\\.[0-9]+)*$").matches(file.name) ||
                Regex("^llvm-[A-Za-z0-9_.+-]+$").matches(file.name)) {
                file.setExecutable(true, false)
            }
        }
        runCatching {
            Runtime.getRuntime().exec(arrayOf("chmod", "-R", "755", root.absolutePath)).waitFor()
        }
    }


    /**
     * Smoke-tests the extracted JDK in place (staging dir, which is renamed to
     * filesDir/toolchain on activation). Works because the app targets SDK 28;
     * with targetSdk >= 29 Android blocks execve() from app-private storage.
     */
    private fun installExecutableRuntime(staging: File) {
        val runtime = File(staging, "runtime")
        require(runtime.isDirectory) { "JDK runtime directory is missing." }
        // Remove leftover copy from older builds that used code_cache.
        File(context.codeCacheDir, "mobilestudio-jdk").deleteRecursively()
        runtime.walkTopDown().filter { it.isFile && (it.parentFile?.name == "bin" || it.name == "java" || it.name == "jspawnhelper") }
            .forEach { it.setExecutable(true, false) }
        val java = File(runtime, "bin/java")
        check(java.isFile) { "runtime/bin/java missing after extraction." }
        requireAarch64Elf(java, "runtime/bin/java")
        val marker = File(staging, ".mobilestudio-jdk-verification-failed.txt")
        // A runtime that cannot launch must never be activated as an installed toolchain.
        // Previously this exception was swallowed and the broken JDK appeared "ready".
        try {
            verifyJava17(java)
            marker.delete()
        } catch (error: Exception) {
            marker.writeText(
                "JDK verification failed; this runtime was NOT activated.\n" +
                    "${error.javaClass.simpleName}: ${error.message}\n"
            )
            throw IOException(
                "Android ARM64 JDK verification failed. Choose a Bionic-compatible Android JDK 17 archive. " +
                    "Details: ${error.message}", error
            )
        }
    }

    private fun validateMandatoryComponents(root: File) {
        val java = File(root, "runtime/bin/java")
        require(java.isFile) { "Android-host JDK missing: runtime/bin/java" }
        requireAarch64Elf(java, "runtime/bin/java")

        val gradleLib = File(root, "gradle/lib")
        val launcherExists = File(gradleLib, "gradle-launcher-9.3.1.jar").isFile
        require(launcherExists) {
            "Gradle 9.3.1 is required by this project's AGP 9.1.1. " +
                "Install the Gradle 9.3.1 Android-host distribution in toolchain/gradle."
        }
        require(File(gradleLib, "plugins").isDirectory || gradleLib.isDirectory) {
            "Gradle plugin libraries missing: gradle/lib/"
        }
        require(File(root, "sdk/platforms/android-36/android.jar").isFile) {
            "Android SDK Platform 36 missing: sdk/platforms/android-36/android.jar"
        }

        val buildTools = File(root, "sdk/build-tools").listFiles()?.filter { it.isDirectory }.orEmpty()
        val valid = buildTools.firstOrNull { dir ->
            val aapt = File(dir, "aapt2")
            aapt.isFile && File(dir, "lib/d8.jar").isFile &&
                File(dir, "apksigner").isFile && File(dir, "zipalign").isFile
        } ?: throw IOException("Build Tools incomplete: require aapt2, D8, apksigner and zipalign.")
        requireAarch64Elf(File(valid, "aapt2"), "sdk/build-tools/<version>/aapt2")
    }

    private fun verifyJava17(java: File) {
        val releaseFile = File(java.parentFile?.parentFile, "release")
        val releaseText = if (releaseFile.isFile) runCatching { releaseFile.readText() }.getOrDefault("") else ""
        require(releaseText.contains("JAVA_VERSION=\"17", ignoreCase = true) ||
            releaseText.contains("JAVA_VERSION=17", ignoreCase = true)) {
            "The imported runtime must be JDK 17. Runtime release metadata was not Java 17."
        }
        // Keep the Android process linker namespace intact. Do not prepend JDK lib
        // directories to LD_LIBRARY_PATH: this can shadow Android system libraries
        // and create unresolved symbols under Bionic. Android-host JDK builds must
        // carry their own correct runtime search metadata.
        val runtimeHome = java.parentFile?.parentFile
            ?: throw IOException("Invalid JDK layout: expected runtime/bin/java.")
        val process = try {
            ProcessBuilder(java.absolutePath, "-version")
                .redirectErrorStream(true)
                .apply {
                    environment()["JAVA_HOME"] = runtimeHome.absolutePath
                    environment().remove("JAVA_TOOL_OPTIONS")
                    environment().remove("_JAVA_OPTIONS")
                    environment().remove("JDK_JAVA_OPTIONS")
                    environment().remove("LD_PRELOAD")
                }
                .start()
        } catch (e: IOException) {
            val detail = e.message ?: e.javaClass.simpleName
            throw IOException(
                "Cannot launch Android ARM64 JDK at ${java.absolutePath}: $detail. " +
                    "Check that the archive is a Bionic/Android build (not desktop Linux) and all JDK .so libraries are present.",
                e
            )
        }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IOException("JDK startup timed out. The runtime may be incompatible with this Android device.")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        // Accept "17", "17.0.x", and vendor builds such as "17-internal" or "17+35".
        require(process.exitValue() == 0 && Regex("version \"17([.\\-+_\"]|$)").containsMatchIn(output)) {
            "JDK startup validation failed (exit ${process.exitValue()}). Install an Android/Bionic ARM64 JDK 17. Output: $output"
        }
    }

    private fun requireAarch64Elf(file: File, label: String) {
        file.inputStream().use { input ->
            val h = ByteArray(20)
            var n = 0
            while (n < h.size) {
                val read = input.read(h, n, h.size - n)
                if (read < 0) break
                n += read
            }
            require(n == 20 && h[0] == 0x7f.toByte() && h[1] == 'E'.code.toByte() &&
                h[2] == 'L'.code.toByte() && h[3] == 'F'.code.toByte()) {
                "$label must be an ELF executable."
            }
            val machine = (h[18].toInt() and 0xff) or ((h[19].toInt() and 0xff) shl 8)
            require(machine == 183) {
                "$label must be AArch64/ARM64 ELF (machine=183); found machine=$machine."
            }
        }
    }

    private fun detectComponents(root: File): List<String> = buildList {
        add("Android-host ARM64 JDK")
        add("Gradle distribution")
        add("Android SDK platform")
        add("Android Build Tools: AAPT2/D8/R8/apksigner/zipalign")
        if (File(root, "ndk").isDirectory) add("Android NDK toolchain")
        if (File(root, "cmake").isDirectory) add("CMake and Ninja build tools")
    }

    companion object {
        private const val MAX_ENTRIES = 100_000
        private const val MAX_EXPANDED_BYTES = 8L * 1024 * 1024 * 1024
        private const val MIN_FREE_SPACE = 512L * 1024 * 1024
    }
}
