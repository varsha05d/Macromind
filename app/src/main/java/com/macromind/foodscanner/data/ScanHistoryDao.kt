package com.macromind.foodscanner.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * ScanHistoryDao — Room DAO for scan history CRUD.
 *
 * All queries return Flow for reactive UI updates.
 * Insert/delete are suspend for coroutine usage.
 */
@Dao
interface ScanHistoryDao {

    /** All scans, newest first */
    @Query("SELECT * FROM scan_history ORDER BY timestamp DESC")
    fun getAll(): Flow<List<ScanHistoryEntity>>

    /** Total scan count (for badge on MainActivity) */
    @Query("SELECT COUNT(*) FROM scan_history")
    fun getCount(): Flow<Int>

    /** Insert a new scan result */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ScanHistoryEntity)

    /** Delete a single scan */
    @Delete
    suspend fun delete(entity: ScanHistoryEntity)

    /** Delete a scan by ID */
    @Query("DELETE FROM scan_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Clear all history */
    @Query("DELETE FROM scan_history")
    suspend fun deleteAll()
}
