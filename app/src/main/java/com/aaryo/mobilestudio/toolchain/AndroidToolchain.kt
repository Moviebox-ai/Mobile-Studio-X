package com.aaryo.mobilestudio.toolchain

import android.content.Context
import java.io.File

/**
 * Locations of the real Android build tools used by the on-device compiler.
 *
 * Every field is nullable on purpose: the compiler reports exactly which tool is missing
 * instead of silently producing a broken APK.
 */
data class AndroidToolchain(
    val root: File,
    val java: File?,
    val javac: File?,
    val aapt2: File?,
    val zipalign: File?,
    val dexerJar: File?,
    val apksignerBinary: File?,
    val apksignerJar: File?,
    val androidJar: File?,
    val kotlinLibJars: List<File>,
    val kotlinStdlibJar: File?,
    val buildToolsVersion: String?
) {
    val hasApksigner: Boolean get() = apksignerBinary?.isFile == true || apksignerJar?.isFile == true

    /** Human readable list of missing components for the current source mix. */
    fun missingComponents(kotlinSourceCount: Int): List<String> = buildList {
        if (java?.isFile != true) add("Android ARM64 JDK 17 (runtime/bin/java)")
        if (javac?.isFile != true) add("Java compiler • javac (runtime/bin/javac, shipped with the JDK 17 runtime)")
        if (aapt2?.isFile != true) add("Android Build Tools • AAPT2 (sdk/build-tools/<version>/aapt2)")
        if (androidJar?.isFile != true) add("Android SDK Platform • android.jar (sdk/platforms/android-*/android.jar)")
        if (dexerJar?.isFile != true) add("DEX compiler • d8.jar (sdk/build-tools/<version>/lib/d8.jar)")
        if (zipalign?.isFile != true) add("zipalign (sdk/build-tools/<version>/zipalign)")
        if (!hasApksigner) add("apksigner (sdk/build-tools/<version>/lib/apksigner.jar)")
        if (kotlinSourceCount > 0 && kotlinLibJars.isEmpty()) {
            add("Kotlin compiler • kotlinc (toolchain/kotlin/lib/kotlin-compiler.jar)")
        }
        if (kotlinSourceCount > 0 && kotlinStdlibJar?.isFile != true) {
            add("Kotlin standard library (toolchain/kotlin/lib/kotlin-stdlib.jar)")
        }
    }

    companion object {
        private const val KOTLIN_COMPILER_MAIN = "kotlin-compiler.jar"

        fun resolve(context: Context): AndroidToolchain = resolve(File(context.filesDir, "toolchain"))

        fun resolve(root: File): AndroidToolchain {
            val java = findJava(root)
            val javac = java?.parentFile?.resolve("javac")?.takeIf { it.isFile }

            val buildTools = newestVersionDirectory(File(root, "sdk/build-tools"))
            val platform = newestVersionDirectory(File(root, "sdk/platforms"))
            val androidJar = platform?.resolve("android.jar")?.takeIf { it.isFile }
                ?: runCatching {
                    File(root, "sdk/platforms").walkTopDown().maxDepth(3)
                        .filter { it.isFile && it.name == "android.jar" }
                        .maxByOrNull { it.lastModified() }
                }.getOrNull()

            val libDir = buildTools?.resolve("lib")
            val dexerJar = listOf(
                libDir?.resolve("d8.jar"),
                libDir?.resolve("r8.jar")
            ).firstOrNull { it?.isFile == true }
            val kotlinLibDir = listOf(
                File(root, "kotlin/lib"),
                File(root, "kotlinc/lib"),
                File(root, "kotlin-compiler/lib")
            ).firstOrNull { it.isDirectory }
            val kotlinLibJars = kotlinLibDir?.listFiles()
                ?.filter { it.isFile && it.extension == "jar" }
                ?.sortedBy { it.name }
                .orEmpty()
            val kotlinCompiler = kotlinLibJars.firstOrNull { it.name == KOTLIN_COMPILER_MAIN }
                ?: kotlinLibJars.firstOrNull { it.name.startsWith("kotlin-compiler") }
            val usableKotlinJars = if (kotlinCompiler == null) emptyList() else kotlinLibJars

            return AndroidToolchain(
                root = root,
                java = java,
                javac = javac,
                aapt2 = buildTools?.resolve("aapt2")?.takeIf { it.isFile },
                zipalign = buildTools?.resolve("zipalign")?.takeIf { it.isFile },
                dexerJar = dexerJar,
                apksignerBinary = buildTools?.resolve("apksigner")?.takeIf { it.isFile },
                apksignerJar = libDir?.resolve("apksigner.jar")?.takeIf { it.isFile },
                androidJar = androidJar,
                kotlinLibJars = usableKotlinJars,
                kotlinStdlibJar = kotlinLibDir?.resolve("kotlin-stdlib.jar")?.takeIf { it.isFile },
                buildToolsVersion = buildTools?.name
            )
        }

        /**
         * Every plausible `java` executable inside the toolchain root, best candidate first.
         *
         * Installers have used `runtime/bin/java` and `jdk/<slot>/bin/java` over time, and an
         * imported archive sometimes nests the runtime one level deeper. The inspector, the JDK
         * manager and the Gradle engine must all agree on what "the JDK" is, otherwise the UI
         * reports a JDK as missing while builds quietly use one (or the other way round).
         */
        fun javaCandidates(toolchainRoot: File): List<File> = buildList {
            add(File(toolchainRoot, "runtime/bin/java"))
            File(toolchainRoot, "jdk").listFiles().orEmpty()
                .filter { it.isDirectory }
                .sortedBy { it.name }
                .forEach { add(File(it, "bin/java")) }
            runCatching {
                File(toolchainRoot, "runtime").walkTopDown().maxDepth(3)
                    .filter { it.isFile && it.name == "java" && it.parentFile?.name == "bin" }
                    .toList()
            }.getOrDefault(emptyList()).sortedBy { it.absolutePath }.let { addAll(it) }
        }.distinctBy { it.absolutePath }.filter { it.isFile }

        fun findJava(toolchainRoot: File): File? = javaCandidates(toolchainRoot).firstOrNull()

        /** Marker written by RuntimeInstaller when the JDK smoke test failed on this device. */
        fun jdkVerificationMarker(toolchainRoot: File): File =
            File(toolchainRoot, ".mobilestudio-jdk-verification-failed.txt")

        fun jdkVerificationError(toolchainRoot: File): String? {
            val marker = jdkVerificationMarker(toolchainRoot)
            if (!marker.isFile) return null
            val text = runCatching { marker.readText() }.getOrDefault("")
            val detail = text.lines().firstOrNull { it.isNotBlank() && !it.startsWith("JDK verification failed") }
            return (detail ?: "the installed JDK could not start on this device").trim()
        }

        /**
         * Picks the newest `34.0.4`-style version directory. Versions are compared numerically
         * per segment so `36.0.0` wins over `9.0.0`.
         */
        internal fun newestVersionDirectory(parent: File?): File? =
            parent?.listFiles()?.filter { it.isDirectory }?.maxWithOrNull { a, b -> compareVersions(a.name, b.name) }

        internal fun compareVersions(a: String, b: String): Int {
            val av = Regex("\\d+").findAll(a).mapNotNull { it.value.toIntOrNull() }.toList()
            val bv = Regex("\\d+").findAll(b).mapNotNull { it.value.toIntOrNull() }.toList()
            for (i in 0 until maxOf(av.size, bv.size)) {
                val cmp = (av.getOrNull(i) ?: 0).compareTo(bv.getOrNull(i) ?: 0)
                if (cmp != 0) return cmp
            }
            return a.compareTo(b)
        }
    }
}
