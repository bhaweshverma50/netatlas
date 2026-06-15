package atlas.netatlas.collect.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import atlas.model.NetworkType
import atlas.model.SignalReading
import atlas.model.Source
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class ReadingDaoTest {

    private lateinit var db: CollectorDb
    private lateinit var dao: ReadingDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, CollectorDb::class.java).build()
        dao = db.readingDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun reading(
        deviceId: String = "dev-1",
        tsEpochMs: Long = 1_700_000_000_000L,
        lat: Double = 28.6139,
        lng: Double = 77.2090,
        rsrq: Int? = -10,
        sinr: Int? = 12,
        asu: Int? = 30,
        cellId: Long? = 123456789L,
        tac: Int? = 4321,
        pci: Int? = 55,
        earfcn: Int? = 1850,
    ): SignalReading = SignalReading(
        deviceId = deviceId,
        tsEpochMs = tsEpochMs,
        lat = lat,
        lng = lng,
        locAccuracyM = 5.0,
        speedMps = 1.5,
        isMoving = true,
        mcc = 405,
        mnc = 861,
        carrierName = "Jio",
        networkType = NetworkType.LTE,
        signalDbm = -95,
        rsrq = rsrq,
        sinr = sinr,
        asu = asu,
        bars = 3,
        cellId = cellId,
        tac = tac,
        pci = pci,
        earfcn = earfcn,
        phoneMake = "Google",
        phoneModel = "Pixel 8",
        osVersion = "15",
        appVersion = "0.1.0",
        source = Source.CROWD,
    )

    @Test
    fun insert_and_count() = runTest {
        dao.insertAll(
            listOf(
                ReadingEntity.fromSignalReading(reading(deviceId = "a")),
                ReadingEntity.fromSignalReading(reading(deviceId = "b")),
                ReadingEntity.fromSignalReading(reading(deviceId = "c")),
            )
        )
        assertEquals(3, dao.count())
        assertEquals(3, dao.unsentCount())
    }

    @Test
    fun unsent_returns_in_id_order_and_respects_limit() = runTest {
        dao.insertAll((1..5).map { ReadingEntity.fromSignalReading(reading(deviceId = "d$it")) })

        val first3 = dao.unsent(3)
        assertEquals(3, first3.size)

        val ids = first3.map { it.id }
        assertEquals(ids.sorted(), ids)

        val all = dao.unsent(100)
        assertEquals(listOf("d1", "d2", "d3"), first3.map { it.deviceId })
        assertEquals(listOf("d1", "d2", "d3", "d4", "d5"), all.map { it.deviceId })
    }

    @Test
    fun markSent_clears_from_unsent() = runTest {
        dao.insertAll((1..3).map { ReadingEntity.fromSignalReading(reading(deviceId = "m$it")) })

        val all = dao.unsent(100)
        assertEquals(3, all.size)

        val firstTwoIds = all.take(2).map { it.id }
        dao.markSent(firstTwoIds)

        assertEquals(1, dao.unsentCount())
        val remaining = dao.unsent(100)
        assertEquals(1, remaining.size)
        assertEquals("m3", remaining.single().deviceId)
    }

    @Test
    fun deleteSent_removes_only_sent() = runTest {
        dao.insertAll((1..3).map { ReadingEntity.fromSignalReading(reading(deviceId = "x$it")) })

        val ids = dao.unsent(100).map { it.id }
        dao.markSent(ids.take(2))
        dao.deleteSent()

        assertEquals(1, dao.count())
        assertEquals(1, dao.unsentCount())
        assertEquals("x3", dao.unsent(100).single().deviceId)
    }

    @Test
    fun roundtrip_entity_preserves_fields() = runTest {
        val full = reading()
        val fullBack = ReadingEntity.fromSignalReading(full).toSignalReading()
        assertEquals(full, fullBack)

        val sparse = reading(
            rsrq = null,
            sinr = null,
            asu = null,
            cellId = null,
            tac = null,
            pci = null,
            earfcn = null,
        )
        val sparseBack = ReadingEntity.fromSignalReading(sparse).toSignalReading()
        assertEquals(sparse, sparseBack)
    }
}
