package com.oscvideoplayer

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class BootWorker(appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {

    companion object {
        private const val TAG = "BootWorker"
        private const val WORK_NAME = "oscplayer_boot_check"
        private const val DEFAULT_INTERVAL_MIN = 30L

        fun schedule(context: Context, intervalMinutes: Long = DEFAULT_INTERVAL_MIN) {
            val actualInterval = intervalMinutes.coerceAtLeast(15L) // WorkManager min 15min
            val workRequest = PeriodicWorkRequestBuilder<BootWorker>(actualInterval, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                workRequest
            )
            Log.d(TAG, "Scheduled periodic health check every ${actualInterval}min")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.d(TAG, "Cancelled periodic health check")
        }
    }

    override fun doWork(): Result {
        Log.d(TAG, "Health check running")

        if (isAppRunning()) {
            Log.d(TAG, "App is already running, skipping start")
            return Result.success()
        }

        Log.d(TAG, "App not running, starting MainActivity")
        try {
            val intent = Intent(applicationContext, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra("from_boot", true)
            }
            applicationContext.startActivity(intent)
            Log.d(TAG, "MainActivity started from worker")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MainActivity: ${e.message}")
        }

        return Result.success()
    }

    private fun isAppRunning(): Boolean {
        return try {
            val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = am.appTasks
            tasks.isNotEmpty()
        } catch (e: Exception) {
            try {
                val am = applicationContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val runningApps = am.runningAppProcesses
                runningApps?.any { it.processName == applicationContext.packageName } == true
            } catch (e2: Exception) {
                Log.e(TAG, "Cannot check running state: ${e2.message}")
                false
            }
        }
    }
}
