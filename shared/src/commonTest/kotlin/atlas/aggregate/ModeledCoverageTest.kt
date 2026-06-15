package atlas.aggregate

import atlas.model.CoverageClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModeledCoverageTest {

    @Test
    fun near_tower_is_strong() {
        // ratio = 0 -> NEAR_DBM = -70.0
        val dbm = ModeledCoverage.estimateDbm(0.0, 1500.0)
        assertEquals(-70.0, dbm, 1e-9)
        assertEquals(CoverageClass.EXCELLENT, Aggregation.classify(dbm))
    }

    @Test
    fun at_range_edge_is_weaker() {
        // ratio = 1 -> EDGE_DBM = -105.0
        val dbm = ModeledCoverage.estimateDbm(1500.0, 1500.0)
        assertEquals(-105.0, dbm, 1e-9)
        // -105 is below the -100 FAIR floor and at/above the -110 POOR floor -> POOR
        assertEquals(CoverageClass.POOR, Aggregation.classify(-105.0))
        assertEquals(CoverageClass.POOR, Aggregation.classify(dbm))
    }

    @Test
    fun beyond_range_is_clamped() {
        // ratio clamped at 1.5 -> -70 + (-35)*1.5 = -122.5
        val atOnePointFive = ModeledCoverage.estimateDbm(2250.0, 1500.0) // exactly 1.5x
        assertEquals(-122.5, atOnePointFive, 1e-9)

        val veryFar = ModeledCoverage.estimateDbm(100000.0, 1500.0)
        assertEquals(-122.5, veryFar, 1e-9)
        // far never drops below the 1.5x floor
        assertTrue(veryFar >= atOnePointFive - 1e-9, "far=$veryFar should not be below 1.5x=$atOnePointFive")
    }

    @Test
    fun min_range_floor() {
        // A tiny / zero range is floored at MIN_RANGE_M so there is no divide-by-zero.
        val zeroRange = ModeledCoverage.estimateDbm(50.0, 0.0)
        // r floored to 100 -> ratio = 50/100 = 0.5 -> -70 + (-35)*0.5 = -87.5
        assertTrue(zeroRange.isFinite(), "result must be finite, was $zeroRange")
        assertEquals(-87.5, zeroRange, 1e-9)

        // At the floored edge (distance == MIN_RANGE_M) -> EDGE_DBM.
        val atFlooredEdge = ModeledCoverage.estimateDbm(100.0, 0.0)
        assertEquals(-105.0, atFlooredEdge, 1e-9)
    }

    @Test
    fun negative_distance_coerced_to_zero() {
        assertEquals(-70.0, ModeledCoverage.estimateDbm(-500.0, 1500.0), 1e-9)
    }

    @Test
    fun dbm_monotonic_in_distance() {
        var prev = ModeledCoverage.estimateDbm(0.0, 1500.0)
        for (d in listOf(100.0, 500.0, 1000.0, 1500.0, 2000.0, 2250.0, 5000.0)) {
            val cur = ModeledCoverage.estimateDbm(d, 1500.0)
            // closer => higher (less negative); farther => lower-or-equal
            assertTrue(cur <= prev + 1e-12, "dbm should not increase with distance: d=$d cur=$cur prev=$prev")
            prev = cur
        }
    }

    @Test
    fun confidence_bounds_and_monotonic() {
        // Always within [0, MAX_CONFIDENCE].
        val probes = listOf(
            Triple(0.0, 1500.0, 0),
            Triple(750.0, 1500.0, 10),
            Triple(1500.0, 1500.0, 100),
            Triple(3000.0, 1500.0, 5),
            Triple(-100.0, 1500.0, -5),
            Triple(50.0, 0.0, 1000),
        )
        for ((d, r, s) in probes) {
            val c = ModeledCoverage.confidence(d, r, s)
            assertTrue(c in 0.0..ModeledCoverage.MAX_CONFIDENCE, "confidence out of range for ($d,$r,$s): $c")
        }

        // Decreases (non-increasing) with distance, samples fixed.
        var prev = ModeledCoverage.confidence(0.0, 1500.0, 20)
        for (d in listOf(100.0, 500.0, 1000.0, 1500.0, 2000.0, 2250.0)) {
            val cur = ModeledCoverage.confidence(d, 1500.0, 20)
            assertTrue(cur <= prev + 1e-12, "confidence should not increase with distance: d=$d cur=$cur prev=$prev")
            prev = cur
        }

        // ~0 at or beyond 1.5x range.
        assertEquals(0.0, ModeledCoverage.confidence(2250.0, 1500.0, 20), 1e-9)
        assertEquals(0.0, ModeledCoverage.confidence(10000.0, 1500.0, 20), 1e-9)

        // Higher samples => >= confidence (other inputs fixed).
        val few = ModeledCoverage.confidence(500.0, 1500.0, 1)
        val many = ModeledCoverage.confidence(500.0, 1500.0, 500)
        assertTrue(many >= few, "more samples should not lower confidence: few=$few many=$many")

        // Negative / zero samples handled (no NaN/negative), and zero-sample <= some-sample.
        val zeroSamples = ModeledCoverage.confidence(500.0, 1500.0, 0)
        val negSamples = ModeledCoverage.confidence(500.0, 1500.0, -10)
        assertEquals(zeroSamples, negSamples, 1e-12)
        assertTrue(zeroSamples in 0.0..ModeledCoverage.MAX_CONFIDENCE)
        assertTrue(zeroSamples <= few + 1e-12, "zero samples should not exceed positive-sample confidence")
    }

    @Test
    fun confidence_matches_formula() {
        // d=200, r=2000, s=30:
        // ratio = 200/2000 = 0.1
        // distFactor = 1 - 0.1/1.5 = 0.9333333333333333
        // sampleFactor = 30/(30+50) = 0.375
        // conf = 0.30 * 0.9333333333333333 * (0.5 + 0.5*0.375)
        //      = 0.30 * 0.9333333333333333 * 0.6875 = 0.1925
        val c = ModeledCoverage.confidence(200.0, 2000.0, 30)
        assertEquals(0.1925, c, 1e-9)
    }

    @Test
    fun estimate_combines() {
        // d=200, r=2000: ratio=0.1 -> -70 + (-35)*0.1 = -73.5
        val est = ModeledCoverage.estimate(200.0, 2000.0, 30)
        assertEquals(-73.5, est.meanDbm, 1e-9)
        assertEquals(ModeledCoverage.estimateDbm(200.0, 2000.0), est.meanDbm, 1e-12)
        assertEquals(ModeledCoverage.confidence(200.0, 2000.0, 30), est.confidence, 1e-12)
        assertTrue(est.confidence <= ModeledCoverage.MAX_CONFIDENCE, "confidence ${est.confidence} exceeds cap")
        assertTrue(est.confidence in 0.0..0.30)
        assertEquals(Aggregation.classify(est.meanDbm), est.coverageClass)
        assertEquals(CoverageClass.EXCELLENT, est.coverageClass) // -73.5 >= -80
    }

    @Test
    fun estimate_default_samples_is_zero() {
        val est = ModeledCoverage.estimate(200.0, 2000.0)
        assertEquals(ModeledCoverage.confidence(200.0, 2000.0, 0), est.confidence, 1e-12)
    }
}
