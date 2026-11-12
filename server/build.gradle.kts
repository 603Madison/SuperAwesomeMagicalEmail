plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

// One version for the whole project: the Android app's versionName.
val sameVersion: String = Regex("versionName = \"([^\"]+)\"")
    .find(rootProject.file("android/build.gradle.kts").readText())?.groupValues?.get(1) ?: "dev"

version = sameVersion

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

application {
    mainClass.set("com.email.server.MainKt")
    applicationName = "same-server"
}

// The web app, built by `npm run build` in web/ into web/dist, is served from the jar.
val webDist = rootProject.layout.projectDirectory.dir("web/dist")

tasks.processResources {
    inputs.property("version", sameVersion)
    filesMatching("same-version.properties") { expand("version" to sameVersion) }
    from(webDist) { into("web") }
}

tasks.test {
    // The mail tests run real IMAP and SMTP servers (GreenMail) on local ports.
    maxParallelForks = 1
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.sqlite.jdbc)
    implementation(libs.nimbus.jose.jwt)
    implementation(libs.logback.classic)
    implementation(libs.jakarta.mail.api)
    implementation(libs.angus.mail)
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.greenmail)
}
