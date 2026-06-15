package atlas.backend.repo

import atlas.aggregate.Aggregation
import atlas.aggregate.ModeledCoverage
import atlas.backend.db.Database
import atlas.backend.geo.H3
import atlas.ingest.AnomalyFilter
import atlas.model.CoverageClass
import atlas.model.HexAggregate
import atlas.model.NetworkType
import atlas.model.SignalReading
import atlas.model.Source
import java.sql.Connection
import java.sql.Types
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Outcome of an [ReadingRepository.ingest] call. */
data class IngestResult(val accepted: Int, val rejected: Int)

/** A distinct carrier present in the aggregates. */
data class CarrierInfo(val mcc: Int, val mnc: Int, val carrierName: String)

/**
 * Persists raw [SignalReading]s and maintains the derived [hex_aggregates] table.
 *
 * Ingest validates with the shared [AnomalyFilter], stores accepted readings, then
 * recomputes every affected `(h3_r10, mcc, mnc, network_type)` aggregate using the
 * shared [Aggregation] confidence/classify logic so the tested math is what runs.
 *
 * All SQL that carries caller/reading values uses parameterized PreparedStatements.
 */
class ReadingRepository(private val db: Database) {

    /** Key identifying a single aggregate row. */
    private data class HexKey(val h3: Long, val mcc: Int, val mnc: Int, val networkType: String)

    /** Identity of a hex for blending crowd vs modeled coverage. */
    private data class ModeledKey(val h3: Long, val mcc: Int, val mnc: Int, val networkType: NetworkType)

    fun ingest(readings: List<SignalReading>): IngestResult {
        var accepted = 0
        var rejected = 0
        val affected = LinkedHashSet<HexKey>()

        db.transaction {
            val conn = connection.connection as Connection
            conn.prepareStatement(INSERT_READING).use { ps ->
                for (r in readings) {
                    if (!AnomalyFilter.isValid(r).accepted) {
                        rejected++
                        continue
                    }
                    val h3 = H3.cell(r.lat, r.lng, 10)
                    bindReading(ps, r, h3)
                    ps.executeUpdate()
                    accepted++
                    affected += HexKey(h3, r.mcc, r.mnc, r.networkType.name)
                }
            }
            for (key in affected) recomputeAggregate(conn, key)
        }

        return IngestResult(accepted = accepted, rejected = rejected)
    }

    fun hexes(
        minLng: Double,
        minLat: Double,
        maxLng: Double,
        maxLat: Double,
        mcc: Int? = null,
        mnc: Int? = null,
        networkType: NetworkType? = null,
    ): List<HexAggregate> {
        val sql = buildString {
            append(
                """
                SELECT h3_r10, resolution, mcc, mnc, network_type, sample_count,
                       mean_dbm, median_dbm, stddev, confidence, coverage_class,
                       center_lat, center_lng
                FROM hex_aggregates
                WHERE center_geom::geometry && ST_MakeEnvelope(?, ?, ?, ?, 4326)
                """.trimIndent(),
            )
            if (mcc != null) append(" AND mcc = ?")
            if (mnc != null) append(" AND mnc = ?")
            if (networkType != null) append(" AND network_type = ?")
        }

        return db.transaction {
            val conn = connection.connection as Connection
            val crowd = ArrayList<HexAggregate>()
            // Keys (h3 cell, mcc, mnc, networkType) already covered by crowd data; modeled
            // hexes for these keys are suppressed so crowd always wins.
            val crowdKeys = HashSet<ModeledKey>()

            conn.prepareStatement(sql).use { ps ->
                var i = 1
                ps.setDouble(i++, minLng)
                ps.setDouble(i++, minLat)
                ps.setDouble(i++, maxLng)
                ps.setDouble(i++, maxLat)
                if (mcc != null) ps.setInt(i++, mcc)
                if (mnc != null) ps.setInt(i++, mnc)
                if (networkType != null) ps.setString(i++, networkType.name)

                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val cell = rs.getLong("h3_r10")
                        val rowMcc = rs.getInt("mcc")
                        val rowMnc = rs.getInt("mnc")
                        val rowType = NetworkType.valueOf(rs.getString("network_type"))
                        crowdKeys += ModeledKey(cell, rowMcc, rowMnc, rowType)
                        crowd += HexAggregate(
                            h3 = H3.toAddress(cell),
                            resolution = rs.getInt("resolution"),
                            mcc = rowMcc,
                            mnc = rowMnc,
                            networkType = rowType,
                            sampleCount = rs.getInt("sample_count"),
                            meanDbm = rs.getDouble("mean_dbm"),
                            medianDbm = rs.getDouble("median_dbm"),
                            stddev = rs.getDouble("stddev"),
                            confidence = rs.getDouble("confidence"),
                            coverageClass = CoverageClass.valueOf(rs.getString("coverage_class")),
                            centerLat = rs.getDouble("center_lat"),
                            centerLng = rs.getDouble("center_lng"),
                            source = Source.CROWD,
                        )
                    }
                }
            }

