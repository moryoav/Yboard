import java.util.Properties

val localConfig = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val appVersion = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Renders screenshots of the UI on the computer (no phone needed): gradle recordPaparazziDebug
    id("app.cash.paparazzi")
}

android {
    namespace = "com.yboard.keyboard"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    signingConfigs {
        getByName("debug") {
            localConfig.getProperty("yboard.signingStore")?.let { path ->
                storeFile = file(path)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    defaultConfig {
        applicationId = "com.yboard.keyboard"
        minSdk = 26
        targetSdk = 35
        testInstrumentationRunner = "com.yboard.keyboard.KeyboardSmokeInstrumentation"
        versionCode = appVersion.getProperty("versionCode").toInt()
        versionName = appVersion.getProperty("versionName")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_shared", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON") }
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed with the debug key so the APK can be installed directly.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    // WebSocket + HTTP for AI voice typing (OpenAI).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Styles for the password / code chips from the autofill service.
    implementation("androidx.autofill:autofill:1.1.0")

    testImplementation("junit:junit:4.13.2")
}
