package com.aaryo.mobilestudio.toolchain

import java.io.File

/**
 * Direct entry points for Android SDK Build Tools.
 */
class StandaloneAndroidTools(
    private val toolchainRoot: File,
    private val runner: ToolProcessRunner = ToolProcessRunner()
) {
    private val java get() = File(toolchainRoot, "runtime/bin/java")
    private val buildToolsRoot get() = File(toolchainRoot, "sdk/build-tools")
    private val platformRoot get() = File(toolchainRoot, "sdk/platforms")

    private fun newestVersionDirectory(parent: File): File? =
        parent.listFiles()?.filter { it.isDirectory }?.maxWithOrNull { a, b ->
            val av = Regex("\\d+").findAll(a.name).mapNotNull { it.value.toIntOrNull() }.toList()
            val bv = Regex("\\d+").findAll(b.name).mapNotNull { it.value.toIntOrNull() }.toList()
            var comparison = 0
            for (i in 0 until maxOf(av.size, bv.size)) {
                comparison = (av.getOrNull(i) ?: 0).compareTo(bv.getOrNull(i) ?: 0)
                if (comparison != 0) break
            }
            if (comparison == 0) a.name.compareTo(b.name) else comparison
        }

    private fun latestBuildTools(): File = newestVersionDirectory(buildToolsRoot)
        ?: error("Android Build Tools are not installed.")

    private fun androidJar(): File = newestVersionDirectory(platformRoot)
        ?.resolve("android.jar")?.takeIf { it.isFile }
        ?: error("Android SDK platform android.jar is missing.")

    suspend fun compileResources(
        resourceDirectory: File,
        outputArchive: File,
        workingDirectory: File,
        onLine: (String) -> Unit = {}
    ): File {
        require(resourceDirectory.isDirectory) { "Resource directory does not exist." }
        val aapt2 = File(latestBuildTools(), "aapt2").requireExecutable()
        outputArchive.parentFile?.mkdirs()
        runner.requireSuccess(
            listOf(aapt2.absolutePath, "compile", "--dir", resourceDirectory.absolutePath,
                "-o", outputArchive.absolutePath),
            workingDirectory, onLine = onLine
        )
        check(outputArchive.isFile) { "AAPT2 did not create ${outputArchive.absolutePath}" }
        return outputArchive
    }

    suspend fun linkResources(
        compiledResources: File,
        manifest: File,
        outputAp: File,
        workingDirectory: File,
        manifestPackage: String? = null,
        packageId: String? = null,
        onLine: (String) -> Unit = {}
    ): File {
        require(compiledResources.isFile) { "Compiled resources archive is missing." }
        require(manifest.isFile) { "AndroidManifest.xml is missing." }
        val aapt2 = File(latestBuildTools(), "aapt2").requireExecutable()
        outputAp.parentFile?.mkdirs()

        val manifestContent = manifest.readText()
        val hasPackageAttr = manifestContent.contains(Regex("""package\s*=""", RegexOption.IGNORE_CASE))
        val effectiveManifest = if (!hasPackageAttr) {
            val pkg = manifestPackage?.takeIf { it.isNotBlank() } ?: "com.aaryo.mobilestudio"
            val tempManifest = File(outputAp.parentFile, "AndroidManifest.xml")
            val newContent = if (manifestContent.contains("<manifest", ignoreCase = true)) {
                manifestContent.replaceFirst(Regex("""<manifest\b""", RegexOption.IGNORE_CASE), "<manifest package=\"$pkg\"")
            } else {
                manifestContent
            }
            tempManifest.writeText(newContent)
            tempManifest
        } else {
            manifest
        }

        val jar = androidJar()
        require(jar.isFile) { "android.jar not found: ${jar.absolutePath}" }
        jar.setReadable(true, false)

        val args = mutableListOf(aapt2.absolutePath, "link", "-o", outputAp.absolutePath,
            "-I", jar.absolutePath, "--manifest", effectiveManifest.absolutePath,
            "--min-sdk-version", "29", compiledResources.absolutePath)
        if (!manifestPackage.isNullOrBlank()) {
            args.addAll(listOf("--rename-manifest-package", manifestPackage))
        }
        if (!packageId.isNullOrBlank()) args.addAll(listOf("--package-id", packageId))
        runner.requireSuccess(args, workingDirectory, onLine = onLine)
        check(outputAp.isFile) { "AAPT2 did not create ${outputAp.absolutePath}" }
        return outputAp
    }

    suspend fun dex(
        programInputs: List<File>,
        outputDirectory: File,
        minApi: Int,
        workingDirectory: File,
        onLine: (String) -> Unit = {}
    ): File {
        require(programInputs.isNotEmpty() && programInputs.all { it.exists() }) {
            "D8 requires existing class/jar inputs."
        }
        require(minApi >= 21) { "Android min API must be at least 21." }
        val d8Jar = File(latestBuildTools(), "lib/d8.jar").takeIf { it.isFile }
            ?: error("D8 jar is missing.")
        val javaExe = java.requireExecutable()
        outputDirectory.mkdirs()
        val args = mutableListOf(javaExe.absolutePath,
            "-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers",
            "-cp", d8Jar.absolutePath,
            "com.android.tools.r8.D8", "--release", "--min-api", minApi.toString(),
            "--lib", androidJar().absolutePath, "--output", outputDirectory.absolutePath)
        args.addAll(programInputs.map { it.absolutePath })
        runner.requireSuccess(args, workingDirectory, onLine = onLine)
        check(outputDirectory.listFiles()?.any { it.extension == "dex" } == true) {
            "D8 completed but produced no .dex files."
        }
        return outputDirectory
    }

    suspend fun shrink(
        programInputs: List<File>,
        libraryInputs: List<File>,
        rulesFile: File,
        outputDirectory: File,
        minApi: Int,
        workingDirectory: File,
        onLine: (String) -> Unit = {}
    ): File {
        require(programInputs.isNotEmpty() && programInputs.all { it.exists() }) {
            "R8 requires existing program inputs."
        }
        require(libraryInputs.all { it.exists() }) {
            "An R8 library input does not exist."
        }
        require(rulesFile.isFile) { "R8 rules file is missing." }
        val r8Jar = File(latestBuildTools(), "lib/r8.jar").takeIf { it.isFile }
            ?: error("R8 jar is missing.")
        val javaExe = java.requireExecutable()
        outputDirectory.mkdirs()
        val args = mutableListOf(javaExe.absolutePath,
            "-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers",
            "-cp", r8Jar.absolutePath,
            "com.android.tools.r8.R8", "--release", "--min-api", minApi.toString(),
            "--output", outputDirectory.absolutePath, "--pg-conf", rulesFile.absolutePath,
            "--lib", androidJar().absolutePath)
        libraryInputs.forEach { args.addAll(listOf("--lib", it.absolutePath)) }
        args.addAll(programInputs.map { it.absolutePath })
        runner.requireSuccess(args, workingDirectory, onLine = onLine)
        check(outputDirectory.listFiles()?.any { it.extension == "dex" } == true) {
            "R8 completed but produced no .dex files."
        }
        return outputDirectory
    }

    private fun File.requireExecutable(): File {
        require(isFile) { "Required tool is missing: $absolutePath" }
        if (!canExecute()) setExecutable(true, false)
        require(canExecute()) { "Tool is not executable: $absolutePath" }
        return this
    }
}
