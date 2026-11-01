package com.aaryo.mobilestudio.toolchain

import java.io.File

/** Read-only inventory of Android SDK packages available to the Android-host toolchain. */
data class SdkPackageStatus(
    val id: String,
    val title: String,
    val installed: Boolean,
    val detail: String
)

data class SdkInventoryReport(
    val sdkRoot: File,
    val packages: List<SdkPackageStatus>,
    val platforms: List<String>,
    val buildTools: List<String>,
    val commandLineTools: List<String>,
    val platformTools: Boolean
) {
    val installedCount: Int get() = packages.count { it.installed }
    val totalCount: Int get() = packages.size
}

class SdkInventory(private val toolchainRoot: File) {
    private val sdkRoot = File(toolchainRoot, "sdk")

    fun inspect(): SdkInventoryReport {
        val platforms = childDirectories(File(sdkRoot, "platforms"))
            .filter { File(File(sdkRoot, "platforms/$it"), "android.jar").isFile }
        val buildTools = childDirectories(File(sdkRoot, "build-tools"))
            .filter { version ->
                val dir = File(sdkRoot, "build-tools/$version")
                File(dir, "aapt2").isFile && File(dir, "zipalign").isFile
            }
        val cmdlineRoot = File(sdkRoot, "cmdline-tools")
        val cmdline = childDirectories(cmdlineRoot).filter { File(cmdlineRoot, "$it/bin/sdkmanager").isFile }
        val platformToolsDir = File(sdkRoot, "platform-tools")
        val platformTools = File(platformToolsDir, "adb").isFile

        val packages = listOf(
            SdkPackageStatus("platform", "Android SDK Platforms", platforms.isNotEmpty(), platforms.joinToString().ifBlank { "No android.jar found" }),
            SdkPackageStatus("build-tools", "Android SDK Build Tools", buildTools.isNotEmpty(), buildTools.joinToString().ifBlank { "AAPT2 / zipalign not found" }),
            SdkPackageStatus("cmdline-tools", "Android SDK Command-line Tools", cmdline.isNotEmpty(), cmdline.joinToString().ifBlank { "sdkmanager not installed" }),
            SdkPackageStatus("platform-tools", "Android SDK Platform Tools", platformTools, if (platformTools) "adb detected" else "adb not installed"),
            SdkPackageStatus("licenses", "Android SDK Licenses", File(sdkRoot, "licenses").listFiles()?.any { it.isFile } == true, "License files are checked locally; acceptance is not inferred from package presence")
        )
        return SdkInventoryReport(sdkRoot, packages, platforms, buildTools, cmdline, platformTools)
    }

    private fun childDirectories(parent: File): List<String> =
        parent.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
}
