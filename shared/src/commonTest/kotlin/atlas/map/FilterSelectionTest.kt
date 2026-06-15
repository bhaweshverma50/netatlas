package atlas.map

import atlas.model.Carrier
import atlas.model.NetworkType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The filter bar exposes two independent dropdowns (carrier + network) whose selections
 * must merge into a single [HexFilter]. This pure helper keeps that merge logic out of the
 * Compose layer so it is testable on the JVM.
 */
class FilterSelectionTest {

    private val airtel = Carrier(mcc = 404, mnc = 45, carrierName = "Airtel")

    @Test
    fun all_carriers_and_all_networks_is_an_empty_filter() {
        assertEquals(
            HexFilter(),
            FilterSelection(carrier = null, networkType = null).toHexFilter(),
        )
    }

    @Test
    fun a_carrier_sets_mcc_and_mnc() {
        assertEquals(
            HexFilter(mcc = 404, mnc = 45),
            FilterSelection(carrier = airtel, networkType = null).toHexFilter(),
        )
    }

    @Test
    fun a_network_sets_networkType() {
        assertEquals(
            HexFilter(networkType = NetworkType.NR_SA),
            FilterSelection(carrier = null, networkType = NetworkType.NR_SA).toHexFilter(),
        )
    }

    @Test
    fun both_selections_merge() {
        assertEquals(
            HexFilter(mcc = 404, mnc = 45, networkType = NetworkType.LTE),
            FilterSelection(carrier = airtel, networkType = NetworkType.LTE).toHexFilter(),
        )
    }
}