            val modeled = modeledHexes(conn, minLng, minLat, maxLng, maxLat, mcc, mnc, networkType, crowdKeys)
            crowd + modeled
        }
    }

    /**
     * Synthesizes modeled coverage hexes from `cell_towers` within the bbox so areas near a
     * tower but without crowd data aren't blank.
     *
     * For each tower we map its `radio` to a [NetworkType], take a bounded k-ring of H3 cells
     * around it ([MAX_K] caps the patch size — a documented simplification keeping the response
     * bounded rather than tiling the tower's full range), and run each in-bbox cell through the
     * shared [ModeledCoverage.estimate]. Cells already covered by crowd ([crowdKeys]) are
     * skipped; for the rest we keep the best (highest-dBm) estimate per (cell, mcc, mnc, type).
     */
    private fun modeledHexes(
        conn: Connection,
        minLng: Double,
        minLat: Double,
        maxLng: Double,
        maxLat: Double,
        mcc: Int?,
        mnc: Int?,
        networkType: NetworkType?,
        crowdKeys: Set<ModeledKey>,
    ): List<HexAggregate> {
        val sql = buildString {
            append(
                """
                SELECT radio, mcc, mnc, lat, lng, range_m, samples
                FROM cell_towers
                WHERE geom::geometry && ST_MakeEnvelope(?, ?, ?, ?, 4326)
                """.trimIndent(),
            )
            if (mcc != null) append(" AND mcc = ?")
            if (mnc != null) append(" AND mnc = ?")
        }

        // Best estimate kept per modeled key.
        val best = LinkedHashMap<ModeledKey, HexAggregate>()

        conn.prepareStatement(sql).use { ps ->
            var i = 1
            ps.setDouble(i++, minLng)
            ps.setDouble(i++, minLat)
            ps.setDouble(i++, maxLng)
            ps.setDouble(i++, maxLat)
            if (mcc != null) ps.setInt(i++, mcc)
            if (mnc != null) ps.setInt(i++, mnc)

            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val mappedType = radioToNetworkType(rs.getString("radio"))
                    // Network filter applies to the modeled side too.
                    if (networkType != null && mappedType != networkType) continue

                    val towerMcc = rs.getInt("mcc")
                    val towerMnc = rs.getInt("mnc")
                    val towerLat = rs.getDouble("lat")
                    val towerLng = rs.getDouble("lng")
                    val rangeM = rs.getInt("range_m").let { if (rs.wasNull()) DEFAULT_RANGE_M else it }
                    val samples = rs.getInt("samples").let { if (rs.wasNull()) 0 else it }

                    val cell0 = H3.cell(towerLat, towerLng, 10)
                    val k = (rangeM / HEX_EDGE_M).toInt().coerceIn(1, MAX_K)
                    for (cell in H3.kRing(cell0, k)) {
                        val (centerLat, centerLng) = H3.center(cell)
                        // Only emit cells whose center is inside the requested bbox.
                        if (centerLng < minLng || centerLng > maxLng || centerLat < minLat || centerLat > maxLat) continue

                        val key = ModeledKey(cell, towerMcc, towerMnc, mappedType)
                        if (key in crowdKeys) continue

                        val dist = haversineMeters(centerLat, centerLng, towerLat, towerLng)
                        val est = ModeledCoverage.estimate(dist, rangeM.toDouble(), samples)
                        val existing = best[key]
                        if (existing != null && existing.meanDbm >= est.meanDbm) continue

                        best[key] = HexAggregate(
                            h3 = H3.toAddress(cell),
                            resolution = 10,
                            mcc = towerMcc,
                            mnc = towerMnc,
                            networkType = mappedType,
                            sampleCount = 0,
                            meanDbm = est.meanDbm,
                            medianDbm = est.meanDbm,
                            stddev = 0.0,
                            confidence = est.confidence,
                            coverageClass = est.coverageClass,
                            centerLat = centerLat,
                            centerLng = centerLng,
                            source = Source.OPENCELLID,
                        )
                    }
                }
            }
        }

        return best.values.toList()
    }

    /** Maps an OpenCelliD `radio` tag to a [NetworkType]; unknown tags fall back to [NetworkType.UNKNOWN]. */
    private fun radioToNetworkType(radio: String?): NetworkType = when (radio?.trim()?.uppercase()) {
        "LTE" -> NetworkType.LTE
        "NR" -> NetworkType.NR_SA
        "UMTS", "WCDMA" -> NetworkType.UMTS
        "GSM" -> NetworkType.GSM
        else -> NetworkType.UNKNOWN
    }

    /** Great-circle distance in meters between two lat/lng points (R = 6371000 m). */
    private fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        return r * 2 * atan2(sqrt(a), sqrt(1 - a))
    }

    fun carriers(): List<CarrierInfo> = db.transaction {
        val conn = connection.connection as Connection
        // Distinct carriers in hex_aggregates; pick the most common carrier_name
        // seen in signal_readings for that (mcc, mnc) as the representative.
        val sql = """
            SELECT a.mcc, a.mnc,
                   (SELECT sr.carrier_name
                      FROM signal_readings sr
                     WHERE sr.mcc = a.mcc AND sr.mnc = a.mnc
                     GROUP BY sr.carrier_name
                     ORDER BY count(*) DESC
                     LIMIT 1) AS carrier_name
            FROM (SELECT DISTINCT mcc, mnc FROM hex_aggregates) a
            ORDER BY a.mcc, a.mnc
        """.trimIndent()
        conn.prepareStatement(sql).use { ps ->
            val out = ArrayList<CarrierInfo>()
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out += CarrierInfo(
                        mcc = rs.getInt("mcc"),
                        mnc = rs.getInt("mnc"),
                        carrierName = rs.getString("carrier_name") ?: "",
                    )
                }
            }
            out
        }
    }

    /**
     * Recomputes the aggregate for [key] from all matching rows in signal_readings
     * and upserts it into hex_aggregates. Confidence and coverage class are derived
     * in Kotlin via the shared [Aggregation] logic.
     */
    private fun recomputeAggregate(conn: Connection, key: HexKey) {
        var count = 0
        var mean = 0.0
        var median = 0.0
        var p10 = 0.0
        var stddev = 0.0
        var meanRsrq: Double? = null
        var meanSinr: Double? = null
        var devices = 0

        conn.prepareStatement(AGGREGATE_QUERY).use { ps ->
            ps.setLong(1, key.h3)
            ps.setInt(2, key.mcc)
            ps.setInt(3, key.mnc)
            ps.setString(4, key.networkType)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    count = rs.getInt("n")
                    mean = rs.getDouble("mean_dbm")
                    median = rs.getDouble("median_dbm")
                    p10 = rs.getDouble("p10_dbm")
                    // stddev_samp is NULL for n=1 -> treat as 0.0
                    stddev = rs.getDouble("stddev").let { if (rs.wasNull()) 0.0 else it }
                    meanRsrq = rs.getDouble("mean_rsrq").let { if (rs.wasNull()) null else it }
                    meanSinr = rs.getDouble("mean_sinr").let { if (rs.wasNull()) null else it }
                    devices = rs.getInt("devices")
                }
            }
        }

        // No surviving readings for this key (shouldn't happen on ingest, but be safe).
        if (count == 0) return

        val confidence = Aggregation.confidence(count, devices, stddev)
        val coverageClass = Aggregation.classify(mean).name
        val (centerLat, centerLng) = H3.center(key.h3)
        val resolution = H3.resolution(key.h3)

        conn.prepareStatement(UPSERT_AGGREGATE).use { ps ->
            var i = 1
            ps.setLong(i++, key.h3)
            ps.setInt(i++, resolution)
            ps.setInt(i++, key.mcc)
            ps.setInt(i++, key.mnc)
            ps.setString(i++, key.networkType)
            ps.setInt(i++, count)
            ps.setDouble(i++, mean)
            ps.setDouble(i++, median)
            ps.setDouble(i++, p10)
            ps.setDouble(i++, stddev)
            if (meanRsrq != null) ps.setDouble(i++, meanRsrq!!) else ps.setNull(i++, Types.DOUBLE)
            if (meanSinr != null) ps.setDouble(i++, meanSinr!!) else ps.setNull(i++, Types.DOUBLE)
            ps.setDouble(i++, confidence)
            ps.setString(i++, coverageClass)
            ps.setInt(i++, devices)
            ps.setDouble(i++, centerLat)
            ps.setDouble(i++, centerLng)
            // center_geom built from lng/lat
            ps.setDouble(i++, centerLng)
            ps.setDouble(i++, centerLat)
            ps.executeUpdate()
        }
    }

    private fun bindReading(ps: java.sql.PreparedStatement, r: SignalReading, h3: Long) {
        var i = 1
        ps.setString(i++, r.deviceId)
        ps.setDouble(i++, r.tsEpochMs / 1000.0) // to_timestamp expects seconds
        ps.setDouble(i++, r.lat)
        ps.setDouble(i++, r.lng)
        // geom from lng/lat
        ps.setDouble(i++, r.lng)
        ps.setDouble(i++, r.lat)
        ps.setDouble(i++, r.locAccuracyM)
        ps.setDouble(i++, r.speedMps)
        ps.setBoolean(i++, r.isMoving)
        ps.setLong(i++, h3)
        ps.setInt(i++, r.mcc)
        ps.setInt(i++, r.mnc)
        ps.setString(i++, r.carrierName)
        ps.setString(i++, r.networkType.name)
        ps.setInt(i++, r.signalDbm)
        setNullableInt(ps, i++, r.rsrq)
        setNullableInt(ps, i++, r.sinr)
        setNullableInt(ps, i++, r.asu)
        ps.setInt(i++, r.bars)
        if (r.cellId != null) ps.setLong(i++, r.cellId!!) else ps.setNull(i++, Types.BIGINT)
        setNullableInt(ps, i++, r.tac)
        setNullableInt(ps, i++, r.pci)
        setNullableInt(ps, i++, r.earfcn)
        ps.setString(i++, r.phoneMake)
        ps.setString(i++, r.phoneModel)
        ps.setString(i++, r.osVersion)
        ps.setString(i++, r.appVersion)
        ps.setString(i++, r.source.name)
    }

    private fun setNullableInt(ps: java.sql.PreparedStatement, idx: Int, value: Int?) {
        if (value != null) ps.setInt(idx, value) else ps.setNull(idx, Types.INTEGER)
    }

    private companion object {
        /** Default nominal tower range (m) when OpenCelliD leaves range_m blank. */
        const val DEFAULT_RANGE_M = 1000

        /** Approx. H3 resolution-10 edge length (m); used to size the modeled k-ring patch. */
        const val HEX_EDGE_M = 65.0

        /**
         * Hard cap on the modeled k-ring radius per tower, keeping the response bounded.
         * Raised from 4 to 7 so each tower's modeled patch is wider and neighboring
         * towers' patches overlap into continuous coverage rather than isolated blobs.
         * Note: the modeled cell count per tower grows ~k², so the response size grows
         * with it — 7 stays comfortably bounded for the demo bbox/tower density.
         */
        const val MAX_K = 7

        val INSERT_READING = """
            INSERT INTO signal_readings (
                device_id, ts, lat, lng, geom, loc_accuracy_m, speed_mps, is_moving,
                h3_r10, mcc, mnc, carrier_name, network_type, signal_dbm,
                rsrq, sinr, asu, bars, cell_id, tac, pci, earfcn,
                phone_make, phone_model, os_version, app_version, source
            ) VALUES (
                ?, to_timestamp(?), ?, ?,
                ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            )
        """.trimIndent()

        val AGGREGATE_QUERY = """
            SELECT count(*)                                                   AS n,
                   avg(signal_dbm)                                            AS mean_dbm,
                   percentile_cont(0.5) WITHIN GROUP (ORDER BY signal_dbm)    AS median_dbm,
                   percentile_cont(0.1) WITHIN GROUP (ORDER BY signal_dbm)    AS p10_dbm,
                   stddev_samp(signal_dbm)                                    AS stddev,
                   avg(rsrq)                                                  AS mean_rsrq,
                   avg(sinr)                                                  AS mean_sinr,
                   count(DISTINCT device_id)                                  AS devices
            FROM signal_readings
            WHERE h3_r10 = ? AND mcc = ? AND mnc = ? AND network_type = ?
        """.trimIndent()

        val UPSERT_AGGREGATE = """
            INSERT INTO hex_aggregates (
                h3_r10, resolution, mcc, mnc, network_type, sample_count,
                mean_dbm, median_dbm, p10_dbm, stddev, mean_rsrq, mean_sinr,
                confidence, coverage_class, contributing_devices,
                center_lat, center_lng, center_geom
            ) VALUES (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
            )
            ON CONFLICT (h3_r10, mcc, mnc, network_type) DO UPDATE SET
                resolution           = EXCLUDED.resolution,
                sample_count         = EXCLUDED.sample_count,
                mean_dbm             = EXCLUDED.mean_dbm,
                median_dbm           = EXCLUDED.median_dbm,
                p10_dbm              = EXCLUDED.p10_dbm,
                stddev               = EXCLUDED.stddev,
                mean_rsrq            = EXCLUDED.mean_rsrq,
                mean_sinr            = EXCLUDED.mean_sinr,
                confidence           = EXCLUDED.confidence,
                coverage_class       = EXCLUDED.coverage_class,
                contributing_devices = EXCLUDED.contributing_devices,
                center_lat           = EXCLUDED.center_lat,
                center_lng           = EXCLUDED.center_lng,
                center_geom          = EXCLUDED.center_geom,
                last_updated         = now()
        """.trimIndent()
    }
}
