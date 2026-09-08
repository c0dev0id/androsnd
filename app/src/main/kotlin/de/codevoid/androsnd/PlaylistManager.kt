package de.codevoid.androsnd

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import de.codevoid.androsnd.model.PlaylistFolder
import de.codevoid.androsnd.model.Song

/**
 * Owns the published library and the playback cursor. Finding the files is
 * [LibraryScanner]'s job and shaping them into a library is [Library.of]'s, so
 * this class is only about publishing the result and remembering where playback is.
 */
class PlaylistManager(
    context: Context,
    private val scanner: LibraryScanner = SafLibraryScanner(context)
) {

    companion object {
        const val PREFS_NAME = "androsnd_prefs"
        private const val KEY_FOLDER_URI = "folder_uri"
        private const val KEY_LAST_SONG_URI = "last_song_uri"
        private const val KEY_SHUFFLE_ON = "shuffle_on"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Scan results are published as one reference — see Library. The three getters
    // below are a convenience for readers that need a single collection; a reader
    // that needs two must take `library` once, because each getter re-reads the
    // field and a scan landing between them would hand back a mismatched pair.
    @Volatile var library: Library = Library.EMPTY
        private set

    val songs: List<Song> get() = library.songs
    val folders: List<PlaylistFolder> get() = library.folders
    val foldersByPath: Map<String, PlaylistFolder> get() = library.foldersByPath

    // Playback cursor. currentIndex is written by scanFolder() on an IO thread;
    // the other two are only mutated from the main thread today. All three are
    // volatile so a read from any thread sees the latest write.
    //
    // Both currentIndex and isShuffleOn are also persisted — see moveCursorTo.
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
        val uri = getCurrentSong()?.uri?.toString() ?: return
        prefs.edit().putString(KEY_LAST_SONG_URI, uri).apply()
    }

    /**
     * Position of the song remembered by the previous session, or 0 when there is
     * none in this library. The stored URI is deliberately left alone when it does
     * not resolve — an empty or partial scan (unmounted SD card, revoked SAF grant)
     * must not erase the bookmark that a later, complete scan can still honour.
     */
    private fun indexOfRememberedSong(inLibrary: List<Song>): Int {
        val savedUri = prefs.getString(KEY_LAST_SONG_URI, null) ?: return 0
        return inLibrary.indexOfFirst { it.uri.toString() == savedUri }.coerceAtLeast(0)
    }

    fun clear() {
        library = Library.EMPTY
        currentIndex = 0
        nextQueueIndex = -1
    }

    fun scanFolder(treeUri: Uri, onProgress: ((Int) -> Unit)? = null) {
        // Recorded before the walk, so a folder the user picked is still restored on
        // the next start even if this scan fails part way through.
        prefs.edit().putString(KEY_FOLDER_URI, treeUri.toString()).apply()

        val scanned = Library.of(scanner.scan(treeUri, onProgress))

        library = scanned
        currentIndex = indexOfRememberedSong(scanned.songs)
    }

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
