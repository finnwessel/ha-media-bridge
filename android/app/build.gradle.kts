plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.finnwessel.mediabridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.finnwessel.mediabridge"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "0.7.1"
    }

    buildFeatures {
        buildConfig = true
    }

    // Fixed key committed to the repo so every CI build can be installed over
    // the previous one with `adb install -r`. Not a secret: installing still
    // requires ADB access to the device.
    signingConfigs {
        create("shared") {
            storeFile = file("signing.keystore")
            storePassword = "mediabridge"
            keyAlias = "mediabridge"
            keyPassword = "mediabridge"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("shared")
        }
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    lint {
        // Sideloaded utility: Play-Store-only checks don't apply.
        disable += setOf("ProtectedPermissions", "QueryAllPackagesPermission")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
