plugins {
    kotlin("jvm") version "2.0.21"
    id("io.ktor.plugin") version "3.0.3"
}

group = "com.trsproject"
version = "0.1.0"

application {
    mainClass.set("com.trsproject.server.ApplicationKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-cio")
    implementation("io.ktor:ktor-server-websockets")
    implementation("io.ktor:ktor-server-content-negotiation")
    implementation("io.ktor:ktor-serialization-kotlinx-json")
    implementation("ch.qos.logback:logback-classic:1.5.12")

    // Story 1: forwards streamed audio to Google Speech-to-Text
    implementation("com.google.cloud:google-cloud-speech:4.51.0")

    testImplementation("io.ktor:ktor-server-test-host")
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(21)
}
