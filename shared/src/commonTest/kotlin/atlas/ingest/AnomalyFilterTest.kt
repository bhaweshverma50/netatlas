package atlas.ingest

import atlas.model.NetworkType
import atlas.model.SignalReading
import atlas.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AnomalyFilterTest {

    private fun validReading(): SignalReading = SignalReading(
        deviceId = "dev1", tsEpochMs = 1_700_000_000_000,
        lat = 12.97, lng = 77.59, locAccuracyM = 8.0, speedMps = 0.0, isMoving = false,
        mcc = 404, mnc = 45, carrierName = "Airtel", networkType = NetworkType.LTE,
        signalDbm = -95, rsrq = -10, sinr = 12, asu = 16, bars = 3,
        cellId = 12345L, tac = 678, pci = 90, earfcn = 1850,
        phoneMake = "Samsung", phoneModel = "SM-S911B",
        osVersion = "14", appVersion = "0.1.0", source = Source.CROWD,
    )

    @Test
    fun accepts_plausible() {
        val result = AnomalyFilter.isValid(validReading())
        assertTrue(result.accepted)
        assertNull(result.reason)
    }

    @Test
    fun rejects_dbm_too_high() {
        val result = AnomalyFilter.isValid(validReading().copy(signalDbm = 10))
        assertEquals(false, result.accepted)
        assertEquals("dbm_out_of_range", result.reason)
    }

    @Test
    fun rejects_dbm_too_low() {
        val result = AnomalyFilter.isValid(validReading().copy(signalDbm = -200))
        assertEquals(false, result.accepted)
        assertEquals("dbm_out_of_range", result.reason)
    }

    @Test
    fun rejects_poor_gps() {
        val result = AnomalyFilter.isValid(validReading().copy(locAccuracyM = 500.0))
        assertEquals(false, result.accepted)
        assertEquals("poor_gps", result.reason)
    }

    @Test
    fun rejects_impossible_speed() {
        val result = AnomalyFilter.isValid(validReading().copy(speedMps = 200.0))
        assertEquals(false, result.accepted)
        assertEquals("impossible_speed", result.reason)
    }

    @Test
    fun rejects_bad_latlng() {
        val result = AnomalyFilter.isValid(validReading().copy(lat = 99.0))
        assertEquals(false, result.accepted)
        assertEquals("bad_latlng", result.reason)
    }

    @Test
    fun rejects_bad_bars() {
        val result = AnomalyFilter.isValid(validReading().copy(bars = 7))
        assertEquals(false, result.accepted)
        assertEquals("bad_bars", result.reason)
    }

    @Test
    fun accepts_boundary_values() {
        val high = AnomalyFilter.isValid(validReading().copy(signalDbm = -30))
        assertTrue(high.accepted)
        assertNull(high.reason)

        val low = AnomalyFilter.isValid(validReading().copy(signalDbm = -140))
        assertTrue(low.accepted)
        assertNull(low.reason)

        val gps = AnomalyFilter.isValid(validReading().copy(locAccuracyM = 100.0))
        assertTrue(gps.accepted)
        assertNull(gps.reason)
    }
}
