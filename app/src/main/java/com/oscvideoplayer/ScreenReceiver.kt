package com.oscvideoplayer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log

class ScreenReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ScreenReceiver"
        private var registered = false
        private var receiver: ScreenReceiver? = null

        fun register(context: Context) {
            if (registered) {
                Log.d(TAG, "Already registered, skipping")
                return
            }
            val r = ScreenReceiver()
            receiver = r
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            }
            try {
                context.applicationContext.registerReceiver(r, filter)
                registered = true
                Log.d(TAG, "ScreenReceiver registered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register: ${e.message}")
            }
        }

        fun unregister(context: Context) {
            if (!registered) return
            try {
                context.applicationContext.unregisterReceiver(receiver)
                Log.d(TAG, "ScreenReceiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister: ${e.message}")
            }
            registered = false
            receiver = null
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Screen event: ${intent.action}")

        when (intent.action) {
            Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                launchIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
                Log.d(TAG, "Started app from screen event")
            }
            Intent.ACTION_SCREEN_OFF -> {
                Log.d(TAG, "Screen off event")
            }
        }
    }
}
