package atlas.backend.ingest

/**
 * One parsed OpenCelliD tower row, mapped onto the `cell_towers` columns.
 *
 * `rangeM`/`samples` are nullable because the source CSV leaves them blank for
 * towers with no estimated coverage radius / sample count.
 */
data class TowerRow(
    val radio: String,
    val mcc: Int,
    val mnc: Int,
    val area: Int,
    val cellId: Long,
    val lng: Double,
    val lat: Double,
    val rangeM: Int?,
    val samples: Int?,
)

/**
 * Pure, tolerant parser for OpenCelliD-format CSV.
 *
 * Expected header / row layout (comma-separated):
 * ```
 * radio,mcc,net,area,cell,unit,lon,lat,range,samples,changeable,created,updated,averageSignal
 * ```
 * Column mapping to [TowerRow]: radio→radio, mcc→mcc, net→mnc, area→area,
 * cell→cellId, lon→lng, lat→lat, range→rangeM, samples→samples. The remaining
 * columns (unit, changeable, created, updated, averageSignal) are ignored.
 *
 * Tolerance contract: [parseLine] NEVER throws. Any header, blank, or malformed
 * line (wrong column count, non-numeric required field, or lat/lng out of the
 * valid -90..90 / -180..180 range) returns `null`.
 */
object OpenCelliDParser {

    // Index of each field in the comma-split row.
    private const val IDX_RADIO = 0
    private const val IDX_MCC = 1
    private const val IDX_NET = 2
    private const val IDX_AREA = 3
    private const val IDX_CELL = 4
    private const val IDX_LON = 6
    private const val IDX_LAT = 7
    private const val IDX_RANGE = 8
    private const val IDX_SAMPLES = 9

    /** Minimum number of columns we need to read everything up to `samples`. */
    private const val MIN_COLUMNS = IDX_SAMPLES + 1

    /**
     * Parses a single CSV line into a [TowerRow], or returns `null` for the
     * header row, blank lines, and anything malformed. Never throws.
     */
    fun parseLine(line: String): TowerRow? {
        if (line.isBlank()) return null

        // Keep trailing empty fields ("...,,") by using limit = -1.
        val f = line.split(",")
        if (f.size < MIN_COLUMNS) return null

        val radio = f[IDX_RADIO].trim()
        // The header row leads with the literal column name "radio".
        if (radio.equals("radio", ignoreCase = true)) return null

        val mcc = f[IDX_MCC].trim().toIntOrNull() ?: return null
        val mnc = f[IDX_NET].trim().toIntOrNull() ?: return null
        val area = f[IDX_AREA].trim().toIntOrNull() ?: return null
        val cellId = f[IDX_CELL].trim().toLongOrNull() ?: return null
        val lng = f[IDX_LON].trim().toDoubleOrNull() ?: return null
        val lat = f[IDX_LAT].trim().toDoubleOrNull() ?: return null

        if (lat < -90.0 || lat > 90.0) return null
        if (lng < -180.0 || lng > 180.0) return null

        // range / samples are optional: blank → null, present-but-bad → null.
        val rangeM = f[IDX_RANGE].trim().let { if (it.isEmpty()) null else it.toIntOrNull() }
        val samples = f[IDX_SAMPLES].trim().let { if (it.isEmpty()) null else it.toIntOrNull() }

        return TowerRow(
            radio = radio,
            mcc = mcc,
            mnc = mnc,
            area = area,
            cellId = cellId,
            lng = lng,
            lat = lat,
            rangeM = rangeM,
            samples = samples,
        )
    }

    /** Maps [lines] through [parseLine], dropping every `null` (header/blank/bad). */
    fun parse(lines: Sequence<String>): Sequence<TowerRow> =
        lines.mapNotNull(::parseLine)
}
