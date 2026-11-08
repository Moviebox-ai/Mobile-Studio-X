package com.aaryo.mobilestudio.toolchain

import java.io.File

enum class ErrorCategory(val displayName: String) {
    TAGGED_POINTER_SIGABRT("Android Runtime / Tagged Pointers"),
    JVM_CRASH("JVM Crash At Startup"),
    OUT_OF_MEMORY("Memory / JVM Heap Exhaustion"),
    KOTLIN_COMPILATION("Kotlin Compiler (kotlinc)"),
    JAVA_COMPILATION("Java Compiler (javac)"),
    AAPT2_RESOURCE("Resource Compiler (AAPT2)"),
    MANIFEST_MERGER("Manifest Merger"),
    DEX_COMPILATION("DEX / R8 Optimization"),
    MISSING_SDK("Android SDK / Build-Tools"),
    DEPENDENCY_RESOLUTION("Gradle Dependencies"),
    SIGNING_ERROR("APK Signing / Keystore"),
    GRADLE_COMPATIBILITY("Gradle / AGP Version"),
    GENERIC_BUILD("General Build Error")
}

data class BuildDiagnostic(
    val category: ErrorCategory,
    val title: String,
    val summary: String,
    val filePath: String? = null,
    val lineNumber: Int? = null,
    val rootCause: String,
    val recommendedFix: String,
    val relevantLogs: List<String>
)

object ErrorDiagnosticsEngine {

    /**
     * True when the output contains a Bionic loader failure, i.e. the process never reached
     * `main()`. A shim that Bionic refuses is not the JVM's fault, so these lines must never be
     * reported as "reinstall the JDK".
     */
    fun isLinkerRejection(text: String): Boolean =
        text.contains("CANNOT LINK EXECUTABLE", true) ||
            text.contains("has load segments that are both writable and executable", true) ||
            text.contains("has invalid e_shstrndx", true) ||
            text.contains("has no section headers", true) ||
            text.contains("is for EM_", true) ||
            text.contains("cannot locate symbol", true)

    /** True only for the allocator abort that heap tagging causes ("Pointer tag ... truncated"). */
    fun isTaggedPointerAbort(text: String): Boolean =
        !isLinkerRejection(text) &&
            (text.contains("Pointer tag for", true) || text.contains("tagged-pointers", true))

