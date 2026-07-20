package com.oscvideoplayer

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.concurrent.thread

class OSCServer(
    private val port: Int,
    private val context: Context?,
    scanner: VideoScanner? = null
) {

    private var serviceContext: Context? = context
    var videoScanner: VideoScanner? = scanner
    private val fileManager: FileManager? by lazy { context?.let { FileManager(it) } }

    companion object {
        private const val TAG = "OSCServer"
        private var instance: OSCServer? = null
        private var pendingVideoPath: String? = null
        private var pendingActivity: MainActivity? = null

        fun getInstance(): OSCServer? = instance

        fun getPort(): Int = instance?.port ?: 8000

        fun setCallback(activity: MainActivity) {
            if (instance != null) {
                instance?.mainActivity = activity
            } else {
                pendingActivity = activity
            }
            Log.d(TAG, "setCallback: activity=$activity, instance=${instance != null}")
        }

        fun setPendingVideoPath(path: String) {
            pendingVideoPath = path
        }

        fun getAndClearPendingVideo(): String? {
            val path = pendingVideoPath
            pendingVideoPath = null
            return path
        }

        fun setVideoScanner(scanner: VideoScanner?) {
            instance?.videoScanner = scanner
        }
    }

    init {
        instance = this
        pendingActivity?.let {
            mainActivity = it
            pendingActivity = null
            Log.d(TAG, "Applied pending activity after server init")
        }
    }

    private var socket: DatagramSocket? = null
    private var isRunning = false
    @Volatile
    private var mainActivity: MainActivity? = null

    fun getMainActivity(): MainActivity? = mainActivity
    private val clients = mutableMapOf<String, ClientInfo>()

    data class ClientInfo(
        val address: InetAddress,
        val sourcePort: Int,
        var replyPort: Int
    )

    private fun getClient(address: InetAddress, sourcePort: Int): ClientInfo {
        val key = address.hostAddress ?: return ClientInfo(address, sourcePort, 12000)
        return clients.getOrPut(key) { ClientInfo(address, sourcePort, 12000) }
    }

    private fun updateClientReplyPort(address: InetAddress, replyPort: Int) {
        val key = address.hostAddress ?: return
        clients[key]?.replyPort = replyPort
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        thread {
            try {
                socket = DatagramSocket(port)
                Log.d(TAG, "OSC Server started on port $port")
                val buffer = ByteArray(8192)
                while (isRunning) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket?.receive(packet)
                        val client = getClient(packet.address, packet.port)
                        processMessage(packet.data, packet.length, client)
                    } catch (e: Exception) {
                        if (isRunning) Log.e(TAG, "Receive error: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server error: ${e.message}")
            }
        }
    }

    fun stop() {
        isRunning = false
        socket?.close()
        socket = null
        instance = null
    }

    private fun processMessage(data: ByteArray, length: Int, client: ClientInfo) {
        try {
            val msg = parseOSCMessage(data, length)
            if (msg != null) {
                Log.d(TAG, "[RECV] ${msg.address} args=${msg.args}")
                handleMessage(msg, client)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error: ${e.message}")
        }
    }

    private fun parseOSCMessage(data: ByteArray, length: Int): OSCMessage? {
        if (length < 8) return null
        var i = 0

        val address = readString(data, i)
        if (address == null || address.isEmpty()) return null
        i += align4(address.toByteArray(Charsets.UTF_8).size + 1)
        if (i >= length) return null

        val typeTagFull = readStringRaw(data, i) ?: return null
        if (!typeTagFull.startsWith(",")) return OSCMessage(address, emptyList())
        val typeTag = typeTagFull.substring(1)
        i += align4(typeTagFull.toByteArray(Charsets.UTF_8).size + 1)

        val args = mutableListOf<Any>()
        for (ch in typeTag) {
            if (i >= length) break
            when (ch) {
                's', 'S' -> {
                    val s = readString(data, i) ?: ""
                    i += align4(s.toByteArray(Charsets.UTF_8).size + 1)
                    args.add(s)
                }
                'i' -> {
                    if (i + 4 > length) break
                    val v = ((data[i].toInt() and 0xFF) shl 24) or
                            ((data[i + 1].toInt() and 0xFF) shl 16) or
                            ((data[i + 2].toInt() and 0xFF) shl 8) or
                            (data[i + 3].toInt() and 0xFF)
                    args.add(v)
                    i += 4
                }
                'f' -> {
                    if (i + 4 > length) break
                    val bits = ((data[i].toInt() and 0xFF) shl 24) or
                            ((data[i + 1].toInt() and 0xFF) shl 16) or
                            ((data[i + 2].toInt() and 0xFF) shl 8) or
                            (data[i + 3].toInt() and 0xFF)
                    args.add(Float.fromBits(bits).toDouble())
                    i += 4
                }
                'T' -> args.add(true)
                'F' -> args.add(false)
                'b' -> {
                    if (i + 4 > length) break
                    val blobSize = ((data[i].toInt() and 0xFF) shl 24) or
                            ((data[i + 1].toInt() and 0xFF) shl 16) or
                            ((data[i + 2].toInt() and 0xFF) shl 8) or
                            (data[i + 3].toInt() and 0xFF)
                    i += 4
                    if (i + blobSize > length) break
                    val blob = data.copyOfRange(i, i + blobSize)
                    i += align4(blobSize)
                    args.add(blob)
                }
                else -> {
                    Log.w(TAG, "Unknown OSC type tag: $ch")
                }
            }
        }
        return OSCMessage(address, args)
    }

    private fun readString(data: ByteArray, offset: Int): String? {
        val utf8String = readStringRaw(data, offset) ?: return null
        return try {
            utf8String
        } catch (e: Exception) {
            null
        }
    }

    private fun readStringRaw(data: ByteArray, offset: Int): String? {
        if (offset >= data.size) return null
        var end = offset
        val len = data.size
        while (end < len) {
            if (data[end].toInt() == 0) break
            end++
        }
        return try {
            String(data, offset, end - offset, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun align4(size: Int): Int = (size + 3) / 4 * 4

    private fun handleMessage(msg: OSCMessage, client: ClientInfo) {
        val parts = msg.address.trimStart('/').split("/")
        val mainCmd = parts.getOrNull(0) ?: return
        var response: OSCMessage? = null

        when (mainCmd) {
            "play" -> {
                var filename = if (parts.size > 1) parts.subList(1, parts.size).joinToString("/") else ""
                if (filename.isEmpty() && msg.args.isNotEmpty()) {
                    filename = msg.args[0]?.toString() ?: ""
                }
                val videoPath = findVideo(filename)
                if (videoPath != null) {
                    pendingVideoPath = videoPath
                    startMainActivity(videoPath)
                    response = OSCMessage("/Playing", listOf(filename.ifEmpty { videoPath.substringAfterLast("/") }))
                } else {
                    response = OSCMessage("/Error", listOf("Video not found: $filename"))
                }
            }

            "discover" -> {
                val ip = mainActivity?.getLocalIPAddress() ?: "unknown"
                val mac = mainActivity?.getMacAddress() ?: "unknown"
                response = OSCMessage("/Discover", listOf(ip, mac))
            }

            "alignment" -> {
                val sub = parts.getOrNull(1) ?: ""
                when (sub) {
                    "prepare" -> {
                        val idx = (msg.args.getOrNull(0) as? Number)?.toInt() ?: 0
                        val kfMs = (msg.args.getOrNull(1) as? Number)?.toLong() ?: 0L
                        val futureMs = (msg.args.getOrNull(2) as? Number)?.toLong() ?: 2000L
                        mainActivity?.alignmentPrepare(idx, kfMs, futureMs) { posMs, durStr ->
                            sendResponse(OSCMessage("/Alignment/ready", listOf(
                                idx.toString(),
                                mainActivity?.getPlaylistItems()?.getOrNull(idx)?.get("name") as? String ?: "",
                                posMs.toString(),
                                durStr
                            )), client)
                        }
                        return
                    }
                    "play" -> {
                        mainActivity?.alignmentPlay()
                        response = OSCMessage("/Alignment", listOf("play"))
                    }
                    else -> response = OSCMessage("/Error", listOf("Unknown alignment command"))
                }
            }

            "getgop" -> {
                val name = if (parts.size > 1) parts.subList(1, parts.size).joinToString("/") else ""
                         ?: (msg.args.getOrNull(0) as? String)?.takeIf { it.isNotEmpty() } ?: ""
                val videoPath = findVideo(name)
                if (videoPath != null) {
                    val kfs = mainActivity?.getVideoKeyframes(videoPath)
                    if (kfs != null) {
                        response = OSCMessage("/GOP", listOf(kfs.first.toString(), kfs.second.toString(), kfs.third.toString()))
                    } else {
                        response = OSCMessage("/Error", listOf("Cannot read GOP: $name"))
                    }
                } else {
                    response = OSCMessage("/Error", listOf("Video not found: $name"))
                }
            }

            "stop" -> {
                mainActivity?.stopVideo()
                response = OSCMessage("/Stopped", listOf(""))
            }

            "playpause" -> {
                mainActivity?.togglePause()
                response = OSCMessage("/Paused", listOf(mainActivity?.isPaused() == true))
            }

            "pause" -> {
                val sub = parts.getOrNull(1) ?: ""
                if (sub == "toggle") {
                    mainActivity?.togglePause()
                } else {
                    val value = if (sub.isEmpty() && msg.args.isNotEmpty()) {
                        (msg.args[0] as? Number)?.toInt() ?: -1
                    } else sub.toIntOrNull() ?: -1
                    when (value) {
                        0 -> mainActivity?.resumeVideo()
                        1 -> mainActivity?.pauseVideo()
                        else -> mainActivity?.togglePause()
                    }
                }
                response = OSCMessage("/Paused", listOf(mainActivity?.isPaused() == true))
            }

            "mute" -> {
                val value = if (parts.size > 1) parts[1].toIntOrNull()
                           else (msg.args.getOrNull(0) as? Number)?.toInt() ?: -1
                when (value) {
                    0 -> mainActivity?.unmute()
                    1 -> mainActivity?.mute()
                    else -> mainActivity?.toggleMute()
                }
                response = OSCMessage("/Mute", listOf(mainActivity?.isMuted() == true))
            }

            "volume" -> {
                var volume = if (parts.size > 1) parts[1].toFloatOrNull() else null
                if (volume == null && msg.args.isNotEmpty()) {
                    volume = (msg.args[0] as? Number)?.toFloat()
                }
                volume = (volume ?: 50f).coerceIn(0f, 100f)
                mainActivity?.setVolume(volume)
                response = OSCMessage("/Volume", listOf("${volume.toInt()}%"))
            }

            "seek" -> {
                val ms = if (parts.size > 1) parts[1].toLongOrNull()
                         else (msg.args.getOrNull(0) as? Number)?.toLong()
                if (ms != null && ms >= 0) {
                    mainActivity?.seekToMs(ms)
                    response = OSCMessage("/SeekTo", listOf("${ms}ms"))
                } else {
                    response = OSCMessage("/Error", listOf("seek requires ms >= 0"))
                }
            }

            "speed" -> {
                var speed = if (parts.size > 1) parts[1].toFloatOrNull() else null
                if (speed == null && msg.args.isNotEmpty()) {
                    speed = (msg.args[0] as? Number)?.toFloat()
                }
                if (speed != null) {
                    speed = speed.coerceIn(0.25f, 4.0f)
                    mainActivity?.setPlaybackSpeed(speed)
                    response = OSCMessage("/Speed", listOf(speed.toDouble()))
                } else {
                    response = OSCMessage("/Speed", listOf(mainActivity?.getPlaybackSpeed() ?: 1.0))
                }
            }

            "playlist" -> {
                val sub = parts.getOrNull(1) ?: ""
                when (sub) {
                    "add" -> {
                        var name = parts.getOrNull(2) ?: ""
                        if (name.isEmpty() && msg.args.isNotEmpty()) name = msg.args[0]?.toString() ?: ""
                        val videoPath = findVideo(name)
                        if (videoPath != null) {
                            mainActivity?.addToPlaylist(videoPath)
                            response = OSCMessage("/PlaylistAdded", listOf(name.ifEmpty { videoPath.substringAfterLast("/") }))
                        } else {
                            response = OSCMessage("/Error", listOf("Video not found: $name"))
                        }
                    }
                    "remove" -> {
                        val idx = parts.getOrNull(2)?.toIntOrNull() ?: -1
                        if (idx >= 0) {
                            mainActivity?.removeFromPlaylist(idx)
                            response = OSCMessage("/PlaylistRemoved", listOf(idx))
                        } else response = OSCMessage("/Error", listOf("Invalid index"))
                    }
                    "clear" -> {
                        mainActivity?.clearPlaylist()
                        response = OSCMessage("/PlaylistCleared", listOf())
                    }
                    "next" -> {
                        mainActivity?.playNext()
                        response = OSCMessage("/PlaylistNext", listOf())
                    }
                    "prev" -> {
                        mainActivity?.playPrevious()
                        response = OSCMessage("/PlaylistPrev", listOf())
                    }
                    "index" -> {
                        val idx = parts.getOrNull(2)?.toIntOrNull() ?: -1
                        if (idx >= 0) {
                            mainActivity?.jumpToPlaylistIndex(idx)
                            response = OSCMessage("/PlaylistIndex", listOf(idx))
                        } else response = OSCMessage("/Error", listOf("Invalid index"))
                    }
                    "mode" -> {
                        val mode = parts.getOrNull(2) ?: ""
                        if (mode.isNotEmpty()) {
                            mainActivity?.setPlaylistMode(mode)
                            response = OSCMessage("/PlaylistMode", listOf(mode))
                        } else response = OSCMessage("/PlaylistMode", listOf(mainActivity?.getPlaylistMode() ?: "all"))
                    }
                    "get" -> {
                        val items = mainActivity?.getPlaylistItems() ?: emptyList()
                        val lines = mutableListOf<String>()
                        for ((i, it) in items.withIndex()) {
                            val name = it["name"] ?: ""
                            val path = it["path"] as? String ?: ""
                            var duration = 0L; var fps = 0.0; var secondKf = 0L; var lastKf = 0L
                            if (path.isNotEmpty() && java.io.File(path).exists()) {
                                try {
                                    val r = android.media.MediaMetadataRetriever()
                                    r.setDataSource(path)
                                    duration = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                                    r.release()
                                } catch (_: Exception) {}
                                val kfs = mainActivity?.getVideoKeyframes(path)
                                if (kfs != null) { secondKf = kfs.first; lastKf = kfs.second; fps = kfs.third }
                            }
                            lines.add("$i $name $duration $fps $secondKf $lastKf")
                        }
                        response = OSCMessage("/Playlist", listOf(lines.joinToString("\n")))
                    }
                    else -> response = OSCMessage("/Error", listOf("Unknown playlist command"))
                }
            }

            "subtitle" -> {
                val sub = parts.getOrNull(1) ?: ""
                when {
                    sub == "0" || sub == "off" || sub == "false" -> {
                        mainActivity?.setSubtitle(null)
                        response = OSCMessage("/Subtitle", listOf("off"))
                    }
                    sub.isNotEmpty() -> {
                        mainActivity?.setSubtitle(sub)
                        response = OSCMessage("/Subtitle", listOf(sub))
                    }
                    msg.args.isNotEmpty() -> {
                        val path = msg.args[0]?.toString() ?: ""
                        mainActivity?.setSubtitle(path)
                        response = OSCMessage("/Subtitle", listOf(path))
                    }
                    else -> response = OSCMessage("/Error", listOf("Usage: /subtitle/path or /subtitle/0"))
                }
            }

            "fullscreen" -> {
                response = OSCMessage("/Fullscreen", listOf(1))
            }

            "tct" -> {
                var text = ""
                var fontSize = 48
                var position = 0
                if (parts.size > 1) {
                    val textParam = parts.subList(1, parts.size).joinToString("/")
                    val textParts = textParam.split("/")
                    text = textParts.getOrNull(0) ?: ""
                    fontSize = textParts.getOrNull(1)?.toIntOrNull() ?: 48
                    position = textParts.getOrNull(2)?.toIntOrNull() ?: 0
                } else if (msg.args.isNotEmpty()) {
                    text = msg.args.getOrNull(0)?.toString() ?: ""
                    fontSize = (msg.args.getOrNull(1) as? Number)?.toInt() ?: 48
                    position = (msg.args.getOrNull(2) as? Number)?.toInt() ?: 0
                }
                mainActivity?.showText(text, fontSize, position)
                val isVisible = text.isNotEmpty() && text != "0"
                response = OSCMessage("/TCT", listOf(if (isVisible) "T" else "F"))
            }

            "info" -> {
                val param = parts.getOrNull(1) ?: ""
                if (param == "display") {
                    val dinfo = mainActivity?.getDisplayInfo() ?: emptyMap()
                    val lines = dinfo.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                    response = OSCMessage("/DisplayInfo", listOf(lines))
                } else {
                    val name = if (param.isEmpty() && msg.args.isNotEmpty()) msg.args[0]?.toString() ?: "" else param
                    if (name.isNotEmpty()) {
                        val videoPath = findVideo(name)
                        if (videoPath != null) {
                            val retriever = android.media.MediaMetadataRetriever()
                            try {
                                retriever.setDataSource(videoPath)
                                val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                                val w = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) ?: "?"
                                val h = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT) ?: "?"
                                val file = File(videoPath)
                                response = OSCMessage("/VideoInfo", listOf(
                                    "name:${file.name}",
                                    "path:$videoPath",
                                    "size:${file.length()}",
                                    "duration:${duration}ms",
                                    "resolution:${w}x${h}"
                                ))
                            } catch (e: Exception) {
                                response = OSCMessage("/Error", listOf("Cannot read info: ${e.message}"))
                            } finally {
                                retriever.release()
                            }
                        } else {
                            response = OSCMessage("/Error", listOf("Video not found: $name"))
                        }
                    } else {
                        val info = mainActivity?.getVideoInfo() ?: emptyMap()
                        response = OSCMessage("/Info", listOf(
                            "filename:${info["filename"]}",
                            "resolution:${info["width"]}x${info["height"]}",
                            "duration:${info["duration"]}ms",
                            "fps:${info["frameRate"]}",
                            "position:${info["currentPosition"]}ms",
                            "playing:${if (info["isPlaying"] == true) "yes" else "no"}"
                        ))
                    }
                }
            }

            "status" -> {
                val info = mainActivity?.getVideoInfo() ?: emptyMap()
                val playlistInfo = mainActivity?.getPlaylistStatus()
                response = OSCMessage("/Status", listOf(
                    "playing:${info["isPlaying"]}",
                    "filename:${info["filename"]}",
                    "position:${info["currentPosition"]}ms",
                    "duration:${info["duration"]}ms",
                    "volume:${info["volume"]}",
                    "speed:${info["speed"]}",
                    "loop:${info["loop"]}",
                    "playlistIndex:${playlistInfo?.get("index")}",
                    "playlistSize:${playlistInfo?.get("size")}",
                    "playlistMode:${playlistInfo?.get("mode")}"
                ))
            }

            "fps" -> {
                val sub = parts.getOrNull(1) ?: ""
                when (sub) {
                    "set" -> {
                        val value = if (msg.args.isNotEmpty()) (msg.args[0] as? Number)?.toInt() ?: -1 else -1
                        if (value > 0) {
                            mainActivity?.setVideoFrameRate(value.toDouble())
                            response = OSCMessage("/FPS", listOf(value))
                        } else {
                            response = OSCMessage("/Error", listOf("Usage: /fps/set with frame rate arg"))
                        }
                    }
                    else -> {
                        response = OSCMessage("/FPS", listOf(mainActivity?.getVideoFrameRate() ?: 30.0))
                    }
                }
            }

            "rm" -> {
                val filename = parts.getOrNull(1) ?: ""
                if (msg.args.isNotEmpty() && filename.isEmpty()) {
                    val argFilename = msg.args[0]?.toString() ?: ""
                    if (argFilename.isNotEmpty()) {
                        val videoPath = findVideo(argFilename)
                        if (videoPath != null && mainActivity?.deleteVideo(videoPath) == true) {
                            response = OSCMessage("/Removed", listOf(argFilename))
                        } else {
                            response = OSCMessage("/Error", listOf("File not found: $argFilename"))
                        }
                    }
                } else {
                    val videoPath = findVideo(filename)
                    if (videoPath != null && mainActivity?.deleteVideo(videoPath) == true) {
                        response = OSCMessage("/Removed", listOf(filename))
                    } else {
                        response = OSCMessage("/Error", listOf("File not found: $filename"))
                    }
                }
            }

            "rename" -> {
                val p1 = parts.getOrNull(1) ?: ""
                val p2 = parts.getOrNull(2) ?: ""
                val nameArg = when {
                    p1.isNotEmpty() && p2.isNotEmpty() -> p1
                    p1.isNotEmpty() && msg.args.isNotEmpty() -> p1
                    msg.args.size >= 2 -> msg.args[0]?.toString() ?: ""
                    else -> ""
                }
                val newNameArg = when {
                    p1.isNotEmpty() && p2.isNotEmpty() -> p2
                    p1.isNotEmpty() && msg.args.isNotEmpty() -> msg.args[0]?.toString() ?: ""
                    msg.args.size >= 2 -> msg.args[1]?.toString() ?: ""
                    else -> ""
                }
                if (nameArg.isNotEmpty() && newNameArg.isNotEmpty()) {
                    val videoPath = findVideo(nameArg)
                    if (videoPath != null) {
                        val success = fileManager?.renameFile(videoPath, newNameArg) ?: false
                        if (success) {
                            mainActivity?.reloadVideos()
                            response = OSCMessage("/Renamed", listOf("$nameArg -> $newNameArg"))
                        } else {
                            response = OSCMessage("/Error", listOf("Rename failed"))
                        }
                    } else {
                        response = OSCMessage("/Error", listOf("Video not found: $nameArg"))
                    }
                } else {
                    response = OSCMessage("/Error", listOf("Usage: /rename/oldName/newName or /rename with 2 args"))
                }
            }

            "cp" -> {
                val sub = parts.getOrNull(1) ?: ""
                val name = parts.getOrNull(2) ?: ""
                val argName = if (name.isEmpty() && msg.args.isNotEmpty()) msg.args[0]?.toString() ?: "" else name
                when (sub) {
                    "tousb", "usb", "toexternal" -> {
                        if (argName.isEmpty()) {
                            response = OSCMessage("/Error", listOf("Usage: /cp/tousb/name"))
                        } else {
                            val videoPath = findVideo(argName)
                            if (videoPath != null) {
                                val result = fileManager?.copyToUSB(videoPath) ?: false
                                response = OSCMessage("/Copied", listOf(if (result) "OK" else "Failed (no USB?)"))
                            } else {
                                response = OSCMessage("/Error", listOf("Video not found: $argName"))
                            }
                        }
                    }
                    "tointernal", "internal" -> {
                        if (argName.isEmpty()) {
                            response = OSCMessage("/Error", listOf("Usage: /cp/tointernal/name"))
                        } else {
                            val videoPath = findVideo(argName)
                            if (videoPath != null) {
                                val result = fileManager?.copyToInternal(videoPath) ?: false
                                response = OSCMessage("/Copied", listOf(if (result) "OK" else "Failed"))
                            } else {
                                response = OSCMessage("/Error", listOf("Video not found: $argName"))
                            }
                        }
                    }
                    else -> response = OSCMessage("/Error", listOf("Usage: /cp/tousb/name or /cp/tointernal/name"))
                }
            }

            "port" -> {
                var replyInfo = "Port set"
                val port = if (parts.size > 1) parts[1].toIntOrNull()
                          else (msg.args.getOrNull(0) as? Number)?.toInt()
                if (port != null && port > 0) {
                    updateClientReplyPort(client.address, port)
                    replyInfo = "Port: $port"
                }
                response = OSCMessage("/OSCPlayer", listOf(replyInfo))
            }

            "config" -> {
                val key = parts.getOrNull(1) ?: ""
                val value = parts.getOrNull(2) ?: ""
                when (key) {
                    "dir" -> {
                        val dir = if (value.isNotEmpty()) value
                        else if (msg.args.isNotEmpty()) msg.args[0]?.toString() ?: ""
                        else ""
                        if (dir.isNotEmpty()) {
                            mainActivity?.setDefaultDirectory(dir)
                            response = OSCMessage("/Config", listOf("dir=$dir"))
                        } else {
                            response = OSCMessage("/Config", listOf("dir=${mainActivity?.getDefaultDirectory()}"))
                        }
                    }
                    "heartbeat" -> {
                        mainActivity?.watchdogPing()
                        response = OSCMessage("/Config", listOf("heartbeat"))
                    }
                    "watchdog" -> {
                        val enable = if (value.isNotEmpty()) value.toIntOrNull() ?: 1
                        else (msg.args.getOrNull(0) as? Number)?.toInt() ?: 1
                        if (enable == 0) {
                            mainActivity?.watchdogStop()
                            response = OSCMessage("/Config", listOf("watchdog=off"))
                        } else {
                            mainActivity?.watchdogStart()
                            response = OSCMessage("/Config", listOf("watchdog=on"))
                        }
                    }
                    "startup" -> {
                        val name = if (value.isNotEmpty()) value
                        else if (msg.args.isNotEmpty()) msg.args[0]?.toString() ?: ""
                        else ""
                        if (name.isNotEmpty()) {
                            val videoPath = findVideo(name)
                            if (videoPath != null) {
                                val ext = File(videoPath).name.substringAfterLast('.', "mp4")
                                val newPath = File(File(videoPath).parent, "hello.$ext").absolutePath
                                val success = fileManager?.renameFile(videoPath, "hello.$ext") ?: false
                                if (success) {
                                    mainActivity?.reloadVideos()
                                    response = OSCMessage("/Config", listOf("startup=$name -> hello.$ext"))
                                } else {
                                    response = OSCMessage("/Error", listOf("Set startup failed"))
                                }
                            } else {
                                response = OSCMessage("/Error", listOf("Video not found: $name"))
                            }
                        } else {
                            val helloVideos = videoScanner?.findHelloVideo()
                            response = OSCMessage("/Config", listOf("startup=${helloVideos?.name ?: "none"}"))
                        }
                    }
                    "keepalive" -> {
                        val sub = parts.getOrNull(2) ?: ""
                        when {
                            sub == "alarm" -> {
                                val secs = (msg.args.getOrNull(0) as? Number)?.toInt()
                                    ?: parts.getOrNull(3)?.toIntOrNull() ?: 0
                                if (secs > 0) {
                                    mainActivity?.setKeepaliveAlarm(secs)
                                    response = OSCMessage("/Config", listOf("keepalive_alarm=${secs}s"))
                                } else {
                                    response = OSCMessage("/Config", listOf("keepalive_alarm=${mainActivity?.getKeepaliveAlarm() ?: 60}s"))
                                }
                            }
                            sub == "workmanager" -> {
                                val mins = (msg.args.getOrNull(0) as? Number)?.toLong()
                                    ?: parts.getOrNull(3)?.toLongOrNull() ?: 0L
                                if (mins > 0) {
                                    mainActivity?.setKeepaliveWorkmanager(mins)
                                    response = OSCMessage("/Config", listOf("keepalive_workmanager=${mins}min"))
                                } else {
                                    response = OSCMessage("/Config", listOf("keepalive_workmanager=${mainActivity?.getKeepaliveWorkmanager() ?: 30}min"))
                                }
                            }
                            else -> {
                                val alarm = mainActivity?.getKeepaliveAlarm() ?: 60
                                val wm = mainActivity?.getKeepaliveWorkmanager() ?: 30
                                response = OSCMessage("/Config", listOf("alarm=${alarm}s  workmanager=${wm}min"))
                            }
                        }
                    }
                    "reload" -> {
                        mainActivity?.reloadVideos()
                        response = OSCMessage("/Config", listOf("reloaded"))
                    }
                    "display" -> {
                        val sub = parts.getOrNull(2) ?: ""
                        when (sub) {
                            "mode" -> {
                                val modeVal = parts.getOrNull(3)?.toIntOrNull() ?: -1
                                if (modeVal >= 0) {
                                    mainActivity?.setColorMode(modeVal)
                                    response = OSCMessage("/Config", listOf("colorMode=$modeVal"))
                                } else {
                                    response = OSCMessage("/Config", listOf("Usage: /config/display/mode/N (0=default, 1=wide, 2=HDR)"))
                                }
                            }
                            "info" -> {
                                val dinfo = mainActivity?.getDisplayInfo() ?: emptyMap()
                                val lines = dinfo.entries.joinToString("\n") { "${it.key}: ${it.value}" }
                                response = OSCMessage("/DisplayInfo", listOf(lines))
                            }
                            else -> {
                                response = OSCMessage("/Config", listOf("Unknown display command: $sub"))
                            }
                        }
                    }
                    else -> response = OSCMessage("/Error", listOf("Unknown config key: $key"))
                }
            }

            "schedule" -> {
                val sub = parts.getOrNull(1) ?: ""
                when (sub) {
                    "start" -> {
                        val time = (msg.args.getOrNull(0) as? String)?.takeIf { it.isNotEmpty() }
                            ?: parts.getOrNull(2) ?: ""
                        if (time.isNotEmpty()) {
                            mainActivity?.scheduleStart(time)
                            response = OSCMessage("/Schedule", listOf("start=$time"))
                        } else response = OSCMessage("/Schedule", listOf(mainActivity?.getScheduleStatus() ?: "no schedule"))
                    }
                    "stop" -> {
                        val time = (msg.args.getOrNull(0) as? String)?.takeIf { it.isNotEmpty() }
                            ?: parts.getOrNull(2) ?: ""
                        if (time.isNotEmpty()) {
                            mainActivity?.scheduleStop(time)
                            response = OSCMessage("/Schedule", listOf("stop=$time"))
                        } else response = OSCMessage("/Schedule", listOf(mainActivity?.getScheduleStatus() ?: "no schedule"))
                    }
                    "clear" -> {
                        mainActivity?.scheduleClear()
                        response = OSCMessage("/Schedule", listOf("cleared"))
                    }
                    else -> response = OSCMessage("/Schedule", listOf(mainActivity?.getScheduleStatus() ?: "no schedule"))
                }
            }

            "power" -> {
                val sub = parts.getOrNull(1) ?: ""
                when (sub) {
                    "on" -> {
                        mainActivity?.powerOn()
                        response = OSCMessage("/Power", listOf("on"))
                    }
                    "off" -> {
                        mainActivity?.powerOff()
                        response = OSCMessage("/Power", listOf("off"))
                    }
                    "restart" -> {
                        response = OSCMessage("/Power", listOf("restarting"))
                        mainActivity?.restartPlayer()
                    }
                    "exit" -> {
                        mainActivity?.stopVideo()
                        mainActivity?.finish()
                        return
                    }
                    "shutdown" -> { sendShutdown(); return }
                    "reboot" -> { sendReboot(); return }
                    "schedule" -> {
                        val action = parts.getOrNull(2) ?: ""
                        val time = (msg.args.getOrNull(0) as? String)?.takeIf { it.isNotEmpty() }
                            ?: parts.getOrNull(3) ?: ""
                        when (action) {
                            "on" -> {
                                mainActivity?.schedulePowerOn(time)
                                response = OSCMessage("/Power", listOf("schedule=on:$time"))
                            }
                            "off" -> {
                                mainActivity?.schedulePowerOff(time)
                                response = OSCMessage("/Power", listOf("schedule=off:$time"))
                            }
                            "shutdown" -> {
                                mainActivity?.schedulePowerShutdown(time)
                                response = OSCMessage("/Power", listOf("schedule=shutdown:$time"))
                            }
                            "reboot" -> {
                                mainActivity?.schedulePowerReboot(time)
                                response = OSCMessage("/Power", listOf("schedule=reboot:$time"))
                            }
                            "clear" -> {
                                mainActivity?.schedulePowerClear()
                                response = OSCMessage("/Power", listOf("schedule=cleared"))
                            }
                            else -> response = OSCMessage("/Error", listOf("Usage: /power/schedule/on|off|shutdown|reboot|clear"))
                        }
                    }
                    else -> response = OSCMessage("/Power", listOf("usage: /power/on|off|schedule"))
                }
            }

            "overlay" -> {
                mainActivity?.toggleDebugOverlay()
                response = OSCMessage("/Overlay", listOf(mainActivity?.isDebugOverlayOn() == true))
            }

            "upload" -> {
                val url = mainActivity?.getHttpServerUrl() ?: "unknown"
                response = OSCMessage("/Upload", listOf("Open browser and go to: $url"))
            }

            "help" -> {
                val helpText = buildString {
                    appendLine("=== OSCVideoPlayer v2.0 ===")
                    appendLine("Playback:")
                    appendLine("  /play[name]       Play video (optional name)")
                    appendLine("  /stop             Stop playback, black screen")
                    appendLine("  /pause/toggle     Toggle play/pause")
                    appendLine("  /pause[0|1]       0=resume, 1=pause")
                    appendLine("  /volume/0.0-1.0   Set volume")
                    appendLine("  /seek/ms          Seek to position (ms)")
                    appendLine("  /speed/0.5-4.0    Set playback speed")
                    appendLine("Playlist:")
                    appendLine("  /playlist/add/name   Add to playlist")
                    appendLine("  /playlist/remove/idx Remove from playlist")
                    appendLine("  /playlist/clear      Clear playlist")
                    appendLine("  /playlist/next|prev  Navigate playlist")
                    appendLine("  /playlist/index/idx  Jump to index")
                    appendLine("  /playlist/mode/0|1|2|3  Playlist mode")
                    appendLine("  /playlist/get        Get full playlist with indices")
                    appendLine("Display:")
                    appendLine("  /tct/text/size/pos  Show text overlay")
                    appendLine("  /fullscreen         Fullscreen")
                    appendLine("  /upload             Show web upload URL")
                    appendLine("Info:")
                    appendLine("  /info[/name]        Video info (optional name)")
                    appendLine("  /info/display       Show display capabilities")
                    appendLine("  /status             Full status")
                    appendLine("  /fps                Get FPS")
                    appendLine("  /fps/set/rate       Set FPS")
                    appendLine("Config:")
                    appendLine("  /config/dir[/path]  Get/set default dir")
                    appendLine("  /config/watchdog[/0|1]  Toggle watchdog")
                    appendLine("  /config/heartbeat   Ping watchdog")
                    appendLine("  /config/reload      Rescan videos")
                    appendLine("  /config/startup/name  Set startup video")
                    appendLine("  /config/display/mode/N  Set color mode")
                    appendLine("  /config/display/info    Show display info")
                    appendLine("Schedule:")
                    appendLine("  /schedule/start/HH:mm  Set start time")
                    appendLine("  /schedule/stop/HH:mm   Set stop time")
                    appendLine("  /schedule/clear        Clear schedule")
                    appendLine("Power:")
                    appendLine("  /power/on|off         Display power")
                    appendLine("  /power/restart        Restart player")
                    appendLine("  /power/exit           Exit app")
                    appendLine("  /power/shutdown       Shutdown device")
                    appendLine("  /power/reboot         Reboot device")
                    appendLine("  /power/schedule/on|off/HH:mm")
                    appendLine("File:")
                    appendLine("  /rm/name              Delete video")
                    appendLine("  /rename/old/new       Rename video")
                    appendLine("  /cp/tousb/name        Copy video to USB")
                    appendLine("  /cp/tointernal/name   Copy video from USB")
                    appendLine("  /port/name/port       Set reply port")
                    appendLine("  /help                 Show this help")
                    appendLine("System:")
                    appendLine("  /launcher             Return to system launcher")
                }
                response = OSCMessage("/Help", listOf(helpText))
            }

            "launcher" -> {
                mainActivity?.returnToSystemLauncher()
                response = OSCMessage("/Launcher", listOf("ok"))
            }

            else -> {
                response = OSCMessage("/Unknown", listOf("Unknown: ${msg.address}"))
            }
        }

        response?.let { sendResponse(it, client) }
    }

    private fun sendShutdown() {
        try {
            Runtime.getRuntime().exec("su -c 'reboot -p'")
            Log.d(TAG, "Shutdown command sent")
        } catch (e: Exception) {
            Log.e(TAG, "Shutdown failed: ${e.message}")
        }
    }

    private fun sendReboot() {
        try {
            Runtime.getRuntime().exec("su -c 'reboot'")
            Log.d(TAG, "Reboot command sent")
        } catch (e: Exception) {
            Log.e(TAG, "Reboot failed: ${e.message}")
        }
    }

    private fun startMainActivity(videoPath: String) {
        try {
            val intent = android.content.Intent(serviceContext, MainActivity::class.java).apply {
                action = "com.oscvideoplayer.PLAY"
                putExtra("video_path", videoPath)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            serviceContext?.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start MainActivity: ${e.message}")
        }
    }

    private fun findVideo(filename: String): String? {
        var videos = mainActivity?.getVideoList() ?: emptyList()
        if (videos.isEmpty()) videos = videoScanner?.scanAllVideos() ?: emptyList()
        if (videos.isEmpty()) return null
        if (filename.isEmpty()) {
            return videos.find { it.name.lowercase().startsWith("hello.") }?.path
                ?: videos.firstOrNull()?.path
        }
        videos.find { it.name == filename }?.let { return it.path }
        videos.find { it.name.contains(filename, ignoreCase = true) }?.let { return it.path }
        return null
    }

    private fun sendResponse(msg: OSCMessage, client: ClientInfo) {
        try {
            val targetPort = if (client.replyPort > 0) client.replyPort else client.sourcePort
            val address = client.address
            val data = createOSCMessage(msg.address, msg.args)
            var ipStr = address.hostAddress ?: return
            if (ipStr.startsWith("/")) ipStr = ipStr.substring(1)
            val targetAddress = InetAddress.getByName(ipStr)
            val packet = DatagramPacket(data, data.size, targetAddress, targetPort)
            socket?.send(packet)
            Log.d(TAG, "[SEND] ${msg.address} to $ipStr:$targetPort")
        } catch (e: Exception) {
            Log.e(TAG, "Send error: ${e.message}")
        }
    }

    private fun createOSCMessage(address: String, args: List<Any>): ByteArray {
        val baos = ByteArrayOutputStream()
        val addrBytes = address.toByteArray(Charsets.UTF_8)
        baos.write(addrBytes)
        baos.write(0)
        while (baos.size() % 4 != 0) baos.write(0)

        if (args.isNotEmpty()) {
            val tags = args.map {
                when (it) {
                    is String -> 's'
                    is Int -> 'i'
                    is Float -> 'f'
                    is Double -> 'd'
                    is Boolean -> if (it) 'T' else 'F'
                    else -> 's'
                }
            }
            val typeStr = "," + tags.joinToString("")
            val typeBytes = typeStr.toByteArray(Charsets.UTF_8)
            baos.write(typeBytes)
            while (baos.size() % 4 != 0) baos.write(0)

            for (arg in args) {
                when (arg) {
                    is String -> {
                        val sb = arg.toByteArray(Charsets.UTF_8)
                        baos.write(sb)
                        baos.write(0)
                        while (baos.size() % 4 != 0) baos.write(0)
                    }
                    is Int -> {
                        baos.write((arg shr 24) and 0xFF)
                        baos.write((arg shr 16) and 0xFF)
                        baos.write((arg shr 8) and 0xFF)
                        baos.write(arg and 0xFF)
                    }
                    is Float -> {
                        val bits = arg.toRawBits()
                        baos.write((bits shr 24) and 0xFF)
                        baos.write((bits shr 16) and 0xFF)
                        baos.write((bits shr 8) and 0xFF)
                        baos.write(bits and 0xFF)
                    }
                    is Double -> {
                        val bits = arg.toRawBits()
                        baos.write(((bits shr 56) and 0xFF).toInt())
                        baos.write(((bits shr 48) and 0xFF).toInt())
                        baos.write(((bits shr 40) and 0xFF).toInt())
                        baos.write(((bits shr 32) and 0xFF).toInt())
                        baos.write(((bits shr 24) and 0xFF).toInt())
                        baos.write(((bits shr 16) and 0xFF).toInt())
                        baos.write(((bits shr 8) and 0xFF).toInt())
                        baos.write((bits and 0xFF).toInt())
                    }
                    is Boolean -> {}
                    is ByteArray -> {
                        baos.write((arg.size shr 24) and 0xFF)
                        baos.write((arg.size shr 16) and 0xFF)
                        baos.write((arg.size shr 8) and 0xFF)
                        baos.write(arg.size and 0xFF)
                        baos.write(arg)
                        while (baos.size() % 4 != 0) baos.write(0)
                    }
                    else -> {
                        val sb = arg.toString().toByteArray(Charsets.UTF_8)
                        baos.write(sb)
                        baos.write(0)
                        while (baos.size() % 4 != 0) baos.write(0)
                    }
                }
            }
        } else {
            val comma = byteArrayOf(0x2C)
            baos.write(comma)
            while (baos.size() % 4 != 0) baos.write(0)
        }

        return baos.toByteArray()
    }

    data class OSCMessage(
        val address: String,
        val args: List<Any>
    )
}
