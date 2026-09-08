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
        private const val KEY_LAST_SONG_URI = "last_song_uri"
        private const val KEY_SHUFFLE_ON = "shuffle_on"
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

    // Scan results are published as a single atomic reference so any thread
    // that captures the snapshot sees a consistent songs/folders/foldersByPath
    // triple — no partial-update window between three separate field writes.
    private data class Snapshot(
        val songs: List<Song>,
        val folders: List<PlaylistFolder>,
        val foldersByPath: Map<String, PlaylistFolder>
    )

    @Volatile private var snapshot = Snapshot(emptyList(), emptyList(), emptyMap())

    val songs: List<Song> get() = snapshot.songs
    val folders: List<PlaylistFolder> get() = snapshot.folders
    val foldersByPath: Map<String, PlaylistFolder> get() = snapshot.foldersByPath

    // Playback cursor. currentIndex is written by scanFolder() on an IO thread;
    // the other two are only mutated from the main thread today. All three are
    // volatile so a read from any thread sees the latest write.
    //
    // currentIndex and isShuffleOn survive a restart: every cursor move records
    // the song's URI in prefs (see moveCursorTo), and a scan restores the cursor
    // from it. isShuffleOn is seeded from prefs here and rewritten on each toggle.
    //
    // Visibility is all this buys: they are independent fields, so an update
    // spanning more than one of them — toggleShuffle()'s read-modify-write, or
    // selectNextQueueSong() reading isShuffleOn/currentIndex to derive
    // nextQueueIndex — is still not atomic. Those mutators must stay on a single
    // thread; only the reads are safe to do from anywhere.
    @Volatile var currentIndex: Int = 0
        private set
    @Volatile var isShuffleOn: Boolean = prefs.getBoolean(KEY_SHUFFLE_ON, false)
        private set
    @Volatile var nextQueueIndex: Int = -1
        private set

    fun loadSavedFolder(): Uri? {
        val uriString = prefs.getString(KEY_FOLDER_URI, null) ?: return null
        return Uri.parse(uriString)
    }

    /**
     * Moves the playback cursor and remembers the song it landed on. Indices are
     * rebuilt from scratch by every scan, so the URI is the only handle that stays
     * valid across a restart.
     */
    private fun moveCursorTo(index: Int) {
        currentIndex = index
        val uri = songs.getOrNull(index)?.uri?.toString() ?: return
        prefs.edit().putString(KEY_LAST_SONG_URI, uri).apply()
    }

    /**
     * Position of the song remembered by the previous session, or 0 when there is
     * none in this library. The stored URI is deliberately left alone when it does
     * not resolve — an empty or partial scan (unmounted SD card, revoked SAF grant)
     * must not erase the bookmark that a later, complete scan can still honour.
     */
    private fun indexOfRememberedSong(library: List<Song>): Int {
        val savedUri = prefs.getString(KEY_LAST_SONG_URI, null) ?: return 0
        return library.indexOfFirst { it.uri.toString() == savedUri }.coerceAtLeast(0)
    }

    fun clear() {
        snapshot = Snapshot(emptyList(), emptyList(), emptyMap())
        currentIndex = 0
        nextQueueIndex = -1
    }

    fun scanFolder(treeUri: Uri, onProgress: ((Int) -> Unit)? = null) {
        prefs.edit().putString(KEY_FOLDER_URI, treeUri.toString()).apply()

        // Build into local collections so any thread holding the old _songs/_folders
        // reference continues to see a consistent, complete list until we atomically swap.
        val newSongs = mutableListOf<Song>()
        val newFolders = mutableListOf<PlaylistFolder>()
        val newFoldersByPath = HashMap<String, PlaylistFolder>()

        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val rootName = queryDisplayName(treeUri, rootDocId) ?: "Music"
        scanTree(treeUri, rootDocId, rootName, rootName, newSongs, newFolders, newFoldersByPath, onProgress)

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

        // Single volatile write publishes all three collections simultaneously.
        snapshot = Snapshot(reorderedSongs, newFolders, newFoldersByPath)
        currentIndex = indexOfRememberedSong(reorderedSongs)
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
        outFoldersByPath: HashMap<String, PlaylistFolder>,
        onProgress: ((Int) -> Unit)? = null
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
                onProgress?.invoke(outSongs.size)
            }
        }

        for ((docId, name) in subDirs) {
            val subPath = "$parentPath/$name"
            scanTree(treeUri, docId, name, subPath, outSongs, outFolders, outFoldersByPath, onProgress)
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
        moveCursorTo((currentIndex + 1) % songs.size)
        return getCurrentSong()
    }

    fun prevSong(): Song? {
        if (songs.isEmpty()) return null
        moveCursorTo(if (currentIndex <= 0) songs.size - 1 else currentIndex - 1)
        return getCurrentSong()
    }

    fun shuffleSong(): Song? {
        if (songs.isEmpty()) return null
        moveCursorTo(kotlin.random.Random.nextInt(songs.size))
        return getCurrentSong()
    }

    fun setCurrentIndex(index: Int) {
        if (index in songs.indices) moveCursorTo(index)
    }

    fun toggleShuffle(): Boolean {
        isShuffleOn = !isShuffleOn
        prefs.edit().putBoolean(KEY_SHUFFLE_ON, isShuffleOn).apply()
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
