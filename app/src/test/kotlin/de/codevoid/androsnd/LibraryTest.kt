package de.codevoid.androsnd

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.codevoid.androsnd.model.RadioStation
import de.codevoid.androsnd.model.Song
import de.codevoid.androsnd.model.StationGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ordering and numbering, which used to be tangled up in the tree walk. Song
 * indices are the app's addressing scheme — folders hold indices, not songs — so
 * "numbered to match display order" is the invariant everything downstream rests on.
 */
@RunWith(AndroidJUnit4::class)
class LibraryTest {

    private fun song(name: String, folder: String) = Song(
        uri = Uri.parse("content://tree/document/$folder/$name"),
        displayName = name,
        folderPath = "/music/$folder",
        folderName = folder
    )

    private fun folder(name: String, vararg songNames: String) = ScannedFolder(
        name = name,
        path = "/music/$name",
        coverUri = null,
        songs = songNames.map { song(it, name) }
    )

    @Test
    fun `an empty scan produces an empty library`() {
        val library = Library.of(emptyList())

        assertEquals(emptyList<Song>(), library.songs)
        assertEquals(0, library.folders.size)
        assertTrue(library.foldersByPath.isEmpty())
    }

    /** Case-sensitive, unlike the song ordering below — 'B' sorts before 'a'. */
    @Test
    fun `folders are listed in case-sensitive name order`() {
        val library = Library.of(listOf(folder("a", "x.mp3"), folder("B", "y.mp3")))

        assertEquals(listOf("B", "a"), library.folders.map { it.name })
    }

    /** Case-insensitive, unlike the folder ordering above — 'a' sorts before 'B'. */
    @Test
    fun `songs inside a folder are ordered case-insensitively`() {
        val library = Library.of(listOf(folder("Album", "B.mp3", "a.mp3")))

        assertEquals(listOf("a.mp3", "B.mp3"), library.songs.map { it.displayName })
    }

    @Test
    fun `songs are numbered in display order across folders`() {
        val library = Library.of(
            listOf(folder("Second", "s1.mp3", "s2.mp3"), folder("First", "f1.mp3"))
        )

        assertEquals(
            listOf("f1.mp3", "s1.mp3", "s2.mp3"),
            library.songs.map { it.displayName }
        )
        assertEquals(listOf(0), library.folders[0].songs)
        assertEquals(listOf(1, 2), library.folders[1].songs)
    }

    @Test
    fun `a folder's indices resolve to that folder's own songs`() {
        val library = Library.of(
            listOf(folder("One", "a.mp3", "b.mp3"), folder("Two", "c.mp3"))
        )

        for (folder in library.folders) {
            for (index in folder.songs) {
                assertEquals(folder.path, library.songs[index].folderPath)
            }
        }
    }

    /**
     * Callers reach a folder by path and then use its indices, so the map has to
     * hold the very same objects as the list, not equal copies.
     */
    @Test
    fun `foldersByPath holds the same instances as folders`() {
        val library = Library.of(listOf(folder("One", "a.mp3"), folder("Two", "b.mp3")))

        for (folder in library.folders) {
            assertSame(folder, library.foldersByPath[folder.path])
        }
    }

    @Test
    fun `a folder that was scanned with no songs contributes none`() {
        val library = Library.of(listOf(folder("Empty"), folder("Full", "a.mp3")))

        assertEquals(1, library.songs.size)
        assertEquals(emptyList<Int>(), library.foldersByPath["/music/Empty"]?.songs)
    }

    /**
     * Radio reuses this same builder — a station group is a folder, a station is a Song
     * with a stream URI. This walks the actual adapter (toScannedFolders) into the builder
     * to prove groups become ordered folders and stations get contiguous indices.
     */
    @Test
    fun `station groups flow through the builder as folders and indexed stations`() {
        val repo = StationRepository(ApplicationProvider.getApplicationContext())
        val library = Library.of(repo.toScannedFolders(listOf(
            StationGroup("Beta", listOf(RadioStation("B", "http://b"))),
            StationGroup("Alpha", listOf(
                RadioStation("A1", "http://a1"),
                RadioStation("A2", "http://a2")
            ))
        )))

        assertEquals(listOf("Alpha", "Beta"), library.folders.map { it.name })
        assertEquals(listOf("A1", "A2", "B"), library.songs.map { it.displayName })
        assertEquals(listOf(0, 1), library.folders[0].songs)
        assertEquals(listOf(2), library.folders[1].songs)
        assertEquals("http://a1", library.songs[0].uri.toString())
    }
}
