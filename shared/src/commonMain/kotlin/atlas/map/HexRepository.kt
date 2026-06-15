package atlas.map

import atlas.model.Carrier
import atlas.model.HexAggregate
import atlas.model.NetworkType
import atlas.net.ApiClient

/** Geographic window the map is currently showing. */
data class BoundingBox(
    val minLng: Double,
    val minLat: Double,
    val maxLng: Double,
    val maxLat: Double,
)

/** Optional carrier/network filters applied to the heatmap query. */
data class HexFilter(
    val mcc: Int? = null,
    val mnc: Int? = null,
    val networkType: NetworkType? = null,
)

/**
 * Thin domain wrapper over [ApiClient] so the view model stays decoupled from the
 * HTTP layer. Translates [BoundingBox] + [HexFilter] into the client's flat params.
 */
open class HexRepository(private val api: ApiClient) {

    open suspend fun hexes(bbox: BoundingBox, filter: HexFilter = HexFilter()): List<HexAggregate> =
        api.getHexes(
            minLng = bbox.minLng,
            minLat = bbox.minLat,
            maxLng = bbox.maxLng,
            maxLat = bbox.maxLat,
            mcc = filter.mcc,
            mnc = filter.mnc,
            networkType = filter.networkType,
        )

    open suspend fun carriers(): List<Carrier> = api.getCarriers()
}
