
plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("org.jetbrains.kotlinx.atomicfu") version "0.26.1"
}

group = "org.jetbrains.mcp"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("io.ktor:ktor-client-apache:3.0.2")
    implementation("io.ktor:ktor-server-sse:3.0.2")
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
