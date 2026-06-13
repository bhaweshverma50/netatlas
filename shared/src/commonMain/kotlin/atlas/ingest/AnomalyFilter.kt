package atlas.ingest

import atlas.model.SignalReading

/**
 * Pure, dependency-free filter that rejects implausible/garbage crowd readings
 * before they pollute aggregates. Returns the FIRST failing rule's reason so the
 * outcome is deterministic.
 */
object AnomalyFilter {
    data class Result(val accepted: Boolean, val reason: String? = null)

    fun isValid(r: SignalReading): Result {
        if (r.lat !in -90.0..90.0 || r.lng !in -180.0..180.0) return Result(false, "bad_latlng")
        if (r.signalDbm !in -140..-30) return Result(false, "dbm_out_of_range")
        if (r.locAccuracyM > 100.0) return Result(false, "poor_gps")
        if (r.speedMps > 140.0) return Result(false, "impossible_speed")
        if (r.bars !in 0..4) return Result(false, "bad_bars")
        return Result(true)
    }
}
