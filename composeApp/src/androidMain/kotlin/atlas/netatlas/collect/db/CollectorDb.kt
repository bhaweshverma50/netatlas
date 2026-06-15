package atlas.netatlas.collect.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [ReadingEntity::class], version = 1, exportSchema = false)
abstract class CollectorDb : RoomDatabase() {
    abstract fun readingDao(): ReadingDao
}
