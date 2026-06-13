package com.podmix.v2.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "backend_health")
data class BackendHealthEntity(
    @PrimaryKey val endpoint: String,
    val status: String,
    val checkedAtEpochMillis: Long,
    val isReachable: Boolean,
    val message: String
)
