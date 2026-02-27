package com.androsnd

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.support.v4.media.session.PlaybackStateCompat
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
    var repeatMode: Int = PlaybackStateCompat.REPEAT_MODE_NONE
        private set

    fun setRepeatMode(mode: Int) {
        if (mode in listOf(PlaybackStateCompat.REPEAT_MODE_NONE, PlaybackStateCompat.REPEAT_MODE_ONE,
                           PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP)) {
            repeatMode = mode
        }
    }

    fun loadSavedFolder(): Uri? {
        val uriString = prefs.getString(KEY_FOLDER_URI, null) ?: return null
        return Uri.parse(uriString)
    }

    fun scanFolder(treeUri: Uri) {
        prefs.edit().putString(KEY_FOLDER_URI, treeUri.toString()).apply()
        _songs.clear()
        _folders.clear()

        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = queryDisplayName(treeUri, rootDocId) ?: "Music"
        scanTree(treeUri, rootDocId, rootName, rootName)

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

    private fun queryDisplayName(treeUri: Uri, documentId: String): String? {
        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        context.contentResolver.query(docUri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    private fun scanTree(treeUri: Uri, parentDocId: String, folderDisplayName: String, parentPath: String) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        val audioFiles = mutableListOf<Pair<String, String>>() // docId to displayName
        val subDirs = mutableListOf<Pair<String, String>>() // docId to displayName

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                val docId = cursor.getString(idIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: continue
                val mime = cursor.getString(mimeIdx) ?: ""
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    subDirs.add(docId to name)
                } else if (isAudioFile(name)) {
                    audioFiles.add(docId to name)
                }
            }
        }

        if (audioFiles.isNotEmpty()) {
            val folder = PlaylistFolder(name = folderDisplayName, path = parentPath)
            _folders.add(folder)

            for ((docId, name) in audioFiles) {
                val songIndex = _songs.size
                val songUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                _songs.add(
                    Song(
                        uri = songUri,
                        displayName = name ?: "Unknown",
                        folderPath = parentPath,
                        folderName = folderName
                    )
                )
                folder.songs.add(songIndex)
            }
        }

        for ((docId, name) in subDirs) {
            val subPath = "$parentPath/$name"
            scanTree(treeUri, docId, name, subPath)
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
