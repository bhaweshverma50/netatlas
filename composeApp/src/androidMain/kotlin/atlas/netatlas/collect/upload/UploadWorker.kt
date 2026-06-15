package atlas.netatlas.collect.upload

import android.content.Context
import androidx.room.Room
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import atlas.net.defaultApiClient
import atlas.netatlas.collect.db.CollectorDb

/**
 * Thin [CoroutineWorker] that drains the offline reading queue.
 *
 * All real logic lives in [ReadingUploader] (which is unit-tested directly). This
 * worker only wires up the Room database and the OkHttp-backed [ApiClient], runs
 * the drain, and maps its outcome onto a WorkManager [Result].
 */
class UploadWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val db = Room.databaseBuilder(
            applicationContext,
            CollectorDb::class.java,
            DB_NAME,
        ).build()
        return try {
            val uploader = ReadingUploader(
                dao = db.readingDao(),
                api = defaultApiClient(BASE_URL),
            )
            when (uploader.uploadPending()) {
                ReadingUploader.Outcome.UPLOADED,
                ReadingUploader.Outcome.EMPTY -> Result.success()
                ReadingUploader.Outcome.RETRY -> Result.retry()
            }
        } finally {
            db.close()
        }
    }

    companion object {
        /** Room database file shared with the collector's queue. */
        const val DB_NAME = "collector.db"

        /**
         * Emulator → host alias. Fine as a POC constant; M2.4/M3 can make this
         * configurable.
         */
        const val BASE_URL = "http://10.0.2.2:8080"

        private const val UNIQUE_WORK_NAME = "netatlas-upload"

        /** Enqueues a one-time upload that only runs once the device is online. */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
