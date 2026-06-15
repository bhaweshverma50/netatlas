package atlas.collect

import atlas.model.NetworkType
import atlas.model.Source
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalMapperTest {

    private fun lteSample(): RawCellSample = RawCellSample(
        networkType = NetworkType.LTE,
        signalDbm = -95,
        rsrq = -10,
        sinr = 12,
        asu = 16,
        bars = 3,
        cellId = 12345L,
        tac = 678,
        pci = 90,
        earfcn = 1850,
    )

    private fun fix(speedMps: Double = 0.0): LocationFix = LocationFix(
        lat = 12.97,
        lng = 77.59,
        accuracyM = 8.0,
        speedMps = speedMps,
    )

    private val carrier = CarrierId(mcc = 404, mnc = 45, carrierName = "Airtel")
    private val device = DeviceInfo(
        phoneMake = "Samsung",
        phoneModel = "SM-S911B",
        osVersion = "14",
        appVersion = "0.1.0",
    )

    @Test
    fun maps_all_fields_across() {
        val reading = SignalMapper.toReading(
            deviceId = "dev1",
            tsEpochMs = 1_700_000_000_000,
            sample = lteSample(),
            location = fix(speedMps = 0.0),
            carrier = carrier,
            device = device,
        )

        assertEquals("dev1", reading.deviceId)
        assertEquals(1_700_000_000_000, reading.tsEpochMs)
        assertEquals(12.97, reading.lat)
        assertEquals(77.59, reading.lng)
        assertEquals(8.0, reading.locAccuracyM)
        assertEquals(0.0, reading.speedMps)
        assertEquals(404, reading.mcc)
        assertEquals(45, reading.mnc)
        assertEquals("Airtel", reading.carrierName)
        assertEquals(NetworkType.LTE, reading.networkType)
        assertEquals(-95, reading.signalDbm)
        assertEquals(-10, reading.rsrq)
        assertEquals(12, reading.sinr)
        assertEquals(16, reading.asu)
        assertEquals(3, reading.bars)
        assertEquals(12345L, reading.cellId)
        assertEquals(678, reading.tac)
        assertEquals(90, reading.pci)
        assertEquals(1850, reading.earfcn)
        assertEquals("Samsung", reading.phoneMake)
        assertEquals("SM-S911B", reading.phoneModel)
        assertEquals("14", reading.osVersion)
        assertEquals("0.1.0", reading.appVersion)
        assertEquals(Source.CROWD, reading.source)
    }

    @Test
    fun null_optionals_preserved() {
        val sample = lteSample().copy(
            rsrq = null,
            sinr = null,
            asu = null,
            cellId = null,
            tac = null,
            pci = null,
            earfcn = null,
        )

        val reading = SignalMapper.toReading(
            deviceId = "dev1",
            tsEpochMs = 1_700_000_000_000,
            sample = sample,
            location = fix(),
            carrier = carrier,
            device = device,
        )

        assertNull(reading.rsrq)
        assertNull(reading.sinr)
        assertNull(reading.asu)
        assertNull(reading.cellId)
        assertNull(reading.tac)
        assertNull(reading.pci)
        assertNull(reading.earfcn)
    }

    @Test
    fun nr_sample_maps_network_type() {
        val sample = lteSample().copy(networkType = NetworkType.NR_SA, signalDbm = -88)

        val reading = SignalMapper.toReading(
            deviceId = "dev1",
            tsEpochMs = 1_700_000_000_000,
            sample = sample,
            location = fix(),
            carrier = carrier,
            device = device,
        )

        assertEquals(NetworkType.NR_SA, reading.networkType)
        assertEquals(-88, reading.signalDbm)
    }

    @Test
    fun stationary_is_not_moving() {
        val reading = SignalMapper.toReading(
            deviceId = "dev1",
            tsEpochMs = 1_700_000_000_000,
            sample = lteSample(),
            location = fix(speedMps = 0.0),
            carrier = carrier,
            device = device,
        )
        assertFalse(reading.isMoving)
    }

    @Test
    fun walking_is_moving() {
        val reading = SignalMapper.toReading(
            deviceId = "dev1",
            tsEpochMs = 1_700_000_000_000,
            sample = lteSample(),
            location = fix(speedMps = 1.5),
            carrier = carrier,
            device = device,
        )
        assertTrue(reading.isMoving)
    }

    @Test
    fun threshold_is_inclusive() {
        val reading = SignalMapper.toReading(
            deviceId = "dev1",
            tsEpochMs = 1_700_000_000_000,
            sample = lteSample(),
            location = fix(speedMps = SignalMapper.MOVING_SPEED_THRESHOLD_MPS),
            carrier = carrier,
            device = device,
        )
        assertTrue(reading.isMoving)
    }

    @Test
    fun source_defaults_to_crowd_and_can_override() {
        val defaulted = SignalMapper.toReading(
            deviceId = "dev1",
            tsEpochMs = 1_700_000_000_000,
            sample = lteSample(),
            location = fix(),
            carrier = carrier,
            device = device,
        )
        assertEquals(Source.CROWD, defaulted.source)

        val overridden = SignalMapper.toReading(
            deviceId = "dev1",
            tsEpochMs = 1_700_000_000_000,
            sample = lteSample(),
            location = fix(),
            carrier = carrier,
            device = device,
            source = Source.OPENCELLID,
        )
        assertEquals(Source.OPENCELLID, overridden.source)
    }
}
