plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.vermasrijan.pixelnpu"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.vermasrijan.pixelnpu"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // LiteRT-LM and the Tensor NPU dispatch library only ship for 64-bit ARM.
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            // Koog discovers @Tool methods via kotlin-reflect; keep R8 off until keep rules exist.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        // LiteRT loads the NPU dispatch library by path from nativeLibraryDir, so native
        // libraries must be extracted at install time rather than mapped from the APK.
        jniLibs.useLegacyPackaging = true
        // Same exclusion Koog's Android demo uses; its KMP artifacts ship overlapping META-INF files.
        resources.excludes += "META-INF/**"
    }
}

dependencies {
    implementation(libs.koog.agents)
    implementation(libs.litertlm.android)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
}
