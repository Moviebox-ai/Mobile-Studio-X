package com.aaryo.mobilestudio

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.aaryo.mobilestudio.data.ProjectWorkspace
import com.aaryo.mobilestudio.toolchain.ErrorDiagnosticsEngine
import com.aaryo.mobilestudio.toolchain.JdkManager
import com.aaryo.mobilestudio.toolchain.RuntimeInspector
import com.aaryo.mobilestudio.toolchain.RuntimeStatus
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
