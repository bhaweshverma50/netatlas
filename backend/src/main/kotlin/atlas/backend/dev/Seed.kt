package atlas.backend.dev

import atlas.model.NetworkType
import atlas.model.ReadingBatch
import atlas.model.SignalReading
import atlas.model.Source
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.math.PI
import kotlin.math.sin

/**
 * Dev-only seeder. Generates ~500 synthetic [SignalReading]s along a travel route
 * across Bengaluru and POSTs them, in batches, to a running netatlas backend.
 *
 * Everything here is DETERMINISTIC: a fixed base timestamp and a closed-form,
 * index-driven jitter (no `Random`, no wall-clock) so repeated runs produce the
 * exact same data. All values are kept inside the [atlas.ingest.AnomalyFilter]'s
 * accepted ranges so nothing is rejected.
 *
 * This is a separate utility — it does NOT touch any production code path.
 * Run with: `./gradlew :backend:seed` (server must be up on :8080).
 */

/** Total synthetic readings to generate. */
private const val TOTAL = 500

/** Readings per POSTed [ReadingBatch]. */
private const val BATCH_SIZE = 100

/** Fixed base time so timestamps are reproducible (NOT System.currentTimeMillis). */
private const val BASE_TS_MS = 1_700_000_000_000L

/** A waypoint on the synthetic route. */
private data class Waypoint(val lat: Double, val lng: Double)

/** Bengaluru route: Koramangala -> MG Road -> Yeshwanthpur, roughly NW across the city. */
private val ROUTE = listOf(
    Waypoint(12.9352, 77.6245),
    Waypoint(12.9716, 77.5946),
    Waypoint(13.0099, 77.5550),
)

/** A carrier on the network (mcc/mnc + display name). */
private data class Carrier(val mcc: Int, val mnc: Int, val name: String, val network: NetworkType)

/** Two carriers so `/carriers` returns 2 and the mnc filter is demonstrable. */
private val CARRIERS = listOf(
    Carrier(mcc = 404, mnc = 45, name = "Airtel", network = NetworkType.LTE),
    Carrier(mcc = 404, mnc = 10, name = "Jio", network = NetworkType.NR_SA),
)

/** A handful of devices so contributing_devices / confidence varies per hex. */
private val DEVICES = listOf("dev-alpha", "dev-bravo", "dev-charlie", "dev-delta")

/** Phone models paired with their make/os, indexed alongside [CARRIERS]. */
private data class Phone(val make: String, val model: String, val os: String)

private val PHONES = listOf(
    Phone(make = "Samsung", model = "SM-S911B", os = "Android 14"),
    Phone(make = "Google", model = "Pixel 8", os = "Android 14"),
)

/**
 * Interpolates [count] evenly spaced points along the polyline through [ROUTE].
 * The first point is the first waypoint, the last is the final waypoint.
 */
private fun interpolateRoute(count: Int): List<Waypoint> {
    val segments = ROUTE.size - 1
    val out = ArrayList<Waypoint>(count)
    for (i in 0 until count) {
        // Position along the whole route in [0, segments].
        val t = if (count == 1) 0.0 else i.toDouble() / (count - 1) * segments
        val seg = t.toInt().coerceIn(0, segments - 1)
        val local = t - seg
        val a = ROUTE[seg]
        val b = ROUTE[seg + 1]
        out += Waypoint(
            lat = a.lat + (b.lat - a.lat) * local,
            lng = a.lng + (b.lng - a.lng) * local,
        )
    }
    return out
}

/**
 * Deterministic signal level (dBm) for the [i]-th point of [total].
 *
 * Base around -85 dBm (GOOD) with a smooth Gaussian-ish dip toward ~-110 dBm
 * (POOR) in a "dead zone" in the middle third of the route, plus a small
 * index-driven sinusoidal jitter. Always clamped to the valid -140..-30 range.
 */
