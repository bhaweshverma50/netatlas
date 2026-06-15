package atlas.net

import atlas.model.NetworkType
import atlas.model.ReadingBatch
import atlas.model.SignalReading
import atlas.model.Source
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ApiClientTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun reading(deviceId: String = "dev-1"): SignalReading = SignalReading(
        deviceId = deviceId,
        tsEpochMs = 1_700_000_000_000L,
        lat = 28.6139,
        lng = 77.2090,
        locAccuracyM = 5.0,
        speedMps = 1.5,
        isMoving = true,
        mcc = 405,
        mnc = 861,
        carrierName = "Jio",
        networkType = NetworkType.LTE,
        signalDbm = -95,
        rsrq = -10,
        sinr = 12,
        asu = 30,
        bars = 3,
        cellId = 123456789L,
        tac = 4321,
        pci = 55,
        earfcn = 1850,
        phoneMake = "Google",
        phoneModel = "Pixel 8",
        osVersion = "15",
        appVersion = "0.1.0",
        source = Source.CROWD,
    )

    private fun mockClient(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) { json() }
        expectSuccess = false
    }

    @Test
    fun posts_batch_to_readings_endpoint() = runTest {
        var seenPath: String? = null
        var seenMethod: HttpMethod? = null
        var seenCount: Int = -1

        val engine = MockEngine { request ->
            seenPath = request.url.encodedPath
            seenMethod = request.method
            val bodyText = (request.body as io.ktor.http.content.OutgoingContent).let {
                // Read the request body bytes and decode the batch.
                val bytes = (it as io.ktor.http.content.TextContent).text
                bytes
            }
            val batch = json.decodeFromString(ReadingBatch.serializer(), bodyText)
            seenCount = batch.readings.size
            respond(
                content = ByteReadChannel("""{"accepted":2,"rejected":0}"""),
                status = HttpStatusCode.Accepted,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }

        val api = ApiClient("http://example.test:8080", mockClient(engine))
        val ok = api.postReadings(ReadingBatch(listOf(reading("a"), reading("b"))))

        assertTrue(ok, "202 must be treated as accepted")
        assertTrue(seenPath!!.endsWith("/readings"), "path was $seenPath")
        assertEquals(HttpMethod.Post, seenMethod)
        assertEquals(2, seenCount)
    }

    @Test
    fun non_2xx_returns_false() = runTest {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel("boom"),
                status = HttpStatusCode.InternalServerError,
            )
        }
        val api = ApiClient("http://example.test:8080", mockClient(engine))
        val ok = api.postReadings(ReadingBatch(listOf(reading())))
        assertFalse(ok, "500 must not throw and must return false")
    }

    @Test
    fun created_201_is_not_accepted() = runTest {
        val engine = MockEngine {
            respond(
                content = ByteReadChannel("""{"accepted":1,"rejected":0}"""),
                status = HttpStatusCode.Created,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = ApiClient("http://example.test:8080", mockClient(engine))
        val ok = api.postReadings(ReadingBatch(listOf(reading())))
        assertFalse(ok, "only 202 counts; 201 must return false")
    }

    @Test
    fun trims_trailing_slash_in_base_url() = runTest {
        var seenPath: String? = null
        val engine = MockEngine { request ->
            seenPath = request.url.encodedPath
            respond(
                content = ByteReadChannel("""{"accepted":1,"rejected":0}"""),
                status = HttpStatusCode.Accepted,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = ApiClient("http://example.test:8080/", mockClient(engine))
        api.postReadings(ReadingBatch(listOf(reading())))
        assertEquals("/readings", seenPath, "trailing slash must not produce //readings")
    }
}
