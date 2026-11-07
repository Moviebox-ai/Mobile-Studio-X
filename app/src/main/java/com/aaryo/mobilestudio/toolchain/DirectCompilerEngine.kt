package com.aaryo.mobilestudio.toolchain

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Direct on-device APK compiler.
 *
 * The compiler drives the *real* Android build tools that ship with the on-device toolchain:
 *
 *  1. AAPT2 `compile` + `link`  → binary `AndroidManifest.xml`, compiled `resources.arsc`/`the compiled res directory`,
 *                                 and generated `R.java`.
 *  2. `javac` and/or the Kotlin compiler (`kotlinc`, K2 JVM backend) → `.class` files.
 *  3. D8                        → `classes.dex`.
 *  4. APK assembly              → linked resources + dex + assets + `lib/<abi>/ native .so files`.
 *  5. `zipalign`                → 4-byte aligned, uncompressed `resources.arsc`.
 *  6. `apksigner`               → v1 + v2 + v3 signature schemes, verified afterwards.
 *
 * Earlier versions of this class *fabricated* an APK: raw XML resources, a hand-written
 * `resources.arsc`, a synthetic `classes.dex` that never contained the user's code, and a v1
 * signature whose digests could not match the manifest. Such packages always failed to install
 * (`INSTALL_PARSE_FAILED_*`), even though the build reported success. The fabricated output has
 * been removed: if a required tool is missing, the build now fails with an instruction that says
 * exactly which component to install.
 */
