package com.podmix.v2.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.podmix.v2.data.local.dao.BackendHealthDao
import com.podmix.v2.data.local.entity.BackendHealthEntity

@Database(
    entities = [BackendHealthEntity::class],
    version = 1,
    exportSchema = false
)
abstract class PodmixV2Database : RoomDatabase() {
    abstract fun backendHealthDao(): BackendHealthDao
}
