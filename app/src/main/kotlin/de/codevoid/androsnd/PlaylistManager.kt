package de.codevoid.androsnd

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import de.codevoid.androsnd.model.PlaylistFolder
import de.codevoid.androsnd.model.Song

class PlaylistManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "androsnd_prefs"
        private const val KEY_FOLDER_URI = "folder_uri"
        private val AUDIO_EXTENSIONS = setOf("mp3", "ogg", "flac", "aac", "m4a", "opus")
        private val COVER_IMAGE_NAMES = setOf(
            "cover.jpg", "cover.jpeg", "cover.png",
            "folder.jpg", "folder.jpeg", "folder.png",
            "artwork.jpg", "artwork.jpeg", "artwork.png",
            "album.jpg", "album.jpeg", "album.png",
            "front.jpg", "front.jpeg", "front.png"
        )
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var _songs = mutableListOf<Song>()
    val songs: List<Song> get() = _songs

    private var _folders = mutableListOf<PlaylistFolder>()
    val folders: List<PlaylistFolder> get() = _folders

    private var _foldersByPath = HashMap<String, PlaylistFolder>()
    val foldersByPath: Map<String, PlaylistFolder> get() = _foldersByPath

    var currentIndex: Int = 0
        private set
    var isShuffleOn: Boolean = false
        private set
    var nextQueueIndex: Int = -1
        private set

    fun loadSavedFolder(): Uri? {
        val uriString = prefs.getString(KEY_FOLDER_URI, null) ?: return null
        return Uri.parse(uriString)
    }

    fun scanFolder(treeUri: Uri) {
        prefs.edit().putString(KEY_FOLDER_URI, treeUri.toString()).apply()

        // Build into local collections so any thread holding the old _songs/_folders
        // reference continues to see a consistent, complete list until we atomically swap.
        val newSongs = mutableListOf<Song>()
        val newFolders = mutableListOf<PlaylistFolder>()
        val newFoldersByPath = HashMap<String, PlaylistFolder>()

        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = queryDisplayName(treeUri, rootDocId) ?: "Music"
        scanTree(treeUri, rootDocId, rootName, rootName, newSongs, newFolders, newFoldersByPath)

        newFolders.sortBy { it.name }
        newFolders.forEach { folder ->
            folder.songs.sortWith(compareBy(String.CASE_INSENSITIVE_ORDER) { newSongs[it].displayName })
        }

        // Rebuild newSongs in display order so index-based navigation follows display order
        val reorderedSongs = mutableListOf<Song>()
        for (folder in newFolders) {
            val newIndices = mutableListOf<Int>()
            for (oldIndex in folder.songs) {
                newIndices.add(reorderedSongs.size)
                reorderedSongs.add(newSongs[oldIndex])
            }
            folder.songs.clear()
            folder.songs.addAll(newIndices)
        }

        // Atomically publish — JVM guarantees reference assignments are atomic, so any
        // thread that already captured the old _songs reference keeps seeing intact data.
        _songs = reorderedSongs
        _folders = newFolders
        _foldersByPath = newFoldersByPath
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

    private fun scanTree(
        treeUri: Uri,
        parentDocId: String,
        folderDisplayName: String,
        parentPath: String,
        outSongs: MutableList<Song>,
        outFolders: MutableList<PlaylistFolder>,
        outFoldersByPath: HashMap<String, PlaylistFolder>
    ) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        val audioFiles = mutableListOf<Triple<String, String, Long>>() // docId, displayName, lastModified
        val subDirs = mutableListOf<Pair<String, String>>() // docId to displayName
        var coverDocId: String? = null

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val lastModIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                val docId = cursor.getString(idIdx) ?: continue
                val name = cursor.getString(nameIdx) ?: continue
                val mime = cursor.getString(mimeIdx) ?: ""
                val lastMod = if (lastModIdx >= 0) cursor.getLong(lastModIdx) else 0L
                when {
                    mime == DocumentsContract.Document.MIME_TYPE_DIR -> subDirs.add(docId to name)
                    isAudioFile(name) -> audioFiles.add(Triple(docId, name, lastMod))
                    isCoverImage(name) -> coverDocId = docId
                }
            }
        }

        if (audioFiles.isNotEmpty()) {
            val coverUri = coverDocId?.let { DocumentsContract.buildDocumentUriUsingTree(treeUri, it) }
            val folder = PlaylistFolder(name = folderDisplayName, path = parentPath, coverUri = coverUri)
            outFolders.add(folder)
            outFoldersByPath[parentPath] = folder

            for ((docId, name, lastMod) in audioFiles) {
                val songIndex = outSongs.size
                val songUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                outSongs.add(
                    Song(
                        uri = songUri,
                        displayName = name,
                        folderPath = parentPath,
                        folderName = folderDisplayName,
                        duration = 0L,
                        lastModified = lastMod
                    )
                )
                folder.songs.add(songIndex)
            }
        }

        for ((docId, name) in subDirs) {
            val subPath = "$parentPath/$name"
            scanTree(treeUri, docId, name, subPath, outSongs, outFolders, outFoldersByPath)
        }
    }

    private fun isAudioFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    private fun isCoverImage(name: String): Boolean = name.lowercase() in COVER_IMAGE_NAMES

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

    fun selectNextQueueSong() {
        if (songs.isEmpty()) {
            nextQueueIndex = -1
            return
        }
        nextQueueIndex = if (isShuffleOn) {
            if (songs.size > 1) {
                var r: Int
                do { r = kotlin.random.Random.nextInt(songs.size) } while (r == currentIndex)
                r
            } else 0
        } else {
            (currentIndex + 1) % songs.size
        }
    }
}
