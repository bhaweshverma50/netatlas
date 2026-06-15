package atlas.backend.ingest

import atlas.backend.db.Database
import java.io.File
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types

/**
 * Loads OpenCelliD-format cell-tower data into the `cell_towers` table.
 *
 * Rows are UPSERTed on the natural key `(mcc, mnc, area, cell_id)`, so re-importing
 * the same dump is idempotent: existing towers are updated in place, the row count
 * does not grow, and `updated_at` is refreshed. `geom` is built server-side from
 * lng/lat via `ST_SetSRID(ST_MakePoint(...),4326)::geography`.
 *
 * All value-carrying SQL uses a parameterized [PreparedStatement], batched per import.
 */
class TowerImporter(private val db: Database) {

    /** Parses [file] as OpenCelliD CSV and UPSERTs the valid rows. Returns the count imported. */
    fun importFrom(file: File): Int =
        file.bufferedReader().useLines { lines ->
            importRows(OpenCelliDParser.parse(lines).toList())
        }

    /** UPSERTs [rows] into `cell_towers`. Returns the number of rows written. */
    fun importRows(rows: List<TowerRow>): Int {
        if (rows.isEmpty()) return 0
        return db.transaction {
            val conn = connection.connection as Connection
            conn.prepareStatement(UPSERT_TOWER).use { ps ->
                for (row in rows) {
                    bind(ps, row)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            rows.size
        }
    }

    private fun bind(ps: PreparedStatement, row: TowerRow) {
        var i = 1
        ps.setString(i++, row.radio)
        ps.setInt(i++, row.mcc)
        ps.setInt(i++, row.mnc)
        ps.setInt(i++, row.area)
        ps.setLong(i++, row.cellId)
        ps.setDouble(i++, row.lng)
        ps.setDouble(i++, row.lat)
        if (row.rangeM != null) ps.setInt(i++, row.rangeM) else ps.setNull(i++, Types.INTEGER)
        if (row.samples != null) ps.setInt(i++, row.samples) else ps.setNull(i++, Types.INTEGER)
        // geom is built from lng/lat (two more bind params).
        ps.setDouble(i++, row.lng)
        ps.setDouble(i++, row.lat)
    }

    private companion object {
        val UPSERT_TOWER = """
            INSERT INTO cell_towers (
                radio, mcc, mnc, area, cell_id, lng, lat, range_m, samples, geom, updated_at
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                now()
            )
            ON CONFLICT (mcc, mnc, area, cell_id) DO UPDATE SET
                radio      = EXCLUDED.radio,
                lng        = EXCLUDED.lng,
                lat        = EXCLUDED.lat,
                range_m    = EXCLUDED.range_m,
                samples    = EXCLUDED.samples,
                geom       = EXCLUDED.geom,
                updated_at = now()
        """.trimIndent()
    }
}
