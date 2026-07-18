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

    companion object {
        private const val TAG = "FileManager"
        private val VIDEO_EXTENSIONS = setOf(
            "mp4", "mkv", "avi", "mov", "webm", "m4v", "ts", "m2ts",
            "flv", "wmv", "mpg", "mpeg", "3gp", "ogv"
        )

        fun getDefaultDirectory(): String {
            return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath
        }

        fun isVideoFile(filename: String): Boolean {
            if (filename.startsWith(".")) return false
            val ext = filename.substringAfterLast('.', "").lowercase()
            return ext in VIDEO_EXTENSIONS
        }
    }

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

    fun getUSBStorages(): List<File> {
        val checked = mutableSetOf<String>()
        val result = mutableListOf<File>()

        // 1. Try StorageManager (API 24+) for reliable volume detection
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
                val volumes = sm.storageVolumes
                for (vol in volumes) {
                    val isEmulated = vol.isEmulated
                    val isPrimary = vol.isPrimary
                    if (!isEmulated && !isPrimary) {
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

        // 2. Fallback: scan common mount points
        val candidates = listOf("/storage", "/mnt/media_rw", "/mnt/usb_storage",
            "/mnt/external_sd", "/mnt/extsd")
        for (base in candidates) {
            val dir = File(base)
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.forEach { file ->
                    val name = file.name
                    if (file.isDirectory && !name.contains("emulated") && !name.contains("self")
                        && name != "sdcard0" && name != "primary") {
                        if (checked.add(file.absolutePath)) {
                            result.add(file)
                        }
                    }
                }
            }
        }

        return result
    }

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
            Log.e(TAG, "No writable USB storage found")
            return false
        }
        val destFile = File(usbDir, sourceFile.name)
        // 1. Try normal Java copy
        try {
            FileInputStream(sourceFile).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Copied to USB: ${destFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Normal USB copy failed: ${e.message}, trying root...")
        }
        // 2. Fallback: root-based copy (rooted devices)
        return rootCopy(sourcePath, destFile.absolutePath)
    }

    private fun rootCopy(source: String, dest: String): Boolean {
        // 1. Try piped stdin approach (avoids su dialog on some devices)
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su"))
            val os = java.io.DataOutputStream(proc.outputStream)
            os.writeBytes("cp \"$source\" \"$dest\"\n")
            os.writeBytes("exit\n")
            os.flush()
            val done = proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            if (!done) {
                proc.destroy()
                // 2. Fallback: try with -c flag
                val proc2 = Runtime.getRuntime().exec(arrayOf("su", "-c", "cp \"$source\" \"$dest\""))
                val done2 = proc2.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                if (!done2) { proc2.destroy(); return false }
                if (proc2.exitValue() == 0) { Log.d(TAG, "Root copy success"); return true }
                Log.e(TAG, "Root copy failed: ${proc2.exitValue()}")
                return false
            }
            if (proc.exitValue() == 0) {
                Log.d(TAG, "Root copy success")
                true
            } else {
                Log.e(TAG, "Root copy failed: ${proc.exitValue()}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Root copy exception: ${e.message}")
            false
        }
    }

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

    fun deleteFile(path: String): Boolean {
        return try {
            val file = File(path)
            if (!file.exists()) {
                Log.e(TAG, "File not found: $path")
                return false
            }

            // 1. Try direct File.delete() (works on internal with WRITE_EXTERNAL_STORAGE)
            if (file.delete()) return true

            // 2. Try MediaStore ContentResolver (works on most external storage)
            val videoUri = getVideoMediaStoreUri(path)
            if (videoUri != null) {
                val deleted = context.contentResolver.delete(videoUri, null, null)
                if (deleted > 0) {
                    Log.d(TAG, "Deleted via MediaStore: $path")
                    return true
                }
            }

            // 3. If on external storage, also try raw file with different approach
            val parent = file.parentFile
            if (parent != null && !parent.absolutePath.contains("/emulated/")) {
                // Try to rename first (some devices allow rename but not delete)
                val trashFile = File(parent, ".trash_${file.name}")
                if (file.renameTo(trashFile)) {
                    trashFile.delete()
                    if (!trashFile.exists()) {
                        Log.d(TAG, "Deleted via rename+delete: $path")
                        return true
                    }
                    // rename back if delete failed
                    trashFile.renameTo(file)
                }
            }

            Log.e(TAG, "Delete failed (no permission): $path")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Delete failed: ${e.message}")
            false
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
                        isFromUSB = false
                    ))
                }
            }
        }
        return videos.sortedByDescending { it.size }
    }
}
