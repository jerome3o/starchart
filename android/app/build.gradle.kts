plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
}

// CI passes these from the release tag (e.g. v0.2.0 -> -PversionName=0.2.0).
val appVersionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
val appVersionCode = ((project.findProperty("versionCode") as String?) ?: "1").toInt()

android {
    namespace = "io.github.jerome3o.starchart"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.jerome3o.starchart"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        // MapLibre bundles native code per ABI; every modern phone is arm64,
        // and shipping only that keeps the APK a third of the size.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
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

}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.maplibre.gl:android-sdk:11.8.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.exifinterface:exifinterface:1.4.2")

    // App Functions: lets on-device agents (Gemini) discover and call the
    // functions in StarchartAppFunctionService. Android 16+ only.
    implementation("androidx.appfunctions:appfunctions:1.0.0-alpha12")
    ksp("androidx.appfunctions:appfunctions-compiler:1.0.0-alpha12")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp { arg("appfunctions:aggregateAppFunctions", "true") }
