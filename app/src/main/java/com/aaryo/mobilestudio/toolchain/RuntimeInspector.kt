package com.aaryo.mobilestudio.toolchain

import android.content.Context
import java.io.File

data class RuntimeStatus(
    val javaExecutable: File?, val gradleLauncher: File?, val aapt2: File?,
    val d8: File?, val r8: File?, val apksigner: File?, val zipalign: File?,
    val cmake: File?, val ninja: File?, val ndkToolchainFile: File?, val androidJar: File?
) {
    val readyForGradle get() = javaExecutable?.let { it.isFile && it.canExecute() } == true && gradleLauncher?.isFile == true
    val readyForAapt2 get() = aapt2?.isFile == true && androidJar?.isFile == true
    val readyForStandaloneDex get() = javaExecutable?.isFile == true && (d8?.isFile == true || r8?.isFile == true) && androidJar?.isFile == true
    val readyForNativeBuild get() = cmake?.isFile == true && ninja?.isFile == true && ndkToolchainFile?.isFile == true
}

class RuntimeInspector(context: Context) {
    private val root = File(context.filesDir, "toolchain")
    private val executableJava = File(context.filesDir, "toolchain/runtime/bin/java")

    fun inspect(): RuntimeStatus {
        // A previous installer version could mark a JDK as installed even when its
        // java -version smoke test failed. Do not advertise that runtime as build-ready.
        val failedVerification = File(root, ".mobilestudio-jdk-verification-failed.txt")
        val java = executableJava.takeIf { it.isFile && !failedVerification.isFile }
        val gradleLib = File(root, "gradle/lib")
        val launcher = gradleLib.listFiles()?.firstOrNull { it.name.startsWith("gradle-launcher-") && it.extension == "jar" }
        val buildTools = newest(File(root, "sdk/build-tools"))
        val platform = newest(File(root, "sdk/platforms"))
        val ndk = newest(File(root, "ndk"))

        val cmake = (listOf(
            File(root, "cmake/bin/cmake"),
            File(root, "cmake/cmake"),
            File(root, "cmake/current/bin/cmake"),
            File(root, "sdk/cmake/bin/cmake")
        ) + File(root, "cmake").listFiles().orEmpty().map { File(it, "bin/cmake") }
          + File(root, "cmake").listFiles().orEmpty().filter { it.name == "cmake" && it.isFile }
          + runCatching { File(root, "cmake").walkTopDown().maxDepth(5).filter { it.name == "cmake" && it.isFile }.toList() }.getOrDefault(emptyList())
        ).firstOrNull { it.isFile }

        val ninja = (listOf(
            File(root, "ninja/ninja"),
            File(root, "cmake/bin/ninja"),
            File(root, "cmake/ninja"),
            File(root, "cmake/current/bin/ninja")
        ) + File(root, "cmake").listFiles().orEmpty().map { File(it, "bin/ninja") }
          + File(root, "cmake").listFiles().orEmpty().filter { it.name == "ninja" && it.isFile }
          + runCatching { File(root, "cmake").walkTopDown().maxDepth(5).filter { it.name == "ninja" && it.isFile }.toList() }.getOrDefault(emptyList())
        ).firstOrNull { it.isFile }

        val ndkToolchain = (listOf(
            ndk?.resolve("build/cmake/android.toolchain.cmake"),
            File(root, "ndk/build/cmake/android.toolchain.cmake"),
            File(root, "ndk/30.0/build/cmake/android.toolchain.cmake")
        ) + File(root, "ndk").listFiles().orEmpty().map { File(it, "build/cmake/android.toolchain.cmake") }
          + runCatching { File(root, "ndk").walkTopDown().maxDepth(5).filter { it.name == "android.toolchain.cmake" && it.isFile }.toList() }.getOrDefault(emptyList())
        ).firstOrNull { it?.isFile == true }

        val r8File = (listOf(
            buildTools?.resolve("lib/r8.jar"),
            File(root, "sdk/build-tools/34.0.4/lib/r8.jar")
        ) + runCatching { File(root, "sdk").walkTopDown().maxDepth(5).filter { it.name == "r8.jar" && it.isFile }.toList() }.getOrDefault(emptyList())
        ).firstOrNull { it?.isFile == true }

        return RuntimeStatus(
            java, launcher,
            buildTools?.resolve("aapt2")?.takeIf { it.isFile },
            buildTools?.resolve("lib/d8.jar")?.takeIf { it.isFile },
            r8File,
            buildTools?.resolve("apksigner")?.takeIf { it.isFile },
            buildTools?.resolve("zipalign")?.takeIf { it.isFile },
            cmake,
            ninja,
            ndkToolchain,
            platform?.resolve("android.jar")?.takeIf { it.isFile }
        )
    }

    private fun newest(parent: File): File? = parent.listFiles()?.filter { it.isDirectory }?.maxWithOrNull { a, b ->
        val av = Regex("\\d+").findAll(a.name).mapNotNull { it.value.toIntOrNull() }.toList()
        val bv = Regex("\\d+").findAll(b.name).mapNotNull { it.value.toIntOrNull() }.toList()
        var cmp = 0
        for (i in 0 until maxOf(av.size, bv.size)) {
            cmp = (av.getOrNull(i) ?: 0).compareTo(bv.getOrNull(i) ?: 0)
            if (cmp != 0) break
        }
        if (cmp == 0) a.name.compareTo(b.name) else cmp
    }
}
