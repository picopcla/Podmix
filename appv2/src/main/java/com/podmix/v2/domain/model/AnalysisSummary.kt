package com.podmix.v2.domain.model

data class AnalysisSummary(
    val status: AnalysisStatus,
    val timestampQuality: TimestampQuality,
    val trackCount: Int,
    val lastUpdatedEpochMillis: Long? = null
)
