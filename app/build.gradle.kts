plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.asl.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.asl.app"
        minSdk = 31
        // Deliberately >= 29: the PoC must work under the modern W^X exec restrictions,
        // NOT by targeting an old SDK to dodge them.
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0-poc"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    // Native PTY helper (app/src/main/cpp/pty.c). CI passes the NDK/CMake versions that are already
    // installed on the runner so Gradle does not download a multi-hundred-MB NDK.
    System.getenv("ASL_NDK_VERSION")?.takeIf { it.isNotBlank() }?.let { ndkVersion = it }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            System.getenv("ASL_CMAKE_VERSION")?.takeIf { it.isNotBlank() }?.let { version = it }
        }
    }

    // The PRoot executables are shipped as lib*.so files and must be EXTRACTED to
    // nativeLibraryDir at install time (that directory is executable; app data is not).
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // Do not strip: these are executables/loader binaries, not normal JNI libraries.
            keepDebugSymbols += "**/*.so"
        }
    }

    // Rootfs tarballs are already compressed.
    androidResources {
        noCompress += listOf("gz", "bin")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.10")

    testImplementation("junit:junit:4.13.2")
}
