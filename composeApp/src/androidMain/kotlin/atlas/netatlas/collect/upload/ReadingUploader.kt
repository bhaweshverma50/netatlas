package atlas.netatlas.collect.upload

import atlas.model.ReadingBatch
import atlas.net.ApiClient
import atlas.netatlas.collect.db.ReadingDao

/**
 * Drains the offline reading queue to the backend in batches.
 *
 * Dependencies are injected so this can be unit-tested without any WorkManager
 * internals: an in-memory [ReadingDao] plus an [ApiClient] backed by a MockEngine.
 */
class ReadingUploader(
    private val dao: ReadingDao,
    private val api: ApiClient,
    private val batchSize: Int = 100,
) {
    enum class Outcome { UPLOADED, EMPTY, RETRY }

    /**
     * Repeatedly pulls up to [batchSize] unsent rows and POSTs them.
     *
     * - Returns [Outcome.EMPTY] if nothing was pending on the first pull.
     * - Returns [Outcome.RETRY] the moment a batch POST fails, leaving that batch
     *   (and everything after it) unsent so the worker can retry.
     * - Returns [Outcome.UPLOADED] once the queue is fully drained and at least one
     *   batch was sent.
     *
     * Rows are marked sent only after the server accepts their batch (HTTP 202).
     */
    suspend fun uploadPending(): Outcome {
        var sentAny = false
        while (true) {
            val rows = dao.unsent(batchSize)
            if (rows.isEmpty()) {
                return if (sentAny) Outcome.UPLOADED else Outcome.EMPTY
            }
            val batch = ReadingBatch(rows.map { it.toSignalReading() })
            val accepted = api.postReadings(batch)
            if (!accepted) {
                return Outcome.RETRY
            }
            dao.markSent(rows.map { it.id })
            sentAny = true
        }
    }
}
