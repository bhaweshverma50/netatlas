package atlas.netatlas.collect.telephony

import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoNr
import android.telephony.CellInfoWcdma
import android.telephony.CellSignalStrengthNr
import android.telephony.CellIdentityNr
import atlas.collect.RawCellSample
import atlas.model.NetworkType

/**
 * Pure mapper from an Android-framework [CellInfo] (a single serving cell) to a
 * tech-agnostic [RawCellSample]. Kept side-effect-free so it can be unit-tested
 * against mocked framework objects (see CellSampleExtractorTest).
 *
 * Framework sentinel values ([CellInfo.UNAVAILABLE] == Int.MAX_VALUE, and the
 * Long.MAX_VALUE used for NR's NCI) are normalised to `null` for the optional
 * fields. Unknown / unsupported subtypes return `null`.
 */
object CellSampleExtractor {

    fun fromCellInfo(info: CellInfo): RawCellSample? = when (info) {
        is CellInfoLte -> fromLte(info)
        is CellInfoNr -> fromNr(info)
        is CellInfoWcdma -> fromWcdma(info)
        is CellInfoGsm -> fromGsm(info)
        else -> null
    }

    private fun fromLte(info: CellInfoLte): RawCellSample {
        val ss = info.cellSignalStrength
        val id = info.cellIdentity
        // rsrp is the LTE-correct dBm; fall back to the generic .dbm if unavailable.
        val rsrp = ss.rsrp.nullIfSentinel()
        return RawCellSample(
            networkType = NetworkType.LTE,
            signalDbm = rsrp ?: ss.dbm,
            rsrq = ss.rsrq.nullIfSentinel(),
            sinr = ss.rssnr.nullIfSentinel(),
            asu = ss.asuLevel.nullIfSentinel(),
            bars = ss.level,
            cellId = id.ci.nullIfSentinel()?.toLong(),
            tac = id.tac.nullIfSentinel(),
            pci = id.pci.nullIfSentinel(),
            earfcn = id.earfcn.nullIfSentinel(),
        )
    }

    private fun fromNr(info: CellInfoNr): RawCellSample {
        val ss = info.cellSignalStrength as CellSignalStrengthNr
        val id = info.cellIdentity as CellIdentityNr
        val ssRsrp = ss.ssRsrp.nullIfSentinel()
        return RawCellSample(
            networkType = NetworkType.NR_SA,
            signalDbm = ssRsrp ?: ss.dbm,
            rsrq = ss.ssRsrq.nullIfSentinel(),
            // ssSinr is the NR signal-to-noise metric.
            sinr = ss.ssSinr.nullIfSentinel(),
            asu = ss.asuLevel.nullIfSentinel(),
            bars = ss.level,
            cellId = id.nci.nullIfSentinelLong(),
            tac = id.tac.nullIfSentinel(),
            pci = id.pci.nullIfSentinel(),
            earfcn = id.nrarfcn.nullIfSentinel(),
        )
    }

    private fun fromWcdma(info: CellInfoWcdma): RawCellSample {
        val ss = info.cellSignalStrength
        val id = info.cellIdentity
        return RawCellSample(
            networkType = NetworkType.UMTS,
            signalDbm = ss.dbm,
            rsrq = null,
            sinr = null,
            asu = ss.asuLevel.nullIfSentinel(),
            bars = ss.level,
            cellId = id.cid.nullIfSentinel()?.toLong(),
            tac = id.lac.nullIfSentinel(),
            pci = id.psc.nullIfSentinel(),
            earfcn = id.uarfcn.nullIfSentinel(),
        )
    }

    private fun fromGsm(info: CellInfoGsm): RawCellSample {
        val ss = info.cellSignalStrength
        val id = info.cellIdentity
        return RawCellSample(
            networkType = NetworkType.GSM,
            signalDbm = ss.dbm,
            rsrq = null,
            sinr = null,
            asu = ss.asuLevel.nullIfSentinel(),
            bars = ss.level,
            cellId = id.cid.nullIfSentinel()?.toLong(),
            tac = id.lac.nullIfSentinel(),
            pci = null,
            earfcn = id.arfcn.nullIfSentinel(),
        )
    }

    /** Framework uses [CellInfo.UNAVAILABLE] (Int.MAX_VALUE) for "not reported". */
    private fun Int.nullIfSentinel(): Int? =
        if (this == CellInfo.UNAVAILABLE || this == Int.MAX_VALUE) null else this

    /** NR's NCI uses the 64-bit Long.MAX_VALUE sentinel. */
    private fun Long.nullIfSentinelLong(): Long? =
        if (this == Long.MAX_VALUE) null else this
}
