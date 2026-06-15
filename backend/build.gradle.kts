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

// Dev-only seeder: posts ~500 synthetic readings to a running server (:8080).
// Server must be up first (`./gradlew :backend:run`). Target URL via env NETATLAS_URL.
tasks.register<JavaExec>("seed") {
    group = "application"
    description = "Generates synthetic readings and POSTs them to a running backend"
    mainClass.set("atlas.backend.dev.SeedKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// Loads OpenCelliD-format cell-tower CSV into the cell_towers table.
// Connects directly to PostGIS via JDBC_URL/DB_USER/DB_PASSWORD (same defaults as the app).
// CSV path: first program arg, then env OPENCELLID_CSV, then ingest-opencellid/sample-bengaluru.csv.
tasks.register<JavaExec>("importOpenCelliD") {
    group = "application"
    description = "Imports OpenCelliD-format cell-tower data into the cell_towers table"
    mainClass.set("atlas.backend.ingest.ImportMainKt")
    classpath = sourceSets["main"].runtimeClasspath
}

tasks.named<Test>("test") {
    useJUnitPlatform()

    // Testcontainers must reach the Docker daemon. The Gradle test JVM doesn't inherit
    // the login shell, and Docker Desktop on macOS uses a non-standard socket
    // (~/.docker/run/docker.sock) that auto-detection misses — so resolve the active
    // docker-context endpoint at configuration time and pass it as DOCKER_HOST (unless
    // one is already exported). Verified load-bearing: without this the tests fail with
    // "Could not find a valid Docker environment".
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
    // docker-java reads the negotiated API version from the `api.version` system property
    // (and DOCKER_API_VERSION env). Modern daemons reject the old default with HTTP 400,
    // so pin a version the daemon accepts. Honour an externally set DOCKER_API_VERSION.
    if (System.getenv("DOCKER_API_VERSION") == null) {
        systemProperty("api.version", "1.44")
        environment("DOCKER_API_VERSION", "1.44")
    }
}
