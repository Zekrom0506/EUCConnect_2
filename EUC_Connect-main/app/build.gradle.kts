import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.util.Properties
import javax.inject.Inject

abstract class InstallDebugDirectTask @Inject constructor(
    private val execOperations: ExecOperations
) : DefaultTask() {

    @TaskAction
    fun installAndLaunch() {
        val localProperties = Properties()
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            localPropertiesFile.inputStream().use(localProperties::load)
        }

        val sdkDir = localProperties.getProperty("sdk.dir")
            ?: System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: error("Android SDK path not found. Set sdk.dir in local.properties.")

        val adb = project.file("$sdkDir/platform-tools/adb.exe")
        val apk = project.layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile

        execOperations.exec {
            commandLine(adb.absolutePath, "wait-for-device")
        }
        execOperations.exec {
            commandLine(adb.absolutePath, "install", "--no-streaming", "-r", "-d", apk.absolutePath)
        }
        execOperations.exec {
            commandLine(
                adb.absolutePath,
                "shell",
                "monkey",
                "-p",
                "com.example.eucconnect",
                "-c",
                "android.intent.category.LAUNCHER",
                "1"
            )
        }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.example.eucconnect"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.eucconnect"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ADD THIS BLOCK HERE:
    buildFeatures {
        viewBinding = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}

tasks.withType<KotlinCompile>().configureEach {
    incremental = false
}

tasks.register<InstallDebugDirectTask>("installDebugDirect") {
    group = "install"
    description = "Builds, installs, and launches the debug APK with adb directly."
    dependsOn("assembleDebug")
}
