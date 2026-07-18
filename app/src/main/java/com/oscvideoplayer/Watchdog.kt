package com.oscvideoplayer

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.Player
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class Watchdog(private val playerProvider: () -> Player?) {

    companion object {
        private const val TAG = "Watchdog"
        private const val CHECK_INTERVAL_MS = 15_000L
        private const val MAX_STALL_TIME_MS = 30_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val isRunning = AtomicBoolean(false)
    private val lastPosition = AtomicLong(-1L)
    private val lastPositionTime = AtomicLong(System.currentTimeMillis())
    private var stallCount = 0
    private var recoveryAttempts = 0
    private var onRecovery: (() -> Unit)? = null

    private val checkRunnable = object : Runnable {
        override fun run() {
            if (!isRunning.get()) return
            try {
                checkHealth()
            } catch (e: Exception) {
                Log.e(TAG, "Health check error: ${e.message}")
            }
            handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    fun start(onRecovery: (() -> Unit)? = null) {
        if (isRunning.getAndSet(true)) return
        this.onRecovery = onRecovery
        handler.post(checkRunnable)
        Log.d(TAG, "Watchdog started")
    }

    fun stop() {
        isRunning.set(false)
        handler.removeCallbacks(checkRunnable)
        stallCount = 0
        recoveryAttempts = 0
        Log.d(TAG, "Watchdog stopped")
    }

    fun ping() {
        lastPositionTime.set(System.currentTimeMillis())
    }

    fun resetStallDetection() {
        lastPosition.set(-1L)
        stallCount = 0
    }

    private fun checkHealth() {
        val player = playerProvider() ?: return
        val now = System.currentTimeMillis()

        try {
            val state = player.playbackState
            val position = player.currentPosition
            val isPlaying = player.isPlaying

            when (state) {
                Player.STATE_BUFFERING -> {
                    val idleTime = now - lastPositionTime.get()
                    if (idleTime > MAX_STALL_TIME_MS) {
                        stallCount++
                        Log.w(TAG, "Stall detected #$stallCount: buffering for ${idleTime}ms")
                        if (stallCount >= 3) {
                            Log.e(TAG, "Too many stalls, attempting recovery")
                            attemptRecovery(player)
                        }
                    }
                }
                Player.STATE_READY -> {
                    if (isPlaying) {
                        val prevPos = lastPosition.get()
                        if (prevPos >= 0 && abs(position - prevPos) < 100) {
                            val idleTime = now - lastPositionTime.get()
                            if (idleTime > MAX_STALL_TIME_MS) {
                                stallCount++
                                Log.w(TAG, "Stall detected #$stallCount: position stuck at ${position}ms")
                                if (stallCount >= 3) {
                                    attemptRecovery(player)
                                }
                            }
                        } else {
                            lastPosition.set(position)
                            lastPositionTime.set(now)
                            stallCount = 0
                        }
                    }
                    stallCount = 0
                }
                Player.STATE_IDLE, Player.STATE_ENDED -> {
                    stallCount = 0
                }
            }

            recoveryAttempts = 0
        } catch (e: Exception) {
            Log.e(TAG, "Health check exception: ${e.message}")
        }
    }

    private fun attemptRecovery(player: Player) {
        if (recoveryAttempts >= 2) {
            Log.e(TAG, "Recovery failed after $recoveryAttempts attempts, triggering full recovery")
            handler.post {
                onRecovery?.invoke()
            }
            return
        }

        recoveryAttempts++
        Log.w(TAG, "Recovery attempt #$recoveryAttempts: stopping and re-preparing")

        try {
            val pos = player.currentPosition
            player.stop()
            player.prepare()
            player.seekTo(pos)
            player.play()
            stallCount = 0
            lastPositionTime.set(System.currentTimeMillis())
        } catch (e: Exception) {
            Log.e(TAG, "Recovery attempt failed: ${e.message}")
        }
    }
}
