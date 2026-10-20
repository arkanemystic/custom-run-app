package dev.pacer.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets WHERE id = :id")
    suspend fun get(id: Long): PresetEntity?

    @Upsert
    suspend fun upsert(p: PresetEntity): Long

    @Query("DELETE FROM presets WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT COUNT(*) FROM presets")
    suspend fun count(): Int
}

@Dao
interface RunDao {
    @Insert
    suspend fun insert(r: RunEntity): Long

    @androidx.room.Update
    suspend fun update(r: RunEntity)

    @Query("SELECT * FROM runs WHERE id = :id")
    suspend fun get(id: Long): RunEntity?

    @Query("SELECT * FROM runs WHERE id = :id")
    fun observe(id: Long): Flow<RunEntity?>

    @Query("SELECT * FROM runs WHERE status = 'ACTIVE' ORDER BY id DESC LIMIT 1")
    suspend fun active(): RunEntity?

    /** Finished runs, including ones never explicitly saved (e.g. the app was closed on the summary). */
    @Query("SELECT * FROM runs WHERE status != 'ACTIVE' ORDER BY startWallMs DESC")
    fun observeHistory(): Flow<List<RunEntity>>

    @Query("SELECT COUNT(*) FROM runs WHERE startWallMs = :startWallMs")
    suspend fun countStartingAt(startWallMs: Long): Int

    @Query("DELETE FROM runs WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertPoints(points: List<PointEntity>)

    @Query("SELECT * FROM points WHERE runId = :runId ORDER BY id")
    suspend fun points(runId: Long): List<PointEntity>
}
