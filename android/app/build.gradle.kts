plugins {
    id("com.android.application") version "9.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("com.google.devtools.ksp") version "2.3.12"
}

android {
    namespace = "dev.mstheesha.afk"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.mstheesha.afk.java"
        minSdk = 35
        targetSdk = 36
        versionCode = 7
        versionName = "2.1"
    }

    ndkVersion = "29.0.14206865"

    buildTypes {
        debug {
            // arm64 for real phones + x86_64 for emulators. The 32-bit ABIs
            // (armeabi-v7a, x86) only add ~80MB of dead libnode weight.
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("debug")
            ndk {
                // Ship arm64-v8a only by default (real phones are 64-bit ARM).
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    // Embedded Node MUST be extracted to real files on disk. The in-APK mmap
    // path (extractNativeLibs=false, the release default) makes Node's dynamic
    // loader tear down V8's mutexes mid-boot -> "destroyed mutex" SIGABRT on
    // Start. The working debug build extracted them; release must too.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}