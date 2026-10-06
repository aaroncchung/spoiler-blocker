import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Keyword expansion: plain Kotlin with no Android code, so it builds and is
// tested on a PC. The app will depend on this module.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Generates the code that turns JSON into the @Serializable classes.
    alias(libs.plugins.kotlin.serialization)
}

// Same bytecode level as the app.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // "api" rather than "implementation" because OkHttpClient appears in
    // KeywordExpander's constructor, so the app needs to see the type too.
    api(libs.okhttp)
    // Lets a coroutine wait for an OkHttp call, and cancel it.
    implementation(libs.okhttp.coroutines)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    // A fake HTTP server for tests, so that no test calls the real API.
    testImplementation(libs.okhttp.mockwebserver)
}
