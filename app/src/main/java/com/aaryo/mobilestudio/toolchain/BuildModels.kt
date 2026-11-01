package com.aaryo.mobilestudio.toolchain

import java.io.File

data class BuildContext(
    val projectDir: File,
    val task: String,
    val runtimeDir: File,
    val gradleDir: File,
    val toolchainRoot: File
)

data class BuildLog(
    val timestamp: Long = System.currentTimeMillis(),
    val line: String
)

sealed interface BuildState {
    data object Idle : BuildState
    data class Running(val task: String) : BuildState
    data class Success(val output: String) : BuildState
    data class Failed(val reason: String) : BuildState
}

enum class CompilerMode(val displayName: String, val badge: String) {
    DIRECT("Fast On-Device Compiler", "⚡ Direct"),
    GRADLE("Gradle Daemon (ARM64)", "🐘 Gradle")
}

