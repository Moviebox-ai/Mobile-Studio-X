package com.aaryo.mobilestudio.toolchain

import java.io.File

class GradleBuildEngine(private val runner: ToolProcessRunner = ToolProcessRunner()) {
    private fun launcherVersionForLog(name: String): String =
        Regex("^gradle-launcher-(.+)\\.jar$").matchEntire(name)?.groupValues?.get(1) ?: "unknown"

    suspend fun run(context: BuildContext, onLine: (String) -> Unit): Result<File> = runCatching {
        onLine("BUILD: Initializing Gradle build engine...")
        onLine("BUILD: Project: ${context.projectDir.absolutePath}")
        onLine("BUILD: Requested task: ${context.task}")
        require(context.projectDir.isDirectory) { "Project directory does not exist." }
        require(File(context.projectDir, "settings.gradle").isFile ||
            File(context.projectDir, "settings.gradle.kts").isFile) {
            "Selected folder is not a Gradle project (settings.gradle[.kts] missing)."
        }
        onLine("BUILD: Validating project structure and Android ARM64 toolchain...")
        val javaExecutable = File(context.runtimeDir, "bin/java")
        require(javaExecutable.isFile && javaExecutable.canExecute()) {
            "Android-host ARM64 JDK missing or not executable: $javaExecutable"
        }
        val lib = File(context.gradleDir, "lib")
        val launcher = lib.listFiles()?.firstOrNull {
            it.name.startsWith("gradle-launcher-") && it.extension == "jar"
        } ?: error("Gradle launcher missing: gradle/lib/gradle-launcher-*.jar")
        onLine("BUILD: Gradle ${launcherVersionForLog(launcher.name)} launcher found; preparing runtime classpath...")
        val allGradleJars = (lib.listFiles().orEmpty().filter { it.isFile && it.extension == "jar" } +
            File(lib, "plugins").listFiles().orEmpty().filter { it.isFile && it.extension == "jar" })
            .distinctBy { it.absolutePath }

        // The Android-host runtime must use the Gradle version required by this project.
        // AGP 9.1.1 is paired with Gradle 9.3.1; an 8.13 runtime (seen on affected devices)
        // is not a valid substitute even if the project wrapper says 9.3.1.
        val launcherVersion = Regex("^gradle-launcher-(.+)\\.jar$")
            .matchEntire(launcher.name)?.groupValues?.get(1)
        require(launcherVersion == "9.3.1") {
            "Incompatible bundled Gradle $launcherVersion. This project uses AGP 9.1.1 and requires " +
                "the Android-host Gradle 9.3.1 distribution. Reinstall/update the toolchain Gradle folder."
        }

        // Gradle's Linux/AArch64 JNI payloads target glibc Linux, not Android/Bionic.
        // Keep the Java API jar but omit Linux-specific native payload jars so Gradle can
        // fall back to its Java implementation where supported.
        // Gradle ships glibc/Linux native integrations. They are not Android/Bionic
        // binaries, even when the device ABI is AArch64. Exclude both native-platform
        // and file-events Linux payloads; Gradle falls back to Java/polling where supported.
        val jars = allGradleJars.filterNot { jar ->
            val name = jar.name.lowercase()
            (name.startsWith("native-platform-linux-") ||
                name.startsWith("native-platform-linux_") ||
                name.startsWith("file-events-linux-") ||
                name.startsWith("file-events-linux_"))
        }
        require(jars.any { it == launcher }) { "Gradle launcher missing from runtime classpath." }
        val classpath = jars.joinToString(File.pathSeparator) { it.absolutePath }
        val root = context.toolchainRoot
        val sdk = File(root, "sdk")
        val homeDir = File(context.projectDir.parentFile, ".mobilestudio-home").apply { mkdirs() }
        val tmpDir = File(context.projectDir.parentFile, ".mobilestudio-tmp").apply { mkdirs() }
        val gradleUserHome = File(context.projectDir.parentFile, ".mobilestudio-gradle").apply { mkdirs() }

        // AGP downloads an x86_64 Linux aapt2 from Maven by default. It cannot execute on
        // an Android ARM64 device, so every APK build fails at resource processing.
        // Point AGP at the ARM64 aapt2 from the installed toolchain instead.
        fun versionKey(name: String): List<Int> =
            Regex("\\d+").findAll(name).mapNotNull { it.value.toIntOrNull() }.toList()
        val buildToolsDir = File(sdk, "build-tools").listFiles().orEmpty()
            .filter { it.isDirectory && File(it, "aapt2").isFile }
            .sortedWith { a, b ->
                val av = versionKey(a.name); val bv = versionKey(b.name)
                var cmp = 0
                for (i in 0 until maxOf(av.size, bv.size)) {
                    cmp = (av.getOrNull(i) ?: 0).compareTo(bv.getOrNull(i) ?: 0)
                    if (cmp != 0) break
                }
                cmp
            }.lastOrNull()
        val aapt2Binary = buildToolsDir?.let { File(it, "aapt2") }
        aapt2Binary?.let { it.setExecutable(true, false) }

        // Pre-accept the standard SDK licenses so AGP never tries (and fails) to auto-download
        // components through a missing sdkmanager.
        runCatching {
            val licenses = File(sdk, "licenses").apply { mkdirs() }
            File(licenses, "android-sdk-license").takeIf { !it.isFile }?.writeText(
                "\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n" +
                    "8933bad161af4178b1185d1a37fbf41ea5269c55\n" +
                    "d56f5187479451eabf01fb78af6dfcb131a6481e\n" +
                    "84831b9409646a918e30573bab4c9c91346d8abd\n"
            )
            File(licenses, "android-sdk-preview-license").takeIf { !it.isFile }?.writeText(
                "\n84831b9409646a918e30573bab4c9c91346d8abd\n"
            )
        }

        // Configure Gradle daemon & worker memory limits so they don't abort with SIGABRT
        val gradleProperties = File(gradleUserHome, "gradle.properties")
        gradleProperties.writeText(
            buildString {
                appendLine("org.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:-UseCompressedOops -XX:-UseCompressedClassPointers -Dfile.encoding=UTF-8")
                appendLine("org.gradle.daemon=false")
                appendLine("org.gradle.parallel=false")
                appendLine("android.useAndroidX=true")
                if (aapt2Binary != null) appendLine("android.aapt2FromMavenOverride=${aapt2Binary.absolutePath}")
            }
        )

        // Keep imported project memory settings suitable for Android-hosted builds.
        val projectProps = File(context.projectDir, "gradle.properties")
        if (projectProps.isFile) {
            val text = projectProps.readText()
            var updated = text
            if (updated.contains("org.gradle.jvmargs=")) {
                val lineRegex = Regex("(?m)^org\\.gradle\\.jvmargs=.*$")
                val currentLine = lineRegex.find(updated)?.value.orEmpty()
                var args = currentLine.substringAfter("=", "")
                args = args.replace(Regex("-Xmx(?:768m|1024m|1536m|2048m|3072m|4096m|1g|2g|3g|4g)"), "-Xmx1024m")
                args = args.replace(Regex("-XX:MaxMetaspaceSize=\\d+[mMgG]"), "-XX:MaxMetaspaceSize=512m")
                args = args.replace(Regex("\\s+"), " ").trim()
                if (!args.contains("-Xmx")) args = "-Xmx1024m $args"
                if (!args.contains("MaxMetaspaceSize")) args = "$args -XX:MaxMetaspaceSize=512m"
                updated = updated.replace(currentLine, "org.gradle.jvmargs=$args")
            } else {
                updated += "\norg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8\n"
            }
            updated = updated.replace("org.gradle.parallel=true", "org.gradle.parallel=false")
            if (updated != text) runCatching { projectProps.writeText(updated) }
        }

        val env = System.getenv().toMutableMap().apply {
            // Do not inherit injected JVM options from the parent IDE process. A stray
            // JAVA_TOOL_OPTIONS/_JAVA_OPTIONS can change the child VM or trigger a crash.
            remove("JAVA_TOOL_OPTIONS")
            remove("_JAVA_OPTIONS")
            remove("JDK_JAVA_OPTIONS")
            remove("GRADLE_OPTS")
            remove("CLASSPATH")
            put("JAVA_HOME", context.runtimeDir.absolutePath)
            put("ANDROID_HOME", sdk.absolutePath)
            put("ANDROID_SDK_ROOT", sdk.absolutePath)
            put("ANDROID_NDK_HOME", File(root, "ndk").absolutePath)
            put("ANDROID_NDK_ROOT", File(root, "ndk").absolutePath)
            put("GRADLE_USER_HOME", gradleUserHome.absolutePath)
            put("HOME", homeDir.absolutePath)
            put("TMPDIR", tmpDir.absolutePath)
            put("ANDROID_USER_HOME", File(homeDir, ".android").apply { mkdirs() }.absolutePath)
            val buildTools = buildToolsDir
            put("PATH", listOf(File(context.runtimeDir, "bin"), buildTools, File(root, "ndk"))
                .filterNotNull().joinToString(File.pathSeparator) { it.absolutePath } +
                File.pathSeparator + System.getenv("PATH").orEmpty())
            // Ensure Bionic pointer-tagging fix shim is preloaded into child JVM processes
            BionicTagFixShim.applyToEnvironment(context.toolchainRoot, this)
        }

        // Fail early with an actionable runtime error instead of reporting a vague Gradle failure.
        onLine("BUILD: Checking Java runtime on-device...")
        var javaProbe = runner.run(
            listOf(javaExecutable.absolutePath, "-version"),
            context.projectDir,
            env,
            onLine,
            timeoutMinutes = 1
        )
        // If a particular device's dynamic linker rejects our generated LD_PRELOAD shim,
        // automatically fall back to running without LD_PRELOAD so it never blocks valid JDKs.
        if (javaProbe.exitCode != 0) {
            env.remove("LD_PRELOAD")
            env["MO_SKIP_TAGFIX"] = "1"
            javaProbe = runner.run(
                listOf(javaExecutable.absolutePath, "-version"),
                context.projectDir,
                env,
                onLine,
                timeoutMinutes = 1
            )
        }
        check(javaProbe.exitCode == 0 && javaProbe.output.any { it.contains("version", true) }) {
            "The installed Android ARM64 Java runtime cannot start (exit ${javaProbe.exitCode}). " +
                "Reinstall a Bionic-compatible ARM64 JDK 17. Output: ${javaProbe.output.takeLast(20).joinToString("\n")}"
        }

        // The same JVM flags are used for the bootstrap probe and the real build. Previously the
        // probe omitted -Dorg.gradle.native=false, so Gradle tried to load glibc/Linux native
        // integrations and the JVM aborted (exit 134) before the build even started.
        val errorFile = File(tmpDir, "hs_err_pid%p.log").absolutePath
        val baseJvmFlags = listOf(
            "-Xmx1024m",
            "-Dfile.encoding=UTF-8",
            "-Djava.io.tmpdir=${tmpDir.absolutePath}",
            "-Duser.home=${homeDir.absolutePath}",
            "-XX:MaxMetaspaceSize=512m",
            "-XX:+UseSerialGC",
            "-XX:-UsePerfData", // no /tmp/hsperfdata on Android
            "-XX:ErrorFile=$errorFile",
            "-Dorg.gradle.native=false",
            "-Dorg.gradle.vfs.watch=false",
            "-Dorg.gradle.internal.instrumentation.agent=false"
        )
        fun gradleCommand(extra: List<String>, args: List<String>) =
            listOf(javaExecutable.absolutePath) + baseJvmFlags + extra +
                listOf("-cp", classpath, "org.gradle.launcher.GradleMain") + args

        // Android devices differ (page size, tagged pointers, CDS archive). Try progressively
        // more conservative JVM flag sets and keep the first one that boots Gradle.
        val flagLadder = listOf(
            emptyList(),
            listOf("-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers"),
            listOf("-Xshare:off", "-XX:TieredStopAtLevel=1"),
            listOf("-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers", "-Xshare:off", "-XX:TieredStopAtLevel=1"),
            // Last resort: interpreter only. JIT-generated code is a common place where tagged
            // pointers get truncated. Very slow, but better than a hard failure.
            listOf("-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers", "-Xshare:off", "-Xint")
        )
        onLine("BUILD: Starting Gradle JVM bootstrap checks. No project task has started yet.")
        var chosenExtra: List<String>? = null
        var lastProbe: ProcessResult? = null
        for ((index, extra) in flagLadder.withIndex()) {
            if (index > 0) onLine("Gradle bootstrap failed; retrying with JVM flags: ${extra.joinToString(" ")}")
            if (extra.contains("-Xint")) onLine("WARNING: interpreter-only mode is very slow; the build may take a long time.")
            onLine("BUILD: Gradle bootstrap attempt ${index + 1}/${flagLadder.size}...")
            val probe = runner.run(gradleCommand(extra, listOf("--version")), context.projectDir, env, onLine, timeoutMinutes = 2)
            lastProbe = probe
            if (probe.exitCode == 0) { chosenExtra = extra; break }
        }
        if (chosenExtra == null) {
            val probe = lastProbe!!
            val fatal = collectFatalLogs(listOf(tmpDir, context.projectDir, context.projectDir.parentFile))
            error(
                "Gradle JVM bootstrap failed (exit ${probe.exitCode}) with all JVM flag sets. " +
                    "This is before project compilation; verify the Android/Bionic JDK and Gradle distribution.\n" +
                    probe.output.takeLast(60).joinToString("\n") +
                    if (fatal.isNotBlank()) "\nJVM FATAL LOGS:\n$fatal" else "\nNo hs_err_pid log was produced."
            )
        }

        val workingExtra: List<String> = chosenExtra!!
        // The forked build JVM reads org.gradle.jvmargs; give it the same working flags.
        runCatching {
            val daemonArgs = (baseJvmFlags.filterNot { it.startsWith("-Djava.io.tmpdir") || it.startsWith("-Duser.home") } +
                workingExtra).joinToString(" ")
            gradleProperties.writeText(
                buildString {
                    appendLine("org.gradle.jvmargs=$daemonArgs")
                    appendLine("org.gradle.daemon=false")
                    appendLine("org.gradle.parallel=false")
                    appendLine("android.useAndroidX=true")
                    if (aapt2Binary != null) appendLine("android.aapt2FromMavenOverride=${aapt2Binary.absolutePath}")
                }
            )
        }

        onLine("BUILD: Gradle JVM ready. Launching project task '${context.task}'...")
        val command = gradleCommand(
            workingExtra,
            listOf("--no-daemon", "--console=plain", "--stacktrace", "--no-watch-fs") +
                listOfNotNull(aapt2Binary?.let { "-Pandroid.aapt2FromMavenOverride=${it.absolutePath}" }) +
                listOf(context.task)
        )
        onLine("BUILD: Process started; waiting for Gradle task output...")
        val result = runner.run(command, context.projectDir, env, onLine, timeoutMinutes = 60)
        onLine("BUILD: Gradle process exited with code ${result.exitCode}.")
        if (result.exitCode != 0) {
            val fatal = collectFatalLogs(listOf(tmpDir, context.projectDir, context.projectDir.parentFile))
            error(
                "Gradle build process failed (exit ${result.exitCode}).\n" +
                    result.output.takeLast(80).joinToString("\n") +
                    if (fatal.isNotBlank()) "\nJVM FATAL LOGS:\n$fatal" else "\nNo hs_err_pid log was produced. Check Android logcat/tombstone for native SIGABRT."
            )
        }
        fun newestOutput(ext: String, kind: String): File? = runCatching {
            context.projectDir.walkTopDown().maxDepth(8)
                .onEnter { it.name != ".git" && it.name != ".gradle" && it.name != "node_modules" }
                .filter { f ->
                    val path = f.invariantSeparatorsPath
                    f.isFile && f.extension == ext && path.contains("/build/outputs/$kind/")
                }
                .maxByOrNull { it.lastModified() }
        }.getOrNull()
        val artifact = when {
            context.task.contains("bundle", true) -> newestOutput("aab", "bundle")
            context.task.contains("assemble", true) -> newestOutput("apk", "apk")
            context.task == "clean" -> context.projectDir
            else -> null
        }
        if ((context.task.contains("assemble", true) || context.task.contains("bundle", true)) && artifact == null) {
            error("Gradle finished but no APK/AAB was found under */build/outputs/. Last output:\n" +
                result.output.takeLast(20).joinToString("\n"))
        }
        artifact ?: File(context.projectDir, "app/build").also {
            check(it.isDirectory) { "Gradle exited successfully but expected app/build output is missing: ${result.output.takeLast(20).joinToString("\n")}" }
        }
    }

    private fun collectFatalLogs(roots: List<File?>): String {
        val files = roots.filterNotNull().filter { it.exists() }
            .flatMap { root -> runCatching {
                if (root.isFile) listOf(root) else root.walkTopDown().maxDepth(5)
                    .filter { it.isFile && it.name.matches(Regex("hs_err_pid.*\\.log")) }
                    .toList()
            }.getOrDefault(emptyList()) }
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
            .take(3)
        return files.joinToString("\n\n") { file ->
            val tail = runCatching { file.readLines().takeLast(100).joinToString("\n") }
                .getOrDefault("Unable to read ${file.absolutePath}")
            "--- ${file.absolutePath} ---\n$tail"
        }
    }
}