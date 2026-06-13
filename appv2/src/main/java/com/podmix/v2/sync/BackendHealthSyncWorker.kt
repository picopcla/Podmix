package com.podmix.v2.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class BackendHealthSyncWorker(
    context: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(context, workerParameters) {
    override suspend fun doWork(): Result = Result.success()
}
