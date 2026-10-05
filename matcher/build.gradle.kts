import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A plain Kotlin module: no Android plugin and no Android classes. That is what
// lets its tests run on a PC in a second or two (decision 14 in
// docs/ARCHITECTURE.md). It has no dependencies besides the Kotlin standard
// library, which the Kotlin plugin adds by itself.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Produce Java 17 bytecode, the same as :app, whichever JDK runs the build.
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
    testImplementation(libs.junit)
}