    /** Analyzes build logs and extracts intelligent diagnostics. */
    fun analyze(logs: List<String>, projectDir: File?): BuildDiagnostic {
        val fullText = logs.joinToString("\n")

        // The generated LD_PRELOAD shim itself was rejected by the loader. This is a bug in this
        // app's compatibility layer, not a broken JDK - say so and offer a way out that does not
        // need a working shim.
        if (fullText.contains("libtagfix.so", true) && isLinkerRejection(fullText)) {
            val relevant = logs.filter {
                it.contains("linker", true) || it.contains("libtagfix.so", true) ||
                    it.contains("CANNOT LINK", true) || it.contains("load segments", true) ||
                    it.contains("e_shstrndx", true)
            }
            return BuildDiagnostic(
                category = ErrorCategory.TAGGED_POINTER_SIGABRT,
                title = "Android rejected the heap-tagging compatibility shim",
                summary = "Bionic refused to load toolchain/compat/libtagfix.so, so every tool that " +
                    "starts through execve() (java, javac, kotlinc, d8, aapt2) stopped before main().",
                filePath = "toolchain/compat/libtagfix.so",
                lineNumber = null,
                rootCause = if (fullText.contains("writable and executable", true)) {
                    "The shared object contains a load segment that is both writable and executable. " +
                        "Android 8.0+ (API 26+) reject such segments (W^X)."
                } else {
                    "The shared object failed Bionic's ELF validation (section headers, program " +
                        "headers or the dynamic section do not match)."
                },
                recommendedFix = "Run Tools > Toolchain > Full toolchain verification: it deletes the " +
                    "cached shim, rebuilds it and re-runs java -version. If the error persists, set " +
                    "MO_SKIP_TAGFIX=1 in the build environment to start tools without the shim, or use " +
                    "Compiler Mode: Direct On-Device Compiler.",
                relevantLogs = relevant.ifEmpty { logs.takeLast(20) }
            )
        }

        // Linker error on child process / shim
        if (fullText.contains("invalid e_shstrndx", true) || (fullText.contains("CANNOT LINK EXECUTABLE", true) && fullText.contains("libtagfix.so", true))) {
            val relevant = logs.filter { it.contains("linker", true) || it.contains("libtagfix.so", true) || it.contains("CANNOT LINK", true) }
            return BuildDiagnostic(
                category = ErrorCategory.TAGGED_POINTER_SIGABRT,
                title = "Android Bionic Linker ELF Validation Error",
                summary = "The dynamic linker rejected the compatibility shim due to section header constraints.",
                filePath = "toolchain/compat/libtagfix.so",
                lineNumber = null,
                rootCause = "Android 8.0+ Bionic linker requires e_shstrndx != 0 and valid section header table for shared objects.",
                recommendedFix = "The app has regenerated a compliant 64-bit ELF shim with valid section headers. You can also use Compiler Mode: Direct On-Device Compiler for in-process compilation.",
                relevantLogs = relevant.ifEmpty { logs.takeLast(10) }
            )
        }

        // 0. JVM died while booting Gradle (exit 134/139/137). Report the REAL output instead of
        // guessing a cause: only blame tagged pointers when the log actually says so.
        val taggedEvidence = fullText.contains("tagged-pointers", true) || fullText.contains("Pointer tag for", true)
        val bootCrash = !taggedEvidence && (fullText.contains("JVM bootstrap failed", true) ||
            Regex("""exit (134|137|139)""").containsMatchIn(fullText) ||
            fullText.contains("A fatal error has been detected", true))
        if (bootCrash) {
            val keys = listOf("fatal", "sig", "problematic frame", "libc", "libjvm", "fortify", "abort",
                "unsatisfiedlink", "cannot", "error", "exception", "no such", "denied", "# ")
            val evidence = logs.filter { line -> keys.any { line.contains(it, true) } }.takeLast(40)
            val exit = Regex("""exit (\d+)""").find(fullText)?.groupValues?.get(1) ?: "?"
            return BuildDiagnostic(
                category = ErrorCategory.JVM_CRASH,
                title = "Gradle JVM crashed during startup (exit $exit)",
                summary = "The Java process aborted before Gradle could start. See the raw JVM output below.",
                filePath = null,
                lineNumber = null,
                rootCause = when (exit) {
                    "134" -> "SIGABRT: the JVM or a native library called abort(). The exact reason is in the JVM output / hs_err lines below."
                    "139" -> "SIGSEGV: a native library crashed (ABI or glibc/Bionic mismatch)."
                    "137" -> "SIGKILL: Android killed the process, usually low memory."
                    else -> "The JVM exited abnormally."
                },
                recommendedFix = "Copy this report (all lines below) and share it. The app already retried several JVM flag sets automatically; if every one failed, the JDK/Gradle archive itself is incompatible with this device.",
                relevantLogs = evidence.ifEmpty { logs.takeLast(40) }
            )
        }

        // 1. Tagged Pointer / SIGABRT
        if (taggedEvidence) {
            val relevant = logs.filter { it.contains("pointer", true) || it.contains("SIGABRT", true) || it.contains("134") }
            return BuildDiagnostic(
                category = ErrorCategory.TAGGED_POINTER_SIGABRT,
                title = "This device's JVM cannot boot Gradle (heap-tagging abort)",
                summary = "Android aborted the Java process with \"Pointer tag ... was truncated\" before Gradle could start. " +
                    "This is a JVM/device incompatibility, not a problem in your project, so the remaining JVM flag sets " +
                    "were skipped instead of retrying for minutes.",
                filePath = "toolchain/runtime/bin/java",
                lineNumber = null,
                rootCause = "The installed JDK strips Android's Top Byte Ignore (TBI) heap tags. Any JVM flag set fails the " +
                    "same way, because the abort comes from the allocator, not from the JIT.",
                recommendedFix = "1) Tools > Toolchain > Download & install to (re)install the bundled Android ARM64 JDK 17. " +
                    "2) If your device offers Developer options > Memory Tagging Extension / heap tagging, turn it off and " +
                    "reinstall this app. 3) Use Compiler Mode \"Direct\" for projects without library dependencies, or build " +
                    "the same project on a PC with Android Studio.",
                relevantLogs = relevant
            )
        }

        // 2. Out of Memory / GC limits
        if (fullText.contains("OutOfMemoryError", true) || fullText.contains("GC overhead limit", true) ||
            fullText.contains("Java heap space", true)) {
            val relevant = logs.filter { it.contains("OutOfMemory", true) || it.contains("heap", true) }
            return BuildDiagnostic(
                category = ErrorCategory.OUT_OF_MEMORY,
                title = "JVM Out Of Memory Error",
                summary = "Gradle compiler exhausted allocated memory during compilation.",
                filePath = "gradle.properties",
                lineNumber = null,
                rootCause = "The compiler process requested more RAM than the device kernel or JVM heap limit permitted.",
                recommendedFix = "Cap org.gradle.jvmargs to -Xmx768m -XX:MaxMetaspaceSize=256m in gradle.properties and disable parallel builds (org.gradle.parallel=false).",
                relevantLogs = relevant
            )
        }

        // 3. Kotlin Compilation Errors
        val kotlinMatch = Regex("""^e:\s+(?:file://)?([^:]+):(\d+):(\d+)\s+(.+)$""", RegexOption.MULTILINE).find(fullText)
        if (kotlinMatch != null) {
            val (file, line, _, msg) = kotlinMatch.destructured
            val relevant = logs.filter { it.contains(file.substringAfterLast("/")) || it.startsWith("e: ") }
            return BuildDiagnostic(
                category = ErrorCategory.KOTLIN_COMPILATION,
                title = "Kotlin Syntax or Type Error",
                summary = msg.trim(),
                filePath = file.trim(),
                lineNumber = line.toIntOrNull(),
                rootCause = "Code syntax or type mismatch in Kotlin source file.",
                recommendedFix = "Open $file at line $line to correct the code syntax, type declaration, or missing import.",
                relevantLogs = relevant.take(8)
            )
        }

        // 4. Java Compilation Errors
        val javaMatch = Regex("""^([^:]+\.java):(\d+):\s+error:\s+(.+)$""", RegexOption.MULTILINE).find(fullText)
        if (javaMatch != null) {
            val (file, line, msg) = javaMatch.destructured
            val relevant = logs.filter { it.contains(file.substringAfterLast("/")) || it.contains("error:") }
            return BuildDiagnostic(
                category = ErrorCategory.JAVA_COMPILATION,
                title = "Java Compiler Error",
                summary = msg.trim(),
                filePath = file.trim(),
                lineNumber = line.toIntOrNull(),
                rootCause = "javac reported compilation error.",
                recommendedFix = "Inspect $file at line $line and fix the syntax or type problem.",
                relevantLogs = relevant.take(8)
            )
        }

        // 5. AAPT2 Resource & Linking Errors
        if (fullText.contains("AAPT2", true) || fullText.contains("Resource and asset merger", true) ||
            fullText.contains("packageDebugResources FAILED", true)) {
            val dupMatch = Regex("""\[([^\]]+)\]\s+([^:]+):\s+(?:Error|Resource and asset merger):\s+(.+)""").find(fullText)
            val file = dupMatch?.groupValues?.getOrNull(2)
            val summary = dupMatch?.groupValues?.getOrNull(3) ?: "Resource packaging or XML parsing error in AAPT2."
            val relevant = logs.filter { it.contains("ERROR:", true) || it.contains("AAPT2", true) || it.contains("Duplicate", true) }
            return BuildDiagnostic(
                category = ErrorCategory.AAPT2_RESOURCE,
                title = "AAPT2 Resource Compilation Error",
                summary = summary,
                filePath = file,
                lineNumber = null,
                rootCause = "Conflicting duplicate resource names, invalid XML structure, or missing resource ID.",
                recommendedFix = "Ensure unique drawable and value names. If duplicate webp/png assets exist in mipmap folders, remove the legacy duplicates.",
                relevantLogs = relevant.take(8)
            )
        }

        // 6. Missing SDK Platform or Build-Tools
        if (fullText.contains("Failed to find target with hash string", true) ||
            fullText.contains("SDK location not found", true) || fullText.contains("android.jar not found", true)) {
            val target = Regex("""'([^']+)'""").find(fullText)?.groupValues?.getOrNull(1) ?: "target SDK platform"
            return BuildDiagnostic(
                category = ErrorCategory.MISSING_SDK,
                title = "Missing Android SDK Component",
                summary = "Required Android platform $target is not installed.",
                filePath = "app/build.gradle[.kts]",
                lineNumber = null,
                rootCause = "The project's compileSdk requires an Android SDK platform that is not present in local toolchain storage.",
                recommendedFix = "Go to More > Toolchains / SDK Manager and install the required Android Platform or adjust compileSdk to an installed platform (e.g. 34 or 36).",
                relevantLogs = logs.filter { it.contains("SDK", true) || it.contains("target", true) }
            )
        }

        // 7. Gradle Dependency Resolution
        if (fullText.contains("Could not resolve", true) || fullText.contains("Failed to resolve", true) ||
            fullText.contains("Could not find", true)) {
            val dep = Regex("""Could not find\s+([^\.]+:[^\.]+)""").find(fullText)?.groupValues?.getOrNull(1) ?: "library artifact"
            return BuildDiagnostic(
                category = ErrorCategory.DEPENDENCY_RESOLUTION,
                title = "Gradle Dependency Resolution Failure",
                summary = "Unable to resolve $dep.",
                filePath = "build.gradle.kts / settings.gradle.kts",
                lineNumber = null,
                rootCause = "Artifact missing from declared repositories (google, mavenCentral) or device is offline without local dependency cache.",
                recommendedFix = "Verify internet connectivity, ensure google() and mavenCentral() are declared in dependencyResolutionManagement, or check for typos in the dependency coordinates.",
                relevantLogs = logs.filter { it.contains("Could not", true) || it.contains("repository", true) }.take(6)
            )
        }

        // 8. Keystore & Signing Errors
        if (fullText.contains("Keystore file", true) && fullText.contains("not found", true) ||
            fullText.contains("validateSigningDebug", true) || fullText.contains("Password verification failed", true)) {
            return BuildDiagnostic(
                category = ErrorCategory.SIGNING_ERROR,
                title = "Keystore Signing Configuration Failure",
                summary = "APK signing key or keystore file was not found or password was invalid.",
                filePath = "app/build.gradle.kts",
                lineNumber = null,
                rootCause = "The signingConfig references a .keystore / .jks file that is absent from the project directory.",
                recommendedFix = "Open Signing Manager to generate or import a keystore, or ensure debug.keystore is generated in the project root.",
                relevantLogs = logs.filter { it.contains("signing", true) || it.contains("keystore", true) }
            )
        }

        // 9. AGP / Gradle / Kotlin plugin version mismatches
        val pluginMismatch = fullText.contains("no longer required for Kotlin support", true) ||
            fullText.contains("requires Gradle", true) ||
            fullText.contains("Minimum supported Gradle version", true) ||
            fullText.contains("Failed to apply plugin 'org.jetbrains.kotlin.android'", true) ||
            fullText.contains("Unsupported class file major version", true)
        if (pluginMismatch) {
            val relevant = logs.filter {
                it.contains("Gradle", true) || it.contains("plugin", true) || it.contains("AGP", true)
            }
            return BuildDiagnostic(
                category = ErrorCategory.GRADLE_COMPATIBILITY,
                title = "Gradle / Android Gradle Plugin version mismatch",
                summary = "The project's build scripts target a different AGP/Gradle generation than the bundled toolchain.",
                filePath = "build.gradle.kts / settings.gradle.kts",
                lineNumber = null,
                rootCause = "The bundled distribution is Gradle 9.3.1, and AGP 9.1.1 compiles Kotlin itself. " +
                    "Build scripts using AGP 8.x and org.jetbrains.kotlin.android cannot run on it.",
                recommendedFix = "Use the plugin versions shipped with new projects in this IDE: " +
                    "id(\"com.android.application\") version \"9.1.1\" and " +
                    "id(\"org.jetbrains.kotlin.plugin.compose\") version \"2.2.10\". " +
                    "Remove org.jetbrains.kotlin.android from every module.",
                relevantLogs = relevant.take(10)
            )
        }

        // 10. Build Tools revision required by AGP is not installed
        if (fullText.contains("Failed to find Build Tools revision", true) ||
            fullText.contains("below the minimum supported version", true)
        ) {
            val revision = Regex("""Build Tools revision ([0-9.]+)""").find(fullText)?.groupValues?.getOrNull(1)
            return BuildDiagnostic(
                category = ErrorCategory.MISSING_SDK,
                title = "Android Build Tools revision missing",
                summary = "Android Build Tools ${revision ?: "required by the plugin"} are not installed on the device.",
                filePath = "app/build.gradle.kts",
                lineNumber = null,
                rootCause = "AGP resolves the Build Tools revision from buildToolsVersion or its own default, and " +
                    "SDK components cannot be downloaded automatically on-device.",
                recommendedFix = "Either install the requested Build Tools revision, or set " +
                    "buildToolsVersion = \"34.0.4\" (the revision bundled with this toolchain) in the app module.",
                relevantLogs = logs.filter { it.contains("Build Tools", true) }.take(10)
            )
        }

        // 11. Direct compiler: incomplete on-device toolchain
        if (fullText.contains("build toolchain is incomplete", true) ||
            fullText.contains("Missing components:", true)
        ) {
            val missing = logs.filter { it.trimStart().startsWith("•") }
            return BuildDiagnostic(
                category = ErrorCategory.MISSING_SDK,
                title = "On-device build toolchain incomplete",
                summary = "The direct compiler stopped before compiling because required tools are missing.",
                filePath = "toolchain/",
                lineNumber = null,
                rootCause = "AAPT2, D8, apksigner, zipalign, android.jar or the Kotlin compiler is not installed, " +
                    "so no valid APK could be produced.",
                recommendedFix = "Open Tools ▸ Toolchain and run \"Download & install\" (or install the matching " +
                    "archive with \"Install local ZIP\"), then build again.",
                relevantLogs = missing.ifEmpty { logs.takeLast(10) }
            )
        }

        // 12. Generic Fallback
        val errorLines = logs.filter {
            it.contains("FAILED", true) || it.contains("ERROR", true) || it.contains("Exception", true)
        }
        return BuildDiagnostic(
            category = ErrorCategory.GENERIC_BUILD,
            title = "Build Task Failed",
            summary = errorLines.lastOrNull()?.trim() ?: "The build encountered an error. Check console logs.",
            filePath = null,
            lineNumber = null,
            rootCause = "Build task exited with non-zero status.",
            recommendedFix = "Review the build console log details below to identify the failing Gradle task or compiler phase.",
            relevantLogs = errorLines.take(10)
        )
    }
}
