plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
    application
}

application {
    mainClass.set("atlas.backend.ApplicationKt")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared"))

    // Ktor server
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Exposed
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)

    // Database driver
    implementation(libs.postgres)

    // H3
    implementation(libs.h3)

    // Logging
    implementation(libs.logback.classic)

    // Tests
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter)
}

tasks.named<Test>("test") {
    useJUnitPlatform()

    // Testcontainers needs to reach the Docker daemon. The Gradle test JVM does not
    // inherit the login shell environment, so resolve the active docker-context
    // endpoint at configuration time and hand it to the test JVM as DOCKER_HOST
    // (unless the developer already exported one).
    //
    // The shaded docker-java in Testcontainers 1.20.x defaults to an old API version
    // that modern Docker daemons (server min API >= 1.40) reject with HTTP 400 on
    // every ping. Pin a compatible API version so the daemon is recognised. Honour an
    // externally set DOCKER_API_VERSION if present.
    if (System.getenv("DOCKER_HOST") == null) {
        val dockerHost = runCatching {
            val proc = ProcessBuilder(
                "docker", "context", "inspect", "--format", "{{.Endpoints.docker.Host}}",
            ).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            out.takeIf { it.startsWith("unix://") || it.startsWith("tcp://") }
        }.getOrNull()
        if (dockerHost != null) environment("DOCKER_HOST", dockerHost)
    }
    // docker-java reads the negotiated API version from the `api.version` system
    // property (and DOCKER_API_VERSION env). Modern Docker daemons reject the old
    // default v1.32 with HTTP 400, so pin a version the daemon accepts.
    if (System.getenv("DOCKER_API_VERSION") == null) {
        systemProperty("api.version", "1.44")
        environment("DOCKER_API_VERSION", "1.44")
    }
}
