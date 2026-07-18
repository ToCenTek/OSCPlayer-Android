package com.oscvideoplayer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

class OSCService : Service() {

    companion object {
        private const val TAG = "OSCService"
        private var isRunning = false
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "OSCServiceChannel"
    }

    private var oscServer: OSCServer? = null
    private var videoScanner: VideoScanner? = null

    override fun onCreate() {
        super.onCreate()
        if (isRunning) {
            Log.d(TAG, "Already running")
            return
        }
        isRunning = true
        Log.d(TAG, "OSC Service created")

        createNotificationChannel()

        videoScanner = VideoScanner(this)
        startOSCServer()

        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.w(TAG, "Foreground failed: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "OSC播放器服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "用于接收远程OSC控制命令"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle("OSC播放器")
            .setContentText("服务运行中, 端口8000")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pendingIntent)
            .setOngoing(false)
            .build()
    }

    private fun startOSCServer() {
        OSCServer.setVideoScanner(videoScanner)
        oscServer = OSCServer(8000, this, videoScanner)
        oscServer?.start()
        Log.d(TAG, "OSC Server started on port 8000")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")

        if (!isAppRunning()) {
            try {
                val mainIntent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(mainIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start MainActivity: ${e.message}")
            }
        }

        return START_STICKY
    }

    private fun isAppRunning(): Boolean {
        return try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val tasks = am.appTasks
            tasks.isNotEmpty()
        } catch (e: Exception) {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val processes = am.runningAppProcesses
                processes?.any { it.processName == packageName } == true
            } catch (e2: Exception) {
                Log.e(TAG, "Cannot check running state: ${e2.message}")
                false
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        oscServer?.stop()
        Log.d(TAG, "OSC Service destroyed")
    }
}
