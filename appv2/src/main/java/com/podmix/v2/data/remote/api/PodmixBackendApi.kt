package com.podmix.v2.data.remote.api

import com.podmix.v2.data.remote.dto.BackendHealthDto
import retrofit2.http.GET

interface PodmixBackendApi {
    @GET("health")
    suspend fun getHealth(): BackendHealthDto
}
