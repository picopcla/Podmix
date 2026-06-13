package com.podmix.v2.domain.repository

import com.podmix.v2.domain.model.BackendHealth
import kotlinx.coroutines.flow.Flow

interface BackendHealthRepository {
    fun observe(): Flow<BackendHealth?>
    suspend fun refresh(): BackendHealth
}
