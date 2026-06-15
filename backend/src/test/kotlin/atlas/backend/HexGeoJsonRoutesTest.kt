package atlas.backend

import atlas.backend.db.Database
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
class HexGeoJsonRoutesTest {

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

    private fun reading(
        dbm: Int,
        lat: Double = 12.9716,
        lng: Double = 77.5946,
        mnc: Int = 45,
        device: String = "dev1",
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
    fun geojson_returns_feature_collection_of_polygons() = withServer {
        postJson("/readings", batchJson(ReadingBatch(listOf(reading(-90), reading(-100)))))

        val resp = client.get("/hexes.geojson?minLng=77&minLat=12&maxLng=78&maxLat=13")
        assertEquals(HttpStatusCode.OK, resp.status)
        assertTrue(
            resp.contentType()?.match(ContentType.Application.Json) == true,
            "expected application/json, got ${resp.contentType()}",
        )

        val root = json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("FeatureCollection", root["type"]!!.jsonPrimitive.content)

        val features = root["features"]!!.jsonArray
        assertTrue(features.size >= 1, "expected at least one feature, got ${features.size}")

        val feature = features[0].jsonObject
        assertEquals("Feature", feature["type"]!!.jsonPrimitive.content)

        val geometry = feature["geometry"]!!.jsonObject
        assertEquals("Polygon", geometry["type"]!!.jsonPrimitive.content)

        // Polygon coordinates: array of linear rings; we ship a single outer ring.
        val ring = geometry["coordinates"]!!.jsonArray[0].jsonArray
        assertTrue(ring.size >= 4, "ring must have >= 4 positions (closed hexagon), got ${ring.size}")

        // Each position is [lng, lat].
        val first = ring.first().jsonArray
        val last = ring.last().jsonArray
        assertEquals(2, first.size, "each position is [lng, lat]")
        assertEquals(
            first[0].jsonPrimitive.content, last[0].jsonPrimitive.content,
            "ring must be closed: first lng == last lng",
        )
        assertEquals(
            first[1].jsonPrimitive.content, last[1].jsonPrimitive.content,
            "ring must be closed: first lat == last lat",
        )

        val props = feature["properties"]!!.jsonObject
        assertNotNull(props["coverageClass"], "properties.coverageClass must be present")
        assertNotNull(props["h3"], "properties.h3 must be present")
        assertNotNull(props["confidence"], "properties.confidence must be present")
    }

    @Test
    fun geojson_carrier_filter_applies() = withServer {
        postJson("/readings", batchJson(ReadingBatch(listOf(reading(-90, mnc = 45), reading(-90, mnc = 10)))))

        val resp = client.get("/hexes.geojson?minLng=77&minLat=12&maxLng=78&maxLat=13&mnc=45")
        assertEquals(HttpStatusCode.OK, resp.status)

        val features = json.parseToJsonElement(resp.bodyAsText()).jsonObject["features"]!!.jsonArray
        assertEquals(1, features.size, "mnc filter should leave a single feature")
        assertEquals(45, features[0].jsonObject["properties"]!!.jsonObject["mnc"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun geojson_missing_param_returns_400() = withServer {
        val resp = client.get("/hexes.geojson")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
