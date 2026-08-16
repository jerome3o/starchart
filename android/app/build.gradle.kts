plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// CI passes these from the release tag (e.g. v0.2.0 -> -PversionName=0.2.0).
val appVersionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
val appVersionCode = ((project.findProperty("versionCode") as String?) ?: "1").toInt()

android {
    namespace = "io.github.jerome3o.starchart"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.jerome3o.starchart"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        // Committed keystore: this key only proves APK authorship for sideloading.
        // Obtainium requires every release to be signed with the same key.
        create("release") {
            storeFile = rootProject.file("keystore/starchart-release.jks")
            storePassword = "starchart"
            keyAlias = "starchart"
            keyPassword = "starchart"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
