package atlas.backend.repo

import atlas.backend.db.Database
import atlas.backend.ingest.TowerImporter
import atlas.backend.ingest.TowerRow
import atlas.model.NetworkType
import atlas.model.Source
import atlas.model.SignalReading
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * Integration tests for the modeled-coverage blend in [ReadingRepository.hexes].
 *
 * A real PostGIS container holds both `cell_towers` (modeled source) and the
 * crowd tables. The blend must: surface modeled hexes around towers where no
 * crowd data exists, let crowd always win for the same (h3, mcc, mnc, network),
 * and apply carrier/network filters to the modeled side too.
 */
@Testcontainers
class ReadingRepositoryBlendTest {

    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(
            DockerImageName.parse("imresamu/postgis:16-3.4").asCompatibleSubstituteFor("postgres"),
        )
    }

    private lateinit var db: Database
    private lateinit var repo: ReadingRepository
    private lateinit var importer: TowerImporter

    // Bengaluru tower location.
    private val towerLat = 12.9716
    private val towerLng = 77.5946

    // A bbox comfortably around the Bengaluru tower(s).
    private val minLng = 77.4
    private val minLat = 12.8
    private val maxLng = 77.7
    private val maxLat = 13.1

    @BeforeEach
    fun setUp() {
        db = Database.connect(postgres.jdbcUrl, postgres.username, postgres.password)
        db.applyMigrations()
        repo = ReadingRepository(db)
        importer = TowerImporter(db)
        db.transaction {
            exec("TRUNCATE TABLE hex_aggregates, signal_readings, cell_towers RESTART IDENTITY")
        }
    }

    /** A valid crowd reading at the tower location (same carrier/network as the LTE tower). */
    private fun reading(
        dbm: Int = -85,
        lat: Double = towerLat,
        lng: Double = towerLng,
        mnc: Int = 45,
        networkType: NetworkType = NetworkType.LTE,
    ): SignalReading = SignalReading(
        deviceId = "dev1",
        tsEpochMs = 1_700_000_000_000L,
        lat = lat,
        lng = lng,
        locAccuracyM = 8.0,
        speedMps = 0.0,
        isMoving = false,
        mcc = 404,
        mnc = mnc,
        carrierName = "TestCarrier",
        networkType = networkType,
        signalDbm = dbm,
        rsrq = -10,
        sinr = 12,
        asu = 20,
        bars = 3,
        cellId = 12345L,
        tac = 1,
        pci = 2,
        earfcn = 1850,
        phoneMake = "TestMake",
        phoneModel = "TestModel",
        osVersion = "1.0",
        appVersion = "1.0",
        source = Source.CROWD,
    )

    @Test
    fun modeled_hexes_returned_when_no_crowd() {
        importer.importRows(
            listOf(TowerRow("LTE", 404, 45, 678, 12345L, towerLng, towerLat, 1500, 42)),
        )

        val hexes = repo.hexes(minLng, minLat, maxLng, maxLat)
        assertTrue(hexes.isNotEmpty(), "expected at least one modeled hex around the tower")
        assertTrue(hexes.all { it.source == Source.OPENCELLID }, "all hexes must be OPENCELLID when no crowd")
        assertTrue(hexes.all { it.sampleCount == 0 }, "modeled hexes carry no samples")
        assertTrue(
            hexes.all { it.confidence in 0.0..0.30 },
            "modeled confidence must be capped at 0.30, got ${hexes.map { it.confidence }}",
        )
    }

    @Test
    fun crowd_overrides_modeled_for_same_cell() {
        // Crowd reading at the tower location -> a CROWD hex at that exact cell.
        repo.ingest(listOf(reading(dbm = -85)))
        // Tower whose modeled patch covers that same cell.
        importer.importRows(
            listOf(TowerRow("LTE", 404, 45, 678, 12345L, towerLng, towerLat, 1500, 42)),
        )

        val hexes = repo.hexes(minLng, minLat, maxLng, maxLat)

        // No (h3, mcc, mnc, network) key appears twice -> crowd replaced, not duplicated.
        val keys = hexes.map { listOf(it.h3, it.mcc, it.mnc, it.networkType) }
        assertEquals(keys.size, keys.toSet().size, "no cell/carrier/network may appear twice")

        // The cell at the tower's own location is the crowd one.
        val crowdHexes = hexes.filter { it.source == Source.CROWD }
        assertEquals(1, crowdHexes.size, "exactly one crowd hex expected")
        val crowdHex = crowdHexes.single()
        assertTrue(crowdHex.sampleCount >= 1, "crowd hex must carry samples")

        // Other covered cells are still modeled.
        val modeled = hexes.filter { it.source == Source.OPENCELLID }
        assertTrue(modeled.isNotEmpty(), "neighboring cells should still be modeled")
        // The crowd cell must NOT also show up as a modeled hex.
        assertFalse(
            modeled.any { it.h3 == crowdHex.h3 && it.mcc == crowdHex.mcc && it.mnc == crowdHex.mnc && it.networkType == crowdHex.networkType },
            "the crowd-covered cell must not be re-emitted as modeled",
        )
    }

    @Test
    fun carrier_filter_applies_to_modeled() {
        importer.importRows(
            listOf(
                TowerRow("LTE", 404, 45, 678, 12345L, towerLng, towerLat, 1500, 42),
                TowerRow("LTE", 404, 10, 99, 22222L, towerLng, towerLat, 1500, 42),
            ),
        )

        val only45 = repo.hexes(minLng, minLat, maxLng, maxLat, mnc = 45)
        assertTrue(only45.isNotEmpty(), "expected modeled hexes for mnc 45")
        assertTrue(only45.all { it.mnc == 45 }, "carrier filter must drop mnc 10 modeled hexes")
        assertTrue(only45.all { it.source == Source.OPENCELLID })
    }

    @Test
    fun networkType_filter_applies_to_modeled() {
        importer.importRows(
            listOf(
                TowerRow("LTE", 404, 45, 678, 12345L, towerLng, towerLat, 1500, 42),
                TowerRow("NR", 404, 45, 12, 98765L, towerLng, towerLat, 1500, 42),
            ),
        )

        val onlyNr = repo.hexes(minLng, minLat, maxLng, maxLat, networkType = NetworkType.NR_SA)
        assertTrue(onlyNr.isNotEmpty(), "expected modeled NR_SA hexes")
        assertTrue(onlyNr.all { it.networkType == NetworkType.NR_SA }, "network filter must drop LTE modeled hexes")
        assertTrue(onlyNr.all { it.source == Source.OPENCELLID })
    }
}
