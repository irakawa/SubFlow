package com.subflow.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "history")
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val detail: String,        // summary line
    val resultCount: Int,
    val method: String,        // which source produced it
    val timestamp: Long,
    val params: String = ""    // serialized params, used to re-run
)

// title is the natural key, so re-following just updates lastEpisode
@Entity(tableName = "favorites")
data class FavoriteEntry(
    @PrimaryKey val title: String,
    val type: String,
    val season: Int,
    val lastEpisode: Int,
    val targetLang: String,
    val format: String,
    val codec: String,
    val audio: String,
    val timestamp: Long
)

@Dao
interface FavoriteDao {
    @Query("SELECT * FROM favorites ORDER BY timestamp DESC")
    fun all(): Flow<List<FavoriteEntry>>

    @Query("SELECT * FROM favorites")
    suspend fun snapshot(): List<FavoriteEntry>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: FavoriteEntry)

    @Delete
    suspend fun delete(entry: FavoriteEntry)

    // atomic advance, only moves forward
    @Query("UPDATE favorites SET lastEpisode = :ep, timestamp = :ts WHERE title = :title AND lastEpisode < :ep")
    suspend fun advanceEpisode(title: String, ep: Int, ts: Long)
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY timestamp DESC LIMIT 50")
    fun all(): Flow<List<HistoryEntry>>

    // returns the new row id
    @Insert
    suspend fun insert(entry: HistoryEntry): Long

    @Delete
    suspend fun delete(entry: HistoryEntry)

    // keep only the most recent rows
    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY timestamp DESC LIMIT :keep)")
    suspend fun prune(keep: Int)
}

@Database(entities = [HistoryEntry::class, FavoriteEntry::class], version = 3, exportSchema = false)
abstract class SubFlowDb : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun favoriteDao(): FavoriteDao

    companion object {
        @Volatile private var instance: SubFlowDb? = null

        fun get(context: Context): SubFlowDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext, SubFlowDb::class.java, "subflow.db"
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
