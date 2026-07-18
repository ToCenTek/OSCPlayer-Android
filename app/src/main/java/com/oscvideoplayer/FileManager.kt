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

        fun isExternalPath(path: String): Boolean {
            return !path.contains("/emulated/") && !path.contains("/sdcard/")
        }
    }

    fun deleteFile(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        // External storage: read-only, never delete
        if (isExternalPath(path)) {
            Log.w(TAG, "Cannot delete external file: $path")
            return false
        }
        return try {
            file.delete() || deleteViaMediaStore(path) || deleteViaRename(file)
        } catch (e: Exception) {
            Log.e(TAG, "Delete failed: ${e.message}")
            false
        }
    }

    private fun deleteViaMediaStore(path: String): Boolean {
        return try {
            val projection = arrayOf(MediaStore.Video.Media._ID)
            val selection = MediaStore.Video.Media.DATA + "=?"
            val selectionArgs = arrayOf(path)
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                    context.contentResolver.delete(uri, null, null) > 0
                } else false
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "MediaStore delete failed: ${e.message}")
            false
        }
    }

    private fun deleteViaRename(file: File): Boolean {
        return try {
            val trash = File(file.parentFile, ".trash_${file.name}")
            if (file.renameTo(trash)) {
                trash.delete()
                if (!trash.exists()) true
                else { trash.renameTo(file); false }
            } else false
        } catch (e: Exception) { false }
    }

    fun copyToInternal(sourcePath: String, targetDir: String = getDefaultDirectory()): Boolean {
        return try {
            val src = File(sourcePath)
            if (!src.exists()) return false
            val destDir = File(targetDir).also { it.mkdirs() }
            if (!destDir.canWrite()) return false
            val dest = File(destDir, src.name).let { f ->
                var i = 1; var result = f
                while (result.exists()) {
                    result = File(destDir, "${src.nameWithoutExtension}_$i.${src.extension}")
                    i++
                }
                result
            }
            FileInputStream(src).use { i -> FileOutputStream(dest).use { o -> i.copyTo(o) } }
            Log.d(TAG, "Copied to internal: ${dest.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Copy to internal failed: ${e.message}")
            false
        }
    }

    fun getUSBStorages(): List<File> {
        val checked = mutableSetOf<String>()
        val result = mutableListOf<File>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                for (vol in sm.storageVolumes) {
                    if (!vol.isEmulated && !vol.isPrimary) {
                        val path = try {
                            StorageVolume::class.java.getMethod("getPath").invoke(vol) as? String
                        } catch (_: Exception) { null }
                        if (path != null && checked.add(path)) result.add(File(path))
                    }
                }
            } catch (_: Exception) {}
        }

        for (base in listOf("/storage", "/mnt/media_rw", "/mnt/usb_storage", "/mnt/external_sd", "/mnt/extsd")) {
            val dir = File(base)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { f ->
                    if (f.isDirectory && !f.name.contains("emulated") && !f.name.contains("self")
                        && f.name != "primary" && f.name != "sdcard0" && checked.add(f.absolutePath))
                        result.add(f)
                }
            }
        }
        return result
    }

    fun copyToUSB(sourcePath: String): Boolean {
        val src = File(sourcePath)
        if (!src.exists()) return false
        val usbDir = getUSBStorages().firstOrNull() ?: return false

        val dest = File(usbDir, src.name)
        // Overwrite if exists
        if (dest.exists()) dest.delete()

        return try {
            FileInputStream(src).use { i -> FileOutputStream(dest).use { o -> i.copyTo(o) } }
            Log.d(TAG, "Copied to USB root: ${dest.absolutePath}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Direct USB copy failed: ${e.message}")
            rootCopy(src.absolutePath, dest.absolutePath)
        }
    }

    private fun rootCopy(source: String, dest: String): Boolean {
        for (cmd in listOf(
            "su -c \"cp \\\"$source\\\" \\\"$dest\\\"\"",
            "su -c \"cat \\\"$source\\\" > \\\"$dest\\\"\""
        )) {
            try {
                val pb = ProcessBuilder(*cmd.split(" ").toTypedArray())
                pb.redirectErrorStream(true)
                pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                val proc = pb.start()
                if (proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) && proc.exitValue() == 0) {
                    Log.d(TAG, "Root copy success: $cmd")
                    return true
                }
                proc.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "Root copy failed: ${e.message}")
            }
        }
        return false
    }

    // Try to make USB writable via chmod (one-time)
    fun ensureUsbWritable(): Boolean {
        for (dir in getUSBStorages()) {
            try {
                val pb = ProcessBuilder("su", "-c", "chmod 777 \"${dir.absolutePath}\"")
                pb.redirectErrorStream(true)
                pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                val proc = pb.start()
                if (proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && proc.exitValue() == 0) {
                    Log.d(TAG, "USB writable: ${dir.absolutePath}")
                    return true
                }
                proc.destroy()
            } catch (_: Exception) {}
        }
        return false
    }

    fun renameFile(oldPath: String, newName: String): Boolean {
        return try { File(oldPath).renameTo(File(File(oldPath).parent, newName)) }
        catch (e: Exception) { Log.e(TAG, "Rename failed: ${e.message}"); false }
    }

    data class MediaItem(
        val name: String, val path: String, val size: Long,
        val mediaType: MediaType, val isFromUSB: Boolean
    )

    fun getMediaFiles(dirPath: String, mediaTypes: Set<MediaType> = MediaType.values().toSet()): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        scanRecursive(File(dirPath), result, mediaTypes, isUSB = false, depth = 0)
        return result.sortedByDescending { it.size }
    }

    fun getUSBMediaFiles(mediaTypes: Set<MediaType> = MediaType.values().toSet()): List<MediaItem> {
        val result = mutableListOf<MediaItem>()
        for (dir in getUSBStorages()) {
            scanRecursive(dir, result, mediaTypes, isUSB = true, depth = 0)
        }
        return result.sortedByDescending { it.size }
    }

    private fun scanRecursive(dir: File, list: MutableList<MediaItem>, types: Set<MediaType>, isUSB: Boolean, depth: Int) {
        if (depth > 8) return
        try {
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) {
                    if (!f.name.startsWith(".") && !f.name.startsWith("Android")
                        && !f.name.equals("System", ignoreCase = true)
                        && !f.name.equals("LOST.DIR", ignoreCase = true))
                        scanRecursive(f, list, types, isUSB, depth + 1)
                } else if (f.isFile) {
                    classifyFile(f.name)?.let { t ->
                        if (t in types) list.add(MediaItem(f.name, f.absolutePath, f.length(), t, isUSB))
                    }
                }
            }
        } catch (_: Exception) {}
    }

    fun getVideos(dirPath: String): List<VideoScanner.VideoItem> {
        val list = mutableListOf<VideoScanner.VideoItem>()
        File(dirPath).takeIf { it.exists() && it.isDirectory }?.listFiles()?.forEach { f ->
            if (f.isFile && isVideoFile(f.name)) {
                list.add(VideoScanner.VideoItem(f.name, f.absolutePath, f.length(), false, "video"))
            }
        }
        return list.sortedByDescending { it.size }
    }
}
