package com.aaryo.mobilestudio

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.aaryo.mobilestudio.data.ProjectWorkspace
import com.aaryo.mobilestudio.toolchain.ToolchainCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class MobileStudioUnitTest {

    @Test
    fun catalogHasComponents() {
        val components = ToolchainCatalog.components
        assertNotNull(components)
        assertEquals(7, components.size)
    }

    @Test
    fun catalogIdsAreDistinct() {
        val ids = ToolchainCatalog.components.map { it.id }
        assertEquals(ids.size, ids.distinct().size)
    }

    @Test
    fun catalogCoversRequiredRoles() {
        val ids = ToolchainCatalog.components.map { it.id }.toSet()
        val expected = setOf("jdk17", "gradle", "build-tools", "r8", "platform-36", "cmake-ninja", "ndk-r30")
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
    fun directCompilerBuildsRealSignedApk() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val workspace = ProjectWorkspace(context)

            val project = workspace.createFromTemplate(
                type = com.aaryo.mobilestudio.data.TemplateType.COMPOSE,
                rawName = "CompileTestApp",
                rawPackage = "com.test.compileapp"
            )

            val compiler = com.aaryo.mobilestudio.toolchain.DirectCompilerEngine(context)
            val logs = mutableListOf<String>()
            val result = compiler.compile(project, isRelease = false) { logs.add(it) }

            assertTrue("Compilation should succeed: ${logs.joinToString("\n")}", result.isSuccess)
            val apk = result.getOrThrow()
            assertTrue("Generated APK should exist on disk", apk.isFile)
            assertTrue("Generated APK should have non-zero size: ${apk.length()} bytes", apk.length() > 1024L)

            val inspector = com.aaryo.mobilestudio.toolchain.ProjectBuildInspector()
            val report = inspector.inspectApk(apk)
            assertTrue("APK must have AndroidManifest.xml", report.hasManifest)
            assertTrue("APK must have classes.dex", report.dexFiles.contains("classes.dex"))
            assertTrue("APK must have META-INF cryptographic signature", report.hasSignatureMetadata)

            val signingEngine = com.aaryo.mobilestudio.toolchain.ApkSigningEngine(context)
            val sigResult = signingEngine.verifyApkSignature(apk).getOrThrow()
            assertTrue("APK must be signed", sigResult.isSigned)
            assertTrue("APK must have V1 signature scheme", sigResult.v1Scheme)

            workspace.deleteProject(project)
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

    @Test
    fun realProjectCreationAndApkBuildVerification() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Application>()
            val workspace = ProjectWorkspace(context)

            // 1. Create a real Compose project
            val projectName = "RealMobileApp"
            val packageName = "com.aaryo.realapp"
            val project = workspace.createFromTemplate(
                type = com.aaryo.mobilestudio.data.TemplateType.COMPOSE,
                rawName = projectName,
                rawPackage = packageName
            )

            assertTrue("Project folder should exist", project.exists() && project.isDirectory)
            val manifestFile = File(project, "app/src/main/AndroidManifest.xml")
            val gradleFile = File(project, "app/build.gradle.kts")
            val ktFile = File(project, "app/src/main/java/com/aaryo/realapp/MainActivity.kt")
            assertTrue("AndroidManifest.xml must exist", manifestFile.exists())
            assertTrue("app/build.gradle.kts must exist", gradleFile.exists())
            assertTrue("MainActivity.kt must exist", ktFile.exists())

            // 2. Execute Real Direct Compilation Engine
            val compiler = com.aaryo.mobilestudio.toolchain.DirectCompilerEngine(context)
            val buildLogs = mutableListOf<String>()
            val buildResult = compiler.compile(project, isRelease = false) { log ->
                buildLogs.add(log)
            }

            assertTrue("Real compilation must succeed without errors: ${buildLogs.joinToString("\n")}", buildResult.isSuccess)
            val apk = buildResult.getOrThrow()
            assertTrue("Output APK file must exist on disk", apk.isFile)
            assertTrue("Output APK must have non-zero size (> 1KB)", apk.length() > 1024L)

            // 3. Inspect and verify internal contents of the generated APK
            java.util.zip.ZipFile(apk).use { zip ->
                val manifestEntry = zip.getEntry("AndroidManifest.xml")
                assertNotNull("APK must contain AndroidManifest.xml", manifestEntry)

                val dexEntry = zip.getEntry("classes.dex")
                assertNotNull("APK must contain classes.dex", dexEntry)

                val arscEntry = zip.getEntry("resources.arsc")
                assertNotNull("APK must contain resources.arsc", arscEntry)

                val certRsaEntry = zip.getEntry("META-INF/CERT.RSA")
                assertNotNull("APK must contain signed RSA signature META-INF/CERT.RSA", certRsaEntry)

                val certSfEntry = zip.getEntry("META-INF/CERT.SF")
                assertNotNull("APK must contain signed manifest list META-INF/CERT.SF", certSfEntry)

                val mfEntry = zip.getEntry("META-INF/MANIFEST.MF")
                assertNotNull("APK must contain META-INF/MANIFEST.MF", mfEntry)

                // Verify Dalvik Executable (DEX) header magic bytes: dex\n035\0
                zip.getInputStream(dexEntry).use { dexIn ->
                    val magic = ByteArray(8)
                    val read = dexIn.read(magic)
                    assertEquals("DEX header must be at least 8 bytes", 8, read)
                    val expectedMagic = byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), 0x0a, '0'.code.toByte(), '3'.code.toByte(), '5'.code.toByte(), 0x00)
                    for (i in 0 until 8) {
                        assertEquals("DEX header byte $i mismatch", expectedMagic[i], magic[i])
                    }
                }
            }

            // 4. Verify Cryptographic V1 Signature on APK
            val signingEngine = com.aaryo.mobilestudio.toolchain.ApkSigningEngine(context)
            val sigCheck = signingEngine.verifyApkSignature(apk).getOrThrow()
            assertTrue("Generated APK must be cryptographically signed", sigCheck.isSigned)
            assertTrue("Generated APK must have valid V1 Signature scheme", sigCheck.v1Scheme)

            // 5. Build Release APK to verify Release pipeline
            val releaseResult = compiler.compile(project, isRelease = true)
            assertTrue("Release APK compilation must succeed", releaseResult.isSuccess)
            val releaseApk = releaseResult.getOrThrow()
            assertTrue("Release APK file must exist", releaseApk.isFile)
            assertTrue("Release APK must be signed", signingEngine.verifyApkSignature(releaseApk).getOrThrow().isSigned)

            workspace.deleteProject(project)
        }
    }

    @Test
    fun bionicTagFixShimGeneratesValidElf64WithSectionHeaders() {
        val elf = com.aaryo.mobilestudio.toolchain.BionicTagFixShim.buildArm64TagFixElf()
        assertEquals(4096, elf.size)

        val buf = java.nio.ByteBuffer.wrap(elf).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        // Check ELF magic
        assertEquals(0x7f.toByte(), buf.get(0))
        assertEquals('E'.code.toByte(), buf.get(1))
        assertEquals('L'.code.toByte(), buf.get(2))
        assertEquals('F'.code.toByte(), buf.get(3))
        assertEquals(2.toByte(), buf.get(4)) // ELFCLASS64
        assertEquals(1.toByte(), buf.get(5)) // ELFDATA2LSB

        // Check Header fields
        val eType = buf.getShort(16).toInt()
        assertEquals(3, eType) // ET_DYN
        val eMachine = buf.getShort(18).toInt()
        assertEquals(183, eMachine) // EM_AARCH64
        val eShoff = buf.getLong(40)
        assertEquals(0x700L, eShoff)
        val eShentsize = buf.getShort(58).toInt()
        assertEquals(64, eShentsize)
        val eShnum = buf.getShort(60).toInt()
        assertEquals(6, eShnum)
        val eShstrndx = buf.getShort(62).toInt()
        assertEquals(1, eShstrndx) // Must be non-zero to pass Bionic linker_phdr check!

        // Read Section Headers
        // Index 1: .shstrtab
        val sh1Type = buf.getInt(0x700 + 1 * 64 + 4)
        assertEquals(3, sh1Type) // SHT_STRTAB
        // Index 2: .dynstr
        val sh2Type = buf.getInt(0x700 + 2 * 64 + 4)
        assertEquals(3, sh2Type) // SHT_STRTAB
        // Index 3: .dynamic
        val sh3Type = buf.getInt(0x700 + 3 * 64 + 4)
        assertEquals(6, sh3Type) // SHT_DYNAMIC
        val sh3Link = buf.getInt(0x700 + 3 * 64 + 40)
        assertEquals(2, sh3Link) // .dynamic links to .dynstr at index 2
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
        assertTrue(diagnostic.title.contains("Linker", ignoreCase = true))
    }
}
