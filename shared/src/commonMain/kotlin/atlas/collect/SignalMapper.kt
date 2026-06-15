package atlas.collect

import atlas.model.NetworkType
import atlas.model.SignalReading
import atlas.model.Source

/** Raw telephony sample already resolved to a single serving cell's values.
 *  The Android-framework extraction (CellInfoLte/CellSignalStrengthNr -> this) is M2.4. */
data class RawCellSample(
    val networkType: NetworkType,
    val signalDbm: Int,
    val rsrq: Int?,
    val sinr: Int?,
    val asu: Int?,
    val bars: Int,          // platform getLevel() 0..4
    val cellId: Long?,
    val tac: Int?,
    val pci: Int?,
    val earfcn: Int?,
)

/** A GPS fix paired with the sample. */
data class LocationFix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Double,
    val speedMps: Double,
)

/** Static device identity. */
data class DeviceInfo(
    val phoneMake: String,
    val phoneModel: String,
    val osVersion: String,
    val appVersion: String,
)

/** Carrier identity from the SIM/network. */
data class CarrierId(
    val mcc: Int,
    val mnc: Int,
    val carrierName: String,
)

object SignalMapper {
    /** Speed (m/s) at or above which a reading is tagged is_moving. ~ brisk walk. */
    const val MOVING_SPEED_THRESHOLD_MPS = 1.0

    /**
     * Combines a captured cell sample, a GPS fix, and device/carrier context into a
     * [SignalReading]. Fields map straight across; [SignalReading.isMoving] is derived
     * from [LocationFix.speedMps] against [MOVING_SPEED_THRESHOLD_MPS] (inclusive).
     *
     * No tech-specific dbm selection happens here — [RawCellSample.signalDbm] is already
     * resolved by the M2.4 extractor.
     */
    fun toReading(
        deviceId: String,
        tsEpochMs: Long,
        sample: RawCellSample,
        location: LocationFix,
        carrier: CarrierId,
        device: DeviceInfo,
        source: Source = Source.CROWD,
    ): SignalReading = SignalReading(
        deviceId = deviceId,
        tsEpochMs = tsEpochMs,
        lat = location.lat,
        lng = location.lng,
        locAccuracyM = location.accuracyM,
        speedMps = location.speedMps,
        isMoving = location.speedMps >= MOVING_SPEED_THRESHOLD_MPS,
        mcc = carrier.mcc,
        mnc = carrier.mnc,
        carrierName = carrier.carrierName,
        networkType = sample.networkType,
        signalDbm = sample.signalDbm,
        rsrq = sample.rsrq,
        sinr = sample.sinr,
        asu = sample.asu,
        bars = sample.bars,
        cellId = sample.cellId,
        tac = sample.tac,
        pci = sample.pci,
        earfcn = sample.earfcn,
        phoneMake = device.phoneMake,
        phoneModel = device.phoneModel,
        osVersion = device.osVersion,
        appVersion = device.appVersion,
        source = source,
    )
}
