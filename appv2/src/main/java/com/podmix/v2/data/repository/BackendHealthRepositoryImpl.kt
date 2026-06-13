package com.podmix.v2.data.repository

import com.podmix.v2.core.config.BackendConfig
import com.podmix.v2.core.logging.AppLogger
import com.podmix.v2.data.local.dao.BackendHealthDao
import com.podmix.v2.data.local.entity.BackendHealthEntity
import com.podmix.v2.data.local.mapper.BackendHealthMapper
import com.podmix.v2.data.remote.api.PodmixBackendApi
import com.podmix.v2.domain.model.BackendHealth
import com.podmix.v2.domain.repository.BackendHealthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackendHealthRepositoryImpl @Inject constructor(
    private val api: PodmixBackendApi,
    private val dao: BackendHealthDao,
    private val mapper: BackendHealthMapper,
    private val logger: AppLogger,
    private val backendConfig: BackendConfig
) : BackendHealthRepository {

    override fun observe(): Flow<BackendHealth?> = dao.observe().map { entity ->
        entity?.let(mapper::toDomain)
    }

    override suspend fun refresh(): BackendHealth {
        val endpoint = backendConfig.baseUrl.removeSuffix("/")
        val now = System.currentTimeMillis()

        val entity = try {
            mapper.fromDto(
                dto = api.getHealth(),
                checkedAtEpochMillis = now,
                endpoint = endpoint
            )
        } catch (throwable: Throwable) {
            logger.warn("BackendHealthRepository", "Health refresh failed", throwable)
            BackendHealthEntity(
                endpoint = endpoint,
                status = "offline",
                checkedAtEpochMillis = now,
                isReachable = false,
                message = throwable.message ?: "Unable to reach backend"
            )
        }

        dao.upsert(entity)
        return mapper.toDomain(entity)
    }
}
