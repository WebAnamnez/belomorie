package com.belomorie.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * DAO для работы с таблицей trackings_local
 */
@Dao
interface TrackingDao {
    
    @Query("SELECT * FROM trackings_local ORDER BY created_at DESC")
    fun getAllTrackings(): Flow<List<TrackingEntity>>
    
    @Query("SELECT * FROM trackings_local WHERE status = :status ORDER BY created_at ASC")
    suspend fun getTrackingsByStatus(status: String): List<TrackingEntity>
    
    @Query("SELECT * FROM trackings_local WHERE id = :id")
    suspend fun getTrackingById(id: String): TrackingEntity?
    
    @Insert
    suspend fun insertTracking(tracking: TrackingEntity)
    
    @Update
    suspend fun updateTracking(tracking: TrackingEntity)
    
    @Delete
    suspend fun deleteTracking(tracking: TrackingEntity)
    
    @Query("DELETE FROM trackings_local WHERE status = 'sent' AND sent_at IS NOT NULL AND sent_at < :cutoffTime")
    suspend fun deleteOldSentTrackings(cutoffTime: Long) // Удаление записей старше 72 часов
    
    @Query("SELECT COUNT(*) FROM trackings_local WHERE status = 'pending'")
    suspend fun getPendingCount(): Int
    
    @Query("SELECT COUNT(*) FROM trackings_local")
    suspend fun getTotalCount(): Int

    @Query("SELECT * FROM trackings_local ORDER BY created_at DESC LIMIT 1")
    suspend fun getLastTracking(): TrackingEntity?
    
    @Query("SELECT * FROM trackings_local ORDER BY created_at DESC LIMIT :limit")
    fun getRecentTrackings(limit: Int): Flow<List<TrackingEntity>>
}






