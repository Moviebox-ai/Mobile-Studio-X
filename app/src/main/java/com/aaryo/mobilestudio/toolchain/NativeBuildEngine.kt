package com.aaryo.mobilestudio.toolchain

import java.io.File

data class NativeBuildResult(val buildDirectory: File, val libraries: List<File>)

/** CMake/Ninja driver. Requires an NDK and host tools explicitly ported to Android ARM64. */
class NativeBuildEngine(private val runner: ToolProcessRunner = ToolProcessRunner()) {
    suspend fun buildArm64(
        projectDirectory: File,
        buildDirectory: File,
        toolchainRoot: File,
        minApi: Int = 29,
        onLine: (String) -> Unit = {}
    ): NativeBuildResult {
        require(minApi >= 29) { "This IDE targets Android API 29 or newer." }
        require(File(projectDirectory, "CMakeLists.txt").isFile) {
            "Native project must contain CMakeLists.txt."
        }
        val cmake = (listOf(
            File(toolchainRoot, "cmake/bin/cmake"),
            File(toolchainRoot, "cmake/cmake"),
            File(toolchainRoot, "cmake/current/bin/cmake"),
            File(toolchainRoot, "sdk/cmake/bin/cmake")
        ) + File(toolchainRoot, "cmake").listFiles().orEmpty().map { File(it, "bin/cmake") }
          + runCatching { File(toolchainRoot, "cmake").walkTopDown().maxDepth(5).filter { it.name == "cmake" && it.isFile }.toList() }.getOrDefault(emptyList())
        ).firstOrNull { it.isFile }
            ?: error("Android-host ARM64 CMake executable is missing.")
        val ninja = (listOf(
            File(toolchainRoot, "ninja/ninja"),
            File(toolchainRoot, "cmake/bin/ninja"),
            File(toolchainRoot, "cmake/ninja"),
            File(toolchainRoot, "cmake/current/bin/ninja")
        ) + File(toolchainRoot, "cmake").listFiles().orEmpty().map { File(it, "bin/ninja") }
          + runCatching { File(toolchainRoot, "cmake").walkTopDown().maxDepth(5).filter { it.name == "ninja" && it.isFile }.toList() }.getOrDefault(emptyList())
        ).firstOrNull { it.isFile }
            ?: error("Android-host ARM64 Ninja executable is missing.")
        val ndkRoot = File(toolchainRoot, "ndk")
        val ndk = ndkRoot.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
            ?: error("Android NDK is not installed.")
        val ndkToolchain = File(ndk, "build/cmake/android.toolchain.cmake")
        require(ndkToolchain.isFile) { "NDK CMake toolchain file is missing." }
        val llvmBin = File(ndk, "toolchains/llvm/prebuilt/android-arm64/bin")
        buildDirectory.mkdirs()
        val env = mapOf(
            "ANDROID_NDK_HOME" to ndk.absolutePath,
            "ANDROID_NDK_ROOT" to ndk.absolutePath,
            "PATH" to listOf(llvmBin, cmake.parentFile, ninja.parentFile)
                .joinToString(File.pathSeparator) { it.absolutePath } +
                File.pathSeparator + System.getenv("PATH").orEmpty()
        )
        val configure = listOf(
            cmake.absolutePath, "-S", projectDirectory.absolutePath,
            "-B", buildDirectory.absolutePath, "-G", "Ninja",
            "-DCMAKE_MAKE_PROGRAM=${ninja.absolutePath}",
            "-DCMAKE_TOOLCHAIN_FILE=${ndkToolchain.absolutePath}",
            "-DANDROID_ABI=arm64-v8a", "-DANDROID_PLATFORM=android-$minApi",
            "-DANDROID_NDK=${ndk.absolutePath}", "-DCMAKE_BUILD_TYPE=Debug"
        )
        runner.requireSuccess(configure, projectDirectory, env, onLine, timeoutMinutes = 30)
        runner.requireSuccess(
            listOf(cmake.absolutePath, "--build", buildDirectory.absolutePath, "--parallel", "2"),
            projectDirectory, env, onLine, timeoutMinutes = 60
        )
        val libraries = buildDirectory.walkTopDown().filter { it.isFile && it.extension == "so" }.toList()
        check(libraries.isNotEmpty()) { "Native build finished but no .so libraries were produced." }
        return NativeBuildResult(buildDirectory, libraries)
    }
}
