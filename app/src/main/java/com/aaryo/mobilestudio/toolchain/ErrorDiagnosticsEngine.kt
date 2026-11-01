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

    /** Analyzes build logs and extracts intelligent diagnostics. */
    fun analyze(logs: List<String>, projectDir: File?): BuildDiagnostic {
        val fullText = logs.joinToString("\n")

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
                title = "ARM64 Pointer Tagging Abort (SIGABRT 134)",
                summary = "Android 11+ Bionic detected pointer address bit-shifting / truncation during JVM execution.",
                filePath = "AndroidManifest.xml / gradle.properties",
                lineNumber = null,
                rootCause = "The 64-bit JVM default Compressed OOPs shifts upper address bits, corrupting Android's Top Byte Ignore (TBI) heap tags.",
                recommendedFix = "The app already opts out (allowNativeHeapPointerTagging=false, memtagMode=off) and retries safer JVM flags. Turn OFF Developer options > Memory Tagging Extension (MTE) if your phone has it, reinstall the app, and if it still fails install a newer tag-safe Android ARM64 JDK 17 via Tools > Local ZIP.",
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

        // 9. Generic Fallback
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
