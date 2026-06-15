package atlas.netatlas.map

import atlas.map.HexDetail
import org.maplibre.geojson.Feature

/**
 * Bridges a tapped MapLibre [Feature] (from `queryRenderedFeatures` on the `hex-fill` layer)
 * into the pure, testable [HexDetail.fromProperties].
 *
 * MapLibre stores feature properties as JSON elements; we flatten each to its string form
 * (numbers via [com.google.gson.JsonElement.getAsString], strings unquoted) and hand the
 * resulting `Map<String,String>` to the shared parser. Returns `null` if the feature carries
 * no usable hex properties.
 */
fun Feature.toHexDetail(): HexDetail? {
    val props = properties() ?: return null
    val map = buildMap {
        for ((key, value) in props.entrySet()) {
            if (value != null && !value.isJsonNull) {
                // getAsString unwraps JSON primitives (strings without quotes, numbers as text).
                put(key, value.asString)
            }
        }
    }
    return HexDetail.fromProperties(map)
}
