package atlas.backend

import atlas.backend.db.Database
import atlas.backend.ingest.TowerImporter
import atlas.backend.ingest.TowerRow
import atlas.backend.repo.ReadingRepository
import atlas.model.NetworkType
import atlas.model.ReadingBatch
import atlas.model.SignalReading
import atlas.model.Source
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * Verifies `GET /hexes.geojson` exposes a `source` property per feature, distinguishing
 * modeled (OPENCELLID) hexes from crowd ones. Same container/testApplication wiring as
 * [HexGeoJsonRoutesTest].
 */
@Testcontainers
class HexGeoJsonSourceTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(
            DockerImageName.parse("imresamu/postgis:16-3.4").asCompatibleSubstituteFor("postgres"),
        )

        private val json = Json { ignoreUnknownKeys = true }
    }

    private lateinit var db: Database
    private lateinit var importer: TowerImporter

    private val towerLat = 12.9716
    private val towerLng = 77.5946
    private val bbox = "minLng=77.4&minLat=12.8&maxLng=77.7&maxLat=13.1"

    @BeforeEach
    fun setUp() {
        db = Database.connect(postgres.jdbcUrl, postgres.username, postgres.password)
        db.applyMigrations()
        importer = TowerImporter(db)
        db.transaction {
            exec("TRUNCATE TABLE hex_aggregates, signal_readings, cell_towers RESTART IDENTITY")
        }
    }

    private fun reading(dbm: Int = -85): SignalReading = SignalReading(
        deviceId = "dev1",
        tsEpochMs = 1_700_000_000_000L,
        lat = towerLat,
        lng = towerLng,
        locAccuracyM = 8.0,
        speedMps = 0.0,
        isMoving = false,
        mcc = 404,
        mnc = 45,
        carrierName = "TestCarrier",
        networkType = NetworkType.LTE,
        signalDbm = dbm,
        rsrq = -10,
        sinr = 12,
        asu = 20,
        bars = 3,
        cellId = 12345L,
        tac = 1,
        pci = 2,
        earfcn = 1850,
        phoneMake = "TestMake",
        phoneModel = "TestModel",
        osVersion = "1.0",
        appVersion = "1.0",
        source = Source.CROWD,
    )

    private fun withServer(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureRouting(ReadingRepository(db))
        }
        block()
    }

    private suspend fun ApplicationTestBuilder.postJson(path: String, body: String) =
        client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun batchJson(batch: ReadingBatch): String = json.encodeToString(ReadingBatch.serializer(), batch)

    @Test
    fun geojson_features_expose_opencellid_source_for_modeled() = withServer {
        importer.importRows(
            listOf(TowerRow("LTE", 404, 45, 678, 12345L, towerLng, towerLat, 1500, 42)),
        )

        val resp = client.get("/hexes.geojson?$bbox")
        assertEquals(HttpStatusCode.OK, resp.status)

        val features = json.parseToJsonElement(resp.bodyAsText()).jsonObject["features"]!!.jsonArray
        assertTrue(features.isNotEmpty(), "expected modeled features around the tower")
        assertTrue(
            features.all { it.jsonObject["properties"]!!.jsonObject["source"]!!.jsonPrimitive.content == "OPENCELLID" },
            "every modeled feature must report source OPENCELLID",
        )
    }

    @Test
    fun geojson_crowd_feature_reports_crowd_source() = withServer {
        importer.importRows(
            listOf(TowerRow("LTE", 404, 45, 678, 12345L, towerLng, towerLat, 1500, 42)),
        )
        postJson("/readings", batchJson(ReadingBatch(listOf(reading()))))

        val resp = client.get("/hexes.geojson?$bbox")
        assertEquals(HttpStatusCode.OK, resp.status)

        val features = json.parseToJsonElement(resp.bodyAsText()).jsonObject["features"]!!.jsonArray
        val sources = features.map { it.jsonObject["properties"]!!.jsonObject["source"]!!.jsonPrimitive.content }
        assertTrue(sources.contains("CROWD"), "expected at least one CROWD feature, got $sources")
        assertTrue(sources.contains("OPENCELLID"), "expected modeled features too, got $sources")
    }
}
