package com.podmix.v2.domain.usecase.health

import com.podmix.v2.domain.model.BackendHealth
import com.podmix.v2.domain.repository.BackendHealthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class ObserveBackendHealthUseCaseTest {
    @Test
    fun `returns repository flow unchanged`() = runTest {
        val expected = BackendHealth(
            status = "ok",
            endpoint = "https://podmix.mb4.fr",
            checkedAtEpochMillis = 42L,
            isReachable = true,
            message = "Backend reachable"
        )
        val repository = object : BackendHealthRepository {
            override fun observe(): Flow<BackendHealth?> = flowOf(expected)
            override suspend fun refresh(): BackendHealth = expected
        }

        val result = ObserveBackendHealthUseCase(repository).invoke().first()

        assertEquals(expected, result)
    }
}
