package com.aaryo.mobilestudio.data

import java.io.File

enum class TemplateType(
    val id: String,
    val title: String,
    val subtitle: String,
    val description: String,
    val defaultAppPrefix: String,
    val defaultPackage: String
) {
    COMPOSE(
        id = "compose",
        title = "Jetpack Compose",
        subtitle = "Material 3 • Declarative UI",
        description = "Modern Android project with Kotlin 1.9+, Jetpack Compose Material 3 UI, and Coroutines.",
        defaultAppPrefix = "ComposeApp",
        defaultPackage = "com.mobilestudio.composeapp"
    ),
    VIEWS(
        id = "views",
        title = "Kotlin + XML Views",
        subtitle = "Material 3 • Layout XML",
        description = "Classic Android application using XML layouts (LinearLayout/ConstraintLayout), Material Design, and Kotlin.",
        defaultAppPrefix = "ViewsApp",
        defaultPackage = "com.mobilestudio.viewsapp"
    ),
    NATIVE_CPP(
        id = "native_cpp",
        title = "C++ Native (NDK + CMake)",
        subtitle = "C++20 • CMake • JNI",
        description = "Native ARM64 C++ project with CMakeLists.txt, JNI bindings, and Gradle external native build integration.",
        defaultAppPrefix = "NativeApp",
        defaultPackage = "com.mobilestudio.nativeapp"
    ),
    MINIMAL_AAPT(
        id = "minimal_aapt",
        title = "Lightweight Quick App",
        subtitle = "Fast Build • Standalone AAPT2",
        description = "Lightweight minimal project designed for rapid on-device resource compiling and packaging without build overhead.",
        defaultAppPrefix = "QuickApp",
        defaultPackage = "com.mobilestudio.quickapp"
    )
}

object ProjectTemplateGenerator {

    fun generate(
        targetDir: File,
        type: TemplateType,
        appName: String,
        packageName: String
    ) {
        targetDir.mkdirs()
        val pkgPath = packageName.replace('.', '/')
        val jniPkg = packageName.replace('.', '_')

        // 1. settings.gradle.kts
        File(targetDir, "settings.gradle.kts").writeText(
            """
            pluginManagement {
                repositories {
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            dependencyResolutionManagement {
                repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                repositories {
                    google()
                    mavenCentral()
                }
            }
            rootProject.name = "$appName"
            include(":app")
            """.trimIndent() + "\n"
        )

        // 2. Root build.gradle.kts
        File(targetDir, "build.gradle.kts").writeText(
            """
            plugins {
                id("com.android.application") version "8.2.2" apply false
                id("org.jetbrains.kotlin.android") version "1.9.22" apply false
            }
            """.trimIndent() + "\n"
        )

        // 3. gradle.properties
        File(targetDir, "gradle.properties").writeText(
            """
            org.gradle.jvmargs=-Xmx768m -XX:MaxMetaspaceSize=256m -XX:-UseCompressedOops -XX:-UseCompressedClassPointers -Dfile.encoding=UTF-8
            org.gradle.daemon=false
            org.gradle.parallel=false
            android.useAndroidX=true
            android.nonTransitiveRClass=true
            """.trimIndent() + "\n"
        )

        // 4. app module directories
        val appDir = File(targetDir, "app").apply { mkdirs() }
        val srcMain = File(appDir, "src/main").apply { mkdirs() }
        val javaDir = File(srcMain, "java/$pkgPath").apply { mkdirs() }
        val resDir = File(srcMain, "res").apply { mkdirs() }
        val resValues = File(resDir, "values").apply { mkdirs() }
        val resLayout = File(resDir, "layout").apply { mkdirs() }

        // strings.xml
        File(resValues, "strings.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">$appName</string>
                <string name="welcome_message">Welcome to $appName</string>
            </resources>
            """.trimIndent() + "\n"
        )

        // colors.xml
        File(resValues, "colors.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <color name="purple_primary">#6750A4</color>
                <color name="purple_dark">#21005D</color>
                <color name="teal_accent">#03DAC5</color>
                <color name="background_dark">#121622</color>
            </resources>
            """.trimIndent() + "\n"
        )

        // AndroidManifest.xml
        File(srcMain, "AndroidManifest.xml").writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="$packageName">

                <application
                    android:allowBackup="true"
                    android:allowNativeHeapPointerTagging="false"
                    android:label="@string/app_name"
                    android:supportsRtl="true"
                    android:theme="@android:style/Theme.Material.Light.NoActionBar">
                    <activity
                        android:name=".MainActivity"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.intent.action.MAIN" />
                            <category android:name="android.intent.category.LAUNCHER" />
                        </intent-filter>
                    </activity>
                </application>
            </manifest>
            """.trimIndent() + "\n"
        )

        when (type) {
            TemplateType.COMPOSE -> {
                // app/build.gradle.kts
                File(appDir, "build.gradle.kts").writeText(
                    """
                    plugins {
                        id("com.android.application")
                        id("org.jetbrains.kotlin.android")
                    }

                    android {
                        namespace = "$packageName"
                        compileSdk = 34

                        defaultConfig {
                            applicationId = "$packageName"
                            minSdk = 26
                            targetSdk = 36
                            versionCode = 1
                            versionName = "1.0"
                        }

                        buildFeatures {
                            compose = true
                        }
                        composeOptions {
                            kotlinCompilerExtensionVersion = "1.5.8"
                        }
                        compileOptions {
                            sourceCompatibility = JavaVersion.VERSION_17
                            targetCompatibility = JavaVersion.VERSION_17
                        }
                        kotlinOptions {
                            jvmTarget = "17"
                        }
                    }

                    dependencies {
                        implementation(platform("androidx.compose:compose-bom:2024.02.00"))
                        implementation("androidx.activity:activity-compose:1.8.2")
                        implementation("androidx.compose.ui:ui")
                        implementation("androidx.compose.ui:ui-graphics")
                        implementation("androidx.compose.ui:ui-tooling-preview")
                        implementation("androidx.compose.material3:material3")
                    }
                    """.trimIndent() + "\n"
                )

                // MainActivity.kt
                File(javaDir, "MainActivity.kt").writeText(
                    """
                    package $packageName

                    import android.os.Bundle
                    import androidx.activity.ComponentActivity
                    import androidx.activity.compose.setContent
                    import androidx.compose.foundation.layout.*
                    import androidx.compose.material3.*
                    import androidx.compose.runtime.*
                    import androidx.compose.ui.Alignment
                    import androidx.compose.ui.Modifier
                    import androidx.compose.ui.unit.dp
                    import androidx.compose.ui.unit.sp

                    class MainActivity : ComponentActivity() {
                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                            setContent {
                                MaterialTheme {
                                    Surface(
                                        modifier = Modifier.fillMaxSize(),
                                        color = MaterialTheme.colorScheme.background
                                    ) {
                                        MainScreen()
                                    }
                                }
                            }
                        }
                    }

