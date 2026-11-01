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
}
