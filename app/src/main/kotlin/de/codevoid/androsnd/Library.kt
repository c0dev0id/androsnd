package de.codevoid.androsnd

import android.net.Uri
import de.codevoid.androsnd.model.PlaylistFolder
import de.codevoid.androsnd.model.Song

/**
 * One folder as the tree walk found it: its songs in whatever order the provider
 * returned them, carrying no indices. Ordering and numbering belong to
 * [Library.of], so the walk has no say in either.
 */
data class ScannedFolder(
    val name: String,
    val path: String,
    val coverUri: Uri?,
    val songs: List<Song>
)

/**
 * The published library. The three collections travel together behind one
 * reference so a reader can never observe a half-updated set — when adding a
 * derived collection, add it here rather than as a field beside it.
 */
data class Library(
    val songs: List<Song>,
    val folders: List<PlaylistFolder>,
    val foldersByPath: Map<String, PlaylistFolder>
) {
    companion object {
        val EMPTY = Library(emptyList(), emptyList(), emptyMap())

        /**
         * Orders folders and their songs the way the UI lists them, then numbers the
         * songs to match, so index-based next/previous follows what the user sees.
         *
         * The two sorts deliberately differ: folders compare case-sensitively (plain
         * String order), songs case-insensitively. That is the ordering the app has
         * always shipped — changing either silently reshuffles every library.
         */
        fun of(scanned: List<ScannedFolder>): Library {
            val songs = mutableListOf<Song>()
            val folders = mutableListOf<PlaylistFolder>()
            val foldersByPath = HashMap<String, PlaylistFolder>()

            for (found in scanned.sortedBy { it.name }) {
                val folder = PlaylistFolder(
                    name = found.name,
                    path = found.path,
                    coverUri = found.coverUri
                )
                val ordered = found.songs.sortedWith(
                    compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
                )
                for (song in ordered) {
                    folder.songs.add(songs.size)
                    songs.add(song)
                }
                folders.add(folder)
                // Must be the same instance as in `folders`: callers reach a folder by
                // path and then use its song indices against `songs`.
                foldersByPath[folder.path] = folder
            }
            return Library(songs, folders, foldersByPath)
        }
    }
}
