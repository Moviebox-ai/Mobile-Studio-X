package com.aaryo.mobilestudio.toolchain

import java.io.File

class Aapt2Engine(toolchainRoot: File, runner: ToolProcessRunner = ToolProcessRunner()) {
    private val tools = StandaloneAndroidTools(toolchainRoot, runner)

    suspend fun compile(
        resDirectory: File,
        compiledResourcesZip: File,
        workingDirectory: File,
        onLine: (String) -> Unit = {}
    ): File = tools.compileResources(resDirectory, compiledResourcesZip, workingDirectory, onLine)

    suspend fun link(
        compiledResourcesZip: File,
        manifest: File,
        outputAp: File,
        workingDirectory: File,
        manifestPackage: String? = null,
        packageId: String? = null,
        onLine: (String) -> Unit = {}
    ): File = tools.linkResources(
        compiledResourcesZip, manifest, outputAp, workingDirectory,
        manifestPackage, packageId, onLine
    )
}

class D8Engine(toolchainRoot: File, runner: ToolProcessRunner = ToolProcessRunner()) {
    private val tools = StandaloneAndroidTools(toolchainRoot, runner)

    suspend fun compileDex(
        inputs: List<File>,
        outputDirectory: File,
        minApi: Int,
        workingDirectory: File,
        onLine: (String) -> Unit = {}
    ): File = tools.dex(inputs, outputDirectory, minApi, workingDirectory, onLine)
}

class R8Engine(toolchainRoot: File, runner: ToolProcessRunner = ToolProcessRunner()) {
    private val tools = StandaloneAndroidTools(toolchainRoot, runner)

    suspend fun shrinkAndDex(
        programInputs: List<File>,
        libraryInputs: List<File>,
        rulesFile: File,
        outputDirectory: File,
        minApi: Int,
        workingDirectory: File,
        onLine: (String) -> Unit = {}
    ): File = tools.shrink(
        programInputs, libraryInputs, rulesFile, outputDirectory, minApi, workingDirectory, onLine
    )
}
