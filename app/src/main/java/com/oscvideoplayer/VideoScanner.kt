package com.oscvideoplayer

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.FileObserver
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import android.util.Log
import java.io.File

class VideoScanner(private val context: Context) {
    var defaultDir: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)

    companion object {
        private const val TAG = "VideoScanner"
        private const val CACHE_TTL_MS = 30_000L

        val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "webm", "m4v", "ts", "m2ts",
            "flv", "wmv", "mpg", "mpeg", "3gp", "ogv", "divx", "vob"
        )

        private fun isVideoFile(filename: String): Boolean {
            if (filename.startsWith(".")) return false
            val ext = filename.substringAfterLast('.', "").lowercase()
            return ext in VIDEO_EXTENSIONS
        }
    }

    data class VideoItem(
        val name: String,
        val path: String,
        val size: Long,
        val isFromUSB: Boolean
    )

    private var cachedVideos: List<VideoItem>? = null
    private var cacheTime: Long = 0L
    private var fileObserver: FileObserver? = null

    fun scanAllVideos(): List<VideoItem> {
        val now = System.currentTimeMillis()
        if (cachedVideos != null && (now - cacheTime) < CACHE_TTL_MS) {
            return cachedVideos!!
        }

        val videos = linkedSetOf<VideoItem>()
        scanInternalStorage(videos)
        scanUSBStorage(videos)

        val sorted = videos.sortedByDescending { it.size }
        cachedVideos = sorted
        cacheTime = now
        return sorted
    }

    fun invalidateCache() {
        cachedVideos = null
        Log.d(TAG, "Video cache invalidated")
    }

    fun watchDirectory(dir: File) {
        stopWatching()
        defaultDir = dir
        if (!dir.exists()) return
        try {
            fileObserver = object : FileObserver(dir.absolutePath, CREATE or DELETE or MOVED_FROM or MOVED_TO) {
                override fun onEvent(event: Int, path: String?) {
                    if (path != null && isVideoFile(path)) {
                        invalidateCache()
                        Log.d(TAG, "File change detected: $path")
                    }
                }
            }
            fileObserver?.startWatching()
            Log.d(TAG, "Watching directory: ${dir.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to watch directory: ${e.message}")
        }
    }

    fun stopWatching() {
        fileObserver?.stopWatching()
        fileObserver = null
    }

    fun scanDirectory(dir: File, isUSB: Boolean): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        scanDirectoryRecursive(dir, result, isUSB)
        return result
    }

    private fun scanInternalStorage(list: MutableSet<VideoItem>) {
        if (defaultDir.exists() && defaultDir.isDirectory) {
            scanDirectoryRecursive(defaultDir, list, false)
        }
    }

    private fun scanUSBStorage(list: MutableSet<VideoItem>) {
        val checked = mutableSetOf<String>()

        // 1. Use StorageManager (API 24+) for reliable external volume detection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                for (vol in sm.storageVolumes) {
                    if (!vol.isEmulated && !vol.isPrimary) {
                        val path = try {
                            val mGetPath = StorageVolume::class.java.getMethod("getPath")
                            mGetPath.invoke(vol) as? String
                        } catch (e: Exception) { null }
                        if (path != null && checked.add(path)) {
                            scanDirectoryRecursive(File(path), list, true)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "StorageManager scan failed: ${e.message}")
            }
        }

        // 2. Fallback: scan common mount points
        val candidates = listOf("/storage", "/mnt/media_rw", "/mnt/usb_storage",
            "/mnt/external_sd", "/mnt/extsd")
        for (base in candidates) {
            val dir = File(base)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    if (file.isDirectory && !file.name.contains("emulated")
                        && !file.name.contains("self") && file.name != "primary"
                        && file.name != "sdcard0"
                        && checked.add(file.absolutePath)) {
                        scanDirectoryRecursive(file, list, true)
                    }
                }
            }
        }
    }

    private fun scanDirectoryRecursive(dir: File, list: MutableCollection<VideoItem>, isUSB: Boolean) {
        try {
            val maxDepth = 8
            scanRecursive(dir, list, isUSB, 0, maxDepth)
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning directory ${dir.absolutePath}: ${e.message}")
        }
    }

    private fun scanRecursive(dir: File, list: MutableCollection<VideoItem>, isUSB: Boolean, depth: Int, maxDepth: Int) {
        if (depth > maxDepth) return
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                if (!file.name.startsWith(".") && !file.name.startsWith("Android")) {
                    scanRecursive(file, list, isUSB, depth + 1, maxDepth)
                }
            } else if (file.isFile && isVideoFile(file.name)) {
                list.add(VideoItem(
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    isFromUSB = isUSB
                ))
            }
        }
    }

    fun findVideo(filename: String): VideoItem? {
        val videos = scanAllVideos()
        if (filename.isEmpty()) {
            return videos.find { it.name.lowercase().startsWith("hello.") }
                ?: videos.firstOrNull()
        }
        val exact = videos.find { it.name == filename }
        if (exact != null) return exact
        return videos.find { it.name.contains(filename, ignoreCase = true) }
    }

    fun findHelloVideo(): VideoItem? {
        val videos = scanAllVideos()
        return videos.find { it.name.lowercase().startsWith("hello.") }
            ?: videos.firstOrNull()
    }
}
