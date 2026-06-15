package atlas.net

import atlas.model.Carrier
import atlas.model.HexAggregate
import atlas.model.NetworkType
import atlas.model.ReadingBatch
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

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

    /**
     * GETs `/hexes` for the given bounding box, optionally filtered.
     *
     * The bbox params are always sent; [mcc], [mnc] and [networkType] are appended only
     * when non-null (`network` carries `networkType.name`). A non-2xx response yields an
     * empty list rather than an exception, so the map degrades gracefully offline.
     */
    suspend fun getHexes(
        minLng: Double,
        minLat: Double,
        maxLng: Double,
        maxLat: Double,
        mcc: Int? = null,
        mnc: Int? = null,
        networkType: NetworkType? = null,
    ): List<HexAggregate> {
        return try {
            val response = http.get("$baseUrl/hexes") {
                parameter("minLng", minLng)
                parameter("minLat", minLat)
                parameter("maxLng", maxLng)
                parameter("maxLat", maxLat)
                mcc?.let { parameter("mcc", it) }
                mnc?.let { parameter("mnc", it) }
                networkType?.let { parameter("network", it.name) }
            }
            if (response.status.isSuccess()) response.body() else emptyList()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList() // offline / connection refused -> no hexes, never crash
        }
    }

    /**
     * GETs `/hexes.geojson` for the bbox (optionally filtered) and returns the raw GeoJSON
     * body string for direct hand-off to MapLibre.
     *
     * This must never throw: a non-2xx response **or** a transport failure (e.g. the device is
     * offline — exactly the situation this app exists to map) both yield an empty
     * `FeatureCollection`, so the map simply renders nothing rather than crashing.
     */
    suspend fun getHexesGeoJson(
        minLng: Double,
        minLat: Double,
        maxLng: Double,
        maxLat: Double,
        mcc: Int? = null,
        mnc: Int? = null,
        networkType: NetworkType? = null,
    ): String = try {
        val response = http.get("$baseUrl/hexes.geojson") {
            parameter("minLng", minLng)
            parameter("minLat", minLat)
            parameter("maxLng", maxLng)
            parameter("maxLat", maxLat)
            mcc?.let { parameter("mcc", it) }
            mnc?.let { parameter("mnc", it) }
            networkType?.let { parameter("network", it.name) }
        }
        if (response.status.isSuccess()) response.bodyAsText() else EMPTY_FEATURE_COLLECTION
    } catch (e: CancellationException) {
        throw e // don't swallow coroutine cancellation
    } catch (e: Exception) {
        EMPTY_FEATURE_COLLECTION // offline / DNS / connection refused -> render nothing
    }

    /** GETs `/carriers`; an empty list on any non-2xx response or transport failure. */
    suspend fun getCarriers(): List<Carrier> = try {
        val response = http.get("$baseUrl/carriers")
        if (response.status.isSuccess()) response.body() else emptyList()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }

    companion object {
        /** Valid empty GeoJSON returned whenever a hex fetch fails (offline or non-2xx). */
        const val EMPTY_FEATURE_COLLECTION: String = """{"type":"FeatureCollection","features":[]}"""
    }
}
