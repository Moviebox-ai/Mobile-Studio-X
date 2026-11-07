package com.aaryo.mobilestudio.toolchain

enum class ArchiveFormat { ZIP, TAR_XZ, TAR_GZ, FILE }

/**
 * One downloadable piece of the on-device toolchain.
 *
 * [stripComponents] works like `tar --strip-components`: that many leading path
 * segments are dropped from every archive entry. [targetPath] is relative to the
 * toolchain root; it is a directory for ZIP/TAR_XZ and the final file for FILE.
 */
data class ToolchainComponent(
    val id: String,
    val title: String,
    val url: String,
    val format: ArchiveFormat,
    val sha256: String?,
    val sizeBytes: Long,
    val stripComponents: Int,
    val targetPath: String
)

/**
 * Built-in download list used by the one-click installer.
 * Current catalog contains the explicitly listed downloadable components.
 * SDK Manager, Platform Tools and additional optional tools are tracked in IMPLEMENTATION_ROADMAP.md.
 */
object ToolchainCatalog {

    const val JDK17_ARM64_URL: String =
        "https://github.com/itsaky/openjdk-17-android/releases/download/01-01-2022/jdk17-arm64.tar.xz"
    const val JDK17_ARM64_SHA256: String =
        "1bfde21d5b5d6ed4632c4c36245f2a61532b8c641531c5f468148024223a2b63"
    const val JDK17_ARM64_SIZE_BYTES: Long = 157_457_188L

    const val GRADLE_9_3_1_URL: String =
        "https://services.gradle.org/distributions/gradle-9.3.1-bin.zip"
    const val GRADLE_9_3_1_SHA256: String =
        "b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06"
    const val GRADLE_9_3_1_SIZE_BYTES: Long = 137_037_885L

    private const val ANDROIDIDE_TOOLS =
        "https://github.com/AndroidIDEOfficial/androidide-tools/releases/download"

    /**
     * Kotlin/JVM compiler used by the direct on-device compiler. The official distribution
     * archive is `kotlinc/`-rooted, hence `stripComponents = 1`.
     */
    const val KOTLIN_COMPILER_2_2_10_URL: String =
        "https://github.com/JetBrains/kotlin/releases/download/v2.2.10/kotlin-compiler-2.2.10.zip"
    const val KOTLIN_COMPILER_2_2_10_SHA256: String =
        "302d1d8e671e5c3207e6ed62ff11fb555462a628e22a1158254dcaaf7e7394bc"
    const val KOTLIN_COMPILER_2_2_10_SIZE_BYTES: Long = 78_048_449L

    val components: List<ToolchainComponent> = listOf(
        ToolchainComponent(
            id = "jdk17",
            title = "OpenJDK 17 (Android ARM64)",
            url = JDK17_ARM64_URL,
            format = ArchiveFormat.TAR_XZ,
            sha256 = JDK17_ARM64_SHA256,
            sizeBytes = JDK17_ARM64_SIZE_BYTES,
            stripComponents = 0,
            targetPath = "runtime"
        ),
        ToolchainComponent(
            id = "gradle",
            title = "Gradle 9.3.1",
            url = GRADLE_9_3_1_URL,
            format = ArchiveFormat.ZIP,
            sha256 = GRADLE_9_3_1_SHA256,
            sizeBytes = GRADLE_9_3_1_SIZE_BYTES,
            stripComponents = 1,
            targetPath = "gradle"
        ),
        ToolchainComponent(
            id = "kotlin",
            title = "Kotlin Compiler 2.2.10 (kotlinc)",
            url = KOTLIN_COMPILER_2_2_10_URL,
            format = ArchiveFormat.ZIP,
            sha256 = KOTLIN_COMPILER_2_2_10_SHA256,
            sizeBytes = KOTLIN_COMPILER_2_2_10_SIZE_BYTES,
            stripComponents = 1,
            targetPath = "kotlin"
        ),
        ToolchainComponent(
            id = "build-tools",
            title = "Android Build Tools 34.0.4 (ARM64)",
            url = "$ANDROIDIDE_TOOLS/v34.0.4/build-tools-34.0.4-aarch64.tar.xz",
            format = ArchiveFormat.TAR_XZ,
            sha256 = "4bbbdeee608ff8d3a2c1534d0a5270305a02b1f200403a536c75ac84c41e52fa",
            sizeBytes = 41_666_832L,
            stripComponents = 2,
            targetPath = "sdk/build-tools/34.0.4"
        ),
        ToolchainComponent(
            id = "r8",
            title = "R8 8.2.42 (Dexer & Shrinker)",
            url = "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.2.42/r8-8.2.42.jar",
            format = ArchiveFormat.FILE,
            sha256 = "a1d7f902d56cfa8d1e8cd64aa250e63549359fcd096eb793286787b1a1e76db1",
            sizeBytes = 15_296_058L,
            stripComponents = 0,
            targetPath = "sdk/build-tools/34.0.4/lib/r8.jar"
        ),
        ToolchainComponent(
            id = "platform-36",
            title = "Android Platform 36 (android.jar)",
            url = "https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-36/android.jar",
            format = ArchiveFormat.FILE,
            sha256 = "231e29e345e85c7107bde3e08ece052c067ebbe9d72256f036b9a126e4dde0f6",
            sizeBytes = 53_871_685L,
            stripComponents = 0,
            targetPath = "sdk/platforms/android-36/android.jar"
        ),
        ToolchainComponent(
            id = "cmake-ninja",
            title = "CMake 3.25.1 & Ninja (ARM64)",
            url = "https://github.com/MrIkso/AndroidIDE-NDK/releases/download/cmake/cmake-3.25.1-android-aarch64.zip",
            format = ArchiveFormat.ZIP,
            sha256 = "9d11ca6a8c09e65c45b58b8b8e4d63d2feada37fbf2cb9239a9a7943cee31154",
            sizeBytes = 21_579_421L,
            stripComponents = 1,
            targetPath = "cmake"
        ),
        ToolchainComponent(
            id = "ndk-r30",
            title = "Android NDK r30 Toolchain (ARM64)",
            url = "https://github.com/HomuHomu833/android-ndk-custom/releases/download/r30/android-ndk-r30-aarch64-linux-android.tar.xz",
            format = ArchiveFormat.TAR_XZ,
            sha256 = "406afc90e64eee11caf39c0eadb0fc829b126387f18e50562320cdc6162e5a2b",
            sizeBytes = 186_322_532L,
            stripComponents = 1,
            targetPath = "ndk/30.0"
        )
    )

    val totalBytes: Long get() = components.sumOf { it.sizeBytes }

    /** Human-readable reasons the catalog cannot be used yet; empty when it is ready. */
    fun configurationProblems(list: List<ToolchainComponent> = components): List<String> = buildList {
        for (c in list) {
            if (c.url.isBlank()) {
                add("${c.title}: download URL is not set (edit ToolchainCatalog.kt).")
            } else if (!c.url.startsWith("https://", ignoreCase = true)) {
                add("${c.title}: URL must use HTTPS.")
            }
            c.sha256?.let { if (!Regex("[0-9a-f]{64}").matches(it)) add("${c.title}: sha256 must be 64 lowercase hex characters.") }
        }
    }
}
