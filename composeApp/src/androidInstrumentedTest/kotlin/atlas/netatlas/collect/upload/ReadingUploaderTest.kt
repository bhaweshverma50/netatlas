package atlas.netatlas.collect.upload

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import atlas.model.NetworkType
import atlas.model.SignalReading
import atlas.model.Source
import atlas.net.ApiClient
import atlas.netatlas.collect.db.CollectorDb
import atlas.netatlas.collect.db.ReadingDao
import atlas.netatlas.collect.db.ReadingEntity
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

@RunWith(AndroidJUnit4::class)
class ReadingUploaderTest {

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

    private fun reading(deviceId: String = "dev-1"): SignalReading = SignalReading(
        deviceId = deviceId,
        tsEpochMs = 1_700_000_000_000L,
        lat = 28.6139,
        lng = 77.2090,
        locAccuracyM = 5.0,
        speedMps = 1.5,
        isMoving = true,
        mcc = 405,
        mnc = 861,
        carrierName = "Jio",
        networkType = NetworkType.LTE,
        signalDbm = -95,
        rsrq = -10,
        sinr = 12,
        asu = 30,
        bars = 3,
        cellId = 123456789L,
        tac = 4321,
        pci = 55,
        earfcn = 1850,
        phoneMake = "Google",
        phoneModel = "Pixel 8",
        osVersion = "15",
        appVersion = "0.1.0",
        source = Source.CROWD,
    )

    private fun apiRespondingWith(
        status: HttpStatusCode,
        postCounter: AtomicInteger? = null,
    ): ApiClient {
        val engine = MockEngine {
            postCounter?.incrementAndGet()
            respond(
                content = ByteReadChannel("""{"accepted":0,"rejected":0}"""),
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val http = HttpClient(engine) {
            install(ContentNegotiation) { json() }
            expectSuccess = false
        }
        return ApiClient("http://10.0.2.2:8080", http)
    }

    @Test
    fun uploads_and_marks_sent_on_202() = runTest {
        dao.insertAll((1..3).map { ReadingEntity.fromSignalReading(reading("a$it")) })

        val uploader = ReadingUploader(dao, apiRespondingWith(HttpStatusCode.Accepted))
        val outcome = uploader.uploadPending()

        assertEquals(ReadingUploader.Outcome.UPLOADED, outcome)
        assertEquals(0, dao.unsentCount())
    }

    @Test
    fun retry_and_keeps_unsent_on_failure() = runTest {
        dao.insertAll((1..3).map { ReadingEntity.fromSignalReading(reading("b$it")) })

        val uploader = ReadingUploader(dao, apiRespondingWith(HttpStatusCode.InternalServerError))
        val outcome = uploader.uploadPending()

        assertEquals(ReadingUploader.Outcome.RETRY, outcome)
        assertEquals(3, dao.unsentCount())
    }

    @Test
    fun empty_queue_returns_empty() = runTest {
        val uploader = ReadingUploader(dao, apiRespondingWith(HttpStatusCode.Accepted))
        val outcome = uploader.uploadPending()

        assertEquals(ReadingUploader.Outcome.EMPTY, outcome)
    }

    @Test
    fun batches_large_queue() = runTest {
        dao.insertAll((1..250).map { ReadingEntity.fromSignalReading(reading("c$it")) })

        val postCount = AtomicInteger(0)
        val uploader = ReadingUploader(
            dao,
            apiRespondingWith(HttpStatusCode.Accepted, postCount),
            batchSize = 100,
        )
        val outcome = uploader.uploadPending()

        assertEquals(ReadingUploader.Outcome.UPLOADED, outcome)
        assertEquals(0, dao.unsentCount())
        assertEquals(3, postCount.get(), "250 rows / 100 per batch = 3 POSTs")
    }
}
