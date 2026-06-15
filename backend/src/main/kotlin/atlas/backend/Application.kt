package atlas.backend

import atlas.backend.db.Database
import atlas.backend.repo.ReadingRepository
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

/**
 * Backend entry point. Boots a Netty-backed Ktor server, connects to PostGIS,
 * applies migrations, and serves the ingest/query API.
 */
fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8080
    embeddedServer(Netty, port = port, host = "0.0.0.0") { module() }.start(wait = true)
}

/**
 * Production wiring: builds a [Database] from the environment, applies migrations,
 * installs JSON content negotiation, and registers the routes.
 *
 * Routing is kept separate ([configureRouting]) from database construction so
 * tests can supply their own repository.
 */
fun Application.module() {
    val db = Database.connect(
        jdbcUrl = System.getenv("JDBC_URL") ?: "jdbc:postgresql://localhost:5432/netatlas",
        user = System.getenv("DB_USER") ?: "netatlas",
        password = System.getenv("DB_PASSWORD") ?: "netatlas",
    )
    db.applyMigrations()

    install(ContentNegotiation) { json() }
    configureRouting(ReadingRepository(db))
}