class DirectCompilerEngine(
    private val context: Context,
    private val runner: ToolProcessRunner = ToolProcessRunner()
) {

    suspend fun compile(
        projectDir: File,
        isRelease: Boolean = false,
        keystoreFile: File? = null,
        keystorePassword: String? = null,
        keyAlias: String? = null,
        onLine: (String) -> Unit = {}
    ): Result<File> = runCatching {
        // Every stage below is blocking file/process work (resource copying, zip assembly,
        // dex packaging). Running it on the caller's main-thread dispatcher froze the UI.
        withContext(Dispatchers.IO) {
            build(projectDir, isRelease, keystoreFile, keystorePassword, keyAlias, onLine)
        }
    }

    private suspend fun build(
        projectDir: File,
        isRelease: Boolean,
        keystoreFile: File?,
        keystorePassword: String?,
        keyAlias: String?,
        onLine: (String) -> Unit
    ): File {
        val startedAt = System.currentTimeMillis()
        val variant = if (isRelease) "release" else "debug"
        onLine("BUILD: Mobile Studio X direct compiler (AAPT2 + kotlinc/javac + D8 + apksigner)")
        require(projectDir.isDirectory) { "Project directory does not exist: ${projectDir.absolutePath}" }

        // ---------------------------------------------------------------- 1. project analysis
        onLine("[1/7] CONFIG: reading manifest and Gradle configuration...")
        val layout = ProjectLayout.discover(projectDir)
        val manifestText = layout.manifest.readText()
        val gradleText = layout.gradleScriptText()
        val packageName = extractPackageName(manifestText, gradleText)
        val minSdk = extractInt(gradleText, "minSdk", 26)
        val targetSdk = maxOf(minSdk, extractInt(gradleText, "targetSdk", 36))
        val versionCode = extractInt(gradleText, "versionCode", 1)
        val versionName = extractString(gradleText, "versionName") ?: "1.0"
        val kotlinSources = layout.sourceFiles.filter { it.extension == "kt" }
        val javaSources = layout.sourceFiles.filter { it.extension == "java" }

        onLine("  • module: ${layout.moduleDir.name}")
        onLine("  • applicationId: $packageName")
        onLine("  • minSdk $minSdk · targetSdk $targetSdk · versionCode $versionCode ($versionName)")
        onLine("  • sources: ${kotlinSources.size} Kotlin, ${javaSources.size} Java")
        onLine("  • resources: ${layout.resourceFiles.size} file(s), assets: ${layout.assetFiles.size}")
        require(kotlinSources.isNotEmpty() || javaSources.isNotEmpty()) {
            "No Kotlin/Java sources found under ${layout.moduleDir}/src/main. Add a source file or use the Gradle engine for library-only modules."
        }

        // ---------------------------------------------------------------- toolchain validation
        val toolchain = AndroidToolchain.resolve(context)
        val missing = toolchain.missingComponents(kotlinSources.size)
        require(missing.isEmpty()) {
            buildString {
                appendLine("The on-device build toolchain is incomplete, so no APK was produced.")
                appendLine("Missing components:")
                missing.forEach { appendLine("  • $it") }
                appendLine()
                appendLine("Fix: open Tools ▸ Toolchain and tap \u201CDownload & install\u201D (or import the")
                appendLine("matching archive with \u201CInstall local ZIP\u201D), then build again. The Gradle engine")
                appendLine("can also be used once the same toolchain is installed.")
            }
        }
        onLine("  • toolchain: JDK ${toolchain.java?.parentFile?.parentFile?.name ?: "?"}, " +
            "build-tools ${toolchain.buildToolsVersion ?: "?"}, android.jar ${toolchain.androidJar?.name ?: "?"}")

        // javac, kotlinc, D8 and apksigner all run inside the JVM. If the device's JVM cannot even
        // print its version, every later step fails with a confusing tool error, so check it once
        // here and explain the real cause.
        val jvmProbe = runner.run(
            listOf(toolchain.java!!.absolutePath, "-version"),
            layout.moduleDir,
            onLine = onLine,
            timeoutMinutes = 1
        )
        val jvmText = jvmProbe.output.joinToString("\n")
        if (jvmProbe.exitCode != 0) {
            val tagged = jvmText.contains("Pointer tag for", true) || jvmText.contains("tagged-pointers", true)
            error(
                if (tagged) {
                    "The on-device JVM cannot start on this device: Android aborted it with \"Pointer tag ... was truncated\" " +
                        "(heap-tagging incompatibility, exit ${jvmProbe.exitCode}). javac, kotlinc, D8 and apksigner all need this JVM.\n" +
                        "Fix: Tools > Toolchain > Download & install to reinstall JDK 17. If your device has Developer options > " +
                        "Memory Tagging Extension / heap tagging, disable it and reinstall this app.\n" +
                        "Raw JVM output:\n$jvmText"
                } else {
                    "The Android ARM64 JDK at ${toolchain.java?.absolutePath} cannot run (exit ${jvmProbe.exitCode}). " +
                        "Reinstall JDK 17 from Tools > Toolchain.\nRaw JVM output:\n$jvmText"
                }
            )
        }
        if (!jvmText.contains("version", true) && !jvmText.contains("openjdk", true)) {
            onLine("  • [WARN] 'java -version' printed no version banner; continuing anyway.")
        }

        val workRoot = File(layout.moduleDir, "build/mobilestudio-direct").apply { cleanDirectory() }
        val genDir = File(workRoot, "gen").apply { requireMkdirs() }
        val classesDir = File(workRoot, "classes").apply { requireMkdirs() }
        val dexDir = File(workRoot, "dex").apply { requireMkdirs() }
        val tmpDir = File(workRoot, "tmp").apply { requireMkdirs() }

        // ---------------------------------------------------------------- 2. resources
        onLine("[2/7] RESOURCES: AAPT2 compile + link...")
        val compiledRes = File(workRoot, "compiled-resources.zip")
        if (layout.resourceFiles.isNotEmpty()) {
            val resDir = layout.resDir ?: error("Resource files were found without a res/ directory.")
            runner.requireSuccess(
                listOf(
                    toolchain.aapt2!!.absolutePath, "compile",
                    "--dir", resDir.absolutePath,
                    "-o", compiledRes.absolutePath
                ),
                workRoot, onLine = onLine, timeoutMinutes = 10
            )
        } else {
            onLine("  • no resources to compile; linking manifest only")
        }

        val linkedApk = File(workRoot, "linked.apk")
        val manifestForLink = ensureManifestPackage(layout.manifest, manifestText, packageName, workRoot)
        val linkArgs = mutableListOf(
            toolchain.aapt2!!.absolutePath, "link",
            "-o", linkedApk.absolutePath,
            "-I", toolchain.androidJar!!.absolutePath,
            "--manifest", manifestForLink.absolutePath,
            "--java", genDir.absolutePath,
            "--min-sdk-version", minSdk.toString(),
            "--target-sdk-version", targetSdk.toString(),
            "--version-code", versionCode.toString(),
            "--version-name", versionName
        )
        if (compiledRes.isFile) linkArgs += compiledRes.absolutePath
        runner.requireSuccess(linkArgs, workRoot, onLine = onLine, timeoutMinutes = 10)
        check(linkedApk.isFile) { "AAPT2 did not produce ${linkedApk.name}." }
        onLine("  • linked resources: ${linkedApk.length()} bytes")

        // ---------------------------------------------------------------- 3. sources
        onLine("[3/7] SOURCES: compiling Kotlin/Java...")
        val rClassDirs = compileGeneratedRClass(toolchain, genDir, workRoot, classesDir, onLine)
        val dependencyJars = layout.dependencyJars
        val compileClasspath = buildList {
            add(toolchain.androidJar!!)
            toolchain.kotlinStdlibJar?.let { add(it) }
            addAll(rClassDirs)
            addAll(dependencyJars)
        }.distinctBy { it.absolutePath }

        if (javaSources.isNotEmpty()) {
            compileJava(toolchain, javaSources, compileClasspath, classesDir, workRoot, onLine)
        }
        if (kotlinSources.isNotEmpty()) {
            compileKotlin(toolchain, kotlinSources, compileClasspath, classesDir, workRoot, tmpDir, onLine)
        }
        val classFiles = classesDir.walkTopDown().filter { it.isFile && it.extension == "class" }.toList()
        check(classFiles.isNotEmpty()) {
            "Compilation produced no .class files. Review the compiler output above."
        }
        onLine("  • compiled ${classFiles.size} class file(s)")

        // ---------------------------------------------------------------- 4. dex
        onLine("[4/7] DEX: running D8...")
        val classesJar = File(workRoot, "classes.jar")
        zipDirectory(classesDir, classesJar)
        // The Kotlin standard library is a runtime dependency: unlike Gradle (which packages the
        // dependencies it resolves), the direct compiler has to feed it to D8 as program input,
        // otherwise the built APK crashes with NoClassDefFoundError: kotlin/jvm/internal/Intrinsics.
        val dexProgramInputs = buildList {
            add(classesJar)
            if (kotlinSources.isNotEmpty()) toolchain.kotlinStdlibJar?.let { add(it) }
        }
        val dexArgs = mutableListOf(
            toolchain.java!!.absolutePath,
            "-Xmx768m",
            "-XX:-UseCompressedOops",
            "-XX:-UseCompressedClassPointers",
            "-Dfile.encoding=UTF-8",
            "-Djava.io.tmpdir=${tmpDir.absolutePath}",
            "-cp", toolchain.dexerJar!!.absolutePath,
            "com.android.tools.r8.D8",
            "--release",
            "--min-api", minSdk.toString(),
            "--lib", toolchain.androidJar.absolutePath,
            "--output", dexDir.absolutePath
        )
        // android.jar is already supplied through --lib and the program inputs must not be
        // repeated on the library classpath.
        compileClasspath
            .filter { it.extension == "jar" }
            .filter { it.absolutePath != toolchain.androidJar.absolutePath }
            .filter { candidate -> dexProgramInputs.none { it.absolutePath == candidate.absolutePath } }
            .forEach { dexArgs += listOf("--classpath", it.absolutePath) }
        dexArgs += dexProgramInputs.map { it.absolutePath }
        runner.requireSuccess(dexArgs, workRoot, onLine = onLine, timeoutMinutes = 15)
        val dexFiles = dexDir.listFiles()?.filter { it.isFile && it.extension == "dex" }?.sortedBy { it.name }.orEmpty()
        check(dexFiles.isNotEmpty()) { "D8 finished but produced no .dex file." }
        dexFiles.forEach { onLine("  • ${it.name}: ${it.length()} bytes") }

        // ---------------------------------------------------------------- 5. package
        onLine("[5/7] PACKAGE: assembling APK container...")
        val unsignedApk = File(workRoot, "app-$variant-unsigned.apk")
        assembleApk(linkedApk, dexFiles, layout, unsignedApk)
        onLine("  • unsigned APK: ${unsignedApk.length()} bytes")

        // ---------------------------------------------------------------- 6. align
        onLine("[6/7] ALIGN: zipalign -p 4...")
        val alignedApk = File(workRoot, "app-$variant-aligned.apk")
        runner.requireSuccess(
            listOf(
                toolchain.zipalign!!.absolutePath, "-p", "-f", "4",
                unsignedApk.absolutePath, alignedApk.absolutePath
            ),
            workRoot, onLine = onLine, timeoutMinutes = 10
        )
        check(alignedApk.isFile) { "zipalign did not create ${alignedApk.name}." }

        // ---------------------------------------------------------------- 7. sign
        onLine("[7/7] SIGN: apksigner (v1 + v2 + v3)...")
        val signing = resolveSigningMaterial(keystoreFile, keystorePassword, keyAlias, onLine)
        val outputDir = File(layout.moduleDir, "build/outputs/apk/$variant").apply { requireMkdirs() }
        val finalApk = File(outputDir, "app-$variant.apk")
        if (finalApk.exists()) finalApk.delete()
        signApk(toolchain, alignedApk, finalApk, signing, workRoot, onLine)
        check(finalApk.isFile && finalApk.length() > 0) { "Signing did not produce ${finalApk.name}." }

        val verification = JarV1Verifier.verify(finalApk)
        if (verification.isSigned) {
            onLine("  • V1 signature verified for ${verification.signedEntries} entries (${verification.signers.joinToString()})")
        } else {
            onLine("  [WARN] V1 verification: ${verification.details}")
        }
        val duration = System.currentTimeMillis() - startedAt
        onLine("BUILD: succeeded in ${duration}ms — ${finalApk.absolutePath} (${finalApk.length()} bytes, $variant)")
        return finalApk
    }

    // ------------------------------------------------------------------ build steps

    private suspend fun compileGeneratedRClass(
        toolchain: AndroidToolchain,
        genDir: File,
        workRoot: File,
        classesDir: File,
        onLine: (String) -> Unit
    ): List<File> {
        val rJavaFiles = genDir.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
        if (rJavaFiles.isEmpty()) return emptyList()
        // R classes are compiled straight into the output directory: application code needs them
        // on the compile classpath *and* inside classes.jar so D8 can emit them into the dex.
        runner.requireSuccess(
            listOf(
                toolchain.javac!!.absolutePath,
                "-source", "17", "-target", "17",
                "-encoding", "UTF-8", "-nowarn", "-proc:none",
                "-classpath", toolchain.androidJar!!.absolutePath,
                "-d", classesDir.absolutePath
            ) + rJavaFiles.map { it.absolutePath },
            workRoot, onLine = onLine, timeoutMinutes = 10
        )
        onLine("  • generated R class compiled (${rJavaFiles.size} file(s))")
        return listOf(classesDir)
    }

    private suspend fun compileJava(
        toolchain: AndroidToolchain,
        sources: List<File>,
        classpath: List<File>,
        classesDir: File,
        workRoot: File,
        onLine: (String) -> Unit
    ) {
        onLine("  • javac: ${sources.size} Java file(s)")
        val command = mutableListOf(
            toolchain.javac!!.absolutePath,
            "-source", "17", "-target", "17",
            "-encoding", "UTF-8", "-nowarn", "-proc:none",
            "-d", classesDir.absolutePath
        )
        if (classpath.isNotEmpty()) command += listOf("-classpath", classpath.joinToString(File.pathSeparator) { it.absolutePath })
        command += sources.map { it.absolutePath }
        runner.requireSuccess(command, workRoot, onLine = onLine, timeoutMinutes = 20)
    }

    private suspend fun compileKotlin(
        toolchain: AndroidToolchain,
        sources: List<File>,
        classpath: List<File>,
        classesDir: File,
        workRoot: File,
        tmpDir: File,
        onLine: (String) -> Unit
    ) {
        onLine("  • kotlinc: ${sources.size} Kotlin file(s)")
        val compilerClasspath = toolchain.kotlinLibJars.joinToString(File.pathSeparator) { it.absolutePath }
        val command = mutableListOf(
            toolchain.java!!.absolutePath,
            "-Xmx768m",
            "-XX:-UseCompressedOops",
            "-XX:-UseCompressedClassPointers",
            "-Dfile.encoding=UTF-8",
            "-Djava.io.tmpdir=${tmpDir.absolutePath}",
            "-cp", compilerClasspath,
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
            "-no-stdlib",
            "-no-reflect",
            "-jvm-target", "17",
            "-classpath", classpath.joinToString(File.pathSeparator) { it.absolutePath },
            "-d", classesDir.absolutePath
        )
        command += sources.map { it.absolutePath }
        runner.requireSuccess(command, workRoot, onLine = onLine, timeoutMinutes = 30)
    }

    private fun assembleApk(
        linkedApk: File,
        dexFiles: List<File>,
        layout: ProjectLayout,
        output: File
    ) {
        ZipFile(linkedApk).use { linked ->
            ZipOutputStream(FileOutputStream(output).buffered()).use { zip ->
                for (entry in linked.entries()) {
                    if (entry.isDirectory) continue
                    val copy = ZipEntry(entry.name)
                    copy.time = 0L
                    // resources.arsc must stay uncompressed for Android 11+ installs.
                    if (entry.name == "resources.arsc" || entry.method == ZipEntry.STORED) {
                        copy.method = ZipEntry.STORED
                        copy.size = entry.size
                        copy.compressedSize = entry.size
                        copy.crc = entry.crc
                    }
                    zip.putNextEntry(copy)
                    linked.getInputStream(entry).use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }

                dexFiles.forEach { dex ->
                    zip.putNextEntry(ZipEntry(dex.name))
                    dex.inputStream().use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }

                layout.assetFiles.forEach { asset ->
                    val name = "assets/" + asset.relativeTo(layout.assetsDir!!).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(name))
                    asset.inputStream().use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }

                layout.nativeLibraries.forEach { library ->
                    val name = "lib/" + library.relativeTo(layout.jniLibsDir!!).invariantSeparatorsPath
                    val entry = ZipEntry(name)
                    entry.method = ZipEntry.STORED
                    entry.size = library.length()
                    entry.compressedSize = library.length()
                    entry.crc = crc32(library)
                    zip.putNextEntry(entry)
                    library.inputStream().use { it.copyTo(zip, 64 * 1024) }
                    zip.closeEntry()
                }
            }
        }
    }

    private suspend fun signApk(
        toolchain: AndroidToolchain,
        input: File,
        output: File,
        signing: SigningMaterial,
        workRoot: File,
        onLine: (String) -> Unit
    ) {
        onLine("  • keystore: ${signing.label}")
        val baseArgs = listOf(
            "sign",
            "--ks", signing.keystore.absolutePath,
            "--ks-type", signing.storeType,
            "--ks-key-alias", signing.alias,
            "--ks-pass", "pass:${signing.password}",
            "--key-pass", "pass:${signing.password}",
            "--v1-signing-enabled", "true",
            "--v2-signing-enabled", "true",
            "--v3-signing-enabled", "true",
            "--out", output.absolutePath,
            input.absolutePath
        )
        val binary = toolchain.apksignerBinary
        if (binary != null) {
            binary.setExecutable(true, false)
            runner.requireSuccess(listOf(binary.absolutePath) + baseArgs, workRoot, onLine = onLine, timeoutMinutes = 10)
        } else {
            runner.requireSuccess(
                listOf(
                    toolchain.java!!.absolutePath,
                    "-Xmx512m",
                    "-XX:-UseCompressedOops",
                    "-XX:-UseCompressedClassPointers",
                    "-cp", toolchain.apksignerJar!!.absolutePath,
                    "com.android.apksigner.ApkSignerTool"
                ) + baseArgs,
                workRoot, onLine = onLine, timeoutMinutes = 10
            )
        }

        val verifyArgs = listOf("verify", "--print-certs", "-v", output.absolutePath)
        val verifyResult = if (binary != null) {
            runner.run(listOf(binary.absolutePath) + verifyArgs, workRoot, onLine = onLine, timeoutMinutes = 5)
        } else {
            runner.run(
                listOf(
                    toolchain.java!!.absolutePath,
                    "-cp", toolchain.apksignerJar!!.absolutePath,
                    "com.android.apksigner.ApkSignerTool"
                ) + verifyArgs,
                workRoot, onLine = onLine, timeoutMinutes = 5
            )
        }
        check(verifyResult.exitCode == 0) {
            "apksigner reported an invalid signature (exit ${verifyResult.exitCode}):\n" +
                verifyResult.output.takeLast(30).joinToString("\n")
        }
        verifyResult.output
            .filter { it.contains("scheme", true) || it.contains("Signer", true) }
            .take(6)
            .forEach { onLine("  • $it") }
    }

    // ------------------------------------------------------------------ signing material

    data class SigningMaterial(
        val keystore: File,
        val alias: String,
        val password: String,
        val storeType: String,
        val label: String
    )

    private fun resolveSigningMaterial(
        keystoreFile: File?,
        keystorePassword: String?,
        keyAlias: String?,
        onLine: (String) -> Unit
    ): SigningMaterial {
        if (keystoreFile != null && keystoreFile.isFile &&
            !keystorePassword.isNullOrBlank() && !keyAlias.isNullOrBlank()
        ) {
            val type = detectStoreType(keystoreFile, keystorePassword)
            return SigningMaterial(keystoreFile, keyAlias, keystorePassword, type, "${keystoreFile.name} (alias $keyAlias)")
        }
        if (keystoreFile != null) {
            onLine("  [WARN] Custom keystore requires a password and alias; falling back to the debug key.")
        }
        val debug = getOrCreateDebugKey(context)
        return SigningMaterial(
            keystore = debug.keystoreFile,
            alias = debug.alias,
            password = debug.password,
            storeType = "PKCS12",
            label = "Mobile Studio X debug key (CN=Mobile Studio Developer)"
        )
    }

    private fun detectStoreType(file: File, password: String): String = runCatching {
        KeyStore.getInstance("PKCS12").load(file.inputStream(), password.toCharArray())
        "PKCS12"
    }.getOrDefault("JKS")

    /** Signs an existing archive with the built-in JAR signer (v1 only). */
    fun signApkDirect(
        unsignedApk: File,
        signedApk: File,
        privateKey: PrivateKey,
        certificate: X509Certificate
    ): File = JarV1Signer.sign(unsignedApk, signedApk, privateKey, certificate)

    // ------------------------------------------------------------------ helpers

    private fun ensureManifestPackage(
        manifest: File,
        manifestText: String,
        packageName: String,
        workRoot: File
    ): File {
        if (Regex("""<manifest[^>]*\spackage\s*=""").containsMatchIn(manifestText)) return manifest
        val patched = manifestText.replaceFirst(
            Regex("""<manifest\b"""),
            "<manifest package=\"$packageName\""
        )
        val copy = File(workRoot, "AndroidManifest.xml")
        copy.writeText(patched)
        return copy
    }

    private fun extractPackageName(manifestText: String, gradleText: String): String =
        Regex("""\spackage\s*=\s*["']([^"']+)["']""").find(manifestText)?.groupValues?.getOrNull(1)
            ?: extractString(gradleText, "namespace")
            ?: extractString(gradleText, "applicationId")
            ?: "com.aaryo.mobilestudio"

    private fun extractInt(text: String, key: String, fallback: Int): Int =
        Regex("""$key\s*=\s*(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""$key\s+(\d+)""").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: fallback

    private fun extractString(text: String, key: String): String? =
        Regex("""$key\s*=\s*["']([^"']+)["']""").find(text)?.groupValues?.getOrNull(1)

    private fun zipDirectory(directory: File, output: File) {
        ZipOutputStream(FileOutputStream(output).buffered()).use { zip ->
            directory.walkTopDown().filter { it.isFile }.forEach { file ->
                val name = file.relativeTo(directory).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(name))
                file.inputStream().use { it.copyTo(zip, 64 * 1024) }
                zip.closeEntry()
            }
        }
    }

    private fun crc32(file: File): Long {
        val crc = java.util.zip.CRC32()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    private fun File.requireMkdirs(): File {
        if (!isDirectory && !mkdirs()) error("Cannot create build directory: $absolutePath")
        return this
    }

    private fun File.cleanDirectory() {
        if (isDirectory) deleteRecursively()
        requireMkdirs()
    }

    /** Signing key material persisted in app-private storage. */
    data class DebugKeyInfo(
        val keyPair: KeyPair,
        val certificate: X509Certificate,
        val keystoreFile: File,
        val alias: String,
        val password: String
    )

    /** Resolved Android module layout for a project directory. */
    data class ProjectLayout(
        val projectDir: File,
        val moduleDir: File,
        val manifest: File,
        val resDir: File?,
        val sourceFiles: List<File>,
        val assetsDir: File?,
        val jniLibsDir: File?,
        val dependencyJars: List<File>
    ) {
        val resourceFiles: List<File>
            get() = resDir?.walkTopDown()?.filter { it.isFile && !it.name.startsWith(".") }?.toList().orEmpty()

        val assetFiles: List<File>
            get() = assetsDir?.walkTopDown()?.filter { it.isFile && !it.name.startsWith(".") }?.toList().orEmpty()

        val nativeLibraries: List<File>
            get() = jniLibsDir?.walkTopDown()?.filter { it.isFile && it.extension == "so" }?.toList().orEmpty()

        fun gradleScriptText(): String = listOf("build.gradle.kts", "build.gradle")
            .map { File(moduleDir, it) }
            .firstOrNull { it.isFile }
            ?.let { runCatching { it.readText() }.getOrDefault("") }
            .orEmpty()

        companion object {
            fun discover(projectDir: File): ProjectLayout {
                val moduleDir = File(projectDir, "app").takeIf { it.isDirectory } ?: projectDir
                val srcMain = File(moduleDir, "src/main")
                val manifest = listOf(
                    File(srcMain, "AndroidManifest.xml"),
                    File(moduleDir, "AndroidManifest.xml"),
                    File(projectDir, "AndroidManifest.xml")
                ).firstOrNull { it.isFile } ?: error(
                    "AndroidManifest.xml not found. Expected app/src/main/AndroidManifest.xml in ${projectDir.name}."
                )
                val sourceRoots = listOf(File(srcMain, "java"), File(srcMain, "kotlin")).filter { it.isDirectory }
                val sources = sourceRoots.flatMap { root ->
                    root.walkTopDown()
                        .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                        .toList()
                }
                val libs = listOf(File(moduleDir, "libs"), File(projectDir, "libs"))
                    .filter { it.isDirectory }
                    .flatMap { dir -> dir.listFiles()?.filter { it.isFile && it.extension == "jar" }.orEmpty().toList() }
                return ProjectLayout(
                    projectDir = projectDir,
                    moduleDir = moduleDir,
                    manifest = manifest,
                    resDir = File(srcMain, "res").takeIf { it.isDirectory },
                    sourceFiles = sources,
                    assetsDir = File(srcMain, "assets").takeIf { it.isDirectory },
                    jniLibsDir = File(srcMain, "jniLibs").takeIf { it.isDirectory },
                    dependencyJars = libs
                )
            }
        }
    }

    companion object {
        private const val DEBUG_ALIAS = "androiddebugkey"
        private const val DEBUG_PASSWORD = "android"

        @Volatile
        private var cachedDebugKey: DebugKeyInfo? = null

        /** Creates (or reloads) the persistent debug signing key. */
        @Synchronized
        fun getOrCreateDebugKey(context: Context): DebugKeyInfo {
            cachedDebugKey?.let { if (it.keystoreFile.isFile) return it }
            val keystoreFile = File(context.filesDir, "keystores/debug.keystore").apply { parentFile?.mkdirs() }

            if (keystoreFile.isFile) {
                runCatching {
                    val keyStore = KeyStore.getInstance("PKCS12")
                    keystoreFile.inputStream().use { keyStore.load(it, DEBUG_PASSWORD.toCharArray()) }
                    val privateKey = keyStore.getKey(DEBUG_ALIAS, DEBUG_PASSWORD.toCharArray()) as PrivateKey
                    val certificate = keyStore.getCertificate(DEBUG_ALIAS) as X509Certificate
                    val info = DebugKeyInfo(
                        keyPair = KeyPair(certificate.publicKey, privateKey),
                        certificate = certificate,
                        keystoreFile = keystoreFile,
                        alias = DEBUG_ALIAS,
                        password = DEBUG_PASSWORD
                    )
                    cachedDebugKey = info
                    return info
                }
            }

            val generator = KeyPairGenerator.getInstance("RSA")
            generator.initialize(2048)
            val keyPair = generator.generateKeyPair()
            val certificate = Asn1CertGenerator.generateSelfSignedCert(keyPair, "Mobile Studio Developer", "Mobile Studio X")

            val keyStore = KeyStore.getInstance("PKCS12")
            keyStore.load(null, null)
            keyStore.setKeyEntry(DEBUG_ALIAS, keyPair.private, DEBUG_PASSWORD.toCharArray(), arrayOf(certificate))
            keystoreFile.outputStream().use { keyStore.store(it, DEBUG_PASSWORD.toCharArray()) }

            val info = DebugKeyInfo(keyPair, certificate, keystoreFile, DEBUG_ALIAS, DEBUG_PASSWORD)
            cachedDebugKey = info
            return info
        }
    }
}
