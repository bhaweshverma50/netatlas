package atlas.net

import atlas.model.NetworkType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiClientHexTest {

    private fun mockClient(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) { json() }
        expectSuccess = false
    }

    private fun jsonResponse(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        Triple(body, status, ContentType.Application.Json)

    private val twoHexes = """
        [
          {"h3":"8a2a1072b59ffff","resolution":10,"mcc":405,"mnc":861,"networkType":"LTE",
           "sampleCount":42,"meanDbm":-95.0,"medianDbm":-94.0,"stddev":3.5,"confidence":0.8,
           "coverageClass":"FAIR","centerLat":28.61,"centerLng":77.20},
          {"h3":"8a2a1072b5bffff","resolution":10,"mcc":405,"mnc":861,"networkType":"NR_SA",
           "sampleCount":12,"meanDbm":-80.0,"medianDbm":-81.0,"stddev":2.0,"confidence":0.5,
           "coverageClass":"GOOD","centerLat":28.62,"centerLng":77.21}
        ]
    """.trimIndent()

    @Test
    fun getHexes_builds_path_and_required_bbox_params() = runTest {
        var seenPath: String? = null
        var seenMethod: HttpMethod? = null
        var params: Map<String, String> = emptyMap()
        val engine = MockEngine { request ->
            seenPath = request.url.encodedPath
            seenMethod = request.method
            params = request.url.parameters.names().associateWith { request.url.parameters[it]!! }
            respond(
                content = ByteReadChannel(twoHexes),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = ApiClient("http://example.test:8080", mockClient(engine))
        val result = api.getHexes(minLng = 77.0, minLat = 28.0, maxLng = 78.0, maxLat = 29.0)

        assertEquals("/hexes", seenPath)
        assertEquals(HttpMethod.Get, seenMethod)
        assertEquals("77.0", params["minLng"])
        assertEquals("28.0", params["minLat"])
        assertEquals("78.0", params["maxLng"])
        assertEquals("29.0", params["maxLat"])
        // No optional params when not provided.
        assertNull(params["mcc"])
        assertNull(params["mnc"])
        assertNull(params["network"])
        assertEquals(2, result.size)
        assertEquals("8a2a1072b59ffff", result[0].h3)
        assertEquals(NetworkType.NR_SA, result[1].networkType)
    }

    @Test
    fun getHexes_appends_optional_params_only_when_provided() = runTest {
        var params: Map<String, String> = emptyMap()
        val engine = MockEngine { request ->
            params = request.url.parameters.names().associateWith { request.url.parameters[it]!! }
            respond(
                content = ByteReadChannel("[]"),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = ApiClient("http://example.test:8080", mockClient(engine))
        api.getHexes(
            minLng = 77.0, minLat = 28.0, maxLng = 78.0, maxLat = 29.0,
            mcc = 405, mnc = 861, networkType = NetworkType.LTE,
        )

        assertEquals("405", params["mcc"])
        assertEquals("861", params["mnc"])
        assertEquals("LTE", params["network"])
    }

    @Test
    fun getHexes_non_2xx_returns_empty_list() = runTest {
        val engine = MockEngine {
            respond(content = ByteReadChannel("boom"), status = HttpStatusCode.InternalServerError)
        }
        val api = ApiClient("http://example.test:8080", mockClient(engine))
        val result = api.getHexes(77.0, 28.0, 78.0, 29.0)
        assertTrue(result.isEmpty(), "non-2xx must yield empty list, not throw")
    }

    @Test
    fun getCarriers_parses_array() = runTest {
        var seenPath: String? = null
        val engine = MockEngine { request ->
            seenPath = request.url.encodedPath
            respond(
                content = ByteReadChannel(
                    """[{"mcc":405,"mnc":861,"carrierName":"Jio"},
                       {"mcc":404,"mnc":45,"carrierName":"Airtel"}]""",
                ),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = ApiClient("http://example.test:8080", mockClient(engine))
        val carriers = api.getCarriers()

        assertEquals("/carriers", seenPath)
        assertEquals(2, carriers.size)
        assertEquals("Jio", carriers[0].carrierName)
        assertEquals(45, carriers[1].mnc)
    }

    @Test
    fun getCarriers_failure_returns_empty_list() = runTest {
        val engine = MockEngine {
            respond(content = ByteReadChannel("nope"), status = HttpStatusCode.ServiceUnavailable)
        }
        val api = ApiClient("http://example.test:8080", mockClient(engine))
        assertFalse(api.getCarriers().isNotEmpty(), "failure must yield empty list")
    }
}
