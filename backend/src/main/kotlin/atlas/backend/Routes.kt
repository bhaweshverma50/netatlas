package atlas.backend

import atlas.backend.geo.H3
import atlas.backend.repo.ReadingRepository
import atlas.model.HexAggregate
import atlas.model.NetworkType
import atlas.model.ReadingBatch
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Body for `GET /healthz`. */
@Serializable
data class HealthResponse(val status: String)

/** Body for `POST /readings`, mirroring the repository's ingest counts. */
@Serializable
data class IngestResponse(val accepted: Int, val rejected: Int)

/** A carrier present in the aggregates, serialized for `GET /carriers`. */
@Serializable
data class CarrierResponse(val mcc: Int, val mnc: Int, val carrierName: String)

/** Body for error responses. */
@Serializable
data class ErrorResponse(val error: String)

/** Parsed, validated query for the hex endpoints: the bbox plus optional filters. */
private data class HexQuery(
    val minLng: Double,
    val minLat: Double,
    val maxLng: Double,
    val maxLat: Double,
    val mcc: Int?,
    val mnc: Int?,
    val networkType: NetworkType?,
)

/**
 * Parses and validates the shared hex query params off [call].
 *
 * Returns the [HexQuery] on success, or `null` after having already responded with a
 * 400 (missing/garbage required bbox, or non-integer/unknown optional filter). Callers
 * `return@get` immediately when this yields `null`. Shared by `/hexes` and `/hexes.geojson`
 * so both endpoints validate identically.
 */
private suspend fun parseHexQuery(call: ApplicationCall): HexQuery? {
    val params = call.request.queryParameters
    val minLng = params["minLng"]?.toDoubleOrNull()
    val minLat = params["minLat"]?.toDoubleOrNull()
    val maxLng = params["maxLng"]?.toDoubleOrNull()
    val maxLat = params["maxLat"]?.toDoubleOrNull()
    if (minLng == null || minLat == null || maxLng == null || maxLat == null) {
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorResponse("Required query params minLng, minLat, maxLng, maxLat must be valid numbers"),
        )
        return null
    }

    val mcc = params["mcc"]
    val mnc = params["mnc"]
    val network = params["network"]
    val mccInt = if (mcc != null) mcc.toIntOrNull() ?: run {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("mcc must be an integer"))
        return null
    } else null
    val mncInt = if (mnc != null) mnc.toIntOrNull() ?: run {
        call.respond(HttpStatusCode.BadRequest, ErrorResponse("mnc must be an integer"))
        return null
    } else null
    val networkType = if (network != null) {
        try {
            NetworkType.valueOf(network)
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("Unknown network type: $network"))
            return null
        }
    } else null

    return HexQuery(minLng, minLat, maxLng, maxLat, mccInt, mncInt, networkType)
}

/**
 * Serializes [hexes] as a GeoJSON `FeatureCollection`.
 *
 * Each hex becomes a `Polygon` Feature whose single linear ring is the H3 cell boundary,
 * emitted as `[lng, lat]` positions with the first vertex repeated at the end to close the
 * ring (GeoJSON requirement). Properties carry the coverage metrics the map styles on.
 */
private fun hexesToGeoJson(hexes: List<HexAggregate>): String {
    val collection = buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            for (hex in hexes) {
                // boundary() returns (lat, lng) pairs; GeoJSON wants [lng, lat].
                val boundary = H3.boundary(H3.fromAddress(hex.h3))
                add(
                    buildJsonObject {
                        put("type", "Feature")
                        putJsonObject("geometry") {
                            put("type", "Polygon")
                            putJsonArray("coordinates") {
                                addJsonArray {
                                    for ((lat, lng) in boundary) {
                                        addJsonArray {
                                            add(lng)
                                            add(lat)
                                        }
                                    }
                                    // Close the ring by repeating the first vertex.
                                    boundary.firstOrNull()?.let { (lat, lng) ->
                                        addJsonArray {
                                            add(lng)
                                            add(lat)
                                        }
                                    }
                                }
                            }
                        }
                        putJsonObject("properties") {
                            put("h3", hex.h3)
                            put("meanDbm", hex.meanDbm)
                            put("medianDbm", hex.medianDbm)
                            put("confidence", hex.confidence)
                            put("sampleCount", hex.sampleCount)
                            put("coverageClass", hex.coverageClass.name)
                            put("mcc", hex.mcc)
                            put("mnc", hex.mnc)
                            put("networkType", hex.networkType.name)
                            put("source", hex.source.name)
                        }
                    },
                )
            }
        }
    }
    return collection.toString()
}

/**
 * Installs all HTTP routes against [repo].
 *
 * This deliberately takes a ready repository rather than building a database, so
 * tests can inject a repository backed by a throwaway container. Bad input on
 * `/hexes` (missing/garbage required params) and malformed JSON on `/readings`
 * are reported as 400, never 500.
 */
fun Application.configureRouting(repo: ReadingRepository) {
    routing {
        get("/healthz") {
            call.respond(HealthResponse("ok"))
        }

        post("/readings") {
            val batch = try {
                call.receive<ReadingBatch>()
            } catch (e: BadRequestException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed request body"))
                return@post
            } catch (e: JsonConvertException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed JSON body"))
                return@post
            }
            val result = repo.ingest(batch.readings)
            call.respond(
                HttpStatusCode.Accepted,
                IngestResponse(accepted = result.accepted, rejected = result.rejected),
            )
        }

        get("/hexes") {
            // Bad input (missing/garbage required bbox, or invalid filter) already 400'd inside.
            val q = parseHexQuery(call) ?: return@get
            val hexes = repo.hexes(q.minLng, q.minLat, q.maxLng, q.maxLat, q.mcc, q.mnc, q.networkType)
            call.respond(hexes)
        }

        get("/hexes.geojson") {
            // Same params/validation as /hexes; the hex BOUNDARIES (H3) are computed here
            // because com.uber:h3 is JVM-only — the client just renders the GeoJSON.
            val q = parseHexQuery(call) ?: return@get
            val hexes = repo.hexes(q.minLng, q.minLat, q.maxLng, q.maxLat, q.mcc, q.mnc, q.networkType)
            call.respondText(hexesToGeoJson(hexes), ContentType.Application.Json)
        }

        get("/carriers") {
            val carriers = repo.carriers().map { CarrierResponse(it.mcc, it.mnc, it.carrierName) }
            call.respond(carriers)
        }
    }
}
