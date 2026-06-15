package atlas.backend.geo

import com.uber.h3core.H3Core

/**
 * Thin wrapper over [H3Core] (h3-java 4.1.1) for the netatlas geo layer.
 *
 * H3 cell indices are signed-positive 64-bit `long` values; the backend stores
 * them as BIGINT and also keeps the canonical hex-string form for the API.
 * Resolution 10 (~120 m) is the project's collection resolution.
 */
object H3 {
    // H3Core.newInstance() loads a native lib and can throw IOException; create once, lazily, and reuse.
    private val core: H3Core by lazy { H3Core.newInstance() }

    /** lat/lng -> H3 cell index at [res] (default 10). */
    fun cell(lat: Double, lng: Double, res: Int = 10): Long = core.latLngToCell(lat, lng, res)

    /** Cell center as (lat, lng). */
    fun center(cell: Long): Pair<Double, Double> {
        val ll = core.cellToLatLng(cell)
        return ll.lat to ll.lng
    }

    /** Cell boundary vertices as (lat, lng) pairs (typically 6, sometimes 5 for pentagons). */
    fun boundary(cell: Long): List<Pair<Double, Double>> = core.cellToBoundary(cell).map { it.lat to it.lng }

    /** Canonical lowercase hex string form of a cell (e.g. "8a2a1072b59ffff"). */
    fun toAddress(cell: Long): String = core.h3ToString(cell)

    /** Parse a hex string form back into the numeric cell index. */
    fun fromAddress(addr: String): Long = core.stringToH3(addr)

    /** Resolution of a cell. */
    fun resolution(cell: Long): Int = core.getResolution(cell)

    /** All cells within [k] grid steps of [cell] (the cell itself plus its k-ring disk). */
    fun kRing(cell: Long, k: Int): List<Long> = core.gridDisk(cell, k)
}
