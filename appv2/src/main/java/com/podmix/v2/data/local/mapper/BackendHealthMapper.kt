package com.podmix.v2.data.local.mapper

import com.podmix.v2.data.local.entity.BackendHealthEntity
import com.podmix.v2.data.remote.dto.BackendHealthDto
import com.podmix.v2.domain.model.BackendHealth
import javax.inject.Inject

class BackendHealthMapper @Inject constructor() {
    fun fromDto(dto: BackendHealthDto, checkedAtEpochMillis: Long, endpoint: String): BackendHealthEntity =
        BackendHealthEntity(
            endpoint = endpoint,
            status = dto.status,
            checkedAtEpochMillis = checkedAtEpochMillis,
            isReachable = dto.status.equals("ok", ignoreCase = true),
            message = dto.message ?: "Backend reachable"
        )

    fun toDomain(entity: BackendHealthEntity): BackendHealth =
        BackendHealth(
            status = entity.status,
            endpoint = entity.endpoint,
            checkedAtEpochMillis = entity.checkedAtEpochMillis,
            isReachable = entity.isReachable,
            message = entity.message
        )
}
