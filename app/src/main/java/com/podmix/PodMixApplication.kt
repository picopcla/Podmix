package com.podmix

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.net.Network
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
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class PodMixApplication : Application() {

    @Inject lateinit var downloadManager: EpisodeDownloadManager
    @Inject lateinit var okHttpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()

        AppLogger.init(this)  // doit être AVANT tout le reste
        createNotificationChannel()
        registerNetworkChangeCallback()
        schedulePeriodicRefresh()
        triggerImmediateRefresh()
        downloadManager.checkAndRefinePendingOnStartup()
    }

    /**
     * Purges the OkHttp connection pool every time the default network changes
     * (Wi-Fi <-> cellular, SIM switch, captive portal re-auth, roaming).
     *
     * Without this, OkHttp keeps connections bound to a Network handle that
     * Android has invalidated. Subsequent requests fail with UnknownHostException
     * even though the OS-level DNS works fine, because Java's process-default
     * network still points at the dead Network. Evicting the pool forces every
     * future request to open a fresh socket on the current default network.
     */
    private fun registerNetworkChangeCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                okHttpClient.connectionPool.evictAll()
                AppLogger.net("Default network available → OkHttp pool evicted")
                Log.i("PodMixApp", "Network onAvailable($network) → pool evicted")
            }

            override fun onLost(network: Network) {
                okHttpClient.connectionPool.evictAll()
                AppLogger.net("Default network lost → OkHttp pool evicted")
                Log.i("PodMixApp", "Network onLost($network) → pool evicted")
            }
        })
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
