package atlas.map

import atlas.model.CoverageClass
import atlas.model.NetworkType
import atlas.model.Source

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
    /**
     * Where this hex's coverage came from: real crowd-sourced measurements ([Source.CROWD])
     * or a modeled estimate near a known tower ([Source.OPENCELLID], `sampleCount == 0`).
     * Defaults to [Source.CROWD] so older feature payloads without a `source` property stay
     * back-compatible.
     */
    val source: Source = Source.CROWD,
) {
    companion object {
        /**
         * Parses a [HexDetail] from a MapLibre feature's property map (all values as strings).
         *
         * Returns `null` if a required key is missing or a numeric value is malformed, so the
         * caller can simply skip showing a sheet. Unknown enum values degrade safely rather
         * than throwing: an unknown coverage class becomes [CoverageClass.NO_SIGNAL], an
         * unknown network type becomes [NetworkType.UNKNOWN], and a missing/unknown source
         * becomes [Source.CROWD].
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
            // Missing or unrecognized source degrades to crowd-sourced (back-compat).
            val source = props["source"]?.let { name ->
                Source.entries.firstOrNull { it.name == name }
            } ?: Source.CROWD

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
                source = source,
            )
        }
    }
}
