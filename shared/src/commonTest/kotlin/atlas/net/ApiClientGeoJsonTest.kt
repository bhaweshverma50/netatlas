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
import kotlin.test.assertNull

private const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""

class ApiClientGeoJsonTest {

    private fun mockClient(engine: MockEngine): HttpClient = HttpClient(engine) {
        install(ContentNegotiation) { json() }
        expectSuccess = false
    }

    private val sampleFc = """
        {"type":"FeatureCollection","features":[
          {"type":"Feature","geometry":{"type":"Polygon","coordinates":[[[77.2,28.6],[77.21,28.6],[77.21,28.61],[77.2,28.6]]]},
           "properties":{"h3":"8a2a1072b59ffff","coverageClass":"FAIR","confidence":0.8}}
        ]}
    """.trimIndent()

    @Test
    fun getHexesGeoJson_builds_path_required_params_and_returns_body() = runTest {
        var seenPath: String? = null
        var seenMethod: HttpMethod? = null
        var params: Map<String, String> = emptyMap()
        val engine = MockEngine { request ->
            seenPath = request.url.encodedPath
            seenMethod = request.method
            params = request.url.parameters.names().associateWith { request.url.parameters[it]!! }
            respond(
                content = ByteReadChannel(sampleFc),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = ApiClient("http://example.test:8080", mockClient(engine))
        val body = api.getHexesGeoJson(minLng = 77.0, minLat = 28.0, maxLng = 78.0, maxLat = 29.0)

        assertEquals("/hexes.geojson", seenPath)
        assertEquals(HttpMethod.Get, seenMethod)
        assertEquals("77.0", params["minLng"])
        assertEquals("28.0", params["minLat"])
        assertEquals("78.0", params["maxLng"])
        assertEquals("29.0", params["maxLat"])
        // No optional params unless provided.
        assertNull(params["mcc"])
        assertNull(params["mnc"])
        assertNull(params["network"])
        assertEquals(sampleFc, body, "raw body must be returned verbatim")
    }

    @Test
    fun getHexesGeoJson_appends_optional_params_only_when_provided() = runTest {
        var params: Map<String, String> = emptyMap()
        val engine = MockEngine { request ->
            params = request.url.parameters.names().associateWith { request.url.parameters[it]!! }
            respond(
                content = ByteReadChannel(sampleFc),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val api = ApiClient("http://example.test:8080", mockClient(engine))
        api.getHexesGeoJson(
            minLng = 77.0, minLat = 28.0, maxLng = 78.0, maxLat = 29.0,
            mcc = 405, mnc = 861, networkType = NetworkType.LTE,
        )

        assertEquals("405", params["mcc"])
        assertEquals("861", params["mnc"])
        assertEquals("LTE", params["network"])
    }

    @Test
    fun getHexesGeoJson_non_2xx_returns_empty_feature_collection() = runTest {
        val engine = MockEngine {
            respond(content = ByteReadChannel("boom"), status = HttpStatusCode.InternalServerError)
        }
        val api = ApiClient("http://example.test:8080", mockClient(engine))
        val body = api.getHexesGeoJson(77.0, 28.0, 78.0, 29.0)
        assertEquals(EMPTY_FC, body, "non-2xx must yield an empty FeatureCollection, not the body")
    }

    @Test
    fun getHexesGeoJson_transport_failure_returns_empty_feature_collection() = runTest {
        // Simulate an offline transport error: the engine throws instead of responding.
        val engine = MockEngine { throw RuntimeException("offline") }
        val api = ApiClient("http://example.test:8080", mockClient(engine))
        val body = api.getHexesGeoJson(77.0, 28.0, 78.0, 29.0)
        assertEquals(EMPTY_FC, body, "transport failure must never throw; empty FeatureCollection instead")
    }
}
