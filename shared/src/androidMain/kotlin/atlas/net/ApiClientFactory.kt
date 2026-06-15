package atlas.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

/**
 * Builds an [ApiClient] backed by the OkHttp engine for Android.
 *
 * `expectSuccess = false` keeps non-2xx responses from throwing so [ApiClient]
 * can map status codes to a boolean itself.
 */
fun defaultApiClient(baseUrl: String): ApiClient = defaultApiClient { baseUrl }

/**
 * Builds an [ApiClient] whose base URL is resolved from [baseUrlProvider] on every request,
 * so the app can re-point at a user-entered server (see the heatmap settings dialog) without
 * rebuilding the client, repository, or view model.
 */
fun defaultApiClient(baseUrlProvider: () -> String): ApiClient {
    val http = HttpClient(OkHttp) {
        install(ContentNegotiation) { json() }
        expectSuccess = false
    }
    return ApiClient.withProvider(baseUrlProvider, http)
}
