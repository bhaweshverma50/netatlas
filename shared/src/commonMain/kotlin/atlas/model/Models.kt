package atlas.model

import kotlinx.serialization.Serializable

@Serializable
enum class NetworkType { GSM, UMTS, LTE, NR_NSA, NR_SA, UNKNOWN }

@Serializable
enum class Source { CROWD, OPENCELLID }

@Serializable
enum class CoverageClass { NO_SIGNAL, POOR, FAIR, GOOD, EXCELLENT }

@Serializable
data class SignalReading(
    val deviceId: String, val tsEpochMs: Long,
    val lat: Double, val lng: Double, val locAccuracyM: Double,
    val speedMps: Double, val isMoving: Boolean,
    val mcc: Int, val mnc: Int, val carrierName: String, val networkType: NetworkType,
    val signalDbm: Int, val rsrq: Int?, val sinr: Int?, val asu: Int?, val bars: Int,
    val cellId: Long?, val tac: Int?, val pci: Int?, val earfcn: Int?,
    val phoneMake: String, val phoneModel: String,
    val osVersion: String, val appVersion: String, val source: Source = Source.CROWD,
)

@Serializable
data class ReadingBatch(val readings: List<SignalReading>)

@Serializable
data class HexAggregate(
    val h3: String, val resolution: Int,
    val mcc: Int, val mnc: Int, val networkType: NetworkType,
    val sampleCount: Int, val meanDbm: Double, val medianDbm: Double,
    val stddev: Double, val confidence: Double,
    val coverageClass: CoverageClass, val centerLat: Double, val centerLng: Double,
    val source: Source = Source.CROWD,
)

@Serializable
data class Carrier(val mcc: Int, val mnc: Int, val carrierName: String)
