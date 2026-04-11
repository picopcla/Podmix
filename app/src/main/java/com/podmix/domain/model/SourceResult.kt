package com.podmix.domain.model

enum class SourceStatus { PENDING, RUNNING, SUCCESS, FAILED, SKIPPED }

data class SourceResult(
    val source: String,
    val status: SourceStatus,
    val trackCount: Int = 0,
    val elapsedMs: Long = 0,
    val reason: String = ""
)
