package com.aaryo.mobilestudio.toolchain

import android.content.Context
import java.io.File

enum class JdkVersion(val displayName: String, val majorVersion: Int, val minAgpRequirement: String) {
    JDK_8("JDK 8", 8, "AGP 3.x - 4.x"),
    JDK_11("JDK 11 (LTS)", 11, "AGP 7.0+"),
    JDK_17("JDK 17 (LTS)", 17, "AGP 8.0+ / 9.0+"),
    JDK_21("JDK 21 (LTS)", 21, "AGP 8.5+ / 9.x");

    companion object {
        fun fromMajor(major: Int): JdkVersion = when (major) {
            8 -> JDK_8
            11 -> JDK_11
            17 -> JDK_17
            21 -> JDK_21
            else -> JDK_17
        }
    }
}

data class JdkInstallation(
    val version: JdkVersion,
    val homeDirectory: File,
    val javaExecutable: File,
    val isInstalled: Boolean,
    val isVerified: Boolean,
    val releaseString: String? = null
)

class JdkManager(private val context: Context, private val runner: ToolProcessRunner = ToolProcessRunner()) {
    private val toolchainRoot = File(context.filesDir, "toolchain")
    private val jdkDir = File(toolchainRoot, "jdk")

    init {
        jdkDir.mkdirs()
    }

    /**
     * Returns all known JDK slots and their installation state on device.
     *
     * "Installed" and "verified" are deliberately separate: a JDK archive can be extracted but
     * still abort on this device (tagged-pointer/Scudo crashes). Previously a JDK living at any
     * path other than `toolchain/runtime/bin/java` was reported as "Missing" in the UI even while
     * the Gradle engine happily used it, so both sides now share [AndroidToolchain.javaCandidates].
     */
    fun listInstallations(): List<JdkInstallation> {
        val verificationError = AndroidToolchain.jdkVerificationError(toolchainRoot)
        val defaultRuntime = File(toolchainRoot, "runtime")
        val defaultJava = File(defaultRuntime, "bin/java").takeIf { it.isFile }
            ?: AndroidToolchain.findJava(toolchainRoot)

        return JdkVersion.entries.map { v ->
            val slotDir = File(jdkDir, v.name.lowercase())
            val specificJava = File(slotDir, "bin/java").takeIf { it.isFile }
            val isSpecific = specificJava != null
            val effectiveJava = specificJava ?: defaultJava
            val isTarget = effectiveJava != null && (isSpecific || v == JdkVersion.JDK_17)
            val home = effectiveJava?.parentFile?.parentFile
                ?: if (isSpecific) slotDir else defaultRuntime
            val markerApplies = verificationError != null &&
                home.absolutePath == defaultRuntime.absolutePath
            val verified = isTarget && !markerApplies &&
                effectiveJava != null && effectiveJava.canExecute()

            JdkInstallation(
                version = v,
                homeDirectory = home,
                javaExecutable = effectiveJava ?: File(home, "bin/java"),
                isInstalled = isTarget,
                isVerified = verified,
                releaseString = verificationError.takeIf { markerApplies }
            )
        }
    }

    /** Resolves active JDK for a given project or returns active default JDK 17. */
    fun getActiveJdk(projectDir: File? = null): JdkInstallation {
        val selectedVersion = projectDir?.let { readProjectJdkPreference(it) } ?: JdkVersion.JDK_17
        val installations = listInstallations()
        return installations.firstOrNull { it.version == selectedVersion && it.isInstalled && it.isVerified }
            ?: installations.firstOrNull { it.version == selectedVersion && it.isInstalled }
            ?: installations.firstOrNull { it.isInstalled && it.isVerified }
            ?: installations.firstOrNull { it.isInstalled }
            ?: JdkInstallation(
                version = JdkVersion.JDK_17,
                homeDirectory = File(toolchainRoot, "runtime"),
                javaExecutable = File(toolchainRoot, "runtime/bin/java"),
                isInstalled = false,
                isVerified = false
            )
    }

    /** Saves per-project JDK selection in project preferences. */
    fun setProjectJdk(projectDir: File, version: JdkVersion) {
        val dotConfig = File(projectDir, ".mobilestudio").apply { mkdirs() }
        File(dotConfig, "jdk_version.txt").writeText(version.name)
    }

    private fun readProjectJdkPreference(projectDir: File): JdkVersion? {
        val file = File(projectDir, ".mobilestudio/jdk_version.txt")
        if (!file.isFile) return null
        return runCatching { JdkVersion.valueOf(file.readText().trim()) }.getOrNull()
    }

    /** Runs a live probe check using `java -version`. */
    suspend fun verifyJdk(installation: JdkInstallation): Result<String> = runCatching {
        require(installation.javaExecutable.isFile && installation.javaExecutable.canExecute()) {
            "Java executable missing at ${installation.javaExecutable.absolutePath}"
        }
        val env = mapOf(
            "JAVA_HOME" to installation.homeDirectory.absolutePath,
            "JAVA_TOOL_OPTIONS" to "-XX:-UseCompressedOops -XX:-UseCompressedClassPointers"
        )
        val result = runner.run(
            listOf(installation.javaExecutable.absolutePath, "-version"),
            context.filesDir,
            env,
            timeoutMinutes = 1
        )
        val out = result.output.joinToString("\n")
        check(result.exitCode == 0 || out.contains("version", true)) {
            "Java probe failed with code ${result.exitCode}: $out"
        }
        out
    }

    /** Checks whether selected JDK is compatible with target AGP version. */
    fun checkAgpCompatibility(jdk: JdkVersion, agpVersionString: String?): String? {
        if (agpVersionString == null) return null
        val agpMajor = Regex("""^(\d+)""").find(agpVersionString)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (agpMajor >= 8 && jdk.majorVersion < 17) {
            return "AGP $agpVersionString requires at least JDK 17. Currently selected: ${jdk.displayName}. Please switch project JDK to JDK 17 or JDK 21."
        }
        if (agpMajor < 7 && jdk.majorVersion > 11) {
            return "Legacy AGP $agpVersionString may encounter incompatibilities with ${jdk.displayName}. Recommend JDK 8 or 11."
        }
        return null
    }
}
