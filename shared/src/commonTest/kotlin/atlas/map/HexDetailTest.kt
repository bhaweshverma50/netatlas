package atlas.map

import atlas.model.CoverageClass
import atlas.model.NetworkType
import atlas.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure parsing logic for the hex-detail sheet. The map layer hands us a property map
 * (string values, as MapLibre stringifies feature props), and we turn it into a typed
 * [HexDetail]. Kept in `:shared` so it is unit-testable without an Android device.
 */
class HexDetailTest {

    private val full = mapOf(
        "h3" to "8a2a1072b59ffff",
        "meanDbm" to "-95.5",
        "medianDbm" to "-94.0",
        "confidence" to "0.82",
        "sampleCount" to "42",
        "coverageClass" to "FAIR",
        "mcc" to "404",
        "mnc" to "45",
        "networkType" to "LTE",
    )

    @Test
    fun parses_a_full_property_map() {
        val detail = HexDetail.fromProperties(full)!!
        assertEquals("8a2a1072b59ffff", detail.h3)
        assertEquals(-95.5, detail.meanDbm)
        assertEquals(-94.0, detail.medianDbm)
        assertEquals(0.82, detail.confidence)
        assertEquals(42, detail.sampleCount)
        assertEquals(CoverageClass.FAIR, detail.coverageClass)
        assertEquals(404, detail.mcc)
        assertEquals(45, detail.mnc)
        assertEquals(NetworkType.LTE, detail.networkType)
        // No `source` key -> defaults to crowd-sourced for back-compat.
        assertEquals(Source.CROWD, detail.source)
    }

    @Test
    fun parses_modeled_source() {
        val detail = HexDetail.fromProperties(full + ("source" to "OPENCELLID"))!!
        assertEquals(Source.OPENCELLID, detail.source)
    }

    @Test
    fun missing_source_defaults_to_crowd() {
        val detail = HexDetail.fromProperties(full - "source")!!
        assertEquals(Source.CROWD, detail.source)
    }

    @Test
    fun unknown_source_falls_back_to_crowd() {
        val detail = HexDetail.fromProperties(full + ("source" to "WAT"))!!
        assertEquals(Source.CROWD, detail.source)
    }

    @Test
    fun parses_5g_network_type() {
        val detail = HexDetail.fromProperties(full + ("networkType" to "NR_SA"))!!
        assertEquals(NetworkType.NR_SA, detail.networkType)
    }

    @Test
    fun returns_null_when_required_key_missing() {
        assertNull(HexDetail.fromProperties(full - "h3"), "missing h3 must fail")
        assertNull(HexDetail.fromProperties(full - "meanDbm"), "missing meanDbm must fail")
        assertNull(HexDetail.fromProperties(emptyMap()), "empty map must fail")
    }

    @Test
    fun returns_null_on_garbage_numbers() {
        assertNull(HexDetail.fromProperties(full + ("meanDbm" to "not-a-number")))
        assertNull(HexDetail.fromProperties(full + ("sampleCount" to "x")))
    }

    @Test
    fun unknown_enums_fall_back_safely() {
        // An unrecognized coverage class degrades to NO_SIGNAL rather than throwing.
        val cov = HexDetail.fromProperties(full + ("coverageClass" to "WAT"))!!
        assertEquals(CoverageClass.NO_SIGNAL, cov.coverageClass)
        // An unrecognized network type degrades to UNKNOWN.
        val net = HexDetail.fromProperties(full + ("networkType" to "WAT"))!!
        assertEquals(NetworkType.UNKNOWN, net.networkType)
    }
}
