package com.example.rainwidget

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val p = Prefs(ctx)
        if (p.hasLocation) {
            try {
                val json = withContext(Dispatchers.IO) { Weather.fetchForecastJson(p.lat, p.lon) }
                Weather.parse(json, p.threshold) // 校验一下能解析
                p.lastJson = json
                p.lastFetch = System.currentTimeMillis()
                p.lastError = null
            } catch (e: Exception) {
                p.lastError = e.message ?: e.javaClass.simpleName
                RainWidget.updateAll(ctx)
                return if (runAttemptCount < 2) Result.retry() else Result.success()
            }
        }
        RainWidget.updateAll(ctx)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "rain_periodic"
        private const val NOW = "rain_now"

        private val network = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<RefreshWorker>(30, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun refreshNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<RefreshWorker>()
                .setConstraints(network)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(PERIODIC)
                cancelUniqueWork(NOW)
            }
        }
    }
}
