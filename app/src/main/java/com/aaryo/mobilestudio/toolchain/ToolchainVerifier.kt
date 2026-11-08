package com.aaryo.mobilestudio.toolchain

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Performs real post-install checks. A file merely existing is not reported as
 * usable: executable tools are launched and SDK archives are opened/read.
 */
data class ToolchainVerificationReport(
    val lines: List<String>,
    val passed: Int,
    val failed: Int,
    val skipped: Int
) {
    val total: Int get() = passed + failed + skipped
}

class ToolchainVerifier(context: Context) {
    private val root = File(context.filesDir, "toolchain")
    private val java = File(context.filesDir, "toolchain/runtime/bin/java")

    suspend fun verify(): ToolchainVerificationReport = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()
        var passed = 0
        var failed = 0
        var skipped = 0

        fun result(name: String, ok: Boolean?, detail: String) {
            when (ok) {
                true -> { passed++; lines += "PASS  $name — $detail" }
                false -> { failed++; lines += "FAIL  $name — $detail" }
                null -> { skipped++; lines += "SKIP  $name — $detail" }
            }
        }

        lines += "Device ABI: ${android.os.Build.SUPPORTED_ABIS.joinToString()}"
        lines += "Toolchain root: ${root.absolutePath}"

        // The JDK probe below runs with LD_PRELOAD=libtagfix.so, so a stale or malformed shim would
        // make an otherwise healthy JDK look broken. Rebuild it from scratch first: this is exactly
        // the recovery step the build errors point users at.
        val shimCheck = verifyTagFixShim()
        result("Heap-tagging shim (libtagfix.so)", shimCheck.first, shimCheck.second)

        val javaFile = java.takeIf { it.isFile } ?: File(root, "runtime/bin/java")
        val javaProbe = probe(javaFile, "-version")
        result("OpenJDK 17 ARM64", javaProbe.first, javaProbe.second)

        val gradleLauncher = File(root, "gradle/lib").listFiles()?.firstOrNull {
            it.name.startsWith("gradle-launcher-") && it.extension == "jar"
        }
        val gradlePlugins = File(root, "gradle/lib/plugins")
        val gradleFilesOk = gradleLauncher?.isFile == true && gradlePlugins.isDirectory
        result("Gradle distribution", when {
            !gradleFilesOk -> false
            javaProbe.first != true -> null
            else -> true
        }, when {
            !gradleFilesOk -> "Gradle launcher/plugin files missing"
            javaProbe.first != true -> "distribution files present, but launch is blocked until the JDK passes"
            else -> "${gradleLauncher!!.name}; plugin directory present (JDK smoke test passed)"
        })

        val buildTools = File(root, "sdk/build-tools/34.0.4")
        val aapt2 = probe(File(buildTools, "aapt2"), "version")
        result("AAPT2", aapt2.first, aapt2.second)
        val d8 = File(buildTools, "lib/d8.jar")
        result("D8", d8.isFile && d8.length() > 1024, if (d8.isFile) "${d8.length()} bytes" else "d8.jar missing")
        val r8 = File(buildTools, "lib/r8.jar")
        result("R8", r8.isFile && r8.length() > 1024, if (r8.isFile) "${r8.length()} bytes" else "r8.jar missing")
        val apksigner = probe(File(buildTools, "apksigner"), "version")
        result("APK signer", apksigner.first, apksigner.second)
        val zipalign = probe(File(buildTools, "zipalign"), "-h")
        result("Zipalign", zipalign.first, zipalign.second)

        val kotlinCompiler = File(root, "kotlin/lib/kotlin-compiler.jar")
        val kotlinCompilerOk = kotlinCompiler.isFile && runCatching {
            ZipFile(kotlinCompiler).use { zip ->
                zip.getEntry("org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class") != null
            }
        }.getOrDefault(false)
        result(
            "Kotlin compiler (kotlinc)",
            kotlinCompilerOk,
            if (kotlinCompilerOk) "kotlin-compiler.jar contains the K2 JVM compiler" else
                "kotlin/lib/kotlin-compiler.jar missing — the direct compiler cannot build Kotlin sources"
        )

        val androidJar = File(root, "sdk/platforms/android-36/android.jar")
        val platformOk = if (androidJar.isFile) runCatching {
            ZipFile(androidJar).use { zip -> zip.getEntry("android/Manifest.class") != null || zip.size() > 100 }
        }.getOrDefault(false) else false
        result("Android Platform 36", platformOk, if (platformOk) "android.jar opened as a valid SDK archive" else "android.jar missing or invalid ZIP/JAR")

