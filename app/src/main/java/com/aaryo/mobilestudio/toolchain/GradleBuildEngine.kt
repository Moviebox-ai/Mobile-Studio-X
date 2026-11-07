package com.aaryo.mobilestudio.toolchain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class GradleBuildEngine(
    private val runner: ToolProcessRunner = ToolProcessRunner(),
    /**
     * Optional file that remembers the JVM flag set that booted Gradle on this device, so every
     * later build skips the (slow) bootstrap probing entirely.
     */
    var flagCacheFile: File? = null
) {

    private companion object {
        /** AGP 9.1.1 requires Gradle 9.3.1 or newer. */
        const val MINIMUM_GRADLE_VERSION = "9.3.1"
    }

    private fun launcherVersionForLog(name: String): String =
        Regex("^gradle-launcher-(.+)\\.jar$").matchEntire(name)?.groupValues?.get(1) ?: "unknown"

    suspend fun run(context: BuildContext, onLine: (String) -> Unit): Result<File> = withContext(Dispatchers.IO) { runCatching {
        onLine("BUILD: Initializing Gradle build engine...")
        onLine("BUILD: Project: ${context.projectDir.absolutePath}")
        onLine("BUILD: Requested task: ${context.task}")
        require(context.projectDir.isDirectory) { "Project directory does not exist." }
        require(File(context.projectDir, "settings.gradle").isFile ||
            File(context.projectDir, "settings.gradle.kts").isFile) {
            "Selected folder is not a Gradle project (settings.gradle[.kts] missing)."
        }
        onLine("BUILD: Validating project structure and Android ARM64 toolchain...")
        // Never trust a single hard-coded path: installs have landed in runtime/ and jdk/<slot>/.
        val javaExecutable = File(context.runtimeDir, "bin/java").takeIf { it.isFile && it.canExecute() }
            ?: AndroidToolchain.findJava(context.toolchainRoot)
            ?: error(
                "No Android ARM64 JDK found (looked for runtime/bin/java and jdk/*/bin/java under " +
                    "${context.toolchainRoot.absolutePath}). Open Tools ▸ Toolchain and tap " +
                    "\u201CDownload & install\u201D to install JDK 17 before building."
            )
        if (!javaExecutable.canExecute()) runCatching { javaExecutable.setExecutable(true, false) }
        onLine("BUILD: Java runtime: ${javaExecutable.absolutePath}")
        AndroidToolchain.jdkVerificationError(context.toolchainRoot)?.let { reason ->
            onLine("BUILD: [WARN] This JDK failed its installation smoke test earlier: $reason")
            onLine("BUILD: [WARN] Trying it anyway — if the JVM aborts, reinstall JDK 17 from Tools ▸ Toolchain.")
        }
        val lib = File(context.gradleDir, "lib")
        val launcher = lib.listFiles()?.firstOrNull {
            it.name.startsWith("gradle-launcher-") && it.extension == "jar"
        } ?: error("Gradle launcher missing: gradle/lib/gradle-launcher-*.jar")
        onLine("BUILD: Gradle ${launcherVersionForLog(launcher.name)} launcher found; preparing runtime classpath...")
        val allGradleJars = (lib.listFiles().orEmpty().filter { it.isFile && it.extension == "jar" } +
            File(lib, "plugins").listFiles().orEmpty().filter { it.isFile && it.extension == "jar" })
            .distinctBy { it.absolutePath }

        // The Android-host runtime must use a Gradle version AGP 9.1.1 understands.
        // AGP 9.1.1 requires Gradle >= 9.3.1; an 8.x runtime (seen on affected devices) is not a
        // valid substitute, but a newer 9.x distribution is fine.
        val launcherVersion = launcherVersionForLog(launcher.name)
        val launcherParts = Regex("\\d+").findAll(launcherVersion).mapNotNull { it.value.toIntOrNull() }.toList()
        val launcherTooOld = launcherParts.isNotEmpty() &&
            AndroidToolchain.compareVersions(launcherVersion, MINIMUM_GRADLE_VERSION) < 0
        require(!launcherTooOld) {
            "Incompatible bundled Gradle $launcherVersion. AGP 9.1.1 requires Gradle $MINIMUM_GRADLE_VERSION " +
                "or newer on the Android host. Reinstall/update the Gradle component in the Toolchain screen."
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
        // projectDir can live directly in the workspace root, where parentFile may be null.
        val scratchParent = context.projectDir.absoluteFile.parentFile ?: context.projectDir
        val homeDir = File(scratchParent, ".mobilestudio-home").apply { mkdirs() }
        val tmpDir = File(scratchParent, ".mobilestudio-tmp").apply { mkdirs() }
        val gradleUserHome = File(scratchParent, ".mobilestudio-gradle").apply { mkdirs() }

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
        require(aapt2Binary?.isFile == true) {
            "Android Build Tools are missing AAPT2. AGP would download the x86_64 Linux AAPT2, which cannot " +
                "run on an Android device. Install/repair the Build Tools component in the Toolchain screen."
        }
        onLine("BUILD: Using Build Tools ${buildToolsDir?.name ?: "?"} with on-device AAPT2: ${aapt2Binary?.absolutePath}")

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
        val javaProbeText = javaProbe.output.joinToString("\n")
        if (javaProbe.exitCode != 0) {
            // This is where most broken devices land, and where the old code burned minutes in a
            // retry ladder: a JVM that cannot even print its version will never boot Gradle.
            error(jvmCannotBootMessage(javaProbe.exitCode, javaProbeText))
        }
        if (!javaProbeText.contains("version", ignoreCase = true) && !javaProbeText.contains("openjdk", ignoreCase = true)) {
            onLine("BUILD: [WARN] 'java -version' printed no version banner; continuing with the installed runtime.")
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

        // Android devices differ (page size, tagged pointers, CDS archive), so a bootstrap probe is
        // still the only reliable way to find a working flag set - but it has to be cheap. The old
        // ladder tried five sets with a two minute timeout each, ending in interpreter-only (-Xint)
        // mode. That froze the UI for many minutes and never helped: a JVM that aborts with
        // "Pointer tag ... was truncated" dies for every flag set because the abort comes from the
        // allocator, not from the JIT.
        val cachedExtra = readCachedFlags()
        val taggingSafe = listOf("-XX:-UseCompressedOops", "-XX:-UseCompressedClassPointers")
        // Devices that abort with "Pointer tag ... was truncated" are often only *marginally*
        // broken: the JVM starts, then dies while Gradle allocates. A much smaller heap and no
        // tiered JIT keeps the native footprint low enough that boot can succeed.
        val conservative = taggingSafe + listOf(
            "-Xshare:off", "-XX:TieredStopAtLevel=1",
            "-Xmx512m", "-Xss512k", "-XX:MaxMetaspaceSize=256m"
        )
        val flagLadder = buildList {
            cachedExtra?.let { add(it) }
            add(emptyList())
            add(taggingSafe)
            add(conservative)
        }.distinct()

        // The heap-tagging shim is our own hand-built ELF. If the device's linker rejects it the
        // JVM can abort on its own, so the probe also runs once without LD_PRELOAD.
        val probeEnvironments = listOf(
            env,
            env.toMutableMap().apply {
                remove("LD_PRELOAD")
                put("MO_SKIP_TAGFIX", "1")
            }
        )

        onLine("BUILD: Starting Gradle JVM bootstrap check (at most ${flagLadder.size} flag sets, 60s each).")
        var chosenExtra: List<String>? = null
        var chosenEnv: MutableMap<String, String> = env
        var lastProbe: ProcessResult? = null
        var taggedAbort: String? = null
        val probeDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(180)
        probeLoop@ for ((envIndex, probeEnv) in probeEnvironments.withIndex()) {
            if (envIndex > 0) onLine("BUILD: Retrying the JVM probe without the LD_PRELOAD compatibility shim...")
            for (extra in flagLadder) {
                if (System.nanoTime() > probeDeadline) {
                    onLine("BUILD: JVM probing budget exhausted; stopping here.")
                    break@probeLoop
                }
                val label = if (extra.isEmpty()) "default flags" else extra.joinToString(" ")
                onLine("BUILD: Gradle JVM probe ($label)...")
                val probe = runner.run(
                    gradleCommand(extra, listOf("--version")),
                    context.projectDir,
                    probeEnv,
                    onLine,
                    timeoutMinutes = 1
                )
                lastProbe = probe
                if (probe.exitCode == 0) {
                    chosenExtra = extra
                    chosenEnv = probeEnv
                    break@probeLoop
                }
                val text = probe.output.joinToString("\n")
                if (looksLikeTaggedPointerAbort(text)) {
                    taggedAbort = text
                    // The allocator, not the JIT, killed the JVM: only the most conservative flag
                    // set is worth one more try, everything else is a guaranteed repeat.
                    onLine("BUILD: JVM aborted with an Android heap-tagging error; trying the most conservative flags next.")
                    continue
                }
                onLine("BUILD: JVM probe failed (exit ${probe.exitCode}).")
            }
        }

        if (chosenExtra == null) {
            val fatal = collectFatalLogs(listOf(tmpDir, context.projectDir, context.projectDir.parentFile))
            val details = lastProbe?.output?.takeLast(40)?.joinToString("\n").orEmpty()
            error(
                if (taggedAbort != null) {
                    jvmCannotBootMessage(lastProbe?.exitCode ?: -1, taggedAbort)
                } else {
                    "Gradle JVM bootstrap failed (exit ${lastProbe?.exitCode ?: -1}) before any project task ran. " +
                        "The JDK or the Gradle distribution is unusable on this device.\n" +
                        "Reinstall JDK 17 and Gradle 9.3.1 from Tools > Toolchain, then try again.\n" +
                        details +
                        if (fatal.isNotBlank()) "\nJVM FATAL LOGS:\n$fatal" else "\nNo hs_err_pid log was produced."
                }
            )
        }

        // Remember the working flag set (and whether the shim had to be dropped) for the next build.
        writeCachedFlags(chosenExtra!!)

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
        val result = runner.run(command, context.projectDir, chosenEnv, onLine, timeoutMinutes = 60)
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
    }

    /**
     * A JVM that cannot start is a device/toolchain problem, not a project problem. Say which one
     * it is and stop - retrying flag sets is what made the app appear to hang.
     */
    private fun jvmCannotBootMessage(exitCode: Int, output: String): String {
        val tail = output.lineSequence().filter { it.isNotBlank() }.toList().takeLast(20).joinToString("\n")
        return if (looksLikeTaggedPointerAbort(output)) {
            "The on-device JVM cannot start on this device: Android aborted it with \"Pointer tag ... was truncated\" " +
                "(a heap-tagging incompatibility, exit $exitCode). The build stopped here instead of retrying JVM flag " +
                "sets for minutes.\n" +
                "What to do:\n" +
                "  1. Tools > Toolchain > Download & install   (reinstalls the bundled Android ARM64 JDK 17)\n" +
                "  2. If your device has Developer options > Memory Tagging Extension / heap tagging, disable it and reinstall this app\n" +
                "  3. Build this project on a PC with Android Studio - the generated Gradle files are standard\n" +
                "Raw JVM output:\n$tail"
        } else {
            "The installed Android ARM64 Java runtime cannot start (exit $exitCode), so no Gradle build can run.\n" +
                "Reinstall JDK 17 from Tools > Toolchain (Download & install or Install local ZIP).\n" +
                "Raw JVM output:\n$tail"
        }
    }

    /** The allocator's own message - more flag sets cannot fix it. */
    private fun looksLikeTaggedPointerAbort(text: String): Boolean =
        text.contains("Pointer tag for", ignoreCase = true) || text.contains("tagged-pointers", ignoreCase = true)

    private fun readCachedFlags(): List<String>? = runCatching {
        val file = flagCacheFile ?: return null
        if (!file.isFile) return null
        val line = file.readText().trim()
        if (line.isEmpty()) null else line.split(' ').filter { it.isNotBlank() }
    }.getOrNull()

    private fun writeCachedFlags(flags: List<String>) {
        runCatching {
            val file = flagCacheFile ?: return
            file.writeText(flags.joinToString(" "))
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