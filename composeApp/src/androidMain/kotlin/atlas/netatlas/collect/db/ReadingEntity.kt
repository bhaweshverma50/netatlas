package atlas.netatlas.collect.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import atlas.model.NetworkType
import atlas.model.SignalReading
import atlas.model.Source

/**
 * Room row for a locally-queued [SignalReading]. All [SignalReading] fields are
 * flattened into columns; enums are stored by name. [sent] tracks whether the
 * row has already been uploaded (the M2.3 upload worker flips it).
 */
@Entity(tableName = "queued_readings")
data class ReadingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceId: String,
    val tsEpochMs: Long,
    val lat: Double,
    val lng: Double,
    val locAccuracyM: Double,
    val speedMps: Double,
    val isMoving: Boolean,
    val mcc: Int,
    val mnc: Int,
    val carrierName: String,
    val networkType: String,
    val signalDbm: Int,
    val rsrq: Int?,
    val sinr: Int?,
    val asu: Int?,
    val bars: Int,
    val cellId: Long?,
    val tac: Int?,
    val pci: Int?,
    val earfcn: Int?,
    val phoneMake: String,
    val phoneModel: String,
    val osVersion: String,
    val appVersion: String,
    val source: String,
    val sent: Boolean = false,
) {
    fun toSignalReading(): SignalReading = SignalReading(
        deviceId = deviceId,
        tsEpochMs = tsEpochMs,
        lat = lat,
        lng = lng,
        locAccuracyM = locAccuracyM,
        speedMps = speedMps,
        isMoving = isMoving,
        mcc = mcc,
        mnc = mnc,
        carrierName = carrierName,
        networkType = NetworkType.valueOf(networkType),
        signalDbm = signalDbm,
        rsrq = rsrq,
        sinr = sinr,
        asu = asu,
        bars = bars,
        cellId = cellId,
        tac = tac,
        pci = pci,
        earfcn = earfcn,
        phoneMake = phoneMake,
        phoneModel = phoneModel,
        osVersion = osVersion,
        appVersion = appVersion,
        source = Source.valueOf(source),
    )

    companion object {
        fun fromSignalReading(r: SignalReading): ReadingEntity = ReadingEntity(
            deviceId = r.deviceId,
            tsEpochMs = r.tsEpochMs,
            lat = r.lat,
            lng = r.lng,
            locAccuracyM = r.locAccuracyM,
            speedMps = r.speedMps,
            isMoving = r.isMoving,
            mcc = r.mcc,
            mnc = r.mnc,
            carrierName = r.carrierName,
            networkType = r.networkType.name,
            signalDbm = r.signalDbm,
            rsrq = r.rsrq,
            sinr = r.sinr,
            asu = r.asu,
            bars = r.bars,
            cellId = r.cellId,
            tac = r.tac,
            pci = r.pci,
            earfcn = r.earfcn,
            phoneMake = r.phoneMake,
            phoneModel = r.phoneModel,
            osVersion = r.osVersion,
            appVersion = r.appVersion,
            source = r.source.name,
        )
    }
}
