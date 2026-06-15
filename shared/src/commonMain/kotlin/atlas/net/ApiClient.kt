package atlas.net

import atlas.model.ReadingBatch
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

/**
 * Engine-agnostic HTTP client for posting [ReadingBatch]es to the netatlas backend.
 *
 * The [HttpClient] is injected so the same logic runs under any Ktor engine (OkHttp
 * on Android, a MockEngine in tests). The client must be configured with
 * `expectSuccess = false` so non-2xx responses are inspected here rather than thrown.
 */
class ApiClient(baseUrl: String, private val http: HttpClient) {

    // Drop a single trailing slash so we never build "$baseUrl//readings".
    private val baseUrl: String = baseUrl.trimEnd('/')

    /**
     * POSTs [batch] to `/readings`.
     *
     * @return `true` only when the server replies `202 Accepted`; any other status
     *   (including other 2xx codes and 4xx/5xx) yields `false`, and no exception is
     *   thrown for non-2xx responses.
     */
    suspend fun postReadings(batch: ReadingBatch): Boolean {
        val response = http.post("$baseUrl/readings") {
            contentType(ContentType.Application.Json)
            setBody(batch)
        }
        return response.status == HttpStatusCode.Accepted
    }
}
