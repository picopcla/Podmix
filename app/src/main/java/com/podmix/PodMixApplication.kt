package com.podmix

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.podmix.service.RefreshWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class PodMixApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        schedulePeriodicRefresh()
        triggerImmediateRefresh()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                RefreshWorker.CHANNEL_ID,
                "Nouveaux \u00e9pisodes",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications de nouveaux \u00e9pisodes et sets"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun schedulePeriodicRefresh() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodicWork = PeriodicWorkRequestBuilder<RefreshWorker>(
            6, TimeUnit.HOURS,
            30, TimeUnit.MINUTES // flex interval
        )
            .setConstraints(constraints)
            .addTag("podmix_periodic_refresh")
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "podmix_periodic_refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWork
        )
        Log.i("PodMixApp", "Periodic refresh scheduled every 6 hours")
    }

    private fun triggerImmediateRefresh() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val oneTimeWork = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(constraints)
            .addTag("podmix_startup_refresh")
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            "podmix_startup_refresh",
            ExistingWorkPolicy.REPLACE,
            oneTimeWork
        )
        Log.i("PodMixApp", "Immediate refresh triggered")
    }
}
