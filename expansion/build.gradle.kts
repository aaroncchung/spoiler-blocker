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

// Tries the expansion from a terminal. It runs only when asked for by name:
// ./gradlew :expansion:run --args="\"2026 Japanese Grand Prix\" narrow"
tasks.register<JavaExec>("run") {
    group = "application"
    description = "Makes one real Claude API call and prints the three lists. See Main.kt."
    // Kotlin compiles the top-level main() in Main.kt into a class named MainKt.
    mainClass = "io.github.aaroncchung.spoilerblocker.expansion.MainKt"
    classpath = sourceSets.main.get().runtimeClasspath
    // Start in the repository root, where Main.kt looks for local.properties.
    workingDir = rootDir
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
