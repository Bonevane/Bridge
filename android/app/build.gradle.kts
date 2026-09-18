import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from android/keystore.properties (git-ignored):
//   storeFile=/Users/you/bridge-release.jks
//   storePassword=…
//   keyAlias=bridge
//   keyPassword=…
// Without the file, release builds fall back to the debug key so `assembleRelease`
// still works on a fresh checkout; they just aren't shippable.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.bonevane.bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bonevane.bridge"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0"
        // The bundled dumbpipe binary is arm64 only (every modern phone, including your Pixel).
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off: there is no third-party code to shrink, and renaming
            // could break the app_process entry point (com.bonevane.bridge.Daemon).
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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

    packaging {
        jniLibs {
            // Extract native libs to disk at install time, so we can execute
            // libdumbpipe.so (really a standalone program) as a child process.
            useLegacyPackaging = true
            // It's not a real shared library, so don't try to strip it.
            keepDebugSymbols += "**/libdumbpipe.so"
            keepDebugSymbols += "**/libscrcpy.so"  // scrcpy's server jar, see scripts/
        }
    }
}

dependencies {
    // Jetpack Compose with Material 3 (Material You: the palette follows the
    // system wallpaper on Android 12+). The BOM keeps the artefact versions
    // consistent, so only one version number needs maintaining.
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
