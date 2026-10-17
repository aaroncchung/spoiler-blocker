import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A plain Kotlin module: no Android plugin and no Android classes. That is what
// lets its tests run on a PC in a second or two (decision 14 in
// docs/ARCHITECTURE.md). It has no dependencies besides the Kotlin standard
// library, which the Kotlin plugin adds by itself.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

// The matcher will run inside the app, so it is compiled for Java 17, the same
// as :app. These settings only choose the bytecode version. On their own they
// do not stop the code calling a method that Java 17 lacks; the -Xjdk-release
// flag below does that.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        // Kotlin compiles against the class library of the JDK that runs the
        // build, which is JDK 21 here and in CI. Without this flag the matcher
        // could call a method that is new in Java 21, such as
        // List.removeFirst(), and the build and the tests would pass. Android
        // only has that method from Android 15 (API 35), so the app would crash
        // on Android 14, its minSdk. Android lint looks for this in :app's own
        // code, but not in this module.
        //
        // With the flag, Kotlin compiles against the Java 17 class library
        // instead. A method that Java 17 lacks is then a compile error or, if
        // the Kotlin standard library has a function of the same name, as it
        // does for removeFirst(), a call to that function. Android does not
        // have all of Java 17 either, so this narrows the gap without closing
        // it. There is no compilerOptions setting for it, hence the raw flag.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    testImplementation(libs.junit)
}
