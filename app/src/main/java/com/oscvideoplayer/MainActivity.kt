package com.oscvideoplayer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.util.DisplayMetrics
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

@UnstableApi
class MainActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var isInitialized = false
    private var oscServer: OSCServer? = null
    private var videoScanner: VideoScanner? = null
    private var playlistManager = PlaylistManager()
    private var watchdog: Watchdog? = null
    @Volatile
    private var currentVideoPath: String? = null
    private var isLoopEnabled = true
    private var videoFrameRate = 30.0
    private var videoDuration = 0L
    @Volatile
    private var playbackSpeed = 1.0f
    @Volatile
    private var cachedIsPlaying = false
    private var switchingVideo = false
    @Volatile
    private var cachedPosition = 0L
    @Volatile
    private var cachedVolume = 0f
    private var savedVolume = 0f
    private var debugOverlayEnabled = false

    private lateinit var prefs: SharedPreferences
    private var nsdManager: NsdManager? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null
    private var pendingVideoPath: String? = null
    private var scheduleJob: Job? = null
    private var powerScheduleJob: Job? = null
    private var screenReceiver: ScreenReceiver? = null
    private var scheduleStartTime: String? = null
    private var httpUploadServer: HttpUploadServer? = null
    private var scheduleStopTime: String? = null
    private var powerOnTime: String? = null
    private var powerOffTime: String? = null
    private var powerShutdownTime: String? = null
    private var powerRebootTime: String? = null
    private var alignmentJob: kotlinx.coroutines.Job? = null
    private var speedRecoveryJob: kotlinx.coroutines.Job? = null

    companion object {
        private const val TAG = "MainActivity"
        private const val PREFS_NAME = "OSCVideoPlayer"
        private const val KEY_LAST_VIDEO = "last_video_path"
        private const val KEY_LOOP_ENABLED = "loop_enabled"
        private const val KEY_DEFAULT_DIR = "default_directory"
        private const val KEY_SCHEDULE_START = "schedule_start"
        private const val KEY_SCHEDULE_STOP = "schedule_stop"
        private const val KEY_POWER_ON = "power_on_time"
        private const val KEY_POWER_OFF = "power_off_time"
        private const val KEY_POWER_SHUTDOWN = "power_shutdown_time"
        private const val KEY_POWER_REBOOT = "power_reboot_time"
        private const val KEY_SURFACE_MODE = "surface_mode"
        private const val NSD_SERVICE_TYPE = "_osc._udp."
        private const val NSD_SERVICE_PORT = 8000
        @Volatile
        var activeVideoPath: String? = null
        val interceptedKeys = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS,
            KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3,
            KeyEvent.KEYCODE_F4, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6,
            KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8, KeyEvent.KEYCODE_F9,
            KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12,
            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_VOLUME_MUTE
        )
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            startApp(pendingVideoPath)
        } else {
            Toast.makeText(this, "需要存储权限才能扫描视频", Toast.LENGTH_LONG).show()
            startApp(pendingVideoPath)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemUI()
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        playerView = findViewById(R.id.playerView)
        if (playerView == null) {
            inflatePlayerView()
            playerView = findViewById(R.id.playerView)
        }

        volumeControlStream = android.media.AudioManager.STREAM_MUSIC

        val isFromBoot = intent.getBooleanExtra("from_boot", false)
        Log.d(TAG, "onCreate: isFromBoot=$isFromBoot")

        val videoPath = intent.getStringExtra("video_path")
        pendingVideoPath = videoPath

        requestPermissions(videoPath)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val videoPath = intent.getStringExtra("video_path")
        OSCServer.setCallback(this)
        if (videoPath != null && File(videoPath).exists()) {
            playAndAddToPlaylist(videoPath)
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    private fun requestPermissions(playVideoPath: String? = null) {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissions.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                permissions.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        if (permissions.isNotEmpty()) {
            requestPermissionLauncher.launch(permissions.toTypedArray())
        } else {
            startApp(playVideoPath)
        }
    }

    private fun inflatePlayerView() {
        val container = findViewById<android.widget.FrameLayout>(com.oscvideoplayer.R.id.playerContainer) ?: return
        val useSurface = prefs?.getBoolean(KEY_SURFACE_MODE, true) ?: true
        val layoutRes = if (useSurface) com.oscvideoplayer.R.layout.player_view_surface
                       else com.oscvideoplayer.R.layout.player_view_texture
        container.removeAllViews()
        android.view.LayoutInflater.from(this).inflate(layoutRes, container, true)
    }

    private fun startApp(playVideoPath: String? = null) {
        if (isInitialized) { Log.d(TAG, "startApp skipped, already initialized"); return }
        isInitialized = true

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val keepaliveAlarm = prefs?.getInt("keepalive_alarm_seconds", 60) ?: 60
        val keepaliveWm = prefs?.getLong("keepalive_workmanager_minutes", 30L) ?: 30L
        BootWorker.schedule(this, keepaliveWm)
        AlarmReceiver.schedule(this, keepaliveAlarm * 1000L)

        videoScanner = VideoScanner(this).also {
            it.watchDirectory(File(getDefaultDirectory()))
        }

        startService(Intent(this, OSCService::class.java))
        OSCServer.setCallback(this)

        registerNsdService()

        startHttpUploadServer()

        tryAutoSetDefaultLauncher()

        isLoopEnabled = prefs.getBoolean(KEY_LOOP_ENABLED, true)

        scheduleStartTime = prefs.getString(KEY_SCHEDULE_START, null)
        scheduleStopTime = prefs.getString(KEY_SCHEDULE_STOP, null)
        powerOnTime = prefs.getString(KEY_POWER_ON, null)
        powerOffTime = prefs.getString(KEY_POWER_OFF, null)
        powerShutdownTime = prefs.getString(KEY_POWER_SHUTDOWN, null)
        powerRebootTime = prefs.getString(KEY_POWER_REBOOT, null)

        startScheduleChecker()
        startPowerScheduleChecker()

        requestBatteryOptimizationSilent()

        pendingVideoPath = null

        val videoToPlay = playVideoPath
        if (videoToPlay != null && File(videoToPlay).exists()) {
            playVideo(videoToPlay)
        } else {
            autoPlayHelloVideo()
        }
    }

    private fun requestBatteryOptimizationSilent() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Log.d(TAG, "Battery optimization already disabled")
            } else {
                Log.d(TAG, "Battery optimization is enabled, can't auto-disable without user prompt")
            }
        }
    }

    private fun registerNsdService() {
        try {
            nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
            val hostAddress = getLocalIPAddress() ?: return
            val nsdServiceName = "Android OSCPlayer - ${Build.MODEL}"

            nsdRegistrationListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "NSD registered: ${serviceInfo.serviceName}")
                }
                override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "NSD registration failed: $errorCode")
                }
                override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                    Log.d(TAG, "NSD unregistered")
                }
                override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e(TAG, "NSD unregistration failed: $errorCode")
                }
            }

            val serviceInfo = NsdServiceInfo().apply {
                serviceName = nsdServiceName
                serviceType = NSD_SERVICE_TYPE
                port = NSD_SERVICE_PORT
                host = java.net.InetAddress.getByName(hostAddress)
            }
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdRegistrationListener)
            Log.d(TAG, "NSD: $nsdServiceName on $hostAddress:$NSD_SERVICE_PORT")
        } catch (e: Exception) {
            Log.e(TAG, "NSD error: ${e.message}")
        }
    }

    fun getVideoKeyframes(path: String): Triple<Long, Long, Double>? {
        return try {
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(path)
            val trackCount = extractor.trackCount
            val videoTrack = (0 until trackCount).firstOrNull { i ->
                try { extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true } catch (_: Exception) { false }
            } ?: run { extractor.release(); return null }
            extractor.selectTrack(videoTrack)
            val format = extractor.getTrackFormat(videoTrack)
            var fps = try { if (format.containsKey(android.media.MediaFormat.KEY_FRAME_RATE)) format.getFloat(android.media.MediaFormat.KEY_FRAME_RATE).toDouble() else 0.0 } catch (_: Exception) { 0.0 }
            if (fps <= 0.0) fps = getFrameRate(path)
            var kfTimes = mutableListOf<Long>()
            var sampleCount = 0
            while (extractor.advance()) {
                sampleCount++
                try {
                    val flags = extractor.sampleFlags
                    val time = extractor.sampleTime
                    if (flags and android.media.MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        kfTimes.add(time / 1000)
                    }
                } catch (_: Exception) {}
            }
            extractor.release()
            if (kfTimes.size < 1) return null
            val firstKf = kfTimes[0]
            if (firstKf <= 0) return null
            // filter out trailing zero timestamps (Amlogic platform bug)
            while (kfTimes.size > 1 && kfTimes.last() == 0L) kfTimes.removeAt(kfTimes.lastIndex)
            val lastKf = kfTimes.last()
            Triple(firstKf, lastKf, fps)
        } catch (_: Exception) { null }
    }

    // --- Alignment ---
    private var alignmentTargetTime = 0L
    private var alignmentSeekPos = 0L
    private var alignmentRendered = false
    private var alignmentSeeked = false
    private var alignmentSeekedPos = 0L
    private var alignmentOnReady: ((Long, String) -> Unit)? = null

    fun alignmentPrepare(index: Int, kfMs: Long, onReady: (Long, String) -> Unit) {
        // reset speed tracking
        speedRecoveryJob?.cancel()
        if (playbackSpeed != 1.0f) {
            playbackSpeed = 1.0f
            runOnUiThread { player?.setPlaybackSpeed(1.0f) }
        }
        alignmentSeekPos = kfMs
        alignmentRendered = false
        alignmentSeeked = false
        alignmentOnReady = onReady

        val items = getPlaylistItems()
        val item = items.getOrNull(index) ?: run { onReady(0L, "invalid index"); return }
        val path = item["path"] as? String ?: run { onReady(0L, "no path"); return }
        playVideo(path)
    }

    private fun onAlignmentRendered() {
        Log.d(TAG, "onAlignmentRendered: cb=" + (alignmentOnReady != null))
        if (alignmentOnReady == null) return
        alignmentRendered = true
        player?.pause()
        player?.seekTo(alignmentSeekPos)
    }

    private fun onAlignmentSeeked(posMs: Long) {
        val cb = alignmentOnReady ?: return
        alignmentSeeked = true
        alignmentSeekedPos = posMs
        val dur = player?.duration ?: 0L
        val durStr = String.format("%02d:%02d.%03d", dur / 60000, (dur % 60000) / 1000, dur % 1000)
        cb(posMs, durStr)
    }

    fun alignmentPlay() {
        alignmentOnReady = null
        runOnUiThread { player?.play() }
    }

    private fun hb(eventName: String) {
        val p = player
        val isPaused = p?.isPlaying == false && p?.playbackState == Player.STATE_READY
        val isStopped = p?.playbackState == Player.STATE_IDLE
        val name = currentVideoPath?.substringAfterLast("/") ?: ""
        val pos = p?.currentPosition ?: cachedPosition
        val dur = p?.duration ?: 0L
        val fps = p?.videoFormat?.frameRate?.toDouble() ?: videoFrameRate
        com.oscvideoplayer.OSCServer.getInstance()?.sendHeartbeat(eventName, isPaused, isStopped, name, pos, dur, fps)
    }

    fun getLocalIPAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addresses = intf.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getLocalIPAddress: ${e.message}")
        }
        return null
    }

    fun getMacAddress(): String {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                var hasV4 = false
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a is java.net.Inet4Address) { hasV4 = true; break }
                }
                if (hasV4) {
                    val mac = intf.hardwareAddress ?: continue
                    return mac.joinToString(":") { "%02x".format(it) }
                }
            }
        } catch (_: Exception) {}
        return "unknown"
    }

    private fun unregisterNsdService() {
        try {
            nsdRegistrationListener?.let { nsdManager?.unregisterService(it) }
        } catch (e: Exception) {
            Log.e(TAG, "NSD unregister error: ${e.message}")
        }
    }

    private fun saveLastVideo(path: String) {
        prefs.edit().putString(KEY_LAST_VIDEO, path).apply()
    }

    private fun getLastVideoPath(): String? = prefs.getString(KEY_LAST_VIDEO, null)

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        } else {
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            )
        }
    }

    private fun autoPlayHelloVideo() {
        lifecycleScope.launch(Dispatchers.IO) {
            processPendingPlaylist()

            val loopPrefs = getSharedPreferences("loop", Context.MODE_PRIVATE)
            val loopVideo = loopPrefs.getString("video", null)
            if (loopPrefs.getBoolean("enabled", false) && loopVideo != null && File(loopVideo).exists()) {
                loopPrefs.edit().clear().apply()
                setLoop(true)
                setPlaylistMode("1")
                clearPlaylist()
                addToPlaylist(loopVideo)
                withContext(Dispatchers.Main) { playVideo(loopVideo) }
                return@launch
            }

            val videos = videoScanner?.scanAllVideos() ?: return@launch
            // Rebuild playlist from scanned videos
            playlistManager.clear()
            playlistManager.addAll(videos.map { it.path })
            Log.d(TAG, "Playlist rebuilt: ${videos.size} videos")

            val lastVideo = getLastVideoPath()
            val lastVideoExists = lastVideo != null && File(lastVideo).exists()
            val helloVideo = videos.find { it.name.lowercase().startsWith("hello.") }

            withContext(Dispatchers.Main) {
                val videoToPlay = when {
                    lastVideoExists -> {
                        playlistManager.setCurrentByPath(lastVideo!!)
                        lastVideo
                    }
                    helloVideo != null -> {
                        playlistManager.setCurrentByPath(helloVideo.path)
                        helloVideo.path
                    }
                    videos.isNotEmpty() -> {
                        playlistManager.jumpTo(0)
                        videos.first().path
                    }
                    else -> null
                }
                if (videoToPlay != null) playVideo(videoToPlay)
            }
        }
    }

    private fun processPendingPlaylist() {
        val prefs = getSharedPreferences("playlist", Context.MODE_PRIVATE)
        val pending = prefs.getStringSet("pending", null) ?: return
        prefs.edit().remove("pending").apply()
        val scanner = videoScanner ?: return
        val allVideos = scanner.scanAllVideos()
        for (path in pending) {
            if (allVideos.any { it.path == path }) {
                playlistManager.add(path)
            }
        }
        if (playlistManager.size > 0) {
            val first = playlistManager.currentItem
            if (first != null) {
                val firstPath = first.path
                runOnUiThread { playVideo(firstPath) }
            }
        }
    }

    private fun initializePlayer(): ExoPlayer {
        if (player == null) {
            val hevcSelector = object : MediaCodecSelector {
                override fun getDecoderInfos(
                    mimeType: String,
                    requiresSecureDecoder: Boolean,
                    requiresTunneling: Boolean
                ): List<MediaCodecInfo> {
                    val defaultInfos = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunneling)
                    if (mimeType == MimeTypes.VIDEO_H265) {
                        try {
                            val patched = patchAmlogicHevcDecoder(defaultInfos)
                            if (patched != null) return patched
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to patch Amlogic HEVC decoder", e)
                        }
                    }
                    return defaultInfos
                }
            }

            val renderersFactory = DefaultRenderersFactory(this)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(hevcSelector)

            player = ExoPlayer.Builder(this, renderersFactory)
                .setHandleAudioBecomingNoisy(true)
                .build()
                .apply {
                    repeatMode = if (isLoopEnabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    setPlaybackSpeed(playbackSpeed)

                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            cachedIsPlaying = player?.isPlaying == true
                            cachedPosition = player?.currentPosition ?: 0L

                            if (playbackState == Player.STATE_ENDED) {
                                if (!switchingVideo && !isLoopEnabled) {
                                    val next = playlistManager.next()
                                    if (next != null) {
                                        playVideo(next.path)
                                    } else {
                                        autoPlayHelloVideo()
                                    }
                                }
                            }
                            if (playbackState == Player.STATE_READY) {
                                watchdog?.ping()
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            Log.e(TAG, "Player error: code=${error.errorCode} msg=${error.message}")
                            recoverFromError()
                        }

                        override fun onIsPlayingChanged(isPlaying: Boolean) {
                            cachedIsPlaying = isPlaying
                            if (isPlaying) watchdog?.ping()
                        }

                        override fun onPlaybackParametersChanged(parms: androidx.media3.common.PlaybackParameters) {
                            playbackSpeed = parms.speed
                        }

                         override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                             Log.d(TAG, "VideoSize: ${videoSize.width}x${videoSize.height} unapplied=${videoSize.unappliedRotationDegrees}")
                         }

                          override fun onRenderedFirstFrame() {
                              onAlignmentRendered()
                              hb("onRenderedFirstFrame")
                          }

                         override fun onPositionDiscontinuity(
                             oldPosition: androidx.media3.common.Player.PositionInfo,
                             newPosition: androidx.media3.common.Player.PositionInfo,
                             reason: Int
                          ) {
                               if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                                   onAlignmentSeeked(newPosition.positionMs)
                               }
                               val reasonStr = when (reason) {
                                   Player.DISCONTINUITY_REASON_SEEK -> "onPositionDiscontinuity(SEEK)"
                                   Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "onPositionDiscontinuity(AUTO)"
                                   Player.DISCONTINUITY_REASON_SKIP -> "onPositionDiscontinuity(SKIP)"
                                   Player.DISCONTINUITY_REASON_REMOVE -> "onPositionDiscontinuity(REMOVE)"
                                   Player.DISCONTINUITY_REASON_INTERNAL -> "onPositionDiscontinuity(INTERNAL)"
                                   Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "onPositionDiscontinuity(SEEK_ADJ)"
                                   else -> "onPositionDiscontinuity($reason)"
                               }
                               hb(reasonStr)
                         }
                     })
                }
            playerView?.player = player
            startWatchdog()
            startPositionUpdater()
        }
        return player!!
    }

    private fun patchAmlogicHevcDecoder(infos: List<MediaCodecInfo>): List<MediaCodecInfo>? {
        for (info in infos) {
            if (info.name.contains("OMX.amlogic.hevc.decoder.awesome")) {
                val videoCaps = info.capabilities?.videoCapabilities ?: continue
                var modified = false
                for (field in videoCaps::class.java.declaredFields) {
                    if (field.type != Int::class.javaPrimitiveType) continue
                    field.isAccessible = true
                    when (field.name) {
                        "mMaxWidth", "maxWidth" -> {
                            if (field.getInt(videoCaps) < 7680) {
                                field.setInt(videoCaps, 7680)
                                modified = true
                            }
                        }
                        "mMaxHeight", "maxHeight" -> {
                            if (field.getInt(videoCaps) < 4320) {
                                field.setInt(videoCaps, 4320)
                                modified = true
                            }
                        }
                        "mMaxPixels", "maxPixels" -> {
                            if (field.getInt(videoCaps) < 33177600) {
                                field.setInt(videoCaps, 33177600)
                            }
                        }
                    }
                }
                if (modified) {
                    val mutable = infos.toMutableList()
                    mutable.remove(info)
                    mutable.add(0, info)
                    return mutable
                }
            }
        }
        return null
    }

    private fun startHttpUploadServer() {
        try {
            val dir = getDefaultDirectory()
            httpUploadServer = HttpUploadServer(
                port = 8080,
                uploadDir = dir,
                videoListProvider = { videoScanner?.scanAllVideos() ?: emptyList() },
                playProvider = { path -> runOnUiThread { playAndAddToPlaylist(path) } },
                getVideoInfoProvider = { path -> getFileInfo(path) },
                togglePlayPauseProvider = { runOnUiThread { togglePlayPause() } },
                isPlayingProvider = { cachedIsPlaying },
                currentVideoPathProvider = { activeVideoPath }
            )
            httpUploadServer?.start()
            Log.d(TAG, "HttpUploadServer started on port 8080, dir=$dir")
        } catch (e: Exception) {
            Log.w(TAG, "HttpUploadServer start failed: ${e.message}")
        }
    }

    private fun getFileInfo(path: String): Map<String, Any> {
        val file = File(path)
        val retriever = android.media.MediaMetadataRetriever()
        val info = mutableMapOf<String, Any>("path" to path, "name" to file.name, "size" to file.length())
        try {
            retriever.setDataSource(path)
            info["duration"] = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            info["width"] = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            info["height"] = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            info["frameRate"] = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull() ?: 0.0
            info["mime"] = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: ""
            info["bitrate"] = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0
            info["isExternal"] = com.oscvideoplayer.FileManager.isExternalPath(path)
        } catch (_: Exception) {}
        retriever.release()
        return info
    }

    fun getHttpServerUrl(): String {
        val ip = try {
            val en = java.net.NetworkInterface.getNetworkInterfaces()
            var found: String? = null
            while (en.hasMoreElements()) {
                val intf = en.nextElement()
                if (intf.isLoopback || !intf.isUp) continue
                val addrs = intf.inetAddresses ?: continue
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is java.net.Inet4Address) {
                        found = a.hostAddress ?: continue
                        if (!found!!.startsWith("127.")) break
                    }
                }
                if (found != null && !found!!.startsWith("127.")) break
            }
            found ?: "127.0.0.1"
        } catch (_: Exception) { "127.0.0.1" }
        return "http://$ip:8080"
    }

    private var positionUpdaterJob: Job? = null

    private fun startPositionUpdater() {
        positionUpdaterJob?.cancel()
        positionUpdaterJob = lifecycleScope.launch {
            while (isActive) {
                val p = player
                if (p != null) {
                    cachedPosition = p.currentPosition
                    cachedIsPlaying = p.isPlaying
                    cachedVolume = p.volume
                    // periodic heartbeat for sync monitoring
                    hb("periodic")
                    try {
                        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        cachedVolume = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat()
                    } catch (_: Exception) {}
                }
                if (debugOverlayEnabled) {
                    val vs = p?.videoSize
                    val fmt = p?.videoFormat
                    val w = vs?.width ?: 0
                    val h = vs?.height ?: 0
                    val fps = fmt?.frameRate ?: 0f
                    val state = when (p?.playbackState) {
                        Player.STATE_IDLE -> "IDLE"
                        Player.STATE_BUFFERING -> "BUFF"
                        Player.STATE_READY -> if (cachedIsPlaying) "PLAY" else "PAUS"
                        Player.STATE_ENDED -> "DONE"
                        else -> "?"
                    }
                    val text = "$state\nRendering resolution: ${w}x${h}\nRendering frame rate: ${"%.2f".format(fps)}fps"
                    findViewById<android.widget.TextView>(com.oscvideoplayer.R.id.debugOverlay)?.text = text
                }
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun recoverFromError() {
        val path = currentVideoPath
        player?.stop()
        player?.clearMediaItems()
        player?.release()
        player = null

        if (path != null) {
            Log.d(TAG, "Recovering player for: $path")
            initializePlayer()
            try {
                val mediaItem = MediaItem.fromUri(Uri.fromFile(File(path)))
                player?.setMediaItem(mediaItem)
                player?.prepare()
                player?.play()
            } catch (e: Exception) {
                Log.e(TAG, "Recovery failed: ${e.message}")
                autoPlayHelloVideo()
            }
        }
    }

    fun playVideo(path: String) {
        runOnUiThread {
            switchingVideo = true
            currentVideoPath = path
            activeVideoPath = path
            saveLastVideo(path)
            Log.d(TAG, "playVideo: $path")

            initializePlayer()
            val exoPlayer = player ?: run { switchingVideo = false; return@runOnUiThread }

            videoFrameRate = getFrameRate(path)

            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            val mediaItem = MediaItem.fromUri(Uri.fromFile(File(path)))
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.setPlaybackSpeed(playbackSpeed)
            exoPlayer.prepare()
            exoPlayer.play()

            watchdog?.resetStallDetection()
            hideSystemUI()
            switchingVideo = false
        }
    }

    private fun getFrameRate(path: String): Double {
        return try {
            val extractor = android.media.MediaExtractor()
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull { i ->
                try { extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true } catch (_: Exception) { false }
            }
            val fps = if (track != null) {
                val fmt = extractor.getTrackFormat(track)
                if (fmt.containsKey(android.media.MediaFormat.KEY_FRAME_RATE))
                    try { fmt.getFloat(android.media.MediaFormat.KEY_FRAME_RATE).toDouble() } catch (_: Exception) { 0.0 }
                else 0.0
            } else 0.0
            extractor.release()
            if (fps > 0.0) return fps
            // fallback: metadata
            val r = android.media.MediaMetadataRetriever()
            r.setDataSource(path)
            val fps2 = r.extractMetadata(30)?.toDoubleOrNull() ?: r.extractMetadata(27)?.toDoubleOrNull() ?: 0.0
            r.release()
            fps2
        } catch (_: Exception) { 0.0 }
    }

    fun setVideoFrameRate(fps: Double) { videoFrameRate = fps }
    fun getVideoFrameRate(): Double = videoFrameRate

    fun pauseVideo() = runOnUiThread { player?.pause(); cachedIsPlaying = false }
    fun resumeVideo() = runOnUiThread { player?.play(); cachedIsPlaying = true }
    fun togglePlayPause() {
        if (cachedIsPlaying) pauseVideo() else resumeVideo()
    }

    fun togglePause() {
        runOnUiThread {
            val p = player
            if (p?.isPlaying == true) { p.pause(); cachedIsPlaying = false }
            else { p?.play(); cachedIsPlaying = true }
        }
    }

    fun isPaused(): Boolean { return !cachedIsPlaying }

    fun stopVideo() {
        runOnUiThread {
            player?.stop()
            player?.clearMediaItems()
        }
    }

    fun stopAtFrame(frameNumber: Int) {
        runOnUiThread {
            val p = player ?: return@runOnUiThread
            val fps = videoFrameRate.coerceAtLeast(1.0)
            val positionMs = ((frameNumber - 1) / fps * 1000).toLong().coerceAtLeast(0)
            p.seekTo(positionMs)
            p.pause()
        }
    }

    fun stopAtTime(seconds: Float) {
        runOnUiThread {
            val p = player ?: return@runOnUiThread
            p.seekTo((seconds * 1000).toLong())
            p.pause()
        }
    }

    fun seekToTime(seconds: Float) {
        runOnUiThread {
            player?.seekTo((seconds * 1000).toLong())
        }
    }

    fun seekToMs(ms: Long) {
        runOnUiThread {
            val p = player ?: return@runOnUiThread
            val playAfter = p.isPlaying
            p.seekTo(ms)
            if (playAfter) p.play() else p.pause()
            cachedIsPlaying = playAfter
        }
    }

    fun setVolume(volume: Float) {
        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val targetVol = volume.toInt().coerceIn(0, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC))
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVol, 0)
    }

    fun toggleMute() {
        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val currentVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        if (currentVol > 0) {
            savedVolume = currentVol.toFloat()
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
        } else {
            val targetVol = savedVolume.toInt().coerceIn(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC))
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVol, 0)
        }
    }

    fun isMuted(): Boolean {
        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        return am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) == 0
    }

    fun mute() {
        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        savedVolume = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat()
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 0, 0)
    }

    fun unmute() {
        val am = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val targetVol = savedVolume.toInt().coerceIn(1, am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC))
        am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, targetVol, 0)
    }

    fun setLoop(enabled: Boolean) {
        isLoopEnabled = enabled
        prefs.edit().putBoolean(KEY_LOOP_ENABLED, enabled).apply()
        runOnUiThread {
            player?.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        }
    }

    fun toggleDebugOverlay() {
        debugOverlayEnabled = !debugOverlayEnabled
        runOnUiThread {
            findViewById<android.widget.TextView>(com.oscvideoplayer.R.id.debugOverlay)?.visibility =
                if (debugOverlayEnabled) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    fun isDebugOverlayOn() = debugOverlayEnabled

    fun setPlaybackSpeed(speed: Float, autoRecoverMs: Long = 0L) {
        speedRecoveryJob?.cancel()
        playbackSpeed = speed.coerceIn(0.25f, 4.0f)
        runOnUiThread { player?.setPlaybackSpeed(playbackSpeed) }
        if (autoRecoverMs > 0 && speed != 1.0f) {
            speedRecoveryJob = lifecycleScope.launch {
                delay(autoRecoverMs)
                if (playbackSpeed != 1.0f) {
                    playbackSpeed = 1.0f
                    player?.setPlaybackSpeed(1.0f)
                }
            }
        }
    }

    fun getPlaybackSpeed(): Float = playbackSpeed

    fun getFPS(): Double = videoFrameRate.coerceAtLeast(1.0)

    fun getPosition(): Long = player?.currentPosition ?: 0L
    fun getDuration(): Long = player?.duration ?: 0L

    fun getVideoList(): List<VideoScanner.VideoItem> {
        return videoScanner?.scanAllVideos() ?: emptyList()
    }

    fun getVideoInfo(): Map<String, Any> {
        var width = 0; var height = 0; var duration = 0L
        var frameRate = 30.0; var fileSize = 0L; var videoBitrate = 0
        var audioSampleRate = 0; var channelCount = 2
        val filename = currentVideoPath?.substringAfterLast("/") ?: ""

        // Use ExoPlayer's rendered size first (accurate on all devices)
        player?.let { p ->
            val vs = p.videoSize
            if (vs != null && vs.width > 0 && vs.height > 0) {
                width = vs.width
                height = vs.height
            }
        }

        currentVideoPath?.let { path ->
            try {
                val file = File(path)
                if (file.exists()) fileSize = file.length()
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(path)
                if (width == 0) {
                    width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                }
                duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toDoubleOrNull() ?: 30.0
                videoBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull() ?: 0
                audioSampleRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_SAMPLERATE)?.toIntOrNull() ?: 0
                retriever.release()
            } catch (e: Exception) {
                Log.e(TAG, "getVideoInfo error: ${e.message}")
            }
        }

        return mapOf(
            "filename" to filename,
            "fileSize" to fileSize,
            "width" to width,
            "height" to height,
            "duration" to duration,
            "frameRate" to frameRate,
            "videoBitrate" to videoBitrate,
            "audioSampleRate" to audioSampleRate,
            "channelCount" to channelCount,
            "isPlaying" to cachedIsPlaying,
            "currentPosition" to cachedPosition,
            "volume" to cachedVolume,
            "speed" to playbackSpeed,
            "loop" to isLoopEnabled
        )
    }

    fun getDisplayInfo(): Map<String, Any> {
        val dm = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(dm)
        val display = windowManager.defaultDisplay
        val hdrTypes = if (Build.VERSION.SDK_INT >= 24) {
            try { display.hdrCapabilities?.supportedHdrTypes?.joinToString(",") } catch (_: Exception) { null }
        } else null
        val vs = player?.videoSize
        return mapOf<String, Any>(
            "displayWidth" to dm.widthPixels,
            "displayHeight" to dm.heightPixels,
            "refreshRate" to display.refreshRate,
            "hdrTypes" to (hdrTypes ?: "none"),
            "colorMode" to (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) window.colorMode else 0),
            "renderWidth" to (vs?.width ?: 0),
            "renderHeight" to (vs?.height ?: 0)
        )
    }

    fun setColorMode(mode: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            window.colorMode = mode
        }
    }

    fun deleteVideo(path: String): Boolean {
        return try { File(path).delete() } catch (e: Exception) { false }
    }

    fun showText(text: String, fontSize: Int, position: Int) {
        runOnUiThread {
            val textView = findViewById<TextView>(R.id.overlayText)
            if (text.isEmpty() || text == "0") {
                textView.visibility = View.GONE
            } else {
                textView.text = text
                textView.textSize = fontSize.toFloat()
                textView.visibility = View.VISIBLE
                val gravity = when (position) {
                    1 -> android.view.Gravity.TOP or android.view.Gravity.START
                    2 -> android.view.Gravity.TOP or android.view.Gravity.END
                    3 -> android.view.Gravity.BOTTOM or android.view.Gravity.START
                    4 -> android.view.Gravity.BOTTOM or android.view.Gravity.END
                    else -> android.view.Gravity.CENTER
                }
                textView.gravity = gravity
            }
        }
    }

    fun setSubtitle(path: String?) {
        runOnUiThread {
            val exoPlayer = player ?: return@runOnUiThread
            val currentPath = currentVideoPath ?: return@runOnUiThread

            if (path == null || path == "0" || path == "off") {
                clearSubtitle(exoPlayer, currentPath)
                return@runOnUiThread
            }

            val subtitleFile = File(path)
            if (!subtitleFile.exists()) {
                Log.w(TAG, "Subtitle file not found: $path")
                return@runOnUiThread
            }

            val subtitleUri = Uri.fromFile(subtitleFile)
            val baseItem = MediaItem.fromUri(Uri.fromFile(File(currentPath)))
            val subtitleConfig = MediaItem.SubtitleConfiguration.Builder(subtitleUri)
                .setMimeType("text/x-microdvd")
                .setLanguage("und")
                .build()
            val mediaItem = baseItem.buildUpon()
                .setSubtitleConfigurations(listOf(subtitleConfig))
                .build()

            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.play()
        }
    }

    private fun clearSubtitle(exoPlayer: ExoPlayer, path: String) {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        val mediaItem = MediaItem.fromUri(Uri.fromFile(File(path)))
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.play()
    }

    // --- Playlist ---
    fun addToPlaylist(path: String) {
        playlistManager.add(path)
        if (playlistManager.size == 1) playVideo(path)
    }

    fun playAndAddToPlaylist(path: String) {
        if (!playlistManager.setCurrentByPath(path)) {
            playlistManager.add(path)
        }
        playVideo(path)
    }

    fun removeFromPlaylist(index: Int) {
        playlistManager.remove(index)
    }

    fun clearPlaylist() { playlistManager.clear() }

    fun rebuildPlaylist() {
        lifecycleScope.launch(Dispatchers.IO) {
            val videos = videoScanner?.scanAllVideos() ?: return@launch
            val paths = videos.map { it.path }
            withContext(Dispatchers.Main) {
                playlistManager.clear()
                playlistManager.addAll(paths)
                Log.d(TAG, "Playlist rebuilt: ${videos.size} videos")
            }
        }
    }

    fun playNext() {
        if (playlistManager.size == 0) { Toast.makeText(this, "无播放列表", Toast.LENGTH_SHORT).show(); return }
        val next = playlistManager.next()
        if (next != null) playVideo(next.path)
    }

    fun playPrevious() {
        if (playlistManager.size == 0) { Toast.makeText(this, "无播放列表", Toast.LENGTH_SHORT).show(); return }
        val prev = playlistManager.previous()
        if (prev != null) playVideo(prev.path)
    }

    fun jumpToPlaylistIndex(index: Int) {
        val item = playlistManager.jumpTo(index)
        if (item != null) playVideo(item.path)
    }

    fun setPlaylistMode(mode: String) {
        playlistManager.setRepeatModeFromString(mode)
    }

    fun getPlaylistMode(): String = playlistManager.repeatMode.name.lowercase()

    fun getPlaylistItems(): List<Map<String, Any>> = playlistManager.getPlaylistSnapshot()

    fun getPlaylistStatus(): Map<String, Any> = mapOf(
        "index" to playlistManager.currentItemIndex,
        "size" to playlistManager.size,
        "mode" to getPlaylistMode()
    )

    // --- Subtitle ---
    private var subtitlePath: String? = null

    // --- Default Directory ---
    fun getDefaultDirectory(): String {
        return prefs.getString(KEY_DEFAULT_DIR, null)
            ?: Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath
    }

    fun setDefaultDirectory(path: String) {
        prefs.edit().putString(KEY_DEFAULT_DIR, path).apply()
        videoScanner?.invalidateCache()
        videoScanner?.watchDirectory(File(path))
        rebuildPlaylist()
        Log.d(TAG, "Default directory set to: $path, playlist rebuilt")
    }

    fun reloadVideos() {
        videoScanner?.invalidateCache()
        rebuildPlaylist()
        Log.d(TAG, "Video cache cleared, playlist rebuilt")
    }

    fun getKeepaliveAlarm(): Int = prefs?.getInt("keepalive_alarm_seconds", 60) ?: 60

    fun setKeepaliveAlarm(seconds: Int) {
        val s = seconds.coerceIn(5, 600)
        prefs?.edit()?.putInt("keepalive_alarm_seconds", s)?.apply()
        AlarmReceiver.schedule(this, s * 1000L)
        Log.d(TAG, "Keepalive alarm set to ${s}s")
    }

    fun getKeepaliveWorkmanager(): Long = prefs?.getLong("keepalive_workmanager_minutes", 30L) ?: 30L

    fun setKeepaliveWorkmanager(minutes: Long) {
        val m = minutes.coerceIn(15, 1440)
        prefs?.edit()?.putLong("keepalive_workmanager_minutes", m)?.apply()
        BootWorker.schedule(this, m)
        Log.d(TAG, "Keepalive workmanager set to ${m}min")
    }

    fun restartPlayer() {
        runOnUiThread {
            val path = currentVideoPath ?: return@runOnUiThread
            player?.stop()
            player?.clearMediaItems()
            player?.setMediaItem(androidx.media3.common.MediaItem.fromUri(android.net.Uri.fromFile(java.io.File(path))))
            player?.prepare()
            player?.seekTo(0)
            player?.play()
        }
    }

    // --- Watchdog ---
    fun watchdogStart() {
        if (watchdog == null) {
            watchdog = Watchdog { player }
            watchdog?.start { recoverFromError() }
        }
    }

    fun watchdogStop() {
        watchdog?.stop()
        watchdog = null
    }

    fun watchdogPing() {
        watchdog?.ping()
    }

    private fun startWatchdog() {
        if (watchdog == null) {
            watchdog = Watchdog { player }
            watchdog?.start { recoverFromError() }
        }
    }

    // --- Schedule ---
    private fun startScheduleChecker() {
        scheduleJob?.cancel()
        scheduleJob = lifecycleScope.launch {
            while (isActive) {
                checkSchedule()
                delay(30_000L)
            }
        }
    }

    private suspend fun checkSchedule() {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        scheduleStartTime?.let { time ->
            val parts = time.split(":")
            if (parts.size == 2) {
                val target = parts[0].toIntOrNull()?.times(60)?.plus(parts[1].toIntOrNull() ?: 0) ?: return
                if (currentMinutes == target) {
                    withContext(Dispatchers.Main) {
                        autoPlayHelloVideo()
                    }
                }
            }
        }

        scheduleStopTime?.let { time ->
            val parts = time.split(":")
            if (parts.size == 2) {
                val target = parts[0].toIntOrNull()?.times(60)?.plus(parts[1].toIntOrNull() ?: 0) ?: return
                if (currentMinutes == target) {
                    withContext(Dispatchers.Main) {
                        stopVideo()
                    }
                }
            }
        }
    }

    fun scheduleStart(time: String) {
        scheduleStartTime = time
        prefs.edit().putString(KEY_SCHEDULE_START, time).apply()
        Log.d(TAG, "Schedule start set to $time")
        // if time matches current minute, execute immediately
        kotlinx.coroutines.MainScope().launch { checkSchedule() }
    }

    fun scheduleStop(time: String) {
        scheduleStopTime = time
        prefs.edit().putString(KEY_SCHEDULE_STOP, time).apply()
        Log.d(TAG, "Schedule stop set to $time")
        kotlinx.coroutines.MainScope().launch { checkSchedule() }
    }

    fun scheduleClear() {
        scheduleStartTime = null
        scheduleStopTime = null
        prefs.edit()
            .remove(KEY_SCHEDULE_START)
            .remove(KEY_SCHEDULE_STOP)
            .apply()
        Log.d(TAG, "Schedule cleared")
    }

    fun getScheduleStatus(): String {
        return buildString {
            scheduleStartTime?.let { append("start=$it ") }
            scheduleStopTime?.let { append("stop=$it ") }
            if (isEmpty()) append("no schedule")
        }
    }

    // --- Power Management ---
    private fun startPowerScheduleChecker() {
        powerScheduleJob?.cancel()
        powerScheduleJob = lifecycleScope.launch {
            while (isActive) {
                checkPowerSchedule()
                delay(60_000L)
            }
        }
    }

    private suspend fun checkPowerSchedule() {
        val now = Calendar.getInstance()
        val currentMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        powerOnTime?.let { time ->
            val parts = time.split(":")
            if (parts.size == 2) {
                val target = parts[0].toIntOrNull()?.times(60)?.plus(parts[1].toIntOrNull() ?: 0) ?: return
                if (currentMinutes == target) powerOn()
            }
        }

        powerOffTime?.let { time ->
            val parts = time.split(":")
            if (parts.size == 2) {
                val target = parts[0].toIntOrNull()?.times(60)?.plus(parts[1].toIntOrNull() ?: 0) ?: return
                if (currentMinutes == target) powerOff()
            }
        }

        powerShutdownTime?.let { time ->
            val parts = time.split(":")
            if (parts.size == 2) {
                val target = parts[0].toIntOrNull()?.times(60)?.plus(parts[1].toIntOrNull() ?: 0) ?: return
                if (currentMinutes == target) {
                    powerShutdownTime = null
                    Thread { Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot -p")) }.start()
                }
            }
        }

        powerRebootTime?.let { time ->
            val parts = time.split(":")
            if (parts.size == 2) {
                val target = parts[0].toIntOrNull()?.times(60)?.plus(parts[1].toIntOrNull() ?: 0) ?: return
                if (currentMinutes == target) {
                    powerRebootTime = null
                    Thread { Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot")) }.start()
                }
            }
        }
    }

    private fun suExec(cmd: String): Boolean = try {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.waitFor() == 0
    } catch (_: Exception) { false }

    fun powerOn() {
        try {
            player?.play()
            if (!suExec("echo on 0 > /sys/class/cec/cmd")) {
                suExec("echo 0 > /sys/class/graphics/fb0/blank")
            }
            Log.d(TAG, "Display power ON")
        } catch (e: Exception) {
            Log.e(TAG, "Power on failed: ${e.message}")
        }
    }

    fun powerOff() {
        try {
            player?.pause()
            if (!suExec("echo standby 0 > /sys/class/cec/cmd")) {
                suExec("echo 1 > /sys/class/graphics/fb0/blank")
            }
            Log.d(TAG, "Display power OFF")
        } catch (e: Exception) {
            Log.e(TAG, "Power off failed: ${e.message}")
        }
    }

    fun schedulePowerOn(time: String) {
        powerOnTime = time
        prefs.edit().putString(KEY_POWER_ON, time).apply()
        Log.d(TAG, "Power ON scheduled at $time")
    }

    fun schedulePowerOff(time: String) {
        powerOffTime = time
        prefs.edit().putString(KEY_POWER_OFF, time).apply()
        Log.d(TAG, "Power OFF scheduled at $time")
    }

    fun schedulePowerShutdown(time: String) {
        powerShutdownTime = time
        prefs.edit().putString(KEY_POWER_SHUTDOWN, time).apply()
        Log.d(TAG, "Shutdown scheduled at $time")
    }

    fun schedulePowerReboot(time: String) {
        powerRebootTime = time
        prefs.edit().putString(KEY_POWER_REBOOT, time).apply()
        Log.d(TAG, "Reboot scheduled at $time")
    }

    fun schedulePowerClear() {
        powerOnTime = null
        powerOffTime = null
        prefs.edit().remove(KEY_POWER_ON).remove(KEY_POWER_OFF).apply()
        Log.d(TAG, "Power schedule cleared")
    }

    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && event.action == KeyEvent.ACTION_DOWN) {
            val kc = event.keyCode
            if (interceptedKeys.contains(kc))
                return onKeyDown(kc, event)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val step = 10000L
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { playPrevious(); true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { playNext(); true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                player?.let { it.seekTo(maxOf(0L, it.currentPosition - step)) }
                Toast.makeText(this, "<< 10s", Toast.LENGTH_SHORT).show(); true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                player?.let { it.seekTo(minOf(it.duration, it.currentPosition + step)) }
                Toast.makeText(this, ">> 10s", Toast.LENGTH_SHORT).show(); true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePause(); true }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { resumeVideo(); true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { pauseVideo(); true }
            KeyEvent.KEYCODE_MEDIA_STOP -> { stopVideo(); true }
            // MENU 开关
            KeyEvent.KEYCODE_MENU -> { toggleMenu(::showMainMenu); true }
            // SETTINGS 开关 (适配各种遥控器)
            KeyEvent.KEYCODE_SETTINGS, 176, KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2,
            KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6,
            KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8, KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F10,
            KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12 -> { toggleMenu(::showSettingsMenu); true }
            KeyEvent.KEYCODE_INFO -> { openAboutActivity(); true }
            KeyEvent.KEYCODE_VOLUME_MUTE -> { toggleMute(); true }
            KeyEvent.KEYCODE_HOME -> { returnToSystemLauncher(); true }
            KeyEvent.KEYCODE_BACK -> { finish(); true }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onBackPressed() { finish() }

    private fun toggleMenu(show: () -> Unit) {
        if (menuDialog?.isShowing == true) dismissMenuDialog() else show()
    }

    fun returnToSystemLauncher() {
        try {
            val ourPkg = packageName
            val homes = packageManager.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0
            )
            val realLauncher = homes.firstOrNull { it.activityInfo.packageName != ourPkg }
            if (realLauncher != null) {
                startActivity(Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    `package` = realLauncher.activityInfo.packageName
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Return to launcher failed: ${e.message}")
        }
    }

    private fun showMenuWithWrap(title: String, items: Array<String>, onItemClick: (Int) -> Unit) {
        val adapter = object : android.widget.ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, items) {
            override fun getView(pos: Int, cv: android.view.View?, parent: android.view.ViewGroup): android.view.View {
                val v = super.getView(pos, cv, parent) as android.widget.TextView
                v.setTextColor(0xFFE0E0E0.toInt())
                v.setHintTextColor(0xFF888888.toInt())
                v.gravity = android.view.Gravity.CENTER_VERTICAL
                v.setPadding(32, 18, 32, 18)
                v.textSize = 16f
                v.ellipsize = android.text.TextUtils.TruncateAt.END
                v.maxLines = 1
                return v
            }
        }

        val listView = android.widget.ListView(this)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, pos, _ -> dismissMenuDialog(); onItemClick(pos) }
        listView.divider = android.graphics.drawable.ColorDrawable(0x22FFFFFF.toInt())
        listView.dividerHeight = 1
        listView.setPadding(0, 4, 0, 4)
        listView.setBackgroundColor(0x4C1A1A1A.toInt())
        listView.setSelector(android.graphics.drawable.ColorDrawable(0x44FFCC00.toInt()))
        listView.setOnKeyListener { v, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                val list = v as android.widget.ListView
                val pos = list.selectedItemPosition
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        val next = if (pos <= 0) list.count - 1 else pos - 1
                        list.setSelection(next); return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        val next = if (pos >= list.count - 1) 0 else pos + 1
                        list.setSelection(next); return@setOnKeyListener true
                    }
                    KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> {
                        dismissMenuDialog(); return@setOnKeyListener true
                    }
                }
            }
            false
        }

        val titleView = android.widget.TextView(this).apply {
            text = title
            setTextColor(0xFFE6B800.toInt())
            textSize = 20f
            gravity = android.view.Gravity.CENTER
            setPadding(0, 28, 0, 12)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(titleView, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(listView, android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        menuDialog = android.app.Dialog(this).apply {
            setContentView(root)
            val w = resources.displayMetrics.widthPixels * 20 / 100
            window?.setLayout(w, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x4C1A1A1A.toInt()))
            window?.setGravity(android.view.Gravity.CENTER)
            window?.setDimAmount(0f)
            setCanceledOnTouchOutside(false)
            setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (keyCode) {
                        KeyEvent.KEYCODE_MENU -> { toggleMenu(::showMainMenu); true }
                        KeyEvent.KEYCODE_SETTINGS, 176,
                        KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3,
                        KeyEvent.KEYCODE_F4, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6,
                        KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8, KeyEvent.KEYCODE_F9,
                        KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12 -> {
                            toggleMenu(::showSettingsMenu); true
                        }
                        else -> false
                    }
                } else false
            }
            show()
        }
        listView.post { listView.requestFocus(); listView.setSelection(0) }
    }

    private var menuDialog: android.app.Dialog? = null
    private fun dismissMenuDialog() { menuDialog?.dismiss(); menuDialog = null }

    private fun showMainMenu() {
        showMenuWithWrap("菜单", arrayOf(
            "检索视频",
            "文件信息",
            "播放列表",
            "播放模式",
            "循环开关",
            "显示上传地址",
            "设为开机视频",
            "删除当前视频",
            "系统设置",
            "关于"
        )) { which ->
            when (which) {
                0 -> openSearchActivity()
                1 -> showVideoInfo()
                2 -> showPlaylistStatus()
                3 -> showPlaylistModeMenu()
                4 -> {
                    setLoop(!isLoopEnabled)
                    Toast.makeText(this, "循环: ${if (isLoopEnabled) "开" else "关"}", Toast.LENGTH_SHORT).show()
                }
                5 -> {
                    val url = getHttpServerUrl()
                    android.app.AlertDialog.Builder(this)
                        .setTitle("上传地址")
                        .setMessage("在浏览器中打开:\n$url")
                        .setPositiveButton("确定", null).show()
                }
                6 -> showSetStartupVideo()
                7 -> deleteCurrentVideo()
                8 -> showSettingsMenu()
                9 -> openAboutActivity()
            }
        }
    }

    private fun showPlaylistStatus() {
        val items = getPlaylistItems()
        val size = playlistManager.size
        val idx = playlistManager.currentItemIndex
        val mode = when (getPlaylistMode()) {
            "none" -> "播完停止"
            "one" -> "单曲循环"
            "all" -> "全部循环"
            "shuffle" -> "随机播放"
            else -> getPlaylistMode()
        }
        val status = buildString {
            appendLine("播放列表: $size 个视频")
            appendLine("当前: #$idx (${playlistManager.currentItem?.name ?: "无"})")
            appendLine("模式: $mode")
            if (items.isNotEmpty()) {
                appendLine("")
                for (item in items) {
                    val mark = if (item["index"] as? Int == idx) ">" else " "
                    appendLine("$mark #${item["index"]} ${item["name"]}")
                }
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("播放列表")
            .setMessage(status.trimEnd())
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showPlaylistModeMenu() {
        val modes = arrayOf("全部循环", "单曲循环", "随机播放", "播完停止")
        val modeValues = arrayOf("2", "1", "3", "0")
        android.app.AlertDialog.Builder(this)
            .setTitle("播放模式")
            .setItems(modes) { _, which ->
                setPlaylistMode(modeValues[which])
                Toast.makeText(this, "播放模式: ${modes[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showSetStartupVideo() {
        lifecycleScope.launch(Dispatchers.IO) {
            val videos = videoScanner?.scanAllVideos() ?: return@launch
            if (videos.isEmpty()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "没有可用视频", Toast.LENGTH_SHORT).show()
                }
                return@launch
            }
            val names = videos.map { it.name }.toTypedArray()
            withContext(Dispatchers.Main) {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle("设为开机视频")
                    .setItems(names) { _, which ->
                        val video = videos[which]
                        val ext = video.name.substringAfterLast('.', "mp4")
                        val newName = "hello.$ext"
                        lifecycleScope.launch(Dispatchers.IO) {
                            val fileManager = FileManager(this@MainActivity)
                            val success = fileManager.renameFile(video.path, newName)
                            withContext(Dispatchers.Main) {
                                if (success) {
                                    reloadVideos()
                                    Toast.makeText(this@MainActivity, "已设为开机视频", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(this@MainActivity, "设置失败", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
        }
    }

    // --- Settings Menu ---
    private fun showSettingsMenu() {
        val isSurface = prefs?.getBoolean(KEY_SURFACE_MODE, true) ?: true
        showMenuWithWrap("系统设置", arrayOf(
            "定时开机",
            "定时关机",
            "清除定时",
            "电源管理",
            "显示模式",
            "显示信息",
            "设为默认桌面",
            "返回系统桌面",
            "恢复默认设置",
            "调试信息",
            if (isSurface) "▸ 性能模式" else "▸ 调试模式",
            "退出应用"
        )) { which ->
            when (which) {
                0 -> showTimePickerDialog("定时开机") { time ->
                    schedulePowerOn(time)
                    Toast.makeText(this, "定时开机: $time", Toast.LENGTH_SHORT).show()
                }
                1 -> showTimePickerDialog("定时关机") { time ->
                    schedulePowerOff(time)
                    Toast.makeText(this, "定时关机: $time", Toast.LENGTH_SHORT).show()
                }
                2 -> {
                    scheduleClear()
                    schedulePowerClear()
                    Toast.makeText(this, "已清除所有定时", Toast.LENGTH_SHORT).show()
                }
                3 -> showPowerMenu()
                4 -> showColorModeMenu()
                5 -> showDisplayInfoDialog()
                6 -> showSetDefaultLauncherDialog()
                7 -> returnToSystemLauncher()
                8 -> showResetSettingsDialog()
                9 -> {
                    toggleDebugOverlay()
                    Toast.makeText(this, "调试信息: ${if (debugOverlayEnabled) "开" else "关"}", Toast.LENGTH_SHORT).show()
                }
                10 -> {
                    val newMode = !isSurface
                    prefs?.edit()?.putBoolean(KEY_SURFACE_MODE, newMode)?.apply()
                    Toast.makeText(this, "切换至${if (newMode) "性能" else "调试"}模式, 重启中...", Toast.LENGTH_SHORT).show()
                    recreate()
                }
                11 -> finish()
            }
        }
    }

    private fun showTimePickerDialog(title: String, onConfirm: (String) -> Unit) {
        val cal = java.util.Calendar.getInstance()
        val initialHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val initialMinute = cal.get(java.util.Calendar.MINUTE)
        val timePicker = android.widget.TimePicker(this).apply {
            setIs24HourView(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                hour = initialHour
                minute = initialMinute
            } else {
                @Suppress("DEPRECATION")
                currentHour = initialHour
                @Suppress("DEPRECATION")
                currentMinute = initialMinute
            }
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setView(timePicker)
            .setPositiveButton("确定") { _, _ ->
                val h = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) timePicker.hour
                        else @Suppress("DEPRECATION") timePicker.currentHour
                val m = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) timePicker.minute
                        else @Suppress("DEPRECATION") timePicker.currentMinute
                onConfirm(String.format("%02d:%02d", h, m))
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showPowerMenu() {
        val items = arrayOf("亮屏", "息屏")
        android.app.AlertDialog.Builder(this)
            .setTitle("电源管理")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> powerOn()
                    1 -> powerOff()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showColorModeMenu() {
        val modes = arrayOf("默认 (SDR)", "宽色域", "HDR")
        val modeValues = intArrayOf(0, 1, 2)
        android.app.AlertDialog.Builder(this)
            .setTitle("显示模式")
            .setItems(modes) { _, which ->
                setColorMode(modeValues[which])
                Toast.makeText(this, "显示模式: ${modes[which]}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDisplayInfoDialog() {
        val info = getDisplayInfo()
        val text = info.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        android.app.AlertDialog.Builder(this)
            .setTitle("显示信息")
            .setMessage(text)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun showResetSettingsDialog() {
        android.app.AlertDialog.Builder(this)
            .setTitle("恢复默认设置")
            .setMessage("将清除所有设置并重新扫描视频?")
            .setPositiveButton("确定") { _, _ ->
                prefs.edit().clear().apply()
                videoScanner?.invalidateCache()
                reloadVideos()
                isLoopEnabled = true
                playbackSpeed = 1.0f
                scheduleStartTime = null
                scheduleStopTime = null
                powerOnTime = null
                powerOffTime = null
                Toast.makeText(this, "已恢复默认设置", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setAsDefaultLauncher() {
        try {
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_HOME) }
            startActivity(intent)
            Toast.makeText(this, "请选择OSCVideoPlayer并设为默认", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Set default failed: ${e.message}")
        }
    }

    private fun tryAutoSetDefaultLauncher(): Boolean {
        try {
            Runtime.getRuntime().exec(arrayOf(
                "cmd", "role", "add-role-holder",
                "android.app.role.HOME", packageName
            )).waitFor()
            Log.d(TAG, "Auto-set default launcher via cmd role")
            return true
        } catch (_: Exception) {}

        return false
    }

    private fun showSetDefaultLauncherDialog() {
        if (tryAutoSetDefaultLauncher()) return

        android.app.AlertDialog.Builder(this)
            .setTitle("设为默认桌面")
            .setMessage("是否将OSCVideoPlayer设为默认桌面？\n\n设为默认后，电视开机将自动启动本应用。\n\n选择\"设为默认\"后，在弹出的界面中选择OSCVideoPlayer，并勾选\"默认\"。")
            .setPositiveButton("设为默认") { _, _ -> setAsDefaultLauncher() }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    private fun showVideoInfo() {
        val info = getVideoInfo()
        val filename = info["filename"] as? String ?: ""
        val fileSize = info["fileSize"] as? Long ?: 0L
        val width = info["width"] as? Int ?: 0
        val height = info["height"] as? Int ?: 0
        val duration = info["duration"] as? Long ?: 0L
        val frameRate = info["frameRate"] as? Double ?: 30.0
        val videoBitrate = info["videoBitrate"] as? Int ?: 0
        val audioSampleRate = info["audioSampleRate"] as? Int ?: 0
        val isPlaying = info["isPlaying"] as? Boolean ?: false
        val pos = info["currentPosition"] as? Long ?: 0L
        val volume = info["volume"] as? Float ?: 0f
        val speed = info["speed"] as? Float ?: 1.0f

        val fileSizeStr = when {
            fileSize >= 1L shl 30 -> String.format("%.2f GB", fileSize / (1L shl 30).toDouble())
            fileSize >= 1L shl 20 -> String.format("%.2f MB", fileSize / (1L shl 20).toDouble())
            fileSize >= 1L shl 10 -> String.format("%.2f KB", fileSize / (1L shl 10).toDouble())
            else -> "$fileSize B"
        }

        val infoText = buildString {
            appendLine("文件名: $filename")
            appendLine("大小: $fileSizeStr")
            if (width > 0 && height > 0) appendLine("分辨率: $width x $height")
            if (frameRate > 0) appendLine("帧率: ${String.format("%.2f", frameRate)} FPS")
            if (videoBitrate > 0) appendLine("码率: ${videoBitrate / 1000} Kbps")
            if (audioSampleRate > 0) appendLine("音频: ${audioSampleRate / 1000} kHz")
            appendLine("时长: ${duration / 1000}s")
            appendLine("位置: ${pos / 1000}s")
            appendLine("音量: ${(volume * 100).toInt()}%")
            appendLine("速度: ${String.format("%.2f", speed)}x")
            appendLine("循环: ${if (isLoopEnabled) "开" else "关"}")
            append("播放: ${if (isPlaying) "是" else "否"}")
        }

        android.app.AlertDialog.Builder(this)
            .setTitle("文件信息")
            .setMessage(infoText)
            .setPositiveButton("确定", null)
            .show()
    }

    private fun deleteCurrentVideo() {
        val videoPath = currentVideoPath ?: return
        // 外部存储: 只读不删
        if (com.oscvideoplayer.FileManager.isExternalPath(videoPath)) {
            Toast.makeText(this, "外部存储文件不可删除", Toast.LENGTH_SHORT).show()
            return
        }
        val fileManager = FileManager(this)
        android.app.AlertDialog.Builder(this)
            .setTitle("删除视频")
            .setMessage("确定删除?\n${videoPath.substringAfterLast("/")}")
            .setPositiveButton("删除") { _, _ ->
                if (fileManager.deleteFile(videoPath)) {
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show()
                    currentVideoPath = null
                    stopVideo()
                } else {
                    Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    fun openSearchActivity() {
        startActivity(Intent(this, SearchActivity::class.java))
    }

    fun openAboutActivity() {
        startActivity(Intent(this, AboutActivity::class.java))
    }

    override fun onDestroy() {
        super.onDestroy()
        watchdogStop()
        positionUpdaterJob?.cancel()
        scheduleJob?.cancel()
        powerScheduleJob?.cancel()
        ScreenReceiver.unregister(this)
        unregisterNsdService()
        player?.release()
        player = null
        httpUploadServer?.stop()
        httpUploadServer = null
        videoScanner?.stopWatching()
    }
}
