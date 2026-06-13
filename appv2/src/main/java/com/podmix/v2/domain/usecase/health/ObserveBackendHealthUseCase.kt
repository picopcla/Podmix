package com.podmix.v2.domain.usecase.health

import com.podmix.v2.domain.model.BackendHealth
import com.podmix.v2.domain.repository.BackendHealthRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObserveBackendHealthUseCase @Inject constructor(
    private val repository: BackendHealthRepository
) {
    operator fun invoke(): Flow<BackendHealth?> = repository.observe()
}
