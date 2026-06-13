package atlas.backend.repo

import atlas.backend.db.Database
import atlas.model.NetworkType
import atlas.model.Source
import atlas.model.SignalReading
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

@Testcontainers
class ReadingRepositoryTest {

    companion object {
        @Container
        @JvmStatic
        // arm64-native PostGIS (official postgis/postgis is amd64-only → QEMU emulation on Apple Silicon).
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer(
            DockerImageName.parse("imresamu/postgis:16-3.4").asCompatibleSubstituteFor("postgres"),
        )
    }

    private lateinit var db: Database
    private lateinit var repo: ReadingRepository

    @BeforeEach
    fun setUp() {
        db = Database.connect(postgres.jdbcUrl, postgres.username, postgres.password)
        db.applyMigrations()
        repo = ReadingRepository(db)
        // Independence between tests: clear both tables.
        db.transaction {
            exec("TRUNCATE TABLE hex_aggregates, signal_readings RESTART IDENTITY")
        }
    }

    /** Builds a plausible, valid SignalReading. */
    private fun reading(
        dbm: Int,
        lat: Double = 12.9716,
        lng: Double = 77.5946,
        mnc: Int = 45,
        device: String = "dev1",
        networkType: NetworkType = NetworkType.LTE,
        rsrq: Int? = -10,
        sinr: Int? = 12,
    ): SignalReading = SignalReading(
        deviceId = device,
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
        rsrq = rsrq,
        sinr = sinr,
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
    fun ingest_then_aggregate_produces_one_hex() {
        val result = repo.ingest(listOf(reading(-90), reading(-100)))
        assertEquals(2, result.accepted)
        assertEquals(0, result.rejected)

        val hexes = repo.hexes(-180.0, -90.0, 180.0, 90.0)
        assertEquals(1, hexes.size)
        val hex = hexes.single()
        assertEquals(2, hex.sampleCount)
        assertEquals(-95.0, hex.meanDbm, 0.01)
    }

    @Test
    fun anomalous_readings_are_dropped() {
        // dbm=10 is out of the valid -140..-30 range and must be rejected.
        val result = repo.ingest(listOf(reading(-90), reading(10)))
        assertEquals(1, result.accepted)
        assertEquals(1, result.rejected)

        val hexes = repo.hexes(-180.0, -90.0, 180.0, 90.0)
        assertEquals(1, hexes.size)
        assertEquals(1, hexes.single().sampleCount)
    }

    @Test
    fun bbox_filters_out_distant_hexes() {
        repo.ingest(listOf(reading(-90)))

        // Bengaluru bbox includes the reading.
        val near = repo.hexes(77.0, 12.0, 78.0, 13.0)
        assertEquals(1, near.size)

        // A far-away bbox around (0,0) excludes it.
        val far = repo.hexes(-1.0, -1.0, 1.0, 1.0)
        assertTrue(far.isEmpty())
    }

    @Test
    fun carrier_filter_selects_one() {
        repo.ingest(listOf(reading(-90, mnc = 45), reading(-90, mnc = 10)))

        val all = repo.hexes(-180.0, -90.0, 180.0, 90.0)
        assertEquals(2, all.size)

        val only45 = repo.hexes(-180.0, -90.0, 180.0, 90.0, mnc = 45)
        assertEquals(1, only45.size)
        assertEquals(45, only45.single().mnc)
    }

    @Test
    fun median_and_coverage_class_are_computed() {
        repo.ingest(listOf(reading(-80), reading(-90), reading(-100)))

        val hexes = repo.hexes(-180.0, -90.0, 180.0, 90.0)
        assertEquals(1, hexes.size)
        val hex = hexes.single()
        assertEquals(3, hex.sampleCount)
        assertEquals(-90.0, hex.medianDbm, 0.01)
        // mean is -90.0 -> GOOD per Aggregation.classify.
        assertEquals(atlas.model.CoverageClass.GOOD, hex.coverageClass)
    }
}
