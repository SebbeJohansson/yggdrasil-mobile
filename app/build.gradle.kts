plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release builds take their version from the tag (see .github/workflows/release.yml):
// YGG_VERSION_NAME=1.2.3 gives versionName "1.2.3" and versionCode 10203.
val releaseVersionName: String? = System.getenv("YGG_VERSION_NAME")?.takeIf { it.isNotBlank() }
val releaseVersionCode: Int? = releaseVersionName?.let { v ->
    val (major, minor, patch) = Regex("""^(\d+)\.(\d+)\.(\d+)""").find(v)?.destructured
        ?: error("YGG_VERSION_NAME must start with MAJOR.MINOR.PATCH, got '$v'")
    major.toInt() * 10_000 + minor.toInt() * 100 + patch.toInt()
}

// The release key comes from the environment (GitHub secrets in CI). Without
// it, a release build is left unsigned, and the release workflow stops.
val releaseKeystore: String? = System.getenv("YGG_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

android {
    namespace = "io.github.sebbejohansson.yggdrasil"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "io.github.sebbejohansson.yggdrasil"
        // LiteRT-LM's GPU backend needs a recent Android; the Pixel 9 runs 15+.
        minSdk = 31
        targetSdk = 37
        versionCode = releaseVersionCode ?: 1
        versionName = releaseVersionName ?: "0.1.0-dev"

        // Only ship the native libs for real phones.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("YGG_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("YGG_KEY_ALIAS")
                keyPassword = System.getenv("YGG_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        // Dev builds install next to the release app instead of over it.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // R8 shrinks the code to a fraction; proguard-rules.pro keeps what
            // LiteRT-LM's native side and reflection reach by name.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Unpacked at install: Ygg's own Linux runs proot from nativeLibraryDir
        // (Android runs no program from app storage). LiteRT-LM loads either way,
        // and the APK is smaller compressed.
        jniLibs.useLegacyPackaging = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.litertlm.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Screenshot tests: the screens drawn on the JVM (ScreenshotTest), to look at without a phone.
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
