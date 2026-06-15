package atlas.netatlas.collect.telephony

import android.Manifest
import android.content.Context
import android.os.Build
import android.telephony.CellInfo
import android.telephony.TelephonyManager
import androidx.annotation.RequiresPermission
import atlas.collect.CarrierId
import atlas.collect.RawCellSample
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Reads the current serving-cell sample and carrier identity from the framework
 * [TelephonyManager]. The actual framework→[RawCellSample] mapping lives in the
 * pure, unit-testable [CellSampleExtractor]; this class only handles the async
 * platform plumbing and permission/SIM error cases.
 */
class TelephonyReader(context: Context) {

    private val appContext = context.applicationContext
    private val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    /**
     * Requests a fresh cell-info snapshot (API 29+ `requestCellInfoUpdate`, falling
     * back to the cached `allCellInfo`), picks the registered serving cell, and maps
     * it to a [RawCellSample]. Returns `null` if telephony is unavailable, no cell is
     * reported, or the subtype is unsupported.
     */
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    suspend fun read(): RawCellSample? {
        val tm = tm ?: return null
        return try {
            val cells = requestCellInfo(tm) ?: return null
            val serving = cells.firstOrNull { it.isRegistered } ?: cells.firstOrNull()
            serving?.let { CellSampleExtractor.fromCellInfo(it) }
        } catch (_: SecurityException) {
            null
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun requestCellInfo(tm: TelephonyManager): List<CellInfo>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val fresh = requestCellInfoUpdate(tm)
            if (!fresh.isNullOrEmpty()) return fresh
        }
        // Fallback to the last cached snapshot (also the only option pre-Q).
        @Suppress("DEPRECATION")
        return tm.allCellInfo
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    private suspend fun requestCellInfoUpdate(tm: TelephonyManager): List<CellInfo>? =
        suspendCancellableCoroutine { cont ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                tm.requestCellInfoUpdate(
                    executor,
                    object : TelephonyManager.CellInfoCallback() {
                        override fun onCellInfo(cellInfo: MutableList<CellInfo>) {
                            executor.shutdown()
                            if (cont.isActive) cont.resume(cellInfo)
                        }

                        override fun onError(errorCode: Int, detail: Throwable?) {
                            executor.shutdown()
                            if (cont.isActive) cont.resume(null)
                        }
                    },
                )
            } catch (_: SecurityException) {
                executor.shutdown()
                if (cont.isActive) cont.resume(null)
            }
            cont.invokeOnCancellation { executor.shutdown() }
        }

    /**
     * Resolves carrier identity from the registered network operator. The numeric
     * `networkOperator` is `MCC(3) + MNC(2-3)`. Returns `null` when no SIM/network
     * is registered (empty operator string).
     */
    fun carrierId(): CarrierId? {
        val tm = tm ?: return null
        return try {
            val operator = tm.networkOperator
            if (operator.length < 4) return null
            val mcc = operator.substring(0, 3).toIntOrNull() ?: return null
            val mnc = operator.substring(3).toIntOrNull() ?: return null
            CarrierId(
                mcc = mcc,
                mnc = mnc,
                carrierName = tm.networkOperatorName ?: "",
            )
        } catch (_: SecurityException) {
            null
        }
    }
}
