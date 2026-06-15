package atlas.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelsTest {

    private fun sampleReading(): SignalReading = SignalReading(
        deviceId = "dev1", tsEpochMs = 1_700_000_000_000,
        lat = 12.97, lng = 77.59, locAccuracyM = 8.0, speedMps = 0.0, isMoving = false,
        mcc = 404, mnc = 45, carrierName = "Airtel", networkType = NetworkType.LTE,
        signalDbm = -95, rsrq = -10, sinr = 12, asu = 16, bars = 3,
        cellId = 12345L, tac = 678, pci = 90, earfcn = 1850,
        phoneMake = "Samsung", phoneModel = "SM-S911B",
        osVersion = "14", appVersion = "0.1.0", source = Source.CROWD,
    )

    @Test
    fun reading_roundtrips_json() {
        val r = sampleReading()
        val json = Json.encodeToString(r)
        assertEquals(r, Json.decodeFromString<SignalReading>(json))
    }

    @Test
    fun reading_with_null_optionals_roundtrips_json() {
        val r = sampleReading().copy(rsrq = null, sinr = null, asu = null, cellId = null, tac = null, pci = null, earfcn = null)
        val json = Json.encodeToString(r)
        val decoded = Json.decodeFromString<SignalReading>(json)
        assertEquals(r, decoded)
        assertEquals(null, decoded.rsrq)
        assertEquals(null, decoded.cellId)
    }

    @Test
    fun batch_roundtrips_json() {
        val batch = ReadingBatch(
            readings = listOf(
                sampleReading(),
                sampleReading().copy(deviceId = "dev2", signalDbm = -100, networkType = NetworkType.NR_SA),
            ),
        )
        val json = Json.encodeToString(batch)
        val decoded = Json.decodeFromString<ReadingBatch>(json)
        assertEquals(batch, decoded)
        assertEquals(2, decoded.readings.size)
    }

    @Test
    fun hex_aggregate_roundtrips_json() {
        val hex = HexAggregate(
            h3 = "8a2a1072b59ffff", resolution = 10,
            mcc = 404, mnc = 45, networkType = NetworkType.LTE,
            sampleCount = 12, meanDbm = -95.0, medianDbm = -94.0,
            stddev = 5.5, confidence = 0.82,
            coverageClass = CoverageClass.GOOD, centerLat = 12.9716, centerLng = 77.5946,
        )
        val json = Json.encodeToString(hex)
        assertEquals(hex, Json.decodeFromString<HexAggregate>(json))
    }
}
