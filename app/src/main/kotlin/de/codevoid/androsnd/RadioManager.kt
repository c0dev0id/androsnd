package de.codevoid.androsnd

import android.content.Context
import android.content.SharedPreferences
import de.codevoid.androsnd.model.PlaylistFolder
import de.codevoid.androsnd.model.Song

/**
 * The radio counterpart to [PlaylistManager]: publishes a station [Library] (group ->
 * folder, station -> [Song] with a stream URI) and owns the playback cursor over it.
 *
 * The cursor math is duplicated from [PlaylistManager] on purpose rather than extracted
 * into a shared base — the file path is delicate and better left untouched (see the
 * plan). The bookmark is a separate pref key so switching modes does not cross state.
 */
class RadioManager(
    context: Context,
    private val repository: StationRepository = StationRepository(context)
) {

    companion object {
        private const val PREFS_NAME = "androsnd_prefs"
        private const val KEY_LAST_STATION_URI = "last_station_uri"
        private const val KEY_SHUFFLE_ON = "radio_shuffle_on"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile private var library = Library.EMPTY

    val songs: List<Song> get() = library.songs
    val folders: List<PlaylistFolder> get() = library.folders
    val foldersByPath: Map<String, PlaylistFolder> get() = library.foldersByPath

    @Volatile var currentIndex: Int = 0
        private set
    @Volatile var isShuffleOn: Boolean = prefs.getBoolean(KEY_SHUFFLE_ON, false)
        private set
    @Volatile var nextQueueIndex: Int = -1
        private set

    /** Rebuilds the station library from the bundled set plus the user file. */
    fun load() {
        library = Library.of(repository.toScannedFolders(repository.load()))
        currentIndex = indexOfRememberedStation(library.songs)
        selectNextQueueSong()
    }

    /** Adds a user stream and reloads so it shows up under the "My Streams" group at once. */
    fun addStation(name: String, url: String) {
        repository.addUserStation(name, url)
        load()
    }

    private fun moveCursorTo(index: Int) {
        currentIndex = index
        val uri = getCurrentSong()?.uri?.toString() ?: return
        prefs.edit().putString(KEY_LAST_STATION_URI, uri).apply()
    }

    private fun indexOfRememberedStation(inLibrary: List<Song>): Int {
        val savedUri = prefs.getString(KEY_LAST_STATION_URI, null) ?: return 0
        return inLibrary.indexOfFirst { it.uri.toString() == savedUri }.coerceAtLeast(0)
    }

    fun getCurrentSong(): Song? = songs.getOrNull(currentIndex)

    fun getFolderIndexForSong(songIndex: Int): Int =
        folders.indexOfFirst { it.songs.contains(songIndex) }

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
