package com.androsnd

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.androsnd.model.PlaylistFolder
import com.androsnd.model.Song

class PlaylistManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "androsnd_prefs"
        private const val KEY_FOLDER_URI = "folder_uri"
        private val AUDIO_EXTENSIONS = setOf("mp3", "ogg", "flac", "aac", "m4a", "opus")
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _songs = mutableListOf<Song>()
    val songs: List<Song> get() = _songs

    private val _folders = mutableListOf<PlaylistFolder>()
    val folders: List<PlaylistFolder> get() = _folders

    var currentIndex: Int = 0
        private set
    var isShuffleOn: Boolean = false
        private set

    fun loadSavedFolder(): Uri? {
        val uriString = prefs.getString(KEY_FOLDER_URI, null) ?: return null
        return Uri.parse(uriString)
    }

    fun scanFolder(treeUri: Uri) {
        prefs.edit().putString(KEY_FOLDER_URI, treeUri.toString()).apply()
        _songs.clear()
        _folders.clear()

        val rootDoc = DocumentFile.fromTreeUri(context, treeUri) ?: return
        scanDocumentFile(rootDoc, rootDoc.name ?: "Music")

        _folders.sortBy { it.name }
        _folders.forEach { folder ->
            folder.songs.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { _songs[it].displayName })
        }

        // Rebuild _songs in display order so index-based navigation follows display order
        val reorderedSongs = mutableListOf<Song>()
        for (folder in _folders) {
            val newIndices = mutableListOf<Int>()
            for (oldIndex in folder.songs) {
                newIndices.add(reorderedSongs.size)
                reorderedSongs.add(_songs[oldIndex])
            }
            folder.songs.clear()
            folder.songs.addAll(newIndices)
        }
        _songs.clear()
        _songs.addAll(reorderedSongs)

        currentIndex = 0
    }

    private fun scanDocumentFile(doc: DocumentFile, parentPath: String) {
        if (!doc.isDirectory) return

        val children = doc.listFiles()
        val audioFiles = children.filter { it.isFile && isAudioFile(it.name ?: "") }
        val subDirs = children.filter { it.isDirectory }

        if (audioFiles.isNotEmpty()) {
            val folderName = doc.name ?: parentPath
            val folder = PlaylistFolder(name = folderName, path = parentPath)
            _folders.add(folder)

            for (file in audioFiles) {
                val songIndex = _songs.size
                _songs.add(
                    Song(
                        uri = file.uri,
                        displayName = file.name ?: "Unknown",
                        folderPath = parentPath,
                        folderName = folderName
                    )
                )
                folder.songs.add(songIndex)
            }
        }

        for (subDir in subDirs) {
            val subPath = "$parentPath/${subDir.name}"
            scanDocumentFile(subDir, subPath)
        }
    }

    private fun isAudioFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    fun getCurrentSong(): Song? = songs.getOrNull(currentIndex)

    fun getFolderIndexForSong(songIndex: Int): Int {
        return folders.indexOfFirst { it.songs.contains(songIndex) }
    }

    fun nextSong(): Song? {
        if (songs.isEmpty()) return null
        currentIndex = (currentIndex + 1) % songs.size
        return getCurrentSong()
    }

    fun prevSong(): Song? {
        if (songs.isEmpty()) return null
        currentIndex = if (currentIndex <= 0) songs.size - 1 else currentIndex - 1
        return getCurrentSong()
    }

    fun nextFolder(): Song? {
        if (folders.isEmpty() || songs.isEmpty()) return null
        val currentFolderIndex = getFolderIndexForSong(currentIndex)
        val nextFolderIndex = (currentFolderIndex + 1) % folders.size
        val nextFolder = folders[nextFolderIndex]
        currentIndex = nextFolder.songs.firstOrNull() ?: 0
        return getCurrentSong()
    }

    fun prevFolder(): Song? {
        if (folders.isEmpty() || songs.isEmpty()) return null
        val currentFolderIndex = getFolderIndexForSong(currentIndex)
        val prevFolderIndex = if (currentFolderIndex <= 0) folders.size - 1 else currentFolderIndex - 1
        val prevFolder = folders[prevFolderIndex]
        currentIndex = prevFolder.songs.firstOrNull() ?: 0
        return getCurrentSong()
    }

    fun shuffleSong(): Song? {
        if (songs.isEmpty()) return null
        currentIndex = kotlin.random.Random.nextInt(songs.size)
        return getCurrentSong()
    }

    fun setCurrentIndex(index: Int) {
        if (index in songs.indices) currentIndex = index
    }

    fun toggleShuffle(): Boolean {
        isShuffleOn = !isShuffleOn
        return isShuffleOn
    }
}
