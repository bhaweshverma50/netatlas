package atlas.map

import atlas.model.CoverageClass
import atlas.model.NetworkType

/**
 * The per-hex coverage detail shown when a hexagon is tapped on the map.
 *
 * Mirrors the `properties` block emitted by the backend's `/hexes.geojson` (see
 * `atlas.backend.hexesToGeoJson`). Parsing lives here in `:shared` — rather than in the
 * Android map layer — so it is pure and unit-testable on the JVM.
 */
data class HexDetail(
    val h3: String,
    val meanDbm: Double,
    val medianDbm: Double,
    val confidence: Double,
    val sampleCount: Int,
    val coverageClass: CoverageClass,
    val mcc: Int,
    val mnc: Int,
    val networkType: NetworkType,
) {
    companion object {
        /**
         * Parses a [HexDetail] from a MapLibre feature's property map (all values as strings).
         *
         * Returns `null` if a required key is missing or a numeric value is malformed, so the
         * caller can simply skip showing a sheet. Unknown enum values degrade safely rather
         * than throwing: an unknown coverage class becomes [CoverageClass.NO_SIGNAL] and an
         * unknown network type becomes [NetworkType.UNKNOWN].
         */
        fun fromProperties(props: Map<String, String>): HexDetail? {
            val h3 = props["h3"] ?: return null
            val meanDbm = props["meanDbm"]?.toDoubleOrNull() ?: return null
            val medianDbm = props["medianDbm"]?.toDoubleOrNull() ?: return null
            val confidence = props["confidence"]?.toDoubleOrNull() ?: return null
            val sampleCount = props["sampleCount"]?.toIntOrNull() ?: return null
            val mcc = props["mcc"]?.toIntOrNull() ?: return null
            val mnc = props["mnc"]?.toIntOrNull() ?: return null
            val coverageClass = props["coverageClass"]?.let { name ->
                CoverageClass.entries.firstOrNull { it.name == name }
            } ?: CoverageClass.NO_SIGNAL
            val networkType = props["networkType"]?.let { name ->
                NetworkType.entries.firstOrNull { it.name == name }
            } ?: NetworkType.UNKNOWN

            return HexDetail(
                h3 = h3,
                meanDbm = meanDbm,
                medianDbm = medianDbm,
                confidence = confidence,
                sampleCount = sampleCount,
                coverageClass = coverageClass,
                mcc = mcc,
                mnc = mnc,
                networkType = networkType,
            )
        }
    }
}
