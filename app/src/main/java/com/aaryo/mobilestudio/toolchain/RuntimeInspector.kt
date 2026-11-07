package com.aaryo.mobilestudio.toolchain

import android.content.Context
import java.io.File

data class RuntimeStatus(
    val javaExecutable: File? = null,
    val gradleLauncher: File? = null,
    val aapt2: File? = null,
    val d8: File? = null,
    val r8: File? = null,
    val apksigner: File? = null,
    val zipalign: File? = null,
    val cmake: File? = null,
    val ninja: File? = null,
    val ndkToolchainFile: File? = null,
    val androidJar: File? = null,
    val kotlinCompiler: File? = null,
    /**
     * The JDK is on disk but its `java -version` smoke test failed during installation
     * (`RuntimeInstaller` writes a marker so a broken runtime is never advertised as ready).
     */
    val javaVerificationFailed: Boolean = false,
    /** Short description of why the JDK is not usable, for the UI and the build log. */
    val javaIssue: String? = null
) {
    private val javaReady: Boolean
        get() = javaExecutable?.let { it.isFile && it.canExecute() } == true && !javaVerificationFailed

    val readyForGradle get() = javaReady && gradleLauncher?.isFile == true
    val readyForAapt2 get() = aapt2?.isFile == true && androidJar?.isFile == true
    val readyForStandaloneDex get() = javaReady && (d8?.isFile == true || r8?.isFile == true) && androidJar?.isFile == true
    val readyForNativeBuild get() = cmake?.isFile == true && ninja?.isFile == true && ndkToolchainFile?.isFile == true

    /** The direct compiler needs a working JVM, AAPT2, D8, apksigner, zipalign and android.jar. */
    val readyForDirectBuild get() = javaReady &&
        aapt2?.isFile == true && androidJar?.isFile == true && d8?.isFile == true &&
        apksigner?.isFile == true && zipalign?.isFile == true

    /** One-line JDK state for the "Toolchains & Runtime" card. */
    val javaSummary: String
        get() = when {
            javaVerificationFailed -> "Installed, but the startup test failed"
            javaExecutable == null -> "Not installed"
            javaExecutable.canExecute() -> javaExecutable.parentFile?.parentFile?.name ?: "ready"
            else -> "Found, but not executable"
        }
}

class RuntimeInspector(context: Context) {
    private val root = File(context.filesDir, "toolchain")

    fun inspect(): RuntimeStatus {
        // A previous installer version could mark a JDK as installed even when its
        // java -version smoke test failed. Do not advertise that runtime as build-ready,
        // but do not hide it either: "Missing" is the wrong message for a JDK that is
        // present and broken, because it sends people looking for a download they already have.
        val verificationError = AndroidToolchain.jdkVerificationError(root)
        val java = AndroidToolchain.findJava(root)
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
            javaExecutable = java,
            gradleLauncher = launcher,
            aapt2 = buildTools?.resolve("aapt2")?.takeIf { it.isFile },
            d8 = buildTools?.resolve("lib/d8.jar")?.takeIf { it.isFile },
            r8 = r8File,
            apksigner = buildTools?.resolve("apksigner")?.takeIf { it.isFile },
            zipalign = buildTools?.resolve("zipalign")?.takeIf { it.isFile },
            cmake = cmake,
            ninja = ninja,
            ndkToolchainFile = ndkToolchain,
            androidJar = platform?.resolve("android.jar")?.takeIf { it.isFile },
            kotlinCompiler = listOf(
                File(root, "kotlin/lib/kotlin-compiler.jar"),
                File(root, "kotlinc/lib/kotlin-compiler.jar")
            ).firstOrNull { it.isFile },
            javaVerificationFailed = verificationError != null,
            javaIssue = verificationError
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
