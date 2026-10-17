plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.aaroncchung.spoilerblocker"
    // The newest AndroidX libraries need the Android 17 SDK to compile against.
    // This does not change how the app behaves: targetSdk below decides that.
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.aaroncchung.spoilerblocker"
        // Android 14. See decision 3 in docs/ARCHITECTURE.md.
        minSdk = 34
        // Android 16.
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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

    buildFeatures {
        compose = true
    }

    lint {
        // targetSdk is the phone's Android version on purpose (decision 3), so
        // the "not targeting the latest Android" warning would only be noise.
        disable += "OldTargetApi"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
