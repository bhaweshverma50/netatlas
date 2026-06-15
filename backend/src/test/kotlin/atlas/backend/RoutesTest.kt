package atlas.backend

import atlas.backend.db.Database
import atlas.backend.repo.ReadingRepository
import atlas.model.HexAggregate
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
class RoutesTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(
            DockerImageName.parse("imresamu/postgis:16-3.4").asCompatibleSubstituteFor("postgres"),
        )

        private val json = Json { ignoreUnknownKeys = true }
    }

    private lateinit var db: Database

    @BeforeEach
    fun setUp() {
        db = Database.connect(postgres.jdbcUrl, postgres.username, postgres.password)
        db.applyMigrations()
        db.transaction {
            exec("TRUNCATE TABLE hex_aggregates, signal_readings RESTART IDENTITY")
        }
    }

    /** Builds a plausible, valid SignalReading. */
    private fun reading(
        dbm: Int,
        lat: Double = 12.9716,
        lng: Double = 77.5946,
        mnc: Int = 45,
        device: String = "dev1",
        networkType: NetworkType = NetworkType.LTE,
        rsrq: Int? = -10,
        sinr: Int? = 12,
    ): SignalReading = SignalReading(
        deviceId = device,
        tsEpochMs = 1_700_000_000_000L,
        lat = lat,
        lng = lng,
        locAccuracyM = 8.0,
        speedMps = 0.0,
        isMoving = false,
        mcc = 404,
        mnc = mnc,
        carrierName = "TestCarrier",
        networkType = networkType,
        signalDbm = dbm,
        rsrq = rsrq,
        sinr = sinr,
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

    /**
     * Spins up a Ktor test application wired to a real repository over the
     * Testcontainers PostGIS database. Request/response JSON is handled manually
     * with [json] so the test needs no client-side content-negotiation plugin.
     */
    private fun withServer(block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json() }
            configureRouting(ReadingRepository(db))
        }
        block()
    }

    /** POSTs [body] as raw JSON with an application/json content type. */
    private suspend fun ApplicationTestBuilder.postJson(path: String, body: String) =
        client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun batchJson(batch: ReadingBatch): String = json.encodeToString(ReadingBatch.serializer(), batch)

    @Test
    fun healthz_returns_200() = withServer {
        val resp = client.get("/healthz")
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun post_readings_returns_202_with_counts() = withServer {
        val resp = postJson("/readings", batchJson(ReadingBatch(listOf(reading(-90), reading(10)))))
        assertEquals(HttpStatusCode.Accepted, resp.status)
        val body = json.decodeFromString<IngestResponse>(resp.bodyAsText())
        assertEquals(1, body.accepted)
        assertEquals(1, body.rejected)
    }

    @Test
    fun get_hexes_returns_aggregated_hex() = withServer {
        postJson("/readings", batchJson(ReadingBatch(listOf(reading(-90), reading(-100)))))

        val resp = client.get("/hexes?minLng=77&minLat=12&maxLng=78&maxLat=13")
        assertEquals(HttpStatusCode.OK, resp.status)
        val hexes = json.decodeFromString<List<HexAggregate>>(resp.bodyAsText())
        assertEquals(1, hexes.size)
        assertEquals(2, hexes.single().sampleCount)
    }

    @Test
    fun get_hexes_carrier_filter() = withServer {
        postJson("/readings", batchJson(ReadingBatch(listOf(reading(-90, mnc = 45), reading(-90, mnc = 10)))))

        val resp = client.get("/hexes?minLng=77&minLat=12&maxLng=78&maxLat=13&mnc=45")
        assertEquals(HttpStatusCode.OK, resp.status)
        val hexes = json.decodeFromString<List<HexAggregate>>(resp.bodyAsText())
        assertEquals(1, hexes.size)
        assertEquals(45, hexes.single().mnc)
    }

    @Test
    fun get_hexes_missing_param_returns_400() = withServer {
        val resp = client.get("/hexes")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun get_carriers_lists_present_carrier() = withServer {
        postJson("/readings", batchJson(ReadingBatch(listOf(reading(-90)))))

        val resp = client.get("/carriers")
        assertEquals(HttpStatusCode.OK, resp.status)
        val carriers = json.decodeFromString<List<CarrierResponse>>(resp.bodyAsText())
        assertTrue(carriers.any { it.mcc == 404 }, "expected carrier with mcc=404, got $carriers")
    }

    @Test
    fun post_malformed_json_returns_400() = withServer {
        val resp = postJson("/readings", "{not json")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
