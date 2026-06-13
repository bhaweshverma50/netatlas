package atlas.aggregate

import atlas.model.CoverageClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AggregationTest {

    @Test
    fun mean_and_stddev_match_known_values() {
        // Feed [-90, -100, -95, -85].
        // mean = -92.5
        // sample variance = ((2.5^2)+(7.5^2)+(2.5^2)+(7.5^2))/3
        //                  = (6.25+56.25+6.25+56.25)/3 = 125/3 = 41.6666...
        // sample stddev = sqrt(41.6666...) = 6.454972243679028
        val stats = listOf(-90.0, -100.0, -95.0, -85.0)
            .fold(RunningStats()) { acc, x -> acc.add(x) }

        assertEquals(4, stats.count)
        assertEquals(-92.5, stats.mean, 1e-6)
        assertEquals(6.454972243679028, stats.stddev, 0.01)
    }

    @Test
    fun running_stats_empty_and_single() {
        val empty = RunningStats()
        assertEquals(0, empty.count)
        assertEquals(0.0, empty.stddev, 1e-12)
        assertEquals(0.0, empty.variance, 1e-12)

        val single = RunningStats().add(-77.0)
        assertEquals(1, single.count)
        assertEquals(-77.0, single.mean, 1e-12)
        assertEquals(0.0, single.stddev, 1e-12)
        assertEquals(0.0, single.variance, 1e-12)
    }

    @Test
    fun confidence_rises_with_samples() {
        val low = Aggregation.confidence(count = 1, deviceCount = 1, stddev = 8.0)
        val high = Aggregation.confidence(count = 50, deviceCount = 10, stddev = 2.0)
        assertTrue(low < high, "expected $low < $high")
        assertTrue(low in 0.0..1.0)
        assertTrue(high in 0.0..1.0)
    }

    @Test
    fun confidence_monotonic_in_count() {
        var prev = Aggregation.confidence(count = 1, deviceCount = 3, stddev = 4.0)
        for (c in listOf(2, 5, 10, 25, 100, 1000)) {
            val cur = Aggregation.confidence(count = c, deviceCount = 3, stddev = 4.0)
            assertTrue(cur >= prev, "confidence should not decrease with count: count=$c cur=$cur prev=$prev")
            prev = cur
        }
    }

    @Test
    fun confidence_drops_with_variability() {
        var prev = Aggregation.confidence(count = 20, deviceCount = 5, stddev = 0.0)
        for (s in listOf(1.0, 3.0, 6.0, 12.0, 30.0, 100.0)) {
            val cur = Aggregation.confidence(count = 20, deviceCount = 5, stddev = s)
            assertTrue(cur <= prev, "confidence should not increase with stddev: stddev=$s cur=$cur prev=$prev")
            prev = cur
        }
    }

    @Test
    fun confidence_clamped() {
        val inputs = listOf(
            Triple(0, 0, 0.0),
            Triple(-5, -5, -5.0),
            Triple(1, 1, 8.0),
            Triple(100000, 100000, 0.0),
            Triple(100000, 100000, 100000.0),
            Triple(Int.MAX_VALUE, Int.MAX_VALUE, Double.MAX_VALUE),
        )
        for ((c, d, s) in inputs) {
            val v = Aggregation.confidence(c, d, s)
            assertTrue(v in 0.0..1.0, "confidence out of range for ($c,$d,$s): $v")
        }
    }

    @Test
    fun coverage_class_thresholds() {
        assertEquals(CoverageClass.EXCELLENT, Aggregation.classify(-75.0))
        assertEquals(CoverageClass.EXCELLENT, Aggregation.classify(-80.0)) // boundary, inclusive upper
        assertEquals(CoverageClass.GOOD, Aggregation.classify(-85.0))
        assertEquals(CoverageClass.GOOD, Aggregation.classify(-90.0)) // boundary
        assertEquals(CoverageClass.FAIR, Aggregation.classify(-95.0))
        assertEquals(CoverageClass.FAIR, Aggregation.classify(-100.0)) // boundary
        assertEquals(CoverageClass.POOR, Aggregation.classify(-105.0))
        assertEquals(CoverageClass.POOR, Aggregation.classify(-110.0)) // boundary
        assertEquals(CoverageClass.NO_SIGNAL, Aggregation.classify(-120.0))
        assertEquals(CoverageClass.NO_SIGNAL, Aggregation.classify(-115.0))
    }
}
