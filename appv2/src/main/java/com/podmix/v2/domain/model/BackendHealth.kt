package com.podmix.v2.domain.model

data class BackendHealth(
    val status: String,
    val endpoint: String,
    val checkedAtEpochMillis: Long,
    val isReachable: Boolean,
    val message: String
)
