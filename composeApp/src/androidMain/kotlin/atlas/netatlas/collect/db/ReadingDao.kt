package atlas.netatlas.collect.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadingDao {
    @Insert
    suspend fun insertAll(rows: List<ReadingEntity>)

    /** Live total reading count, for the "Your contributions: N" UI line. */
    @Query("SELECT COUNT(*) FROM queued_readings")
    fun countFlow(): Flow<Int>

    @Query("SELECT * FROM queued_readings WHERE sent = 0 ORDER BY id ASC LIMIT :limit")
    suspend fun unsent(limit: Int): List<ReadingEntity>

    @Query("UPDATE queued_readings SET sent = 1 WHERE id IN (:ids)")
    suspend fun markSent(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM queued_readings")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM queued_readings WHERE sent = 0")
    suspend fun unsentCount(): Int

    @Query("DELETE FROM queued_readings WHERE sent = 1")
    suspend fun deleteSent()
}
