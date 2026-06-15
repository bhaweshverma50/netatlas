package atlas.map

import atlas.model.Carrier
import atlas.model.NetworkType

/**
 * The two independent choices the heatmap's filter bar offers: an optional [carrier] and an
 * optional [networkType] (each `null` meaning "All"). Merging them into the single
 * [HexFilter] the view model understands is pure logic, kept here so it is unit-testable
 * without Compose.
 */
data class FilterSelection(
    val carrier: Carrier? = null,
    val networkType: NetworkType? = null,
) {
    fun toHexFilter(): HexFilter = HexFilter(
        mcc = carrier?.mcc,
        mnc = carrier?.mnc,
        networkType = networkType,
    )
}
