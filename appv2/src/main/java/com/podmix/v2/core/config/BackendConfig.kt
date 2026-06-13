package com.podmix.v2.core.config

import com.podmix.v2.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackendConfig @Inject constructor() {
    val baseUrl: String = BuildConfig.BACKEND_BASE_URL
}