                    @Composable
                    fun MainScreen() {
                        var counter by remember { mutableIntStateOf(0) }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = "Welcome to $appName",
                                fontSize = 24.sp,
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Built on-device with Mobile Studio X",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(onClick = { counter++ }) {
                                Text("Tapped: ${'$'}counter times")
                            }
                        }
                    }
                    """.trimIndent() + "\n"
                )
            }

            TemplateType.VIEWS -> {
                // app/build.gradle.kts
                File(appDir, "build.gradle.kts").writeText(
                    """
                    plugins {
                        id("com.android.application")
                        id("org.jetbrains.kotlin.android")
                    }

                    android {
                        namespace = "$packageName"
                        compileSdk = 34

                        defaultConfig {
                            applicationId = "$packageName"
                            minSdk = 26
                            targetSdk = 36
                            versionCode = 1
                            versionName = "1.0"
                        }
                        compileOptions {
                            sourceCompatibility = JavaVersion.VERSION_17
                            targetCompatibility = JavaVersion.VERSION_17
                        }
                        kotlinOptions {
                            jvmTarget = "17"
                        }
                    }

                    dependencies {
                        implementation("androidx.core:core-ktx:1.12.0")
                        implementation("androidx.appcompat:appcompat:1.6.1")
                        implementation("com.google.android.material:material:1.11.0")
                    }
                    """.trimIndent() + "\n"
                )

                // activity_main.xml
                File(resLayout, "activity_main.xml").writeText(
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent"
                        android:orientation="vertical"
                        android:gravity="center"
                        android:padding="24dp">

                        <TextView
                            android:id="@+id/title_text"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="@string/welcome_message"
                            android:textSize="24sp"
                            android:textStyle="bold" />

                        <TextView
                            android:id="@+id/counter_text"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="16dp"
                            android:text="Tap the button below"
                            android:textSize="16sp" />

                        <Button
                            android:id="@+id/action_button"
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:layout_marginTop="24dp"
                            android:text="Click Me" />
                    </LinearLayout>
                    """.trimIndent() + "\n"
                )

                // MainActivity.kt
                File(javaDir, "MainActivity.kt").writeText(
                    """
                    package $packageName

                    import android.os.Bundle
                    import android.widget.Button
                    import android.widget.TextView
                    import androidx.appcompat.app.AppCompatActivity

                    class MainActivity : AppCompatActivity() {
                        private var count = 0

                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                            setContentView(R.layout.activity_main)

                            val counterText = findViewById<TextView>(R.id.counter_text)
                            val actionButton = findViewById<Button>(R.id.action_button)

                            actionButton.setOnClickListener {
                                count++
                                counterText.text = "Button tapped ${'$'}count times"
                            }
                        }
                    }
                    """.trimIndent() + "\n"
                )
            }

            TemplateType.NATIVE_CPP -> {
                // app/build.gradle.kts
                File(appDir, "build.gradle.kts").writeText(
                    """
                    plugins {
                        id("com.android.application")
                        id("org.jetbrains.kotlin.android")
                    }

                    android {
                        namespace = "$packageName"
                        compileSdk = 34

                        defaultConfig {
                            applicationId = "$packageName"
                            minSdk = 26
                            targetSdk = 36
                            versionCode = 1
                            versionName = "1.0"

                            externalNativeBuild {
                                cmake {
                                    cppFlags += "-std=c++20"
                                }
                            }
                        }
                        externalNativeBuild {
                            cmake {
                                path = file("src/main/cpp/CMakeLists.txt")
                                version = "3.22.1"
                            }
                        }
                        compileOptions {
                            sourceCompatibility = JavaVersion.VERSION_17
                            targetCompatibility = JavaVersion.VERSION_17
                        }
                        kotlinOptions {
                            jvmTarget = "17"
                        }
                    }

                    dependencies {
                        implementation("androidx.core:core-ktx:1.12.0")
                        implementation("androidx.appcompat:appcompat:1.6.1")
                    }
                    """.trimIndent() + "\n"
                )

                // C++ cpp directory
                val cppDir = File(srcMain, "cpp").apply { mkdirs() }
                File(cppDir, "CMakeLists.txt").writeText(
                    """
                    cmake_minimum_required(VERSION 3.22.1)
                    project("nativelib")

                    add_library(
                        nativelib
                        SHARED
                        native-lib.cpp
                    )

                    find_library(
                        log-lib
                        log
                    )

                    target_link_libraries(
                        nativelib
                        ${'$'}{log-lib}
                    )
                    """.trimIndent() + "\n"
                )

                File(cppDir, "native-lib.cpp").writeText(
                    """
                    #include <jni.h>
                    #include <string>

                    extern "C" JNIEXPORT jstring JNICALL
                    Java_${jniPkg}_MainActivity_stringFromJNI(
                            JNIEnv* env,
                            jobject /* this */) {
                        std::string hello = "Hello from C++20 Native Code (ARM64 on Android)!";
                        return env->NewStringUTF(hello.c_str());
                    }
                    """.trimIndent() + "\n"
                )

                // MainActivity.kt
                File(javaDir, "MainActivity.kt").writeText(
                    """
                    package $packageName

                    import android.os.Bundle
                    import android.widget.TextView
                    import androidx.appcompat.app.AppCompatActivity

                    class MainActivity : AppCompatActivity() {

                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                            val tv = TextView(this).apply {
                                textSize = 18f
                                setPadding(48, 64, 48, 64)
                                text = stringFromJNI()
                            }
                            setContentView(tv)
                        }

                        external fun stringFromJNI(): String

                        companion object {
                            init {
                                System.loadLibrary("nativelib")
                            }
                        }
                    }
                    """.trimIndent() + "\n"
                )
            }

            TemplateType.MINIMAL_AAPT -> {
                // app/build.gradle.kts
                File(appDir, "build.gradle.kts").writeText(
                    """
                    plugins {
                        id("com.android.application")
                        id("org.jetbrains.kotlin.android")
                    }

                    android {
                        namespace = "$packageName"
                        compileSdk = 34

                        defaultConfig {
                            applicationId = "$packageName"
                            minSdk = 26
                            targetSdk = 36
                            versionCode = 1
                            versionName = "1.0"
                        }
                    }

                    dependencies {
                        implementation("androidx.core:core-ktx:1.12.0")
                    }
                    """.trimIndent() + "\n"
                )

                // activity_main.xml
                File(resLayout, "activity_main.xml").writeText(
                    """
                    <?xml version="1.0" encoding="utf-8"?>
                    <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                        android:layout_width="match_parent"
                        android:layout_height="match_parent"
                        android:orientation="vertical"
                        android:gravity="center"
                        android:padding="24dp">

                        <TextView
                            android:layout_width="wrap_content"
                            android:layout_height="wrap_content"
                            android:text="@string/welcome_message"
                            android:textSize="22sp"
                            android:textStyle="bold" />
                    </LinearLayout>
                    """.trimIndent() + "\n"
                )

                // MainActivity.kt
                File(javaDir, "MainActivity.kt").writeText(
                    """
                    package $packageName

                    import android.app.Activity
                    import android.os.Bundle
                    import android.widget.TextView

                    class MainActivity : Activity() {
                        override fun onCreate(savedInstanceState: Bundle?) {
                            super.onCreate(savedInstanceState)
                            val tv = TextView(this).apply {
                                text = "Mobile Studio X: $appName is running!"
                                textSize = 20f
                                setPadding(40, 50, 40, 50)
                            }
                            setContentView(tv)
                        }
                    }
                    """.trimIndent() + "\n"
                )
            }
        }
    }
}
