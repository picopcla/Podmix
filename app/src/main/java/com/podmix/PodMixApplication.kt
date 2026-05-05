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
import com.podmix.service.EpisodeDownloadManager
import com.podmix.service.RefreshWorker
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class PodMixApplication : Application() {

    @Inject lateinit var downloadManager: EpisodeDownloadManager

    override fun onCreate() {
        super.onCreate()

        AppLogger.init(this)  // doit être AVANT tout le reste
        createNotificationChannel()
        schedulePeriodicRefresh()
        triggerImmediateRefresh()
        downloadManager.checkAndRefinePendingOnStartup()
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
