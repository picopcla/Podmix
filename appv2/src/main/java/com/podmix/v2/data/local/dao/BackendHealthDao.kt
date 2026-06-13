package com.podmix.v2.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.podmix.v2.data.local.entity.BackendHealthEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BackendHealthDao {
    @Query("SELECT * FROM backend_health LIMIT 1")
    fun observe(): Flow<BackendHealthEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: BackendHealthEntity)
}
