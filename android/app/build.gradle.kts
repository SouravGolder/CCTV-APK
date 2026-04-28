plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.example.cctv_app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.example.cctv_app"
        // FFmpegKit requires API 24+
        minSdk = 24
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        // Supported architectures for FFmpegKit native libraries
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    // FFmpegKit - Community-maintained fork on Maven Central
    // Original arthenica packages were retired in Jan 2025
    // This fork supports 16KB page size (Android 15+) and modern NDK
    // Includes: libavcodec, libavformat, libavutil, libswscale, libswresample + more
    // API: com.arthenica.ffmpegkit.* (same as original FFmpegKit)
    implementation("io.github.jamaismagic.ffmpeg:ffmpeg-kit-lts-16kb:6.1.7")
}

flutter {
    source = "../.."
}
