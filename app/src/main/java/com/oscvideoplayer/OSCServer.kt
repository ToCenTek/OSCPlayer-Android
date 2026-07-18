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
        val key = address.hostAddress ?: return ClientInfo(address, sourcePort, sourcePort)
        return clients.getOrPut(key) { ClientInfo(address, sourcePort, sourcePort) }
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

            "stop" -> {
                val param = parts.getOrNull(1) ?: ""
                val arg = if (param.isEmpty() && msg.args.isNotEmpty()) msg.args[0]?.toString() ?: "" else param

                when {
                    arg == "exit" -> {
                        mainActivity?.stopVideo()
                        mainActivity?.finish()
                        return
                    }
                    arg == "shutdown" -> { sendShutdown(); return }
                    arg == "reboot" -> { sendReboot(); return }
                    arg == "hello" -> {
                        mainActivity?.stopVideo()
                        response = OSCMessage("/Stopped", listOf("hello"))
                    }
                    arg.isEmpty() -> {
                        mainActivity?.stopVideo()
                        response = OSCMessage("/Stopped", listOf(""))
                    }
                    arg.contains(".") -> {
                        val seconds = arg.toFloatOrNull() ?: 0f
                        mainActivity?.stopAtTime(seconds)
                        response = OSCMessage("/Stopped", listOf("${seconds}s"))
                    }
                    else -> {
                        val frameNum = arg.toIntOrNull() ?: 0
                        mainActivity?.stopAtFrame(frameNum)
                        response = OSCMessage("/Stopped", listOf("$frameNum"))
                    }
                }
            }

            "pause" -> {
                val param = parts.getOrNull(1) ?: ""
                val value = if (param.isEmpty() && msg.args.isNotEmpty()) {
                    (msg.args[0] as? Number)?.toInt() ?: -1
                } else param.toIntOrNull() ?: -1

                when (value) {
                    0 -> mainActivity?.resumeVideo()
                    1 -> mainActivity?.pauseVideo()
                    else -> mainActivity?.togglePause()
                }
                response = OSCMessage("/Paused", listOf(mainActivity?.isPaused() == true))
            }

            "volume" -> {
                var volume = if (parts.size > 1) parts[1].toFloatOrNull() else null
                if (volume == null && msg.args.isNotEmpty()) {
                    volume = (msg.args[0] as? Number)?.toFloat()
                }
                volume = (volume ?: 0.5f).coerceIn(0f, 1f)
                mainActivity?.setVolume(volume)
                response = OSCMessage("/Volume", listOf("${(volume * 100).toInt()}%"))
            }

            "loop" -> {
                var enable = true
                val param = parts.getOrNull(1) ?: ""
                if (param.isNotEmpty()) enable = param.toIntOrNull() != 0
                else if (msg.args.isNotEmpty()) enable = (msg.args[0] as? Number)?.toInt() != 0
                mainActivity?.setLoop(enable)
                response = OSCMessage("/Loop", listOf(enable))
            }

            "seek" -> {
                var param = parts.getOrNull(1) ?: ""
                if (param.isEmpty() && msg.args.isNotEmpty()) {
                    param = (msg.args[0] as? Number)?.toString() ?: ""
                }
                when {
                    param.startsWith("-") -> {
                        val seconds = kotlin.math.abs(param.toFloatOrNull() ?: 0f)
                        mainActivity?.seekToTime(-seconds)
                        response = OSCMessage("/SeekTo", listOf("-${seconds}s"))
                    }
                    param.isNotEmpty() -> {
                        val seconds = param.toFloatOrNull() ?: 0f
                        mainActivity?.seekToTime(seconds)
                        response = OSCMessage("/SeekTo", listOf("${seconds}s"))
                    }
                    else -> response = OSCMessage("/Error", listOf("seek requires time"))
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
                    "jump" -> {
                        val idx = parts.getOrNull(2)?.toIntOrNull() ?: -1
                        if (idx >= 0) {
                            mainActivity?.jumpToPlaylistIndex(idx)
                            response = OSCMessage("/PlaylistJump", listOf(idx))
                        } else response = OSCMessage("/Error", listOf("Invalid index"))
                    }
                    "mode" -> {
                        val mode = parts.getOrNull(2) ?: ""
                        if (mode.isNotEmpty()) {
                            mainActivity?.setPlaylistMode(mode)
                            response = OSCMessage("/PlaylistMode", listOf(mode))
                        } else response = OSCMessage("/PlaylistMode", listOf(mainActivity?.getPlaylistMode() ?: "all"))
                    }
                    "list" -> {
                        val items = mainActivity?.getPlaylistItems() ?: emptyList()
                        val names = items.map { it["name"] as? String ?: "" }
                        response = OSCMessage("/Playlist", listOf(names.joinToString("\n")))
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
                var param = parts.getOrNull(1) ?: ""
                if (param.isEmpty() && msg.args.isNotEmpty()) param = msg.args[0]?.toString() ?: ""
                val value = param.toIntOrNull() ?: -1
                if (value > 0) {
                    mainActivity?.setVideoFrameRate(value.toDouble())
                    response = OSCMessage("/FPS", listOf(value))
                } else if (value == 0) {
                    response = OSCMessage("/FPS", listOf("OFF"))
                } else {
                    response = OSCMessage("/FPS", listOf(mainActivity?.getVideoFrameRate() ?: 30.0))
                }
            }

            "list" -> {
                var subCmd = parts.getOrNull(1) ?: ""
                if (subCmd.isEmpty() && msg.args.isNotEmpty()) subCmd = msg.args[0]?.toString() ?: ""
                when (subCmd) {
                    "videos" -> {
                        val videos = mainActivity?.getVideoList() ?: emptyList()
                        response = OSCMessage("/Videos", listOf(videos.joinToString("\n") { it.name }))
                    }
                    "audio" -> response = OSCMessage("/AudioList", listOf("No audio devices"))
                    "display" -> response = OSCMessage("/DisplayList", listOf("Display 0"))
                    "usb", "external" -> {
                        val usbDirs = fileManager?.getUSBStorages() ?: emptyList()
                        val items = mutableListOf<String>()
                        for (dir in usbDirs) {
                            val videos = fileManager?.getVideos(dir.absolutePath) ?: emptyList()
                            for (v in videos) {
                                items.add("[${dir.name}] ${v.name}")
                            }
                        }
                        response = OSCMessage("/ExternalVideos", listOf(
                            if (items.isEmpty()) "No external videos found" else items.joinToString("\n")
                        ))
                    }
                    else -> response = OSCMessage("/Error", listOf("Unknown list command"))
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
                if (msg.args.size >= 2) {
                    val name = msg.args[0]?.toString() ?: ""
                    val portStr = msg.args[1]?.toString() ?: ""
                    val port = portStr.toIntOrNull() ?: 0
                    if (port > 0) {
                        updateClientReplyPort(client.address, port)
                        replyInfo = "Hello, $name:$port"
                    }
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
                    "restart" -> {
                        response = OSCMessage("/Config", listOf("restarting"))
                        mainActivity?.restartPlayer()
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
                        val time = parts.getOrNull(2) ?: ""
                        if (time.isNotEmpty()) {
                            mainActivity?.scheduleStart(time)
                            response = OSCMessage("/Schedule", listOf("start=$time"))
                        } else response = OSCMessage("/Error", listOf("Usage: /schedule/start/HH:mm"))
                    }
                    "stop" -> {
                        val time = parts.getOrNull(2) ?: ""
                        if (time.isNotEmpty()) {
                            mainActivity?.scheduleStop(time)
                            response = OSCMessage("/Schedule", listOf("stop=$time"))
                        } else response = OSCMessage("/Error", listOf("Usage: /schedule/stop/HH:mm"))
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
                    "schedule" -> {
                        val action = parts.getOrNull(2) ?: ""
                        val time = parts.getOrNull(3) ?: ""
                        when (action) {
                            "on" -> {
                                mainActivity?.schedulePowerOn(time)
                                response = OSCMessage("/Power", listOf("schedule=on:$time"))
                            }
                            "off" -> {
                                mainActivity?.schedulePowerOff(time)
                                response = OSCMessage("/Power", listOf("schedule=off:$time"))
                            }
                            "clear" -> {
                                mainActivity?.schedulePowerClear()
                                response = OSCMessage("/Power", listOf("schedule=cleared"))
                            }
                            else -> response = OSCMessage("/Error", listOf("Usage: /power/schedule/on|off|clear/HH:mm"))
                        }
                    }
                    else -> response = OSCMessage("/Power", listOf("usage: /power/on|off|schedule"))
                }
            }

            "screenshot" -> {
                val path = mainActivity?.takeScreenshot()
                response = OSCMessage("/Screenshot", listOf(path ?: "failed"))
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
                    appendLine("  /stop[/time|frame] Stop playback")
                    appendLine("  /pause[/0|1]      Toggle/resume/pause")
                    appendLine("  /volume/0.0-1.0   Set volume")
                    appendLine("  /seek/seconds     Seek to time")
                    appendLine("  /speed/0.5-4.0    Set playback speed")
                    appendLine("  /loop[/0|1]       Toggle loop")
                    appendLine("Playlist:")
                    appendLine("  /playlist/add/name   Add to playlist")
                    appendLine("  /playlist/remove/idx Remove from playlist")
                    appendLine("  /playlist/clear      Clear playlist")
                    appendLine("  /playlist/next|prev  Navigate playlist")
                    appendLine("  /playlist/jump/idx   Jump to index")
                    appendLine("  /playlist/mode/0|1|2|3  Repeat mode")
                    appendLine("  /playlist/list       List playlist")
                    appendLine("Display:")
                    appendLine("  /tct/text/size/pos  Show text overlay")
                    appendLine("  /fullscreen         Fullscreen")
                    appendLine("  /screenshot         Take screenshot")
                    appendLine("  /upload             Show web upload URL")
                    appendLine("Info:")
                    appendLine("  /info[/name]        Video info (optional name)")
                    appendLine("  /info/display       Show display capabilities")
                    appendLine("  /status             Full status")
                    appendLine("  /list/videos        List videos")
                    appendLine("  /list/external      List external videos (USB/SD)")
                    appendLine("  /fps                Get/set FPS")
                    appendLine("Config:")
                    appendLine("  /config/dir[/path]  Get/set default dir")
                    appendLine("  /config/watchdog[/0|1]  Toggle watchdog")
                    appendLine("  /config/heartbeat   Ping watchdog")
                    appendLine("  /config/reload      Rescan videos")
                    appendLine("  /config/restart     Restart player")
                    appendLine("  /config/startup/name  Set startup video")
                    appendLine("  /config/display/mode/N  Set color mode (0=SDR, 1=wide, 2=HDR)")
                    appendLine("  /config/display/info    Show display info")
                    appendLine("Schedule:")
                    appendLine("  /schedule/start/HH:mm  Set start time")
                    appendLine("  /schedule/stop/HH:mm   Set stop time")
                    appendLine("  /schedule/clear        Clear schedule")
                    appendLine("Power:")
                    appendLine("  /power/on|off         Display power")
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
                    appendLine("  /stop/exit            Exit app")
                    appendLine("  /shutdown   Shutdown device")
                    appendLine("  /reboot     Reboot device")
                }
                response = OSCMessage("/Help", listOf(helpText))
            }

            "shutdown" -> { sendShutdown(); return }
            "reboot" -> { sendReboot(); return }
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
            val intent = android.content.Intent("android.intent.action.ACTION_REQUEST_SHUTDOWN").apply {
                putExtra("android.intent.extra.KEY_CONFIRM", false)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            serviceContext?.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Shutdown failed: ${e.message}")
        }
    }

    private fun sendReboot() {
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_REBOOT).apply {
                putExtra("android.intent.extra.KEY_CONFIRM", false)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            serviceContext?.startActivity(intent)
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
