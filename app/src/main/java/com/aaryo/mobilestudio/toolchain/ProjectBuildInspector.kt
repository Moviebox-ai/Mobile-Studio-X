package com.aaryo.mobilestudio.toolchain

import java.io.File
import java.util.zip.ZipFile

/** Lightweight project inspection. It deliberately does not pretend to replace Gradle model sync. */
data class ProjectBuildReport(
    val root: File,
    val modules: List<String>,
    val buildScripts: List<String>,
    val namespace: String?,
    val applicationId: String?,
    val compileSdk: String?,
    val targetSdk: String?,
    val minSdk: String?,
    val warnings: List<String>
)

data class ApkInspection(
    val file: File,
    val sizeBytes: Long,
    val dexFiles: List<String>,
    val nativeAbis: List<String>,
    val hasManifest: Boolean,
    val hasSignatureMetadata: Boolean
)

class ProjectBuildInspector {
    fun inspectProject(project: File): ProjectBuildReport {
        require(project.isDirectory) { "Project folder does not exist." }
        val settings = listOf("settings.gradle.kts", "settings.gradle").map { File(project, it) }.firstOrNull { it.isFile }
        val scripts = project.walkTopDown().maxDepth(4)
            .filter { it.isFile && (it.name == "build.gradle" || it.name == "build.gradle.kts") }
            .filterNot { it.path.contains("/.gradle/") || it.path.contains("/build/") }
            .take(50).toList()
        val settingsText = settings?.readText().orEmpty()
        val declaredModules = if (settingsText.contains("include")) {
            Regex("[\"'](:[A-Za-z0-9:_-]+)[\"']").findAll(settingsText)
                .map { it.groupValues[1].removePrefix(":").replace(':', '/') }.toList()
        } else emptyList()
        val modules = (declaredModules + listOf("app").filter { File(project, "app").isDirectory })
            .distinct().ifEmpty { listOf("root") }
        val allText = scripts.joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }
        fun value(pattern: String): String? = Regex(pattern, RegexOption.MULTILINE)
            .find(allText)?.groupValues?.getOrNull(1)
        val namespace = value("namespace\\s*(?:=)?\\s*[\"']([^\"']+)")
        val appId = value("applicationId\\s*(?:=)?\\s*[\"']([^\"']+)")
        val compile = value("compileSdk\\s*\\{[^}]*release\\((\\d+)")
            ?: value("compileSdk(?:Version)?\\s*(?:=)?\\s*(?:release\\()?\\s*(\\d+|\\w+)")
        val target = value("targetSdk(?:Version)?\\s*(?:=)?\\s*(?:\\(?\\s*)?(\\d+|\\w+)")
        val min = value("minSdk(?:Version)?\\s*(?:=)?\\s*(?:\\(?\\s*)?(\\d+|\\w+)")
        val warnings = buildList {
            if (settings == null) add("Gradle settings file is missing.")
            if (scripts.isEmpty()) add("No Gradle build script found.")
            if (namespace == null) add("Could not statically detect namespace; Gradle may define it through a variable or convention plugin.")
            if (compile == null) add("Could not statically detect compileSdk.")
            if (modules.isEmpty()) add("No Gradle modules detected.")
        }
        return ProjectBuildReport(project, modules, scripts.map { project.toPath().relativize(it.toPath()).toString() }, namespace, appId, compile, target, min, warnings)
    }

    fun inspectApk(apk: File): ApkInspection {
        require(apk.isFile && apk.extension.equals("apk", true)) { "Select a valid APK file." }
        ZipFile(apk).use { zip ->
            val entries = zip.entries().asSequence().map { it.name }.toList()
            return ApkInspection(
                file = apk,
                sizeBytes = apk.length(),
                dexFiles = entries.filter { Regex("classes(\\d+)?\\.dex").matches(it) }.sorted(),
                nativeAbis = entries.filter { it.startsWith("lib/") && it.endsWith(".so") }
                    .mapNotNull { it.split('/').getOrNull(1) }.distinct().sorted(),
                hasManifest = "AndroidManifest.xml" in entries,
                hasSignatureMetadata = entries.any { it.startsWith("META-INF/") && (it.endsWith(".RSA") || it.endsWith(".DSA") || it.endsWith(".EC")) }
            )
        }
    }
}
