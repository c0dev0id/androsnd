package com.androsnd

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import com.androsnd.model.PlaylistFolder
import com.androsnd.model.Song
import org.json.JSONArray
import org.json.JSONObject

class PlaylistManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "androsnd_prefs"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_SCAN_CACHE = "scan_cache"
        private val AUDIO_EXTENSIONS = setOf("mp3", "ogg", "flac", "aac", "m4a", "opus")
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _songs = mutableListOf<Song>()
    val songs: List<Song> get() = _songs

    private val _folders = mutableListOf<PlaylistFolder>()
    val folders: List<PlaylistFolder> get() = _folders

    private val songToFolderIndex = mutableMapOf<Int, Int>()

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
        val startTime = System.currentTimeMillis()
        prefs.edit().putString(KEY_FOLDER_URI, treeUri.toString()).apply()
        _songs.clear()
        _folders.clear()

        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = rootDocId.substringAfterLast(':').substringAfterLast('/').ifEmpty { "Music" }
        scanDocumentTree(treeUri, rootDocId, rootName)

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

        // Build reverse lookup for O(1) getFolderIndexForSong()
        rebuildSongToFolderIndex()

        currentIndex = 0

        saveScanCache()

        val elapsed = System.currentTimeMillis() - startTime
        Log.d("PlaylistManager", "Scan completed: ${_songs.size} songs in ${_folders.size} folders in ${elapsed}ms")
    }

    private fun scanDocumentTree(treeUri: Uri, parentDocId: String, parentPath: String) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        val audioFiles = mutableListOf<Pair<String, Uri>>()
        val subDirs = mutableListOf<Pair<String, String>>()

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext()) {
                val docId = cursor.getString(idCol)
                val name = cursor.getString(nameCol)
                val mime = cursor.getString(mimeCol)

                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    subDirs.add(docId to name)
                } else if (isAudioFile(name)) {
                    val fileUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    audioFiles.add(name to fileUri)
                }
            }
        }

        if (audioFiles.isNotEmpty()) {
            val folderName = parentPath.substringAfterLast('/')
            val folder = PlaylistFolder(name = folderName, path = parentPath)
            _folders.add(folder)

            for ((name, uri) in audioFiles) {
                val songIndex = _songs.size
                _songs.add(Song(uri = uri, displayName = name))
                folder.songs.add(songIndex)
            }
        }

        for ((docId, dirName) in subDirs) {
            scanDocumentTree(treeUri, docId, "$parentPath/$dirName")
        }
    }

    fun saveScanCache() {
        val json = JSONObject().apply {
            val songsArray = JSONArray()
            for (song in _songs) {
                songsArray.put(JSONObject().apply {
                    put("uri", song.uri.toString())
                    put("displayName", song.displayName)
                })
            }
            put("songs", songsArray)

            val foldersArray = JSONArray()
            for (folder in _folders) {
                foldersArray.put(JSONObject().apply {
                    put("name", folder.name)
                    put("path", folder.path)
                    val songIndices = JSONArray()
                    for (idx in folder.songs) songIndices.put(idx)
                    put("songs", songIndices)
                })
            }
            put("folders", foldersArray)
        }
        prefs.edit().putString(KEY_SCAN_CACHE, json.toString()).apply()
    }

    fun loadScanCache(): Boolean {
        val jsonStr = prefs.getString(KEY_SCAN_CACHE, null) ?: return false
        return try {
            val json = JSONObject(jsonStr)
            val songsArray = json.getJSONArray("songs")
            val foldersArray = json.getJSONArray("folders")

            _songs.clear()
            for (i in 0 until songsArray.length()) {
                val obj = songsArray.getJSONObject(i)
                _songs.add(Song(
                    uri = Uri.parse(obj.getString("uri")),
                    displayName = obj.getString("displayName")
                ))
            }

            _folders.clear()
            for (i in 0 until foldersArray.length()) {
                val obj = foldersArray.getJSONObject(i)
                val folder = PlaylistFolder(
                    name = obj.getString("name"),
                    path = obj.getString("path")
                )
                val indices = obj.getJSONArray("songs")
                for (j in 0 until indices.length()) {
                    folder.songs.add(indices.getInt(j))
                }
                _folders.add(folder)
            }

            rebuildSongToFolderIndex()

            currentIndex = 0
            true
        } catch (e: Exception) {
            Log.w("PlaylistManager", "Failed to load scan cache", e)
            clearScanCache()
            false
        }
    }

    fun clearScanCache() {
        prefs.edit().remove(KEY_SCAN_CACHE).apply()
    }

    private fun rebuildSongToFolderIndex() {
        songToFolderIndex.clear()
        for ((fi, folder) in _folders.withIndex()) {
            for (si in folder.songs) {
                songToFolderIndex[si] = fi
            }
        }
    }

    private fun isAudioFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    fun getCurrentSong(): Song? = songs.getOrNull(currentIndex)

    fun getFolderIndexForSong(songIndex: Int): Int = songToFolderIndex[songIndex] ?: -1

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
        if (songs.size > 1) {
            // Pick a random index from 0..(size-2), then skip over currentIndex
            val next = kotlin.random.Random.nextInt(songs.size - 1)
            currentIndex = if (next >= currentIndex) next + 1 else next
        }
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