private fun signalDbmFor(i: Int, total: Int): Int {
    val p = i.toDouble() / (total - 1) // [0, 1] along the route
    // Smooth dip centered at p=0.5; sin^2 over the [0.25, 0.75] window, 0 elsewhere.
    val dip = if (p in 0.25..0.75) {
        val u = (p - 0.25) / 0.5 // [0,1] inside the window
        val s = sin(u * PI)
        s * s * 25.0 // up to -25 dB at the center
    } else {
        0.0
    }
    // Deterministic small jitter in roughly [-2, +2] dB.
    val jitter = sin(i * 0.7) * 2.0
    val dbm = -85.0 - dip + jitter
    return dbm.toInt().coerceIn(-140, -30)
}

/** Maps a dBm level onto 0..4 bars (matches the coverage-class thresholds). */
private fun barsFor(dbm: Int): Int = when {
    dbm >= -80 -> 4
    dbm >= -90 -> 3
    dbm >= -100 -> 2
    dbm >= -110 -> 1
    else -> 0
}

/** Builds the full, deterministic list of synthetic readings. */
private fun buildReadings(): List<SignalReading> {
    val points = interpolateRoute(TOTAL)
    return points.mapIndexed { i, pt ->
        val carrier = CARRIERS[i % CARRIERS.size]
        val phone = PHONES[i % PHONES.size]
        val device = DEVICES[i % DEVICES.size]
        val dbm = signalDbmFor(i, TOTAL)
        val bars = barsFor(dbm)
        SignalReading(
            deviceId = device,
            tsEpochMs = BASE_TS_MS + i.toLong() * 1000L,
            lat = pt.lat,
            lng = pt.lng,
            locAccuracyM = 8.0,
            speedMps = 8.0, // ~29 km/h, plausibly moving through traffic
            isMoving = true,
            mcc = carrier.mcc,
            mnc = carrier.mnc,
            carrierName = carrier.name,
            networkType = carrier.network,
            signalDbm = dbm,
            rsrq = -10 - (i % 5), // -10..-14, in a sane LTE range
            sinr = 10 - (i % 8),  // -... small spread, plausible
            asu = (dbm + 113) / 2, // rough asu mapping, kept small/positive-ish
            bars = bars,
            cellId = 100_000L + (i % 50),
            tac = 4000 + (i % 10),
            pci = i % 504,
            earfcn = if (carrier.network == NetworkType.NR_SA) 632448 else 1850,
            phoneMake = phone.make,
            phoneModel = phone.model,
            osVersion = phone.os,
            appVersion = "0.1.0-seed",
            source = Source.CROWD,
        )
    }
}

fun main() {
    val baseUrl = (System.getenv("NETATLAS_URL") ?: "http://localhost:8080").trimEnd('/')
    val endpoint = "$baseUrl/readings"
    val json = Json { encodeDefaults = true }
    val client = HttpClient.newHttpClient()

    val readings = buildReadings()
    println("netatlas seed: generated ${readings.size} synthetic readings along a Bengaluru route")
    println("netatlas seed: POSTing to $endpoint in batches of $BATCH_SIZE")

    var totalAccepted = 0
    var totalRejected = 0

    readings.chunked(BATCH_SIZE).forEachIndexed { batchIdx, chunk ->
        val body = json.encodeToString(ReadingBatch(chunk))
        val request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 202) {
            System.err.println(
                "netatlas seed: batch $batchIdx FAILED with HTTP ${response.statusCode()}: ${response.body()}",
            )
            error("Seed aborted: server did not return 202 Accepted (got ${response.statusCode()})")
        }
        // Response shape: {"accepted":N,"rejected":M}
        val accepted = extractInt(response.body(), "accepted")
        val rejected = extractInt(response.body(), "rejected")
        totalAccepted += accepted
        totalRejected += rejected
        println("netatlas seed: batch $batchIdx -> accepted=$accepted rejected=$rejected")
    }

    println("netatlas seed: DONE. total accepted=$totalAccepted rejected=$totalRejected")
}

/**
 * Minimal field extractor for the flat `{"accepted":N,"rejected":M}` response,
 * avoiding a dependency on the server's response DTO. Returns 0 if absent.
 */
private fun extractInt(jsonBody: String, field: String): Int {
    val regex = Regex("\"$field\"\\s*:\\s*(-?\\d+)")
    return regex.find(jsonBody)?.groupValues?.get(1)?.toIntOrNull() ?: 0
}
