package com.oscvideoplayer

/**
 * OSCPlayer - OSC protocol video player
 * Copyright (C) 2026 YHC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.View.OnKeyListener
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SearchActivity : AppCompatActivity() {

    companion object {
        private const val METADATA_KEY_FRAME_RATE = 35
    }

    private lateinit var listView: ListView
    private lateinit var sourceSpinner: Spinner
    private var allVideos: List<VideoScanner.VideoItem> = emptyList()
    private var filteredVideos: List<VideoScanner.VideoItem> = emptyList()
    private lateinit var fileManager: FileManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)
        
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        fileManager = FileManager(this)
        
        listView = findViewById(R.id.videoListView)
        sourceSpinner = findViewById(R.id.sourceSpinner)
        
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, 
            arrayOf("全部", "内置存储", "外置存储"))
        sourceSpinner.adapter = spinnerAdapter
        sourceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                filterVideos(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Handle item selection - TV remote
        listView.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Selection changed
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        // Handle click - for both touch and remote OK
        listView.onItemClickListener = AdapterView.OnItemClickListener { _, _, position, _ ->
            val video = filteredVideos[position]
            playVideo(video.path)
        }
        
        // Long press to show options
        listView.onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, position, _ ->
            val video = filteredVideos[position]
            showFileMenu(video)
            true
        }
        
        // Handle remote control keys on the activity
        loadVideos()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                val pos = listView.selectedItemPosition
                if (pos >= 0 && pos < filteredVideos.size) {
                    playVideo(filteredVideos[pos].path)
                    return true
                }
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
                val pos = listView.selectedItemPosition
                if (pos >= 0 && pos < filteredVideos.size) {
                    showFileMenu(filteredVideos[pos])
                    return true
                }
            }
            KeyEvent.KEYCODE_BACK -> {
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun loadVideos() {
        lifecycleScope.launch(Dispatchers.IO) {
            val scanner = VideoScanner(this@SearchActivity)
            allVideos = scanner.scanAllVideos()
            
            withContext(Dispatchers.Main) {
                filterVideos(sourceSpinner.selectedItemPosition)
            }
        }
    }

    private fun filterVideos(source: Int) {
        filteredVideos = when (source) {
            0 -> allVideos
            1 -> allVideos.filter { !it.isFromUSB }
            2 -> allVideos.filter { it.isFromUSB }
            else -> allVideos
        }
        
        updateListView()
    }

    private fun updateListView() {
        val videoNames = filteredVideos.map { 
            "${it.name} (${formatFileSize(it.size)})${if (it.isFromUSB) " [USB]" else ""}"
        }
        
        val adapter = ArrayAdapter(this, R.layout.item_video, videoNames)
        listView.adapter = adapter
        listView.setSelection(0)
        
        if (filteredVideos.isEmpty()) {
            Toast.makeText(this, R.string.no_videos, Toast.LENGTH_SHORT).show()
        }
    }

    private fun playVideo(path: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            action = "com.oscvideoplayer.PLAY"
            putExtra("video_path", path)
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        startActivity(intent)
        finish()
    }

    private fun showFileMenu(video: VideoScanner.VideoItem) {
        val items = mutableListOf<Pair<String, () -> Unit>>()

        items.add("播放" to { playVideo(video.path) })
        items.add("信息" to { showVideoInfo(video) })
        items.add("设为开机视频" to { setAsStartup(video) })
        items.add("添加到播放列表" to { addToPlaylist(video) })
        items.add("设为循环播放" to { setLoopThis(video) })
        items.add("重命名" to { renameVideo(video) })

        if (video.isFromUSB) {
            items.add("复制到内置存储" to { uploadToInternal(video) })
        } else {
            items.add("下载到USB" to { downloadToUSB(video) })
        }

        // 外部存储文件不可删除
        if (!video.isFromUSB) {
            items.add("删除" to { deleteVideo(video) })
        }

        AlertDialog.Builder(this)
            .setTitle(video.name)
            .setItems(items.map { it.first }.toTypedArray()) { _, which ->
                items[which].second()
            }
            .setNegativeButton("关闭", null)
            .show()
    }

    private fun showVideoInfo(video: VideoScanner.VideoItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            val info = buildString {
                appendLine("文件名: ${video.name}")
                appendLine("路径: ${video.path}")
                appendLine("大小: ${formatFileSize(video.size)}")
                appendLine("来源: ${if (video.isFromUSB) "外置存储" else "内置存储"}")

                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(video.path)
                    val duration = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    val width = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val height = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    val rotation = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    var fps = retriever.extractMetadata(METADATA_KEY_FRAME_RATE) ?: "?"
                    val mime = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "?"
                    val bitrate = if (Build.VERSION.SDK_INT >= 30) {
                        retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0
                    } else 0L
                    val date = retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DATE) ?: ""
                    retriever.release()

                    if (fps == "?") {
                        try {
                            val extractor = MediaExtractor()
                            extractor.setDataSource(video.path)
                            for (i in 0 until extractor.trackCount) {
                                val fmt = extractor.getTrackFormat(i)
                                if (fmt.containsKey(MediaFormat.KEY_MIME) && fmt.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                                    val rate = fmt.getFloat(MediaFormat.KEY_FRAME_RATE)
                                    if (rate > 0) {
                                        fps = if (rate == rate.toInt().toFloat()) "${rate.toInt()}" else String.format(Locale.US, "%.2f", rate)
                                    }
                                    break
                                }
                            }
                            extractor.release()
                        } catch (_: Exception) {}
                    }

                    val secs = duration / 1000
                    val timeStr = String.format(Locale.US, "%02d:%02d:%02d", secs / 3600, (secs % 3600) / 60, secs % 60)
                    appendLine("时长: $timeStr")
                    if (width != null && height != null) {
                        appendLine("分辨率: ${width}x${height}")
                    }
                    if (rotation != null && rotation != "0") {
                        appendLine("旋转: ${rotation}°")
                    }
                    appendLine("帧率: ${fps}fps")
                    appendLine("格式: $mime")
                    if (bitrate > 0) {
                        appendLine("码率: ${bitrate / 1000}kbps")
                    }
                    if (date.isNotEmpty()) {
                        appendLine("拍摄日期: $date")
                    }
                } catch (e: Exception) {
                    appendLine("(无法读取视频元数据)")
                }
            }

            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@SearchActivity)
                    .setTitle("视频信息")
                    .setMessage(info.trimEnd())
                    .setPositiveButton("知道了", null)
                    .show()
            }
        }
    }

    private fun setAsStartup(video: VideoScanner.VideoItem) {
        val ext = video.name.substringAfterLast('.', "mp4")
        val newName = "hello.$ext"
        lifecycleScope.launch(Dispatchers.IO) {
            val success = fileManager.renameFile(video.path, newName)
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@SearchActivity, "已设为开机视频: $newName", Toast.LENGTH_SHORT).show()
                    loadVideos()
                } else {
                    Toast.makeText(this@SearchActivity, "设置失败, 请检查权限", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun addToPlaylist(video: VideoScanner.VideoItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            val ma = OSCServer.getInstance()?.getMainActivity()
            if (ma != null) {
                ma.addToPlaylist(video.path)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SearchActivity, "已添加到播放列表 (${ma.getPlaylistItems().size}项)", Toast.LENGTH_SHORT).show()
                }
            } else {
                val prefs = getSharedPreferences("playlist", Context.MODE_PRIVATE)
                val set = prefs.getStringSet("pending", mutableSetOf())?.toMutableSet() ?: mutableSetOf()
                set.add(video.path)
                prefs.edit().putStringSet("pending", set).apply()
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@SearchActivity, "已添加到待播放列表 (播放器启动后生效)", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun setLoopThis(video: VideoScanner.VideoItem) {
        AlertDialog.Builder(this)
            .setTitle("循环模式")
            .setMessage("将循环模式设为「单曲循环」并播放此视频?")
            .setPositiveButton("确定") { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val ma = OSCServer.getInstance()?.getMainActivity()
                    if (ma != null) {
                        ma.setLoop(true)
                        ma.setPlaylistMode("1")
                        ma.clearPlaylist()
                        ma.addToPlaylist(video.path)
                        withContext(Dispatchers.Main) {
                            ma.playVideo(video.path)
                            Toast.makeText(this@SearchActivity, "已设为单曲循环", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        val prefs = getSharedPreferences("loop", Context.MODE_PRIVATE)
                        prefs.edit()
                            .putBoolean("enabled", true)
                            .putString("video", video.path)
                            .apply()
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@SearchActivity, "已保存设置, 启动播放器后生效", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun uploadToInternal(video: VideoScanner.VideoItem) {
        val dialog = android.app.ProgressDialog(this).apply {
            setMessage("正在复制到内置存储...")
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            show()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val success = withContext(NonCancellable) { fileManager.copyToInternal(video.path) }
            withContext(Dispatchers.Main) {
                dialog.dismiss()
                if (success) {
                    Toast.makeText(this@SearchActivity, "复制成功", Toast.LENGTH_LONG).show()
                    loadVideos()
                } else {
                    Toast.makeText(this@SearchActivity, "复制失败", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun downloadToUSB(video: VideoScanner.VideoItem) {
        val usbDirs = fileManager.getUSBStorages()
        if (usbDirs.isEmpty()) {
            Toast.makeText(this, "未检测到外置存储设备", Toast.LENGTH_LONG).show()
            return
        }
        val dialog = android.app.ProgressDialog(this).apply {
            setMessage("正在复制到USB...")
            setCancelable(false)
            setCanceledOnTouchOutside(false)
            show()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            val success = withContext(NonCancellable) { fileManager.copyToUSB(video.path) }
            withContext(Dispatchers.Main) {
                dialog.dismiss()
                if (success) {
                    Toast.makeText(this@SearchActivity, "复制成功", Toast.LENGTH_LONG).show()
                    loadVideos()
                } else {
                    val paths = usbDirs.joinToString(", ") { it.absolutePath }
                    Toast.makeText(this@SearchActivity, "USB写入失败, 请检查USB权限: $paths", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun copyToInternal(video: VideoScanner.VideoItem) {
        lifecycleScope.launch(Dispatchers.IO) {
            val success = fileManager.copyToInternal(video.path)
            
            withContext(Dispatchers.Main) {
                if (success) {
                    Toast.makeText(this@SearchActivity, R.string.copy_success, Toast.LENGTH_LONG).show()
                    loadVideos()
                } else {
                    Toast.makeText(this@SearchActivity, R.string.copy_failed, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun renameVideo(video: VideoScanner.VideoItem) {
        val input = android.widget.EditText(this)
        input.setText(video.name.substringBeforeLast('.'))
        
        AlertDialog.Builder(this)
            .setTitle(R.string.rename)
            .setView(input)
            .setPositiveButton(R.string.confirm) { _, _ ->
                val newName = input.text.toString()
                if (newName.isNotEmpty()) {
                    val ext = video.name.substringAfterLast('.', "")
                    val fullName = if (ext.isNotEmpty()) "$newName.$ext" else newName
                    
                    lifecycleScope.launch(Dispatchers.IO) {
                        val success = fileManager.renameFile(video.path, fullName)
                        
                        withContext(Dispatchers.Main) {
                            if (success) {
                                Toast.makeText(this@SearchActivity, R.string.rename_success, Toast.LENGTH_SHORT).show()
                                loadVideos()
                            } else {
                                Toast.makeText(this@SearchActivity, R.string.rename_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun deleteVideo(video: VideoScanner.VideoItem) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete)
            .setMessage(R.string.confirm_delete)
            .setPositiveButton(R.string.confirm) { _, _ ->
                lifecycleScope.launch(Dispatchers.IO) {
                    val success = fileManager.deleteFile(video.path)
                    
                    withContext(Dispatchers.Main) {
                        if (success) {
                            Toast.makeText(this@SearchActivity, R.string.delete_success, Toast.LENGTH_SHORT).show()
                            loadVideos()
                        } else {
                            Toast.makeText(this@SearchActivity, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun formatFileSize(size: Long): String {
        return when {
            size < 1024 -> "$size B"
            size < 1024 * 1024 -> "${size / 1024} KB"
            size < 1024 * 1024 * 1024 -> "${size / (1024 * 1024)} MB"
            else -> "${size / (1024 * 1024 * 1024)} GB"
        }
    }
}
