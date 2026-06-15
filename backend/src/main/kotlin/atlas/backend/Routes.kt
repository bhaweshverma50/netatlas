package atlas.backend

import atlas.backend.repo.ReadingRepository
import atlas.model.NetworkType
import atlas.model.ReadingBatch
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.JsonConvertException
import io.ktor.server.application.Application
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

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
                return@get
            }

            // Optional filters. A garbage value (e.g. mcc=foo) is a 400, not a silent drop.
            val mcc = params["mcc"]
            val mnc = params["mnc"]
            val network = params["network"]
            val mccInt = if (mcc != null) mcc.toIntOrNull() ?: return@get call.respond(
                HttpStatusCode.BadRequest, ErrorResponse("mcc must be an integer"),
            ) else null
            val mncInt = if (mnc != null) mnc.toIntOrNull() ?: return@get call.respond(
                HttpStatusCode.BadRequest, ErrorResponse("mnc must be an integer"),
            ) else null
            val networkType = if (network != null) {
                try {
                    NetworkType.valueOf(network)
                } catch (e: IllegalArgumentException) {
                    return@get call.respond(
                        HttpStatusCode.BadRequest, ErrorResponse("Unknown network type: $network"),
                    )
                }
            } else null

            val hexes = repo.hexes(minLng, minLat, maxLng, maxLat, mccInt, mncInt, networkType)
            call.respond(hexes)
        }

        get("/carriers") {
            val carriers = repo.carriers().map { CarrierResponse(it.mcc, it.mnc, it.carrierName) }
            call.respond(carriers)
        }
    }
}
