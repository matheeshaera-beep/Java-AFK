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
        versionCode = 9
        versionName = "2.3"
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
            ndk {
                // Ship arm64-v8a only by default (real phones are 64-bit ARM).
                abiFilters += listOf("arm64-v8a")
            }
        }
    }

    // NOTE on app size: useLegacyPackaging was previously required here —
    // the in-APK mmap path made Node's loader tear down V8's mutexes
    // ("destroyed mutex" SIGABRT). It is dropped for minSdk 35; if Start
    // ever SIGABRTs again on-device, restore it first.


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

// Strip debug symbols from the prebuilt NDK .so files as part of the build.
// Runs on the merged release libs (build outputs only — the jniLibs sources
// are never modified), after merge and before packaging.
val ndkHome: String = System.getenv("ANDROID_NDK_HOME")
    ?: (System.getenv("ANDROID_HOME") + "/ndk/29.0.14206865") // matches ndkVersion above
val hostTag =
    if (System.getProperty("os.name").lowercase().contains("win")) "windows-x86_64"
    else "linux-x86_64"
val ndkStrip = "$ndkHome/toolchains/llvm/prebuilt/$hostTag/bin/llvm-strip"

tasks.register<Exec>("stripReleaseNativeLibs") {
    dependsOn("mergeReleaseNativeLibs")
    doFirst {
        val libDir = File(buildDir, "intermediates/merged_native_libs/release")
        val sos = libDir.walkTopDown().filter { it.isFile && it.name.endsWith(".so") }.toList()
        commandLine(listOf(ndkStrip, "--strip-debug") + sos.map { it.absolutePath })
    }
}

afterEvaluate {
    // packageRelease is registered by AGP after this script runs.
    tasks.named("packageRelease") { dependsOn("stripReleaseNativeLibs") }
}