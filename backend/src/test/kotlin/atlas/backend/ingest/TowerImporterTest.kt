package atlas.backend.ingest

import atlas.backend.db.Database
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.sql.Connection

/**
 * Integration tests for [TowerImporter] against a real PostGIS container, mirroring
 * the container + Database setup in ReadingRepositoryTest. Verifies the UPSERT is
 * idempotent (re-import keeps the row count), that the bundled sample CSV imports its
 * valid non-header rows, and that geom is populated.
 */
@Testcontainers
class TowerImporterTest {

    companion object {
        @Container
        @JvmStatic
        // arm64-native PostGIS (official postgis/postgis is amd64-only → QEMU emulation on Apple Silicon).
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(
            DockerImageName.parse("imresamu/postgis:16-3.4").asCompatibleSubstituteFor("postgres"),
        )
    }

    private lateinit var db: Database
    private lateinit var importer: TowerImporter

    @BeforeEach
    fun setUp() {
        db = Database.connect(postgres.jdbcUrl, postgres.username, postgres.password)
        db.applyMigrations()
        importer = TowerImporter(db)
        db.transaction { exec("TRUNCATE TABLE cell_towers") }
    }

    private fun rowCount(): Int = db.transaction {
        val conn = connection.connection as Connection
        conn.prepareStatement("SELECT count(*) FROM cell_towers").use { ps ->
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getInt(1)
            }
        }
    }

    private fun threeRows(): List<TowerRow> = listOf(
        TowerRow("LTE", 404, 45, 678, 12345L, 77.5946, 12.9716, 1500, 42),
        TowerRow("LTE", 404, 45, 678, 12346L, 77.6000, 12.9800, 1200, 30),
        TowerRow("NR", 404, 10, 12, 98765L, 77.6100, 12.9500, 800, 15),
    )

    @Test
    fun import_three_rows_inserts_three() {
        val n = importer.importRows(threeRows())
        assertEquals(3, n)
        assertEquals(3, rowCount())
    }

    @Test
    fun reimport_same_rows_is_idempotent_and_updates() {
        importer.importRows(threeRows())
        // Re-import the same primary keys but with a changed range to confirm UPDATE.
        val updated = threeRows().map { it.copy(rangeM = (it.rangeM ?: 0) + 100) }
        val n = importer.importRows(updated)
        assertEquals(3, n)
        assertEquals(3, rowCount())

        // The first row's range_m should reflect the update (1500 -> 1600).
        val rangeM = db.transaction {
            val conn = connection.connection as Connection
            conn.prepareStatement(
                "SELECT range_m FROM cell_towers WHERE mcc=? AND mnc=? AND area=? AND cell_id=?",
            ).use { ps ->
                ps.setInt(1, 404); ps.setInt(2, 45); ps.setInt(3, 678); ps.setLong(4, 12345L)
                ps.executeQuery().use { rs -> rs.next(); rs.getInt("range_m") }
            }
        }
        assertEquals(1600, rangeM)
    }

    @Test
    fun geom_is_non_null_for_imported_row() {
        importer.importRows(threeRows())
        val nonNullGeom = db.transaction {
            val conn = connection.connection as Connection
            conn.prepareStatement("SELECT count(*) FROM cell_towers WHERE geom IS NOT NULL").use { ps ->
                ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
            }
        }
        assertEquals(3, nonNullGeom)
    }

    @Test
    fun import_bundled_sample_csv_matches_valid_row_count() {
        val csv = findSampleCsv()
        assertTrue(csv.isFile, "sample CSV should exist at ${csv.absolutePath}")

        val expected = OpenCelliDParser.parse(csv.readLines().asSequence()).count()
        assertTrue(expected > 0, "sample CSV should contain at least one valid row")

        val n = importer.importFrom(csv)
        assertEquals(expected, n)
        assertEquals(expected, rowCount())
    }

    /** Locates ingest-opencellid/sample-bengaluru.csv walking up from the working dir. */
    private fun findSampleCsv(): File {
        var current: File? = File(".").absoluteFile.normalize()
        while (current != null) {
            val candidate = File(current, "ingest-opencellid/sample-bengaluru.csv")
            if (candidate.isFile) return candidate
            current = current.parentFile
        }
        return File("ingest-opencellid/sample-bengaluru.csv")
    }
}
