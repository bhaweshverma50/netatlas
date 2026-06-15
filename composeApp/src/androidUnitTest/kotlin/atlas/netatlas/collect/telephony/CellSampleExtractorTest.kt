package atlas.netatlas.collect.telephony

import android.telephony.CellIdentityGsm
import android.telephony.CellIdentityLte
import android.telephony.CellInfo
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import atlas.model.NetworkType
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure unit tests for [CellSampleExtractor]. The Android framework cell objects are
 * mocked with Mockito; `testOptions.unitTests.isReturnDefaultValues = true` lets the
 * un-stubbed final getters return defaults instead of throwing.
 */
class CellSampleExtractorTest {

    private fun lteInfo(
        rsrp: Int = -95,
        rsrq: Int = -10,
        rssnr: Int = 12,
        asu: Int = 30,
        level: Int = 3,
        dbm: Int = -96,
        ci: Int = 123_456,
        tac: Int = 4321,
        pci: Int = 55,
        earfcn: Int = 1850,
    ): CellInfoLte {
        val ss = mock<CellSignalStrengthLte> {
            whenever(it.rsrp).thenReturn(rsrp)
            whenever(it.rsrq).thenReturn(rsrq)
            whenever(it.rssnr).thenReturn(rssnr)
            whenever(it.asuLevel).thenReturn(asu)
            whenever(it.level).thenReturn(level)
            whenever(it.dbm).thenReturn(dbm)
        }
        val id = mock<CellIdentityLte> {
            whenever(it.ci).thenReturn(ci)
            whenever(it.tac).thenReturn(tac)
            whenever(it.pci).thenReturn(pci)
            whenever(it.earfcn).thenReturn(earfcn)
        }
        return mock<CellInfoLte> {
            whenever(it.cellSignalStrength).thenReturn(ss)
            whenever(it.cellIdentity).thenReturn(id)
        }
    }

    @Test
    fun lte_maps_all_fields() {
        val sample = CellSampleExtractor.fromCellInfo(lteInfo())
        requireNotNull(sample)
        assertEquals(NetworkType.LTE, sample.networkType)
        assertEquals(-95, sample.signalDbm)
        assertEquals(-10, sample.rsrq)
        assertEquals(12, sample.sinr)
        assertEquals(30, sample.asu)
        assertEquals(3, sample.bars)
        assertEquals(123_456L, sample.cellId)
        assertEquals(4321, sample.tac)
        assertEquals(55, sample.pci)
        assertEquals(1850, sample.earfcn)
    }

    @Test
    fun lte_falls_back_to_dbm_when_rsrp_unavailable() {
        val sample = CellSampleExtractor.fromCellInfo(lteInfo(rsrp = CellInfo.UNAVAILABLE, dbm = -100))
        requireNotNull(sample)
        assertEquals(-100, sample.signalDbm)
    }

    @Test
    fun lte_unavailable_ci_maps_to_null_cellId() {
        val sample = CellSampleExtractor.fromCellInfo(lteInfo(ci = CellInfo.UNAVAILABLE))
        requireNotNull(sample)
        assertNull(sample.cellId)
    }

    @Test
    fun lte_unavailable_optionals_map_to_null() {
        val sample = CellSampleExtractor.fromCellInfo(
            lteInfo(
                rsrq = CellInfo.UNAVAILABLE,
                rssnr = CellInfo.UNAVAILABLE,
                asu = CellInfo.UNAVAILABLE,
                tac = CellInfo.UNAVAILABLE,
                pci = CellInfo.UNAVAILABLE,
                earfcn = CellInfo.UNAVAILABLE,
            ),
        )
        requireNotNull(sample)
        assertNull(sample.rsrq)
        assertNull(sample.sinr)
        assertNull(sample.asu)
        assertNull(sample.tac)
        assertNull(sample.pci)
        assertNull(sample.earfcn)
    }

    @Test
    fun gsm_maps_fields_with_null_rsrq_sinr_pci() {
        val ss = mock<CellSignalStrengthGsm> {
            whenever(it.dbm).thenReturn(-83)
            whenever(it.asuLevel).thenReturn(20)
            whenever(it.level).thenReturn(2)
        }
        val id = mock<CellIdentityGsm> {
            whenever(it.cid).thenReturn(7777)
            whenever(it.lac).thenReturn(101)
            whenever(it.arfcn).thenReturn(60)
        }
        val info = mock<CellInfoGsm> {
            whenever(it.cellSignalStrength).thenReturn(ss)
            whenever(it.cellIdentity).thenReturn(id)
        }

        val sample = CellSampleExtractor.fromCellInfo(info)
        requireNotNull(sample)
        assertEquals(NetworkType.GSM, sample.networkType)
        assertEquals(-83, sample.signalDbm)
        assertNull(sample.rsrq)
        assertNull(sample.sinr)
        assertNull(sample.pci)
        assertEquals(20, sample.asu)
        assertEquals(2, sample.bars)
        assertEquals(7777L, sample.cellId)
        assertEquals(101, sample.tac)
        assertEquals(60, sample.earfcn)
    }

    @Test
    fun unknown_subtype_returns_null() {
        val info = mock<CellInfo>()
        assertNull(CellSampleExtractor.fromCellInfo(info))
    }
}
