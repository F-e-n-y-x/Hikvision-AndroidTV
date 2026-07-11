import java.util.Properties

plugins {
    id("com.android.application")
}

// Release signing is read from keystore.properties (kept out of version control).
// If the file is absent, the release build falls back to unsigned.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}

android {
    namespace = "com.hiktv.viewer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.hiktv.viewer"
        minSdk = 23          // Android TV (Android 6.0+); required by EncryptedSharedPreferences
        targetSdk = 35
        versionCode = 21
        versionName = "2.4.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystoreProps.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }

    // LibVLC ships native .so for all ABIs; keep them all so it runs on every TV chipset.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Split the APK per CPU type so each TV installs only its own libs (~20 MB instead of 80).
    // x86 is kept for the emulator; real TVs are armeabi-v7a / arm64-v8a. A universal APK is
    // also produced (app-universal-release.apk) for any device / when the ABI is unknown.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86")
            isUniversalApk = true
        }
    }
}

// AGP 9 built-in Kotlin: configure the JVM target here (replaces android.kotlinOptions).
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // ISAPI over HTTP Digest auth. OkHttp is held at 4.12.0 (the last 4.x): okhttp-digest
    // has no OkHttp 5 release, so 5.x cannot be adopted until upstream ships it.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.rburgst:okhttp-digest:3.1.1")

    // Encrypted credential storage (live). security-crypto is deprecated upstream; the
    // DataStore + Tink deps below are the SCAFFOLD for its eventual replacement (see
    // SecureCredentialStore) and are inert until NvrStore.USE_DATASTORE is flipped.
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.google.crypto.tink:tink-android:1.15.0")

    // Video engine: hardware-accelerated, H.264/H.265, RTSP, low-latency tunable.
    // 3.7.5 is the latest stable 3.x (4.0.0 is still EAP/preview).
    implementation("org.videolan.android:libvlc-all:3.7.5")
}