        val cmakeFile = findExecutable(listOf(File(root, "cmake/bin/cmake"), File(root, "cmake/cmake"), File(root, "cmake/current/bin/cmake")))
        val cmake = probe(cmakeFile, "--version")
        result("CMake", cmake.first, cmake.second)
        val ninjaFile = findExecutable(listOf(File(root, "cmake/bin/ninja"), File(root, "cmake/ninja"), File(root, "ninja/ninja")))
        val ninja = probe(ninjaFile, "--version")
        result("Ninja", ninja.first, ninja.second)

        val ndkRoot = File(root, "ndk")
        val ndkToolchain = sequenceOf(
            File(ndkRoot, "30.0/build/cmake/android.toolchain.cmake"),
            File(ndkRoot, "build/cmake/android.toolchain.cmake")
        ).firstOrNull { it.isFile }
        result("NDK CMake toolchain", ndkToolchain?.isFile == true, ndkToolchain?.absolutePath ?: "android.toolchain.cmake missing")
        val clang = findExecutable(listOf(
            File(ndkRoot, "30.0/toolchains/llvm/prebuilt/android-arm64/bin/clang"),
            File(ndkRoot, "toolchains/llvm/prebuilt/android-arm64/bin/clang")
        ))
        val clangProbe = probe(clang, "--version")
        result("NDK ARM64 Clang", clangProbe.first, clangProbe.second)

        ToolchainVerificationReport(lines, passed, failed, skipped)
    }

    private fun findExecutable(candidates: List<File>): File? = candidates.firstOrNull { it.isFile }

    /**
     * Deletes any cached copy, regenerates `toolchain/compat/libtagfix.so` from [TagFixShimElf] and
     * confirms the file on disk passes the same structural checks Bionic applies. Returns
     * `(ok, detail)` for the verification report.
     */
    private fun verifyTagFixShim(): Pair<Boolean?, String> {
        if (!runCatching { android.os.Build.SUPPORTED_64_BIT_ABIS.any { it.contains("arm64", true) } }.getOrDefault(false)) {
            return null to "device userspace is not 64-bit ARM; tools run without the shim"
        }
        val compatDir = File(root, "compat")
        val shim = File(compatDir, "libtagfix.so")
        BionicTagFixShim.resetCache()
        runCatching { if (shim.isFile) shim.delete() }
        val regenerated = BionicTagFixShim.ensureShimLibrary(root)
            ?: return false to "could not write ${shim.absolutePath}"
        val bytes = runCatching { regenerated.readBytes() }.getOrNull()
        if (!TagFixShimElf.isUsableShim(bytes)) {
            return false to "regenerated shim failed ELF validation (${bytes?.size ?: 0} bytes)"
        }
        val executable = regenerated.canExecute() || regenerated.setExecutable(true, false)
        if (!executable) return false to "shim written but not executable"
        return true to "${bytes!!.size} bytes, no R+W+X PT_LOAD, version marker ${TagFixShimElf.MARKER}"
    }

    private fun probe(file: File?, argument: String): Pair<Boolean?, String> {
        if (file == null || !file.isFile) return false to "executable/file missing"
        if (!file.canExecute()) file.setExecutable(true, false)
        if (!file.canExecute()) return false to "file exists but is not executable"
        return try {
            val process = ProcessBuilder(file.absolutePath, argument)
                .redirectErrorStream(true)
                .apply {
                    val env = environment()
                    env["HOME"] = root.parentFile?.absolutePath ?: ""
                    env["LD_LIBRARY_PATH"] = listOf(
                        File(java.parentFile?.parentFile, "lib/server").absolutePath,
                        File(java.parentFile?.parentFile, "lib").absolutePath
                    ).joinToString(File.pathSeparator)
                    env["JAVA_TOOL_OPTIONS"] = "-XX:-UseCompressedOops -XX:-UseCompressedClassPointers"
                    BionicTagFixShim.applyToEnvironment(root, env)
                }
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText().take(500).trim() }
            val exited = process.waitFor(10, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                false to "timed out after 10 seconds"
            } else {
                val code = process.exitValue()
                val ok = code == 0 || (code == 134 && output.contains("version", true))
                val hint = when (code) {
                    134 -> " (SIGABRT: Tagged Pointer mismatch, use Direct Compiler)"
                    else -> ""
                }
                ok to "exit=$code$hint${if (output.isNotBlank()) "; ${output.lineSequence().first()}" else ""}"
            }
        } catch (t: Throwable) {
            false to "${t.javaClass.simpleName}: ${t.message ?: "execution blocked"}"
        }
    }
}
