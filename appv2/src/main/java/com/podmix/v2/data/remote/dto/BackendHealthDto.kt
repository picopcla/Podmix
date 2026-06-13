package com.podmix.v2.data.remote.dto

data class BackendHealthDto(
    val status: String,
    val message: String? = null
)
