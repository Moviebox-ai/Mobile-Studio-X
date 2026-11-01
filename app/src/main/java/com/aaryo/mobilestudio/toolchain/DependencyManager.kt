package com.aaryo.mobilestudio.toolchain

import java.io.File

data class ProjectDependency(
    val configuration: String, // implementation, api, testImplementation, etc.
    val group: String,
    val name: String,
    val version: String,
    val isCachedLocally: Boolean = false
) {
    val coordinate: String get() = "$group:$name:$version"
}

data class DependencyReport(
    val totalDependencies: Int,
    val dependencies: List<ProjectDependency>,
    val repositories: List<String>,
    val offlineCacheSizeBytes: Long,
    val cachedArtifactCount: Int
)

class DependencyManager(private val toolchainRoot: File) {

    /** Parses project Gradle files and Version Catalog to extract dependency list. */
    fun inspectProjectDependencies(projectDir: File): DependencyReport {
        val deps = mutableListOf<ProjectDependency>()
        val repos = mutableListOf<String>()

        val gradleKts = File(projectDir, "app/build.gradle.kts")
        val gradleGroovy = File(projectDir, "app/build.gradle")
        val rootGradleKts = File(projectDir, "build.gradle.kts")
        val settingsKts = File(projectDir, "settings.gradle.kts")
        val tomlFile = File(projectDir, "gradle/libs.versions.toml")

        // Parse versions from TOML catalog if present
        val versionsMap = mutableMapOf<String, String>()
        if (tomlFile.isFile) {
            val tomlText = tomlFile.readText()
            Regex("""([a-zA-Z0-9_\-]+)\s*=\s*"([^"]+)"""").findAll(tomlText).forEach { m ->
                versionsMap[m.groupValues[1]] = m.groupValues[2]
            }
        }

        val gradleContent = when {
            gradleKts.isFile -> gradleKts.readText()
            gradleGroovy.isFile -> gradleGroovy.readText()
            else -> ""
        }

        // Match string-literal dependencies: implementation("group:artifact:version")
        val directPattern = Regex("""(implementation|api|compileOnly|runtimeOnly|testImplementation|androidTestImplementation|ksp|annotationProcessor)\s*\(\s*["']([^:)]+):([^:)]+):([^"'\)]+)["']\s*\)""")
        directPattern.findAll(gradleContent).forEach { match ->
            val (conf, group, name, ver) = match.destructured
            deps.add(ProjectDependency(conf, group.trim(), name.trim(), ver.trim()))
        }

        // Match libs.xxx syntax: implementation(libs.androidx.core.ktx)
        val libsPattern = Regex("""(implementation|api|testImplementation)\s*\(\s*libs\.([a-zA-Z0-9\._\-]+)\s*\)""")
        libsPattern.findAll(gradleContent).forEach { match ->
            val conf = match.groupValues[1]
            val key = match.groupValues[2].replace('.', '-')
            val ver = versionsMap[key] ?: versionsMap["agp"] ?: "1.0.0"
            deps.add(ProjectDependency(conf, "androidx.$key", key, ver))
        }

        // Extract declared repositories
        val allConfigText = (if (settingsKts.isFile) settingsKts.readText() else "") +
            (if (rootGradleKts.isFile) rootGradleKts.readText() else "") + gradleContent

        if (allConfigText.contains("google()", true)) repos.add("Google Maven (https://maven.google.com)")
        if (allConfigText.contains("mavenCentral()", true)) repos.add("Maven Central (https://repo1.maven.org/maven2)")
        if (allConfigText.contains("gradlePluginPortal()", true)) repos.add("Gradle Plugin Portal")

        // Inspect local Gradle cache
        val gradleUserHome = File(projectDir.parentFile, ".mobilestudio-gradle")
        val cacheDir = File(gradleUserHome, "caches/modules-2/files-2.1")
        var cacheSize = 0L
        var cachedCount = 0

        if (cacheDir.isDirectory) {
            cacheDir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    cacheSize += f.length()
                    if (f.extension in listOf("jar", "aar")) cachedCount++
                }
            }
        }

        return DependencyReport(
            totalDependencies = deps.size,
            dependencies = deps.distinctBy { it.coordinate },
            repositories = repos.distinct(),
            offlineCacheSizeBytes = cacheSize,
            cachedArtifactCount = cachedCount
        )
    }
}
