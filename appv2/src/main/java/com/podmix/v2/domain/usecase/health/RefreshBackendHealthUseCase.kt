package com.podmix.v2.domain.usecase.health

import com.podmix.v2.domain.model.BackendHealth
import com.podmix.v2.domain.repository.BackendHealthRepository
import javax.inject.Inject

class RefreshBackendHealthUseCase @Inject constructor(
    private val repository: BackendHealthRepository
) {
    suspend operator fun invoke(): BackendHealth = repository.refresh()
}
