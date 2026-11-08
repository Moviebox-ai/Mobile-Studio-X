package com.aaryo.mobilestudio

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.aaryo.mobilestudio.data.ProjectWorkspace
import com.aaryo.mobilestudio.toolchain.BionicTagFixShim
import com.aaryo.mobilestudio.toolchain.ErrorCategory
import com.aaryo.mobilestudio.toolchain.ErrorDiagnosticsEngine
import com.aaryo.mobilestudio.toolchain.JdkManager
import com.aaryo.mobilestudio.toolchain.RuntimeInspector
import com.aaryo.mobilestudio.toolchain.RuntimeStatus
import com.aaryo.mobilestudio.toolchain.TagFixShimElf
import com.aaryo.mobilestudio.toolchain.ToolchainCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class MobileStudioUnitTest {

    @Test
    fun catalogHasComponents() {
        val components = ToolchainCatalog.components
        assertNotNull(components)
        assertEquals(8, components.size)
    }

    @Test
    fun catalogIdsAreDistinct() {
        val ids = ToolchainCatalog.components.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun catalogCoversRequiredRoles() {
        val ids = ToolchainCatalog.components.map { it.id }.toSet()
        val expected = setOf(
            "jdk17", "gradle", "kotlin", "build-tools", "r8", "platform-36", "cmake-ninja", "ndk-r30"
        )
        assertEquals(expected, ids)
    }

    @Test
    fun catalogConfigurationProblemsAreEmpty() {
        val problems = ToolchainCatalog.configurationProblems()
        assertEquals("Catalog should have 0 configuration problems but had: $problems", 0, problems.size)
    }

    @Test
    fun catalogComponentsHaveValidSizesAndSha256() {
        ToolchainCatalog.components.forEach { component ->
            assertTrue("Component ${component.id} size must be > 0 but was ${component.sizeBytes}", component.sizeBytes > 0L)
            assertNotNull("Component ${component.id} must have a verified SHA-256 hash", component.sha256)
            assertEquals("Component ${component.id} SHA-256 must be 64 hex characters", 64, component.sha256?.length)
        }
        val gradle = ToolchainCatalog.components.first { it.id == "gradle" }
        assertEquals(137_037_885L, gradle.sizeBytes)
        assertEquals("b266d5ff6b90eada6dc3b20cb090e3731302e553a27c5d3e4df1f0d76beaff06", gradle.sha256)
    }

    @Test
    fun projectWorkspaceImportsZipCorrectly() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val workspace = ProjectWorkspace(context)

            val tempZip = File(context.cacheDir, "sample-project.zip")
            ZipOutputStream(FileOutputStream(tempZip)).use { zos ->
                zos.putNextEntry(ZipEntry("sample-project/settings.gradle.kts"))
                zos.write("rootProject.name = \"SampleApp\"".toByteArray())
                zos.closeEntry()

                zos.putNextEntry(ZipEntry("sample-project/app/src/main/AndroidManifest.xml"))
                zos.write("<manifest package=\"com.test.app\"/>".toByteArray())
                zos.closeEntry()
            }

            val imported = workspace.importZip(Uri.fromFile(tempZip))
            assertTrue(imported.exists())
            assertTrue(imported.isDirectory)
            val settingsFile = File(imported, "settings.gradle.kts")
            assertTrue("settings.gradle.kts should be at the root of the imported project", settingsFile.exists())
            assertEquals("rootProject.name = \"SampleApp\"", settingsFile.readText())
            tempZip.delete()
        }
    }

    @Test
    fun projectWorkspaceGeneratesComposeTemplateCorrectly() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val workspace = ProjectWorkspace(context)

            val project = workspace.createFromTemplate(
                type = com.aaryo.mobilestudio.data.TemplateType.COMPOSE,
                rawName = "MyTestComposeApp",
                rawPackage = "com.test.compose"
            )

            assertTrue(project.exists())
            assertTrue(File(project, "settings.gradle.kts").exists())
            assertTrue(File(project, "app/build.gradle.kts").exists())
            assertTrue(File(project, "app/src/main/AndroidManifest.xml").exists())
            val mainActivity = File(project, "app/src/main/java/com/test/compose/MainActivity.kt")
            assertTrue("MainActivity.kt should exist", mainActivity.exists())
            assertTrue(mainActivity.readText().contains("ComponentActivity"))
            assertTrue(mainActivity.readText().contains("MainScreen"))

            val manifest = File(project, "app/src/main/AndroidManifest.xml").readText()
            assertTrue(manifest.contains("package=\"com.test.compose\""))

            workspace.deleteProject(project)
        }
    }

    @Test
    fun projectWorkspaceGeneratesNativeCppTemplateCorrectly() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val workspace = ProjectWorkspace(context)

            val project = workspace.createFromTemplate(
                type = com.aaryo.mobilestudio.data.TemplateType.NATIVE_CPP,
                rawName = "MyNativeApp",
                rawPackage = "com.test.nativeapp"
            )

            assertTrue(project.exists())
            assertTrue(File(project, "app/src/main/cpp/CMakeLists.txt").exists())
            assertTrue(File(project, "app/src/main/cpp/native-lib.cpp").exists())
            val nativeCpp = File(project, "app/src/main/cpp/native-lib.cpp").readText()
            assertTrue(nativeCpp.contains("Java_com_test_nativeapp_MainActivity_stringFromJNI"))

            workspace.deleteProject(project)
        }
    }

    @Test
    fun projectWorkspaceListsAndDeletesProjectsCorrectly() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val workspace = ProjectWorkspace(context)

            val project = workspace.createFromTemplate(
                type = com.aaryo.mobilestudio.data.TemplateType.MINIMAL_AAPT,
                rawName = "ListTestApp",
                rawPackage = "com.test.listapp"
            )

            val list = workspace.listProjects()
            assertTrue(list.any { it.name == project.name })

            val deleted = workspace.deleteProject(project)
            assertTrue(deleted)
            val updatedList = workspace.listProjects()
            assertTrue(updatedList.none { it.name == project.name })
        }
    }

    @Test
    fun directCompilerFailsWithActionableMessageWhenToolchainIsMissing() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val workspace = ProjectWorkspace(context)

            val project = workspace.createFromTemplate(
                type = com.aaryo.mobilestudio.data.TemplateType.MINIMAL_AAPT,
                rawName = "NoToolchainApp",
                rawPackage = "com.test.notoolchain"
            )

            val compiler = com.aaryo.mobilestudio.toolchain.DirectCompilerEngine(context)
            val logs = mutableListOf<String>()
            val result = compiler.compile(project, isRelease = false) { logs.add(it) }

            // Previously this build "succeeded" and produced an APK that Android refused to
            // install (raw XML manifest, fabricated resources.arsc and dex). It must now fail
            // loudly and explain what to install.
            assertTrue("Compilation without a toolchain must fail", result.isFailure)
            val message = result.exceptionOrNull()?.message.orEmpty()
            assertTrue("Diagnostic should list missing components: $message", message.contains("Missing components", true))
            assertTrue("Diagnostic should point at the Toolchain screen: $message", message.contains("Toolchain", true))
            assertTrue("Diagnostic should name AAPT2: $message", message.contains("AAPT2", true))
            assertFalse(
                "No APK may be produced when required tools are missing",
                File(project, "app/build/outputs/apk/debug/app-debug.apk").exists()
            )

            workspace.deleteProject(project)
        }
    }

    @Test
    fun generatedProjectsUsePluginVersionsCompatibleWithBundledGradle() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val workspace = ProjectWorkspace(context)

            val project = workspace.createFromTemplate(
                type = com.aaryo.mobilestudio.data.TemplateType.COMPOSE,
                rawName = "AgpCheckApp",
                rawPackage = "com.test.agpcheck"
            )

            val rootScript = File(project, "build.gradle.kts").readText()
            // The bundled distribution is Gradle 9.3.1; AGP 8.2.2 cannot run on it.
            assertTrue("Root script must request AGP 9.1.1: $rootScript", rootScript.contains("version \"9.1.1\""))
            assertTrue(
                "Root script must declare the Compose compiler plugin: $rootScript",
                rootScript.contains("org.jetbrains.kotlin.plugin.compose")
            )
            assertFalse(
                "AGP 9 compiles Kotlin itself; the kotlin-android plugin must not be applied",
                rootScript.contains("id(\"org.jetbrains.kotlin.android\")")
            )

            val appScript = File(project, "app/build.gradle.kts").readText()
            assertTrue("app script must use buildToolsVersion 34.0.4: $appScript", appScript.contains("buildToolsVersion = \"34.0.4\""))
            assertTrue("app script must compile against SDK 36: $appScript", appScript.contains("compileSdk = 36"))
            assertFalse("app script must not apply kotlin-android", appScript.contains("org.jetbrains.kotlin.android"))
            assertFalse("composeOptions is obsolete with Kotlin 2.x", appScript.contains("composeOptions"))
            assertFalse("kotlinOptions is removed in AGP 9", appScript.contains("kotlinOptions"))
            assertTrue(
                "Compose template must apply the Compose compiler plugin",
                appScript.contains("org.jetbrains.kotlin.plugin.compose")
            )

            workspace.deleteProject(project)
        }
    }

    @Test
    fun jarV1SignerProducesSignatureThatVerifies() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val dir = File(context.cacheDir, "jar-v1-test").apply { mkdirs() }

            val unsigned = File(dir, "unsigned.apk")
            ZipOutputStream(FileOutputStream(unsigned)).use { zos ->
                zos.putNextEntry(ZipEntry("AndroidManifest.xml"))
                zos.write(ByteArray(64) { 7 })
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("classes.dex"))
                zos.write(ByteArray(2048) { 3 })
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("res/layout/activity_main.xml"))
                zos.write("<LinearLayout/>".toByteArray())
                zos.closeEntry()
            }

            val signingEngine = com.aaryo.mobilestudio.toolchain.ApkSigningEngine(context)
            val keystore = signingEngine.createKeystore(
                name = "jar_sign_test",
                alias = "testalias",
                password = "testpassword123"
            ).getOrThrow()
            val keyStore = java.security.KeyStore.getInstance("PKCS12").apply {
                keystore.inputStream().use { load(it, "testpassword123".toCharArray()) }
            }
            val privateKey = keyStore.getKey("testalias", "testpassword123".toCharArray()) as java.security.PrivateKey
            val certificate = keyStore.getCertificate("testalias") as java.security.cert.X509Certificate

            val signed = com.aaryo.mobilestudio.toolchain.JarV1Signer.sign(
                unsigned, File(dir, "signed.apk"), privateKey, certificate
            )
            val verification = com.aaryo.mobilestudio.toolchain.JarV1Verifier.verify(signed)
            assertTrue("V1 signature must verify: ${verification.details}", verification.isSigned)
            assertEquals(3, verification.signedEntries)
            assertTrue(verification.unsignedEntries.isEmpty())

            // A tampered entry must invalidate the signature.
            val tampered = File(dir, "tampered.apk")
            java.util.zip.ZipFile(signed).use { source ->
                ZipOutputStream(FileOutputStream(tampered)).use { zos ->
                    for (entry in source.entries()) {
                        zos.putNextEntry(ZipEntry(entry.name))
                        val bytes = source.getInputStream(entry).readBytes()
                        if (entry.name == "classes.dex") bytes[0] = (bytes[0] + 1).toByte()
                        zos.write(bytes)
                        zos.closeEntry()
                    }
                }
            }
            assertFalse(
                "A modified entry must fail V1 verification",
                com.aaryo.mobilestudio.toolchain.JarV1Verifier.verify(tampered).isSigned
            )

            signed.delete()
            tampered.delete()
            unsigned.delete()
            keystore.delete()
        }
    }

    @Test
    fun apkSigningEngineCreatesPureJavaKeystore() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val signingEngine = com.aaryo.mobilestudio.toolchain.ApkSigningEngine(context)

            val ksResult = signingEngine.createKeystore(
                name = "test_keystore",
                alias = "testalias",
                password = "testpassword123",
                commonName = "Test Developer",
                organization = "Test Org"
            )

            assertTrue("Keystore creation should succeed: ${ksResult.exceptionOrNull()?.message}", ksResult.isSuccess)
            val ksFile = ksResult.getOrThrow()
            assertTrue(ksFile.isFile)

            val inspectResult = signingEngine.inspectKeystore(ksFile, "testpassword123")
            assertTrue("Keystore inspection should succeed: ${inspectResult.exceptionOrNull()?.message}", inspectResult.isSuccess)
            val details = inspectResult.getOrThrow()
            assertTrue("Keystore should contain alias 'testalias'", details.aliases.contains("testalias"))
            assertNotNull("Keystore certificate should have SHA-256 fingerprint", details.firstCertFingerprintSha256)
            assertTrue("Fingerprint should be non-empty", details.firstCertFingerprintSha256!!.isNotBlank())

            ksFile.delete()
        }
    }

    // ---------------------------------------------------------------- heap-tagging shim

    private fun shimBytes() = TagFixShimElf.buildBytes()

    @Test
    fun tagFixShimIsLoadableByAndroidApi26Plus() {
        val elf = shimBytes()
        val buf = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN)

        assertEquals("image size", TagFixShimElf.FILE_SIZE, elf.size)
        assertEquals(0x7f.toByte(), buf.get(0))
        assertEquals('E'.code.toByte(), buf.get(1))
        assertEquals('L'.code.toByte(), buf.get(2))
        assertEquals('F'.code.toByte(), buf.get(3))
        assertEquals(2.toByte(), buf.get(4))   // ELFCLASS64
        assertEquals(1.toByte(), buf.get(5))   // ELFDATA2LSB
        assertEquals(3, buf.getShort(16).toInt())    // ET_DYN
        assertEquals(183, buf.getShort(18).toInt())  // EM_AARCH64
        assertEquals(64, buf.getShort(52).toInt())   // e_ehsize
        assertEquals(56, buf.getShort(54).toInt())   // e_phentsize
        assertEquals(64, buf.getShort(58).toInt())   // e_shentsize

        val phnum = buf.getShort(56).toInt()
        val shnum = buf.getShort(60).toInt()
        val shstrndx = buf.getShort(62).toInt()
        assertEquals(4, phnum)
        assertEquals(8, shnum)
        assertTrue("e_shstrndx must be non-zero, Bionic rejects 0 since API 26", shstrndx > 0)
        // Section header table and program header table must be inside the file.
        assertTrue(buf.getLong(40) + shnum * 64L <= elf.size)
        assertTrue(buf.getLong(32) + phnum * 56L <= elf.size)
    }

    /**
     * The regression that broke the toolchain: a single PT_LOAD with PF_R|PF_W|PF_X is rejected by
     * the linker with "has load segments that are both writable and executable".
     */
    @Test
    fun tagFixShimHasNoWritableExecutableLoadSegment() {
        val elf = shimBytes()
        assertFalse(
            "Bionic refuses W^X load segments on API 26+",
            TagFixShimElf.hasWritableExecutableSegment(elf)
        )

        val buf = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN)
        val phoff = buf.getLong(32).toInt()
        val phnum = buf.getShort(56).toInt()
        var sawCode = false
        var sawData = false
        for (i in 0 until phnum) {
            val base = phoff + i * 56
            if (buf.getInt(base) != 1) continue // PT_LOAD
            val flags = buf.getInt(base + 4)
            assertTrue("load segment $i must be readable", flags and 4 != 0)
            assertFalse("load segment $i must not be W+X", flags and 3 == 3)
            if (flags and 1 != 0) sawCode = true
            if (flags and 2 != 0) sawData = true
        }
        assertTrue("shim needs an executable segment", sawCode)
        assertTrue("the linker must be able to write the GOT (.init_array/.got)", sawData)

        // Program headers have to live inside a file-backed PT_LOAD (ElfReader::CheckPhdr).
        val firstFilesz = buf.getLong(phoff + 32)
        assertTrue(buf.getLong(32) + phnum * 56L <= firstFilesz)
        // 16 KiB page-size kernels reject anything below 16 KiB alignment.
        for (i in 0 until phnum) {
            val base = phoff + i * 56
            if (buf.getInt(base) != 1) continue
            assertTrue("p_align must be >= 16 KiB", buf.getLong(base + 48) >= 0x4000)
        }
    }

    @Test
    fun tagFixShimDeclaresDynamicSectionThatMatchesItsSectionHeader() {
        val elf = shimBytes()
        val buf = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN)
        val shoff = buf.getLong(40).toInt()
        val shnum = buf.getShort(60).toInt()

        var dynamic: Triple<Int, Int, Int>? = null // offset, size, link
        for (i in 0 until shnum) {
            val base = shoff + i * 64
            if (buf.getInt(base + 4) == 6) { // SHT_DYNAMIC
                dynamic = Triple(buf.getLong(base + 24).toInt(), buf.getLong(base + 32).toInt(), buf.getInt(base + 40))
            }
        }
        assertNotNull("shim needs a SHT_DYNAMIC section header", dynamic)
        val (dynOffset, dynSize, link) = dynamic!!

        var ptDynamic: Pair<Int, Int>? = null
        val phoff = buf.getLong(32).toInt()
        for (i in 0 until buf.getShort(56).toInt()) {
            val base = phoff + i * 56
            if (buf.getInt(base) == 2) { // PT_DYNAMIC
                ptDynamic = buf.getLong(base + 8).toInt() to buf.getLong(base + 32).toInt()
            }
        }
        assertEquals("PT_DYNAMIC must match .dynamic offset", dynOffset, ptDynamic!!.first)
        assertEquals("PT_DYNAMIC must match .dynamic size", dynSize, ptDynamic.second)

        // sh_link has to point at a SHT_STRTAB or ReadDynamicSection() fails.
        assertEquals(3, buf.getInt(shoff + link * 64 + 4))

        // The dynamic table must actually request the constructor.
        val tags = (0 until dynSize / 16).associate { i ->
            buf.getLong(dynOffset + i * 16) to buf.getLong(dynOffset + i * 16 + 8)
        }
        assertTrue("DT_INIT_ARRAY missing: the constructor would never run", tags.containsKey(25L))
        assertEquals(8L, tags[27L])       // DT_INIT_ARRAYSZ
        assertTrue(tags.containsKey(1L))  // DT_NEEDED libc.so
        assertTrue(tags.containsKey(4L))  // DT_HASH
        assertTrue(tags.containsKey(7L))  // DT_RELA
        assertEquals(0L, buf.getLong(dynOffset + (tags.size - 1) * 16)) // DT_NULL terminator
    }

    @Test
    fun tagFixShimConstructorCallsMalloptWithHeapTaggingDisabled() {
        val elf = shimBytes()
        val buf = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN)
        // .text is the first PROGBITS section; check the instruction stream itself.
        val shoff = buf.getLong(40).toInt()
        val textOffset = buf.getLong(shoff + 64 + 24).toInt()
        val words = IntArray(12) { buf.getInt(textOffset + it * 4) }

        assertEquals("sub sp, sp, #32", 0xD10083FF.toInt(), words[0])
        assertEquals("mov w0, #-204 (M_BIONIC_SET_HEAP_TAGGING_LEVEL)", 0x12801960, words[3])
        assertEquals("mov w1, #0 (M_HEAP_TAGGING_LEVEL_NONE)", 0x52800001, words[4])
        assertEquals("adrp x16, page of .got", 0xB0000010.toInt(), words[5])
        assertEquals("ldr x17, [x16, #8]", 0xF9400611.toInt(), words[6])
        assertEquals("cbz x17, done (weak symbol may be unresolved)", 0xB4000071.toInt(), words[7])
        assertEquals("blr x17", 0xD63F0220.toInt(), words[8])
        assertEquals("ret", 0xD65F03C0.toInt(), words[11])

        // .got must be the address that adrp/ldr above resolve to: page 0x1000 + offset 8.
        val gotShdr = shoff + 5 * 64
        assertEquals(0x1008L, buf.getLong(gotShdr + 16))
        // .rela.dyn must be a R_AARCH64_GLOB_DAT (1025) for symbol 1 (`mallopt`).
        val dynOffset = buf.getLong(shoff + 3 * 64 + 24).toInt()
        val relaOffset = (0 until 12)
            .map { buf.getLong(dynOffset + it * 16) to buf.getLong(dynOffset + it * 16 + 8) }
            .first { it.first == 7L }.second.toInt()
        assertEquals(0x1008L, buf.getLong(relaOffset))
        assertEquals(1025L, buf.getLong(relaOffset + 8) and 0xFFFFFFFFL)
        assertEquals(1L, buf.getLong(relaOffset + 8) ushr 32)
        // .init_array holds the constructor address.
        assertEquals(textOffset.toLong(), buf.getLong(0x1000))
    }

    @Test
    fun tagFixShimRejectsStaleAndCorruptImages() {
        val fresh = shimBytes()
        assertTrue("a freshly built shim must be accepted", TagFixShimElf.isUsableShim(fresh))
        assertEquals(TagFixShimElf.VERSION, TagFixShimElf.versionOf(fresh))
        assertFalse("null bytes must not be accepted", TagFixShimElf.isUsableShim(null))
        assertFalse(
            "the legacy 4096-byte image must be rebuilt",
            TagFixShimElf.isUsableShim(ByteArray(4096))
        )
        val truncated = fresh.copyOf(fresh.size - 16)
        assertFalse(TagFixShimElf.isUsableShim(truncated))

        val zeroShstrndx = fresh.copyOf().also { it[62] = 0; it[63] = 0 }
        assertFalse("e_shstrndx == 0 is rejected by Bionic", TagFixShimElf.isUsableShim(zeroShstrndx))

        val rwx = fresh.copyOf().also { it[0x44] = 7 } // first PT_LOAD flags = R|W|X
        assertTrue(TagFixShimElf.hasWritableExecutableSegment(rwx))
        assertFalse(TagFixShimElf.isUsableShim(rwx))

        val otherVersion = fresh.copyOf().also { bytes ->
            val marker = "mobilestudio:tagfix:${TagFixShimElf.VERSION}".toByteArray()
            val at = String(bytes, Charsets.US_ASCII).indexOf(String(marker, Charsets.US_ASCII))
            bytes[at + marker.size - 1] = '1'.code.toByte()
        }
        assertEquals(1, TagFixShimElf.versionOf(otherVersion))
        assertFalse("older versions must be regenerated", TagFixShimElf.isUsableShim(otherVersion))
    }

    @Test
    fun bionicTagFixShimWritesAValidatedFileAndSkipsStaleOnes() {
        val filesDir = File.createTempFile("msx", "files").apply { delete(); mkdirs() }
        val anchor = File(filesDir, "toolchain").apply { mkdirs() }
        try {
            BionicTagFixShim.resetCache()
            val shim = BionicTagFixShim.ensureShimLibrary(anchor)
            assertNotNull("shim should be written next to the toolchain", shim)
            assertTrue(shim!!.isFile)
            assertEquals(TagFixShimElf.FILE_SIZE.toLong(), shim.length())
            assertTrue(TagFixShimElf.isUsableShim(shim.readBytes()))

            // A leftover file from the previous generation gets replaced, not reused.
            shim.writeBytes(ByteArray(4096))
            BionicTagFixShim.resetCache()
            val rewritten = BionicTagFixShim.ensureShimLibrary(anchor)
            assertNotNull(rewritten)
            assertEquals(TagFixShimElf.FILE_SIZE.toLong(), rewritten!!.length())
            assertEquals(TagFixShimElf.VERSION, TagFixShimElf.versionOf(rewritten.readBytes()))
            assertTrue(BionicTagFixShim.isShimReady(anchor))
        } finally {
            BionicTagFixShim.resetCache()
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun errorDiagnosticsEngineDetectsLinkerShstrndxError() {
        val logs = listOf(
            "linker: \"/data/user/0/com.aaryo.mobilestudio/files/toolchain/compat/libtagfix.so\" has invalid e_shstrndx",
            "CANNOT LINK EXECUTABLE \"/data/user/0/com.aaryo.mobilestudio/files/toolchain/runtime/bin/java\": \".../libtagfix.so\" has invalid e_shstrndx"
        )
        val diagnostic = com.aaryo.mobilestudio.toolchain.ErrorDiagnosticsEngine.analyze(logs, null)
        assertNotNull(diagnostic)
        assertEquals(com.aaryo.mobilestudio.toolchain.ErrorCategory.TAGGED_POINTER_SIGABRT, diagnostic.category)
        assertTrue(diagnostic.title.contains("shim", ignoreCase = true))
        assertEquals("toolchain/compat/libtagfix.so", diagnostic.filePath)
        assertFalse(
            "a loader failure must never be blamed on the JDK install",
            diagnostic.rootCause.contains("JDK", ignoreCase = true)
        )
    }

    /**
     * The exact failure reported by the linker for the v1 shim: one RWX PT_LOAD. It must produce a
     * shim diagnostic (not "reinstall JDK 17") and never be mistaken for a heap-tagging abort.
     */
    @Test
    fun writableExecutableShimIsReportedAsShimBugNotJdkBug() {
        val logs = listOf(
            "BUILD: Checking Java runtime on-device...",
            "/data/user/0/com.aaryo.mobilestudio/files/toolchain/compat/libtagfix.so\" has load segments that are both writable and executable",
            "CANNOT LINK EXECUTABLE \"/data/user/0/com.aaryo.mobilestudio/files/toolchain/runtime/bin/java\": " +
                "\"/data/user/0/com.aaryo.mobilestudio/files/toolchain/compat/libtagfix.so\" has load segments that are both writable and executable"
        )
        assertTrue(ErrorDiagnosticsEngine.isLinkerRejection(logs.joinToString("\n")))
        assertFalse(
            "a W^X rejection is not a tagged-pointer abort",
            ErrorDiagnosticsEngine.isTaggedPointerAbort(logs.joinToString("\n"))
        )
        val diagnostic = ErrorDiagnosticsEngine.analyze(logs, null)
        assertEquals(ErrorCategory.TAGGED_POINTER_SIGABRT, diagnostic.category)
        assertTrue(diagnostic.title.contains("shim", ignoreCase = true))
        assertTrue("root cause should name the W^X rule", diagnostic.rootCause.contains("writable", ignoreCase = true))
    }
    // ---------------------------------------------------------------- JDK discovery

    private fun installFakeJdk(root: File, relativeJava: String) {
        val java = File(root, relativeJava)
        java.parentFile?.mkdirs()
        java.writeText("#!/bin/bash\nexit 0\n")
        java.setExecutable(true)
    }

    @Test
    fun runtimeStatusDefaultsToNotReady() {
        val status = RuntimeStatus()
        assertFalse(status.readyForGradle)
        assertFalse(status.readyForDirectBuild)
        assertEquals("Not installed", status.javaSummary)
    }

    @Test
    fun jdkInSlotDirectoryIsReportedReady() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val toolchain = File(app.filesDir, "toolchain").apply { deleteRecursively(); mkdirs() }
        File(toolchain, "gradle/lib").mkdirs()
        File(toolchain, "gradle/lib/gradle-launcher-9.3.1.jar").writeText("jar")
        // Installers have used runtime/bin/java *and* jdk/<slot>/bin/java. Both must be detected.
        installFakeJdk(toolchain, "jdk/jdk_17/bin/java")

        val status = RuntimeInspector(app).inspect()
        assertNotNull("A JDK in jdk/<slot>/bin must be discovered", status.javaExecutable)
        assertTrue("Gradle must be considered ready", status.readyForGradle)
        assertFalse(status.javaVerificationFailed)

        val active = JdkManager(app).getActiveJdk()
        assertTrue(active.isInstalled)
        assertTrue(active.isVerified)
        assertEquals("jdk_17", active.homeDirectory.name)
    }

    @Test
    fun failedJdkVerificationIsSurfacedInsteadOfReportedMissing() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val toolchain = File(app.filesDir, "toolchain").apply { deleteRecursively(); mkdirs() }
        File(toolchain, "gradle/lib").mkdirs()
        File(toolchain, "gradle/lib/gradle-launcher-9.3.1.jar").writeText("jar")
        installFakeJdk(toolchain, "runtime/bin/java")
        File(toolchain, ".mobilestudio-jdk-verification-failed.txt").writeText(
            "JDK verification failed; this runtime was NOT activated.\n" +
                "IOException: Pointer tag for 0x720ea570e0 was truncated\n"
        )

        val status = RuntimeInspector(app).inspect()
        // The JDK is present but must never be advertised as build-ready ...
        assertNotNull(status.javaExecutable)
        assertTrue(status.javaVerificationFailed)
        assertFalse(status.readyForGradle)
        assertTrue("A reason must be shown to the user", (status.javaIssue ?: "").isNotBlank())
        // ... and the UI must be able to tell it apart from "Missing".
        assertEquals("Installed, but the startup test failed", status.javaSummary)

        val active = JdkManager(app).getActiveJdk()
        assertTrue(active.isInstalled)
        assertFalse(active.isVerified)
    }

    @Test
    fun heapTaggingAbortProducesActionableDiagnostics() {
        val logs = listOf(
            "BUILD: Checking Java runtime on-device...",
            "Pointer tag for 0x720ea570e0 was truncated, see 'https://source.android.com/devices/tech/debug/tagged-pointers'.",
            "The installed Android ARM64 Java runtime cannot start (exit 134)."
        )
        val diagnostic = ErrorDiagnosticsEngine.analyze(logs, null)
        assertEquals(com.aaryo.mobilestudio.toolchain.ErrorCategory.TAGGED_POINTER_SIGABRT, diagnostic.category)
        assertTrue(
            "The fix must tell the user how to recover",
            diagnostic.recommendedFix.contains("Toolchain", ignoreCase = true) &&
                diagnostic.recommendedFix.contains("Memory Tagging", ignoreCase = true)
        )
    }
}
