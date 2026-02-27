package com.androsnd

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.annotation.WorkerThread
import com.androsnd.model.PlaylistFolder
import com.androsnd.model.Song
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class PlaylistManager(private val context: Context) {

    companion object {
        private const val TAG = "PlaylistManager"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_SCAN_CACHE = "scan_cache"
        private const val SCAN_CACHE_FILENAME = "scan_cache.json"
        private val AUDIO_EXTENSIONS = setOf("mp3", "ogg", "flac", "aac", "m4a", "opus")
    }

    private val prefs: SharedPreferences = PreferencesManager.getPrefs(context)
    private val scanCacheFile: File get() = File(context.filesDir, SCAN_CACHE_FILENAME)

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

    private val playedIndices = mutableSetOf<Int>()

    fun resetPlayedIndices() {
        playedIndices.clear()
    }

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

    fun hasFolderPermission(): Boolean {
        val savedUri = loadSavedFolder() ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri == savedUri && it.isReadPermission
        }
    }

    @WorkerThread
    fun scanFolder(treeUri: Uri) {
        val startTime = System.currentTimeMillis()
        prefs.edit().putString(KEY_FOLDER_URI, treeUri.toString()).apply()
        _songs.clear()
        _folders.clear()

        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = rootDocId.substringAfterLast(':').substringAfterLast('/').ifEmpty { "Music" }
        scanDocumentTree(treeUri, rootDocId, rootName)

        val sortedFolders = _folders
            .sortedBy { it.name }
            .map { folder ->
                folder.copy(songs = folder.songs.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { _songs[it].displayName }))
            }

        // Rebuild _songs in display order so index-based navigation follows display order
        val reorderedSongs = mutableListOf<Song>()
        val reorderedFolders = sortedFolders.map { folder ->
            val newIndices = folder.songs.map { oldIndex ->
                val newIdx = reorderedSongs.size
                reorderedSongs.add(_songs[oldIndex])
                newIdx
            }
            folder.copy(songs = newIndices)
        }
        _songs.clear()
        _songs.addAll(reorderedSongs)
        _folders.clear()
        _folders.addAll(reorderedFolders)

        // Build reverse lookup for O(1) getFolderIndexForSong()
        rebuildSongToFolderIndex()

        currentIndex = 0

        resetPlayedIndices()
        saveScanCache()

        val elapsed = System.currentTimeMillis() - startTime
        Log.d(TAG, "Scan completed: ${_songs.size} songs in ${_folders.size} folders in ${elapsed}ms")
    }

    @WorkerThread
    private fun scanDocumentTree(treeUri: Uri, parentDocId: String, parentPath: String) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )

        val audioFiles = mutableListOf<Pair<String, Uri>>()
        val subDirs = mutableListOf<Pair<String, String>>()

        val cursor = context.contentResolver.query(childrenUri, projection, null, null, null)
        if (cursor == null) {
            Log.w(TAG, "Query returned null for $childrenUri")
            return
        }
        cursor.use { c ->
            val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (c.moveToNext()) {
                val docId = c.getString(idCol)
                val name = c.getString(nameCol)
                val mime = c.getString(mimeCol)

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
            val songIndices = mutableListOf<Int>()
            for ((name, uri) in audioFiles) {
                songIndices.add(_songs.size)
                _songs.add(Song(uri = uri, displayName = name))
            }
            _folders.add(PlaylistFolder(name = folderName, path = parentPath, songs = songIndices))
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
        try {
            scanCacheFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save scan cache to file", e)
        }
    }

    fun loadScanCache(): Boolean {
        val jsonStr = try {
            if (scanCacheFile.exists()) {
                scanCacheFile.readText()
            } else {
                // Migrate from SharedPreferences if present
                val old = prefs.getString(KEY_SCAN_CACHE, null) ?: return false
                try {
                    scanCacheFile.writeText(old)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to migrate scan cache to file", e)
                }
                prefs.edit().remove(KEY_SCAN_CACHE).apply()
                old
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read scan cache file", e)
            return false
        }
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
                val indices = obj.getJSONArray("songs")
                val songIndices = (0 until indices.length()).map { j -> indices.getInt(j) }
                _folders.add(PlaylistFolder(
                    name = obj.getString("name"),
                    path = obj.getString("path"),
                    songs = songIndices
                ))
            }

            rebuildSongToFolderIndex()

            currentIndex = 0
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load scan cache", e)
            clearScanCache()
            false
        }
    }

    /**
     * Returns true if at least one cached song URI is still readable.
     * Used to detect stale caches (e.g. after reinstall or permission expiry).
     * Checks only the first 5 URIs to avoid blocking the calling thread too long.
     */
    fun isCacheValid(): Boolean {
        if (_songs.isEmpty()) return false
        return _songs.take(5).any { song ->
            try {
                context.contentResolver.openInputStream(song.uri)?.use { true } ?: false
            } catch (e: Exception) {
                false
            }
        }
    }

    fun clearScanCache() {
        scanCacheFile.delete()
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

    /**
     * Like shuffleSong() but tracks played songs and returns null when all have been played.
     * Used for REPEAT_MODE_NONE + shuffle to stop playback after all songs are heard.
     */
    fun shuffleSongNoRepeat(): Song? {
        if (songs.isEmpty()) return null
        playedIndices.add(currentIndex)
        val unplayed = songs.indices.filter { it !in playedIndices }
        if (unplayed.isEmpty()) return null
        currentIndex = unplayed[kotlin.random.Random.nextInt(unplayed.size)]
        return getCurrentSong()
    }

    fun setCurrentIndex(index: Int) {
        if (index in songs.indices) currentIndex = index
    }

    fun toggleShuffle(): Boolean {
        isShuffleOn = !isShuffleOn
        resetPlayedIndices()
        return isShuffleOn
    }
}
