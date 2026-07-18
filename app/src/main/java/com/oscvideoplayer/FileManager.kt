package com.oscvideoplayer

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class FileManager(private val context: Context) {

    enum class MediaType { VIDEO, AUDIO, IMAGE }

    companion object {
        private const val TAG = "FileManager"
        private const val PREFS_WRITTEN = "written_files"
        private const val KEY_PATHS = "paths"

        val MEDIA_EXTENSIONS = mapOf(
            MediaType.VIDEO to setOf(
                "mp4", "mkv", "avi", "mov", "webm", "m4v", "ts", "m2ts",
                "flv", "wmv", "mpg", "mpeg", "3gp", "ogv", "divx", "vob"
            ),
            MediaType.AUDIO to setOf(
                "mp3", "wav", "flac", "aac", "ogg", "wma", "m4a", "opus",
                "ac3", "eac3", "dts", "ape", "wv"
            ),
            MediaType.IMAGE to setOf(
                "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic",
                "heif", "tiff", "tif", "svg", "ico"
            )
        )

        fun getDefaultDirectory(): String {
            return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath
        }

        fun classifyFile(filename: String): MediaType? {
            if (filename.startsWith(".")) return null
            val ext = filename.substringAfterLast('.', "").lowercase()
            for ((type, exts) in MEDIA_EXTENSIONS) {
                if (ext in exts) return type
            }
            return null
        }

        fun isVideoFile(filename: String): Boolean = classifyFile(filename) == MediaType.VIDEO
    }

    // --- Written-file tracking ---
    private val writtenPrefs = context.getSharedPreferences(PREFS_WRITTEN, Context.MODE_PRIVATE)

    fun isWrittenByApp(path: String): Boolean {
        return writtenPrefs.getStringSet(KEY_PATHS, emptySet())?.contains(path) == true
    }

    fun markWrittenByApp(path: String) {
        val set = writtenPrefs.getStringSet(KEY_PATHS, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        set.add(path)
        writtenPrefs.edit().putStringSet(KEY_PATHS, set).apply()
        Log.d(TAG, "Tracked written file: $path")
    }

    fun getWrittenFiles(): Set<String> {
        return writtenPrefs.getStringSet(KEY_PATHS, emptySet()) ?: emptySet()
    }

    // --- Delete with permission check ---
    fun canDeleteFile(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        // Internal storage: always allowed
        if (path.contains("/emulated/") || path.contains("/sdcard/")) return true
        // External (USB/SD): only if written by this app
        return isWrittenByApp(path)
    }

    fun deleteFile(path: String): Boolean {
        return try {
            val file = File(path)
            if (!file.exists()) {
                Log.e(TAG, "File not found: $path")
                return false
            }
            if (!canDeleteFile(path)) {
                Log.w(TAG, "Cannot delete external file not written by app: $path")
                return false
            }

            // 1. Try direct delete
            if (file.delete()) {
                removeFromWritten(path)
                return true
            }

            // 2. Try MediaStore
            val uri = getVideoMediaStoreUri(path)
            if (uri != null) {
                val deleted = context.contentResolver.delete(uri, null, null)
                if (deleted > 0) {
                    removeFromWritten(path)
                    return true
                }
            }

            // 3. On external, try rename trick
            val parent = file.parentFile
            if (parent != null && !parent.absolutePath.contains("/emulated/")) {
                val trashFile = File(parent, ".trash_${file.name}")
                if (file.renameTo(trashFile)) {
                    trashFile.delete()
                    if (!trashFile.exists()) {
                        removeFromWritten(path)
                        return true
                    }
                    trashFile.renameTo(file)
                }
            }

            Log.e(TAG, "Delete failed: $path")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Delete exception: ${e.message}")
            false
        }
    }

    private fun removeFromWritten(path: String) {
        val set = writtenPrefs.getStringSet(KEY_PATHS, mutableSetOf())?.toMutableSet() ?: mutableSetOf()
        if (set.remove(path)) {
            writtenPrefs.edit().putStringSet(KEY_PATHS, set).apply()
        }
    }

    private fun getVideoMediaStoreUri(filePath: String): Uri? {
        return try {
            val projection = arrayOf(MediaStore.Video.Media._ID)
            val selection = MediaStore.Video.Media.DATA + "=?"
            val selectionArgs = arrayOf(filePath)
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    return Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore lookup failed: ${e.message}")
            null
        }
    }

    // --- Copy to internal ---
    fun copyToInternal(sourcePath: String, targetDir: String = getDefaultDirectory()): Boolean {
        return try {
            val sourceFile = File(sourcePath)
            if (!sourceFile.exists()) {
                Log.e(TAG, "Source not found: $sourcePath")
                return false
            }
            val destDir = File(targetDir)
            if (!destDir.exists()) destDir.mkdirs()
            val name = sourceFile.name.substringBeforeLast('.')
            val ext = sourceFile.name.substringAfterLast('.', "")
            var destFile = File(destDir, sourceFile.name)
            var idx = 1
            while (destFile.exists()) {
                val newName = "${name}_${idx}.${ext}"
                destFile = File(destDir, newName)
                idx++
            }

            if (!destDir.canWrite()) {
                Log.e(TAG, "Cannot write to target directory: $targetDir")
                return false
            }

            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Copied to: ${destFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Copy failed: ${e.message}")
            false
        }
    }

    // --- USB storage detection ---
    fun getUSBStorages(): List<File> {
        val checked = mutableSetOf<String>()
        val result = mutableListOf<File>()

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
                            result.add(File(path))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "StorageManager scan failed: ${e.message}")
            }
        }

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
                        result.add(file)
                    }
                }
            }
        }

        return result
    }

    // --- Copy to USB with tracking ---
    fun copyToUSB(sourcePath: String): Boolean {
        val sourceFile = File(sourcePath)
        if (!sourceFile.exists()) {
            Log.e(TAG, "Source not found: $sourcePath")
            return false
        }
        val usbDirs = getUSBStorages()
        val usbDir = usbDirs.firstOrNull { it.canWrite() || it.listFiles() != null }
            ?: usbDirs.firstOrNull()
            ?: run {
                Log.e(TAG, "No USB storage found")
                return false
            }

        val name = sourceFile.name.substringBeforeLast('.')
        val ext = sourceFile.name.substringAfterLast('.', "")
        var destFile = File(usbDir, sourceFile.name)
        var idx = 1
        while (destFile.exists()) {
            val newName = "${name}_${idx}.${ext}"
            destFile = File(usbDir, newName)
            idx++
        }

        // 1. Try direct Java copy
        try {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            markWrittenByApp(destFile.absolutePath)
            Log.d(TAG, "Copied to USB: ${destFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Normal copy failed: ${e.message}")
        }

        // 2. Try root copy via su shell
        if (rootCopy(sourceFile.absolutePath, destFile.absolutePath)) {
            markWrittenByApp(destFile.absolutePath)
            return true
        }

        // 3. Try chmod + Java copy via root (make USB writable once)
        if (makeUsbWritable(usbDir.absolutePath)) {
            try {
                FileInputStream(sourceFile).use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                markWrittenByApp(destFile.absolutePath)
                Log.d(TAG, "Copy to USB after chmod: ${destFile.absolutePath}")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "Copy after chmod still failed: ${e.message}")
            }
        }

        return false
    }

    private fun makeUsbWritable(usbPath: String): Boolean {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "chmod 777 \"$usbPath\""))
            val done = proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
            if (done && proc.exitValue() == 0) {
                Log.d(TAG, "USB dir made writable: $usbPath")
                true
            } else {
                Log.w(TAG, "chmod failed or timed out")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "chmod exception: ${e.message}")
            false
        }
    }

    private fun rootCopy(source: String, dest: String): Boolean {
        // Approach 1: su -c with small timeout, stdin from /dev/null
        try {
            val pb = ProcessBuilder("su", "-c", "cp \"$source\" \"$dest\"")
            pb.redirectErrorStream(true)
            pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
            val proc = pb.start()
            val done = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (done && proc.exitValue() == 0) {
                Log.d(TAG, "Root copy (approach 1) success")
                return true
            }
            proc.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Root copy approach 1 failed: ${e.message}")
        }

        // Approach 2: cat source > dest via su
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat \"$source\" > \"$dest\""))
            val done = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
            if (done && proc.exitValue() == 0) {
                Log.d(TAG, "Root copy (approach 2) success")
                return true
            }
            proc.destroy()
        } catch (e: Exception) {
            Log.w(TAG, "Root copy approach 2 failed: ${e.message}")
        }

        return false
    }

    // --- Rename ---
    fun renameFile(oldPath: String, newName: String): Boolean {
        return try {
            val oldFile = File(oldPath)
            if (!oldFile.exists()) return false
            oldFile.renameTo(File(oldFile.parent, newName))
        } catch (e: Exception) {
            Log.e(TAG, "Rename failed: ${e.message}")
            false
        }
    }

    // --- Scan media files ---
    data class MediaItem(
        val name: String,
        val path: String,
        val size: Long,
        val mediaType: MediaType,
        val isFromUSB: Boolean
    )

    fun getMediaFiles(dirPath: String, mediaTypes: Set<MediaType> = MediaType.values().toSet()): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        scanMediaRecursive(File(dirPath), result, mediaTypes, isUSB = false, depth = 0)
        return result.sortedByDescending { it.size }
    }

    fun getUSBMediaFiles(mediaTypes: Set<MediaType> = MediaType.values().toSet()): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        for (usbDir in getUSBStorages()) {
            scanMediaRecursive(usbDir, result, mediaTypes, isUSB = true, depth = 0)
        }
        return result.sortedByDescending { it.size }
    }

    private fun scanMediaRecursive(
        dir: File,
        list: MutableList<MediaItem>,
        mediaTypes: Set<MediaType>,
        isUSB: Boolean,
        depth: Int
    ) {
        if (depth > 8) return
        try {
            val files = dir.listFiles() ?: return
            for (file in files) {
                if (file.isDirectory) {
                    if (!file.name.startsWith(".") && !file.name.startsWith("Android")
                        && !file.name.equals("System", ignoreCase = true)
                        && !file.name.equals("LOST.DIR", ignoreCase = true)) {
                        scanMediaRecursive(file, list, mediaTypes, isUSB, depth + 1)
                    }
                } else if (file.isFile) {
                    val type = classifyFile(file.name) ?: continue
                    if (type in mediaTypes) {
                        list.add(MediaItem(file.name, file.absolutePath, file.length(), type, isUSB))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Scan error ${dir.absolutePath}: ${e.message}")
        }
    }

    // --- Compatibility: get only video files ---
    fun getVideos(dirPath: String): List<VideoScanner.VideoItem> {
        val videos = mutableListOf<VideoScanner.VideoItem>()
        val dir = File(dirPath)
        if (dir.exists() && dir.isDirectory) {
            dir.listFiles()?.forEach { file ->
                if (file.isFile && isVideoFile(file.name)) {
                    videos.add(VideoScanner.VideoItem(
                        name = file.name,
                        path = file.absolutePath,
                        size = file.length(),
                        isFromUSB = false,
                        mediaType = "video"
                    ))
                }
            }
        }
        return videos.sortedByDescending { it.size }
    }
}
