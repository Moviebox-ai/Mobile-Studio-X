package com.aaryo.mobilestudio.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class ProjectWorkspace(private val context: Context) {
    // Build workspace stays app-private for reliable Gradle access. A SAF mirror keeps
    // the user's editable project tree in shared Internal Storage/Mobile Studio X/Projects.
    val root = File(context.filesDir, "projects").apply { mkdirs() }
    private val prefs = context.getSharedPreferences("public_project_storage", Context.MODE_PRIVATE)
    private fun publicTree(): DocumentFile? = prefs.getString("tree_uri", null)?.let { raw ->
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(raw)) }.getOrNull()
    }

    fun setPublicStorageTree(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString("tree_uri", uri.toString()).apply()
        ensurePublicFolders()
    }

    fun hasPublicStorageTree(): Boolean = publicTree() != null

    fun publicStorageLabel(): String {
        val tree = publicTree() ?: return "Not connected — choose Internal Storage root"
        val parent = tree.name ?: "Selected storage folder"
        return "$parent/Mobile Studio X"
    }

    private fun ensurePublicFolders(): DocumentFile? {
        val tree = publicTree() ?: return null
        val appFolder = tree.findFile("Mobile Studio X") ?: tree.createDirectory("Mobile Studio X") ?: return null
        listOf("Projects", "Backups", "Exports", "Downloads", "Templates").forEach {
            if (appFolder.findFile(it) == null) appFolder.createDirectory(it)
        }
        return appFolder
    }

    /** Mirror a build-ready private project into shared storage using SAF (no broad storage permission). */
    suspend fun syncProjectToPublicStorage(projectDir: File): Boolean = withContext(Dispatchers.IO) {
        val appFolder = ensurePublicFolders() ?: return@withContext false
        val projects = appFolder.findFile("Projects") ?: return@withContext false
        val target = projects.findFile(projectDir.name)?.let { existing ->
            if (existing.isDirectory) existing else { existing.delete(); projects.createDirectory(projectDir.name) }
        } ?: projects.createDirectory(projectDir.name) ?: return@withContext false
        target.listFiles().forEach { it.delete() }
        copyDirectoryToDocument(projectDir, target)
        true
    }

    fun removeProjectFromPublicStorage(projectName: String) {
        val appFolder = ensurePublicFolders() ?: return
        appFolder.findFile("Projects")?.findFile(projectName)?.delete()
    }

    private fun copyDirectoryToDocument(source: File, target: DocumentFile) {
        source.listFiles()?.filterNot { it.name.endsWith(".mobilestudio.tmp") }?.forEach { child ->
            if (child.isDirectory) {
                val dir = target.findFile(child.name)?.takeIf { it.isDirectory } ?: target.createDirectory(child.name)
                if (dir != null) copyDirectoryToDocument(child, dir)
            } else {
                val existing = target.findFile(child.name)
                if (existing != null) existing.delete()
                val out = target.createFile("application/octet-stream", child.name) ?: return@forEach
                context.contentResolver.openOutputStream(out.uri, "w")?.use { stream -> child.inputStream().use { it.copyTo(stream) } }
            }
        }
    }

    suspend fun importTree(uri: Uri): File = withContext(Dispatchers.IO) {
        val source = DocumentFile.fromTreeUri(context, uri) ?: throw IOException("Cannot access selected project directory.")
        val name = safeSegment(source.name ?: "ImportedProject")
        var destination = File(root, name)
        var suffix = 2
        while (destination.exists()) destination = File(root, "$name-${suffix++}")
        val staging = File(root, ".import-${System.nanoTime()}").apply { check(mkdirs()) }
        val count = Counter()
        try {
            copyTree(source, staging, count, 0)
            check(staging.listFiles()?.isNotEmpty() == true) { "Selected project folder is empty." }
            if (!staging.renameTo(destination)) throw IOException("Cannot activate imported project.")
            sanitizeProjectForAndroidHost(destination)
            syncProjectToPublicStorage(destination)
            destination
        } catch (t: Throwable) { staging.deleteRecursively(); throw t }
    }

    suspend fun importZip(uri: Uri): File = withContext(Dispatchers.IO) {
        val document = DocumentFile.fromSingleUri(context, uri)
        val rawName = document?.name?.substringBeforeLast(".zip") ?: "ImportedZipProject"
        val baseName = safeSegment(rawName)

        val staging = File(root, ".import-zip-${System.nanoTime()}").apply { check(mkdirs()) }
        val count = Counter()

        try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Cannot read selected ZIP file.")

            java.util.zip.ZipInputStream(inputStream.buffered()).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name.replace('\\', '/')
                    if (!entryName.startsWith("__MACOSX/") && !entryName.endsWith("/.DS_Store") && entryName != ".DS_Store") {
                        count.entries++
                        require(count.entries <= 100_000) { "ZIP archive contains too many files." }

                        val cleanPath = entryName.trimStart('/')
                        val file = File(staging, cleanPath).canonicalFile
                        require(file.toPath().startsWith(staging.canonicalFile.toPath())) {
                            "Zip slip detected: unsafe path $entryName"
                        }

                        if (entry.isDirectory) {
                            file.mkdirs()
                        } else {
                            file.parentFile?.mkdirs()
                            file.outputStream().buffered().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val read = zis.read(buffer)
                                    if (read < 0) break
                                    count.bytes += read
                                    require(count.bytes <= 4L * 1024 * 1024 * 1024) { "Project exceeds 4 GiB import limit." }
                                    output.write(buffer, 0, read)
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            val stagingFiles = staging.listFiles()
            check(!stagingFiles.isNullOrEmpty()) { "The ZIP archive is empty or contains no valid files." }

            // If the zip contained a single top-level folder (e.g. MyProject-main/...), unwrap it
            val effectiveStaging = if (stagingFiles.size == 1 && stagingFiles[0].isDirectory) {
                stagingFiles[0]
            } else {
                staging
            }

            val effectiveName = if (effectiveStaging != staging && baseName.lowercase() in listOf("archive", "download", "project", "master", "main", "source")) {
                safeSegment(effectiveStaging.name)
            } else {
                baseName
            }
            var destination = File(root, effectiveName)
            var suffix = 2
            while (destination.exists()) destination = File(root, "$effectiveName-${suffix++}")

            if (effectiveStaging == staging) {
                if (!staging.renameTo(destination)) {
                    staging.copyRecursively(destination, overwrite = true)
                    staging.deleteRecursively()
                }
            } else {
                if (!effectiveStaging.renameTo(destination)) {
                    effectiveStaging.copyRecursively(destination, overwrite = true)
                }
                staging.deleteRecursively()
            }

            sanitizeProjectForAndroidHost(destination)
            syncProjectToPublicStorage(destination)
            destination
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    private fun copyTree(source: DocumentFile, targetDir: File, count: Counter, depth: Int) {
        require(depth <= 64) { "Project folder nesting is too deep." }
        source.listFiles().forEach { child ->
            count.entries++
            require(count.entries <= 100_000) { "Project contains too many files." }
            val originalName = child.name ?: "unnamed"
            val safeName = safeSegment(originalName)
            // SAF providers can expose different entries with the same display name
            // (for example synthetic/virtual entries). Never abort the whole import:
            // allocate a stable, collision-free sibling name instead.
            val name = uniqueSafeChildName(targetDir, safeName)
            val target = File(targetDir, name).canonicalFile
            require(target.toPath().startsWith(targetDir.canonicalFile.toPath())) { "Unsafe project entry name." }
            if (child.isDirectory) {
                check(target.mkdirs() || target.isDirectory)
                copyTree(child, target, count, depth + 1)
            } else {
                val input = context.contentResolver.openInputStream(child.uri) ?: throw IOException("Cannot read $name")
                input.use { sourceStream -> target.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val n = sourceStream.read(buffer); if (n < 0) break
                        count.bytes += n
                        require(count.bytes <= 4L * 1024 * 1024 * 1024) { "Project exceeds 4 GiB import limit." }
                        output.write(buffer, 0, n)
                    }
                } }
            }
        }
    }

    fun listProjects(): List<File> {
        return root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    suspend fun createFromTemplate(
        type: TemplateType,
        rawName: String,
        rawPackage: String
    ): File = withContext(Dispatchers.IO) {
        val appName = rawName.trim().ifBlank { type.defaultAppPrefix }
        val safeName = safeSegment(appName)
        var destination = File(root, safeName)
        var suffix = 2
        while (destination.exists()) destination = File(root, "$safeName-${suffix++}")

        val cleanPackage = rawPackage.trim().ifBlank { type.defaultPackage }
            .filter { it.isLetterOrDigit() || it == '.' || it == '_' }
            .ifBlank { "com.mobilestudio.app" }

        val staging = File(root, ".template-${System.nanoTime()}").apply { check(mkdirs()) }
        try {
            ProjectTemplateGenerator.generate(
                targetDir = staging,
                type = type,
                appName = appName,
                packageName = cleanPackage
            )
            if (!staging.renameTo(destination)) {
                staging.copyRecursively(destination, overwrite = true)
                staging.deleteRecursively()
            }
            sanitizeProjectForAndroidHost(destination)
            syncProjectToPublicStorage(destination)
            destination
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
    }

    fun deleteProject(projectDir: File): Boolean {
        if (!projectDir.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) return false
        val removed = projectDir.deleteRecursively()
        if (removed) removeProjectFromPublicStorage(projectDir.name)
        return removed
    }

    /**
     * Sanitizes any imported or newly created project so that it runs safely on Android 11+ ARM64 hosts:
     * 1. Injects android:allowNativeHeapPointerTagging="false" in AndroidManifest.xml.
     * 2. Configures -XX:-UseCompressedOops and -XX:-UseCompressedClassPointers in gradle.properties.
     * 3. Prevents out-of-memory crashes by capping desktop JVM heap limits to mobile-safe 768m.
     */
    fun sanitizeProjectForAndroidHost(projectDir: File) {
        runCatching {
            val gradleProps = File(projectDir, "gradle.properties")
            if (!gradleProps.isFile) {
                gradleProps.writeText(
                    "org.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=256m -XX:-UseCompressedOops -XX:-UseCompressedClassPointers -Dfile.encoding=UTF-8\n" +
                        "org.gradle.daemon=false\n" +
                        "org.gradle.parallel=false\n" +
                        "android.useAndroidX=true\n"
                )
            } else {
                var content = gradleProps.readText()
                var modified = false
                val highHeaps = listOf("-Xmx4096m", "-Xmx3072m", "-Xmx2048m", "-Xmx4g", "-Xmx3g", "-Xmx2g")
                for (h in highHeaps) {
                    if (content.contains(h)) {
                        content = content.replace(h, "-Xmx768m")
                        modified = true
                    }
                }
                if (!content.contains("UseCompressedOops")) {
                    content = if (content.contains("org.gradle.jvmargs=")) {
                        content.replace("org.gradle.jvmargs=", "org.gradle.jvmargs=-XX:-UseCompressedOops -XX:-UseCompressedClassPointers ")
                    } else {
                        content + "\norg.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=256m -XX:-UseCompressedOops -XX:-UseCompressedClassPointers -Dfile.encoding=UTF-8\n"
                    }
                    modified = true
                }
                if (modified) {
                    gradleProps.writeText(content)
                }
            }

            projectDir.walkTopDown()
                .filter { it.isFile && it.name == "AndroidManifest.xml" }
                .forEach { manifest ->
                    var xml = manifest.readText()
                    if (xml.contains("<application") && !xml.contains("allowNativeHeapPointerTagging")) {
                        xml = xml.replace("<application", "<application\n        android:allowNativeHeapPointerTagging=\"false\"")
                        manifest.writeText(xml)
                    }
                }
        }
    }

    private fun safeSegment(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
        .trim('.', '_', '-').take(100).ifBlank { "ImportedProject" }

    private fun uniqueSafeChildName(parent: File, safeName: String): String {
        if (!File(parent, safeName).exists()) return safeName
        val dot = safeName.lastIndexOf('.')
        val hasExtension = dot > 0
        val stem = if (hasExtension) safeName.substring(0, dot) else safeName
        val extension = if (hasExtension) safeName.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = "$stem-$index$extension"
            if (!File(parent, candidate).exists()) return candidate
            index++
        }
    }

    private class Counter(var entries: Int = 0, var bytes: Long = 0)
}
