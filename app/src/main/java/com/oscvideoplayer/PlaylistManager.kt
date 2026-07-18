package com.oscvideoplayer

import android.util.Log
import java.io.File

class PlaylistManager {

    companion object {
        private const val TAG = "PlaylistManager"
    }

    enum class RepeatMode {
        NONE,       // Play once, stop at end
        ONE,        // Repeat current video
        ALL,        // Repeat entire playlist
        SHUFFLE     // Shuffle play
    }

    data class PlaylistItem(
        val path: String,
        val name: String,
        val duration: Long = 0L
    )

    private val playlist = mutableListOf<PlaylistItem>()
    private var currentIndex = -1
    var repeatMode: RepeatMode = RepeatMode.ALL
    private var shuffleOrder = mutableListOf<Int>()
    private var playbackHistory = mutableListOf<Int>()

    val size: Int get() = playlist.size
    val isEmpty: Boolean get() = playlist.isEmpty()
    val currentItem: PlaylistItem? get() = if (currentIndex in playlist.indices) playlist[currentIndex] else null
    val currentItemIndex: Int get() = currentIndex
    val allItems: List<PlaylistItem> get() = playlist.toList()

    fun add(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        playlist.add(PlaylistItem(path, file.name, file.length()))
        Log.d(TAG, "Added to playlist: ${file.name}")
        if (currentIndex < 0 && playlist.size == 1) currentIndex = 0
        rebuildShuffleOrder()
        return true
    }

    fun addAll(paths: List<String>) {
        var added = 0
        for (path in paths) {
            val file = File(path)
            if (file.exists()) {
                playlist.add(PlaylistItem(path, file.name, file.length()))
                added++
            }
        }
        if (currentIndex < 0 && playlist.isNotEmpty()) currentIndex = 0
        rebuildShuffleOrder()
        Log.d(TAG, "Added $added items to playlist")
    }

    fun remove(index: Int): Boolean {
        if (index !in playlist.indices) return false
        playlist.removeAt(index)
        if (currentIndex >= playlist.size) currentIndex = playlist.size - 1
        if (currentIndex < 0 && playlist.isNotEmpty()) currentIndex = 0
        rebuildShuffleOrder()
        return true
    }

    fun removeByPath(path: String): Boolean {
        val idx = playlist.indexOfFirst { it.path == path }
        return if (idx >= 0) remove(idx) else false
    }

    fun clear() {
        playlist.clear()
        currentIndex = -1
        shuffleOrder.clear()
        playbackHistory.clear()
        Log.d(TAG, "Playlist cleared")
    }

    fun setCurrentByPath(path: String): Boolean {
        val idx = playlist.indexOfFirst { it.path == path }
        return if (idx >= 0) { currentIndex = idx; true } else false
    }

    fun next(): PlaylistItem? {
        if (playlist.isEmpty()) return null
        return when (repeatMode) {
            RepeatMode.NONE -> {
                if (currentIndex < playlist.size - 1) {
                    currentIndex++
                    playlist[currentIndex]
                } else null
            }
            RepeatMode.ONE -> {
                playlist[currentIndex]
            }
            RepeatMode.ALL -> {
                currentIndex = (currentIndex + 1) % playlist.size
                playlist[currentIndex]
            }
            RepeatMode.SHUFFLE -> {
                playbackHistory.add(currentIndex)
                if (shuffleOrder.isEmpty()) rebuildShuffleOrder()
                currentIndex = shuffleOrder.removeFirst()
                playlist[currentIndex]
            }
        }
    }

    fun previous(): PlaylistItem? {
        if (playlist.isEmpty()) return null
        if (repeatMode == RepeatMode.SHUFFLE && playbackHistory.isNotEmpty()) {
            currentIndex = playbackHistory.removeLast()
            return playlist[currentIndex]
        }
        currentIndex = when {
            currentIndex <= 0 -> playlist.size - 1
            else -> currentIndex - 1
        }
        return playlist[currentIndex]
    }

    fun jumpTo(index: Int): PlaylistItem? {
        if (index !in playlist.indices) return null
        currentIndex = index
        return playlist[index]
    }

    fun getPlaylistSnapshot(): List<Map<String, Any>> {
        return playlist.mapIndexed { idx, item ->
            mapOf(
                "index" to idx,
                "name" to item.name,
                "path" to item.path,
                "isCurrent" to (idx == currentIndex)
            )
        }
    }

    private fun rebuildShuffleOrder() {
        shuffleOrder = (0 until playlist.size).toMutableList()
        shuffleOrder.shuffle()
    }

    fun updateRepeatMode(mode: RepeatMode) {
        repeatMode = mode
        Log.d(TAG, "Repeat mode set to $mode")
    }

    fun setRepeatModeFromString(mode: String): Boolean {
        return when (mode.lowercase()) {
            "none", "0" -> { updateRepeatMode(RepeatMode.NONE); true }
            "one", "1" -> { updateRepeatMode(RepeatMode.ONE); true }
            "all", "2" -> { updateRepeatMode(RepeatMode.ALL); true }
            "shuffle", "3" -> { updateRepeatMode(RepeatMode.SHUFFLE); true }
            else -> false
        }
    }
}
