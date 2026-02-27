package com.androsnd

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.support.v4.media.session.PlaybackStateCompat
import com.androsnd.db.AppDatabase
import com.androsnd.db.entity.FolderEntity
import com.androsnd.db.entity.SongEntity

class PlaylistManager(private val context: Context, private val db: AppDatabase) {

    companion object {
        private const val PREFS_NAME = "androsnd_prefs"
        private const val KEY_FOLDER_URI = "folder_uri"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var _songs: List<SongEntity> = emptyList()
    val songs: List<SongEntity> get() = _songs

    private var _folders: List<FolderEntity> = emptyList()
    val folders: List<FolderEntity> get() = _folders

    // Maps song list index → folder list index for O(1) lookup
    private val songIndexToFolderIndex = mutableMapOf<Int, Int>()

    var currentIndex: Int = 0
        private set
    var isShuffleOn: Boolean = false
        private set
    var repeatMode: Int = PlaybackStateCompat.REPEAT_MODE_NONE
        private set

    fun loadFromDatabase() {
        _folders = db.folderDao().getAllOrdered()
        _songs = db.songDao().getAllOrdered()
        rebuildFolderIndex()
        if (currentIndex >= _songs.size) currentIndex = 0
    }

    private fun rebuildFolderIndex() {
        songIndexToFolderIndex.clear()
        val folderIdToIndex = _folders.withIndex().associate { (fi, folder) -> folder.id to fi }
        _songs.forEachIndexed { si, song ->
            folderIdToIndex[song.folderId]?.let { fi -> songIndexToFolderIndex[si] = fi }
        }
    }

    fun saveFolderUri(uri: Uri) {
        prefs.edit().putString(KEY_FOLDER_URI, uri.toString()).apply()
    }

    fun loadSavedFolderUri(): Uri? {
        val uriString = prefs.getString(KEY_FOLDER_URI, null) ?: return null
        return Uri.parse(uriString)
    }

    fun getCurrentSong(): SongEntity? = _songs.getOrNull(currentIndex)

    fun setCurrentIndex(index: Int) {
        if (index in _songs.indices) currentIndex = index
    }

    fun getFolderIndexForSong(songIndex: Int): Int = songIndexToFolderIndex[songIndex] ?: -1

    fun nextSong(): SongEntity? {
        if (_songs.isEmpty()) return null
        currentIndex = (currentIndex + 1) % _songs.size
        return getCurrentSong()
    }

    fun prevSong(): SongEntity? {
        if (_songs.isEmpty()) return null
        currentIndex = if (currentIndex <= 0) _songs.size - 1 else currentIndex - 1
        return getCurrentSong()
    }

    fun nextFolder(): SongEntity? {
        if (_folders.isEmpty() || _songs.isEmpty()) return null
        val currentFolderIndex = getFolderIndexForSong(currentIndex)
        val nextFolderIndex = (currentFolderIndex + 1) % _folders.size
        val nextFolderId = _folders[nextFolderIndex].id
        val firstSongIdx = _songs.indexOfFirst { it.folderId == nextFolderId }
        if (firstSongIdx >= 0) currentIndex = firstSongIdx
        return getCurrentSong()
    }

    fun prevFolder(): SongEntity? {
        if (_folders.isEmpty() || _songs.isEmpty()) return null
        val currentFolderIndex = getFolderIndexForSong(currentIndex)
        val prevFolderIndex = if (currentFolderIndex <= 0) _folders.size - 1 else currentFolderIndex - 1
        val prevFolderId = _folders[prevFolderIndex].id
        val firstSongIdx = _songs.indexOfFirst { it.folderId == prevFolderId }
        if (firstSongIdx >= 0) currentIndex = firstSongIdx
        return getCurrentSong()
    }

    fun shuffleSong(): SongEntity? {
        if (_songs.isEmpty()) return null
        if (_songs.size > 1) {
            val next = kotlin.random.Random.nextInt(_songs.size - 1)
            currentIndex = if (next >= currentIndex) next + 1 else next
        }
        return getCurrentSong()
    }

    fun toggleShuffle(): Boolean {
        isShuffleOn = !isShuffleOn
        return isShuffleOn
    }

    fun setRepeatMode(mode: Int) {
        if (mode in listOf(
                PlaybackStateCompat.REPEAT_MODE_NONE, PlaybackStateCompat.REPEAT_MODE_ONE,
                PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP
            )
        ) {
            repeatMode = mode
        }
    }
}
