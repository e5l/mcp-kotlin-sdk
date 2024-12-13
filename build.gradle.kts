plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
//    id("org.jetbrains.kotlinx.atomicfu") version "0.26.1"
    application
}

application {
    mainClass.set("CliKt")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "CliKt"
    }

    // This will include all dependencies in the JAR
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) }
    })
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
    implementation("io.ktor:ktor-server-websockets:3.0.2")
    testImplementation(kotlin("test"))
    testImplementation("io.mockk:mockk:1.13.13")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-debug:1.9.0")
    testImplementation("io.ktor:ktor-server-test-host:3.0.2")
    testImplementation("io.ktor:ktor-server-cio:3.0.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test:2.0.21")
}

tasks.test {
    useJUnitPlatform()
}

abstract class GenerateLibVersionTask @Inject constructor(
    @get:Input val libVersion: String,
    @get:OutputDirectory val sourcesDir: File
) : DefaultTask() {
    @TaskAction
    fun generate() {
        val sourceFile = File(sourcesDir, "LibVersion.kt")

        sourceFile.writeText(
            """
            package shared

            const val LIB_VERSION = "$libVersion"
            
            """.trimIndent()
        )
    }
}

val sourcesDir = File(project.layout.buildDirectory.asFile.get(), "generated-sources/libVersion")

val generateLibVersionTask =
    tasks.register<GenerateLibVersionTask>("generateLibVersion", version.toString(), sourcesDir)

kotlin {
    jvmToolchain(21)

    sourceSets {
        main {
            kotlin.srcDir(generateLibVersionTask.map { it.sourcesDir })
        }
    }
}
