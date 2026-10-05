// The Phase 0 probe: a throwaway app that measures experiments E1 to E6.
// See docs/PROBE.md. This module lives only on the `probe` branch.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.aaroncchung.spoilerblocker.probe"
    compileSdk = 37

    defaultConfig {
        // A different id from the real app, so both can be installed at once.
        applicationId = "io.github.aaroncchung.spoilerblocker.probe"
        minSdk = 34
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
        // Same reason as in :app: targetSdk is the phone's Android version on purpose.
        disable += "OldTargetApi"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    testImplementation(libs.junit)
}
