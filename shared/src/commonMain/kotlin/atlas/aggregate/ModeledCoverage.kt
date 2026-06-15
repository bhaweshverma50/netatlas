package atlas.aggregate

import atlas.model.CoverageClass

/**
 * A modeled (non-crowd) coverage estimate for a single hex, synthesized purely
 * from its distance to a nearby tower. [confidence] is deliberately low so the
 * map can draw these hexes faintly, distinct from real crowd-sourced data.
 */
data class ModeledEstimate(
    val meanDbm: Double,
    val confidence: Double,
    val coverageClass: CoverageClass,
)

/**
 * Pure, deterministic modeled-coverage math: predicts a hex's signal from its
 * distance to a nearby OpenCelliD tower of a given nominal range. No I/O, no
 * platform deps, no randomness. Reuses [Aggregation.classify] for thresholds.
 *
 * Used when a hex has NO crowd readings, so the map isn't blank — the estimate
 * is always fainter (capped at [MAX_CONFIDENCE]) than solid crowd aggregates.
 */
object ModeledCoverage {

    /** Estimated dBm right next to a tower (distance 0). */
    const val NEAR_DBM = -70.0

    /** Estimated dBm at the tower's nominal range edge (distance == range). */
    const val EDGE_DBM = -105.0

    /** Floor for a tower's nominal range, guarding tiny/zero ranges (no divide-by-zero). */
    const val MIN_RANGE_M = 100.0

    /** Hard cap on modeled confidence; modeled hexes never exceed this. */
    const val MAX_CONFIDENCE = 0.30

    /** Distance is allowed to extend out to this multiple of the (floored) range. */
    private const val MAX_RATIO = 1.5

    /** Half-saturation point for the sample boost: sampleFactor = samples/(samples+K_SAMPLES). */
    private const val K_SAMPLES = 50.0

    /**
     * Estimated signal (dBm) at [distanceM] from a tower with nominal [rangeM].
     *
     * Linear from [NEAR_DBM] at distance 0 to [EDGE_DBM] at the range edge, allowed
     * to continue out to [MAX_RATIO]x range where it is clamped:
     *
     *   r     = max(rangeM, MIN_RANGE_M)              // floor tiny/zero ranges
     *   ratio = (distanceM / r).coerceIn(0.0, 1.5)    // negative distance -> 0 first
     *   dbm   = NEAR_DBM + (EDGE_DBM - NEAR_DBM) * ratio
     *
     * So 0 -> -70, range -> -105, 1.5x (and beyond, clamped) -> -122.5.
     * Result is always finite. Monotonically non-increasing in distance.
     */
    fun estimateDbm(distanceM: Double, rangeM: Double): Double {
        val r = maxOf(rangeM, MIN_RANGE_M)
        val d = distanceM.coerceAtLeast(0.0)
        val ratio = (d / r).coerceIn(0.0, MAX_RATIO)
        return NEAR_DBM + (EDGE_DBM - NEAR_DBM) * ratio
    }

    /**
     * Low confidence in [0, [MAX_CONFIDENCE]]: higher near the tower, decaying to ~0
     * at [MAX_RATIO]x range, with a small saturating boost from the tower's
     * observation [samples].
     *
     *   r            = max(rangeM, MIN_RANGE_M)
     *   ratio        = (distanceM / r).coerceIn(0.0, 1.5)        // negative distance -> 0
     *   distFactor   = (1 - ratio/1.5).coerceIn(0.0, 1.0)        // 1 at tower, 0 at 1.5x
     *   sampleFactor = samples/(samples + 50)                    // 0 when samples <= 0
     *   confidence   = clamp(MAX_CONFIDENCE * distFactor * (0.5 + 0.5*sampleFactor), 0, MAX_CONFIDENCE)
     *
     * With zero samples the sample boost is 0.5 (half strength); it saturates toward 1.0.
     * Monotonically non-increasing in distance, non-decreasing in samples.
     */
    fun confidence(distanceM: Double, rangeM: Double, samples: Int): Double {
        val r = maxOf(rangeM, MIN_RANGE_M)
        val d = distanceM.coerceAtLeast(0.0)
        val ratio = (d / r).coerceIn(0.0, MAX_RATIO)
        val distFactor = (1.0 - ratio / MAX_RATIO).coerceIn(0.0, 1.0)
        val s = samples.coerceAtLeast(0).toDouble()
        val sampleFactor = if (s <= 0.0) 0.0 else s / (s + K_SAMPLES)
        val raw = MAX_CONFIDENCE * distFactor * (0.5 + 0.5 * sampleFactor)
        return raw.coerceIn(0.0, MAX_CONFIDENCE)
    }

    /**
     * Full modeled estimate for a hex at [distanceM] from a tower of [rangeM] with
     * [samples] observations. Combines [estimateDbm], [confidence], and
     * [Aggregation.classify] (the single source of truth for coverage thresholds).
     */
    fun estimate(distanceM: Double, rangeM: Double, samples: Int = 0): ModeledEstimate {
        val meanDbm = estimateDbm(distanceM, rangeM)
        return ModeledEstimate(
            meanDbm = meanDbm,
            confidence = confidence(distanceM, rangeM, samples),
            coverageClass = Aggregation.classify(meanDbm),
        )
    }
}
