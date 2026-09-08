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
 * The published library: songs numbered in display order, and folders holding
 * indices into that numbering.
 *
 * The collections travel together behind one reference so a reader can never see a
 * half-updated set — a reader that needs more than one of them must take a single
 * reference and use it, not call two getters.
 *
 * Only [of] can build one. Folder indices address positions in [songs], so a
 * Library assembled or copied any other way could hold indices pointing at the
 * wrong songs — the failure this type exists to make impossible.
 */
class Library private constructor(
    val songs: List<Song>,
    val folders: List<PlaylistFolder>
) {
    /** The same instances as [folders]: callers reach a folder by path, then use its indices. */
    val foldersByPath: Map<String, PlaylistFolder> = folders.associateBy { it.path }

    companion object {
        val EMPTY = Library(emptyList(), emptyList())

        /**
         * Orders folders and their songs the way the UI lists them, then numbers the
         * songs to match, so index-based next/previous follows what the user sees.
         *
         * The two sorts deliberately differ: folders compare case-sensitively (plain
         * String order), songs case-insensitively. That is the ordering the app has
         * always shipped — changing either silently reshuffles every library.
         */
        fun of(scanned: List<ScannedFolder>): Library {
            val songs = ArrayList<Song>(scanned.sumOf { it.songs.size })
            val folders = ArrayList<PlaylistFolder>(scanned.size)

            for (found in scanned.sortedBy { it.name }) {
                val folder = PlaylistFolder(
                    name = found.name,
                    path = found.path,
                    coverUri = found.coverUri
                )
                val ordered = found.songs.sortedWith(
                    compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName }
                )
                val firstIndex = songs.size
                songs.addAll(ordered)
                folder.songs.addAll(firstIndex until songs.size)
                folders.add(folder)
            }
            return Library(songs, folders)
        }
    }
}
