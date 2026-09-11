plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bonevane.bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bonevane.bridge"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        // The bundled dumbpipe binary is arm64 only (every modern phone, including your Pixel).
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    packaging {
        jniLibs {
            // Extract native libs to disk at install time, so we can execute
            // libdumbpipe.so (really a standalone program) as a child process.
            useLegacyPackaging = true
            // It's not a real shared library, so don't try to strip it.
            keepDebugSymbols += "**/libdumbpipe.so"
        }
    }
}

// No third-party dependencies on purpose: fewer things to break while learning.
