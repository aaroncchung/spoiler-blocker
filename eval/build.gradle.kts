import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A command-line tool for the PC. It is not part of the app: it runs the
// matcher over labelled examples and counts the mistakes. See README.md here.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Generates the code that reads JSON into the classes marked @Serializable.
    alias(libs.plugins.kotlin.serialization)
    // Adds the `run` task.
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

application {
    // Kotlin puts the top-level main() of Main.kt in a class called MainKt.
    mainClass = "io.github.aaroncchung.spoilerblocker.eval.MainKt"
}

tasks.named<JavaExec>("run") {
    // Gradle would start the program inside eval/. Starting it in the
    // repository root means the two file names are given relative to the
    // folder that ./gradlew is in.
    workingDir = rootDir
}

dependencies {
    implementation(project(":matcher"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
