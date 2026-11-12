// The API's models: plain Kotlin/JVM with kotlinx.serialization, used by the server and the
// Android app, so both always agree on what goes over the wire. The web app's copies are in
// web/src/lib/types.ts and the desktop app's in desktop/src/api.rs: keep them in step.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}
