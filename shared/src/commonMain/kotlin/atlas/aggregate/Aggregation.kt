package atlas.aggregate

import atlas.model.CoverageClass

/**
 * Immutable running statistics using Welford's online algorithm.
 *
 * Each [add] returns a NEW instance, so the type is safe to share and fold over.
 * The [m2] field (sum of squares of differences from the running mean) is carried
 * by the data class purely so the recurrence can continue; it is a private
 * implementation detail and should not be relied on externally.
 */
data class RunningStats(
    val count: Int = 0,
    val mean: Double = 0.0,
    private val m2: Double = 0.0,
) {
    /**
     * Welford update:
     *   count++
     *   delta  = x - mean
     *   mean  += delta / count
     *   delta2 = x - mean      // mean already updated
     *   m2    += delta * delta2
     */
    fun add(x: Double): RunningStats {
        val newCount = count + 1
        val delta = x - mean
        val newMean = mean + delta / newCount
        val delta2 = x - newMean
        val newM2 = m2 + delta * delta2
        return RunningStats(newCount, newMean, newM2)
    }

    /** Sample variance (Bessel-corrected, n-1). 0 when fewer than 2 samples. */
    val variance: Double get() = if (count < 2) 0.0 else m2 / (count - 1)

    /** Sample standard deviation. 0 when fewer than 2 samples. */
    val stddev: Double get() = kotlin.math.sqrt(variance)
}

/**
 * Pure, deterministic aggregation math for turning many readings in an H3 hex
 * into a single confidence score and a coverage class. No dependencies, no I/O.
 */
object Aggregation {

    // --- confidence tuning constants ---------------------------------------
    // confidence = countFactor * deviceFactor * varFactor, each in (0,1],
    // then clamped to [0,1]. All factors saturate, so the product is bounded.

    /** Half-saturation point for sample count. countFactor = count/(count+K_COUNT). */
    private const val K_COUNT = 10.0

    /** Half-saturation point for device diversity. deviceFactor = devices/(devices+K_DEVICE). */
    private const val K_DEVICE = 3.0

    /** dB scale for the variability penalty. varFactor = 1/(1 + stddev/STDDEV_SCALE). */
    private const val STDDEV_SCALE = 6.0

    /**
     * A confidence in [0, 1] that:
     *  - rises (monotonically, non-decreasing) with [count] and saturates toward 1,
     *  - rises (monotonically, non-decreasing) with [deviceCount] (more independent
     *    devices => less single-device bias) and saturates toward 1,
     *  - falls (monotonically, non-increasing) as [stddev] (signal variability) grows.
     *
     * Design (deterministic, no randomness):
     *   countFactor  = count       / (count + K_COUNT)        // saturating, K_COUNT=10
     *   deviceFactor = deviceCount / (deviceCount + K_DEVICE) // saturating, K_DEVICE=3
     *   varFactor    = 1 / (1 + stddev / STDDEV_SCALE)        // penalty, STDDEV_SCALE=6 dB
     *   confidence   = clamp(countFactor * deviceFactor * varFactor, 0.0, 1.0)
     *
     * Negative/garbage inputs are coerced to non-negative before use so the result
     * is always well-defined and stays within [0, 1].
     */
    fun confidence(count: Int, deviceCount: Int, stddev: Double): Double {
        val n = count.coerceAtLeast(0).toDouble()
        val d = deviceCount.coerceAtLeast(0).toDouble()
        val s = if (stddev.isNaN()) Double.POSITIVE_INFINITY else stddev.coerceAtLeast(0.0)

        val countFactor = if (n <= 0.0) 0.0 else n / (n + K_COUNT)
        val deviceFactor = if (d <= 0.0) 0.0 else d / (d + K_DEVICE)
        val varFactor = 1.0 / (1.0 + s / STDDEV_SCALE) // s=+inf => 0.0

        val raw = countFactor * deviceFactor * varFactor
        return raw.coerceIn(0.0, 1.0)
    }

    /**
     * Classifies a mean signal level (RSRP/dBm; higher is better) into a [CoverageClass].
     * Boundaries are inclusive at the upper (better) class:
     *   >= -80  -> EXCELLENT
     *   >= -90  -> GOOD
     *   >= -100 -> FAIR
     *   >= -110 -> POOR
     *   else    -> NO_SIGNAL
     */
    fun classify(meanDbm: Double): CoverageClass = when {
        meanDbm >= -80.0 -> CoverageClass.EXCELLENT
        meanDbm >= -90.0 -> CoverageClass.GOOD
        meanDbm >= -100.0 -> CoverageClass.FAIR
        meanDbm >= -110.0 -> CoverageClass.POOR
        else -> CoverageClass.NO_SIGNAL
    }
}
