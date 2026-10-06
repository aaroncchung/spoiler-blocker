import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    // Generates the code behind @Serializable: for the stored blockers and for
    // the navigation routes.
    alias(libs.plugins.kotlin.serialization)
}

// The Anthropic API key, from the line "anthropic.apiKey=..." in
// local.properties at the top of the repository. Git ignores that file. The
// key is empty when the file or the line is missing, as on CI: the build
// still works, and the app then says that it has no key.
//
// The file is read through "providers" so that Gradle knows the build depends
// on it and notices when it changes.
val localProperties = Properties()
providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText.orNull
    ?.let { text -> localProperties.load(text.reader()) }
val anthropicApiKey: String = localProperties.getProperty("anthropic.apiKey", "").trim()

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
        debug {
            // Puts the key into the generated BuildConfig class, which is
            // how the app reads it. The value is Java source text, so the
            // quotation marks are part of it.
            //
            // A key compiled into an APK can be read out of that APK by
            // anyone who has the file. That is acceptable only because this
            // build is installed on the owner's own phone and nowhere else.
            // It must change before any release: a published app would have
            // to get the key some other way, such as from its user.
            buildConfigField("String", "ANTHROPIC_API_KEY", "\"$anthropicApiKey\"")
        }
        release {
            isMinifyEnabled = false
            // A release build never contains the key, for the reason above.
            buildConfigField("String", "ANTHROPIC_API_KEY", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Generates the BuildConfig class. The notification listener reads
        // BuildConfig.DEBUG so that it only writes to the log in debug builds,
        // and the Anthropic API key is in it (see buildTypes above).
        buildConfig = true
    }

    lint {
        // targetSdk is the phone's Android version on purpose (decision 3), so
        // the "not targeting the latest Android" warning would only be noise.
        disable += "OldTargetApi"
    }
}

dependencies {
    implementation(project(":matcher"))
    // Keyword expansion: the call to the Claude API that suggests the lists
    // for a new blocker. It brings OkHttp, the app's only network code.
    implementation(project(":expansion"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // SavedStateHandle, which keeps the editor's draft when Android stops the
    // app's process in the background.
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.datastore.core.okio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
