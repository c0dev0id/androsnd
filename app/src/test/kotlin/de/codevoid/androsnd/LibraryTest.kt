package de.codevoid.androsnd

import androidx.test.ext.junit.runners.AndroidJUnit4
import de.codevoid.androsnd.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ordering and numbering, which used to be tangled up in the tree walk. Song
 * indices are the app's addressing scheme — folders hold indices, not songs — so
 * "numbered to match display order" is the invariant everything downstream rests on.
 *
 * Library.of itself touches no Android API; the Robolectric runner is here only
 * because Song carries a Uri, which the fixtures have to build.
 */
@RunWith(AndroidJUnit4::class)
class LibraryTest {

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
        val library = Library.of(listOf(scannedFolder("a", "x.mp3"), scannedFolder("B", "y.mp3")))

        assertEquals(listOf("B", "a"), library.folders.map { it.name })
    }

    /** Case-insensitive, unlike the folder ordering above — 'a' sorts before 'B'. */
    @Test
    fun `songs inside a folder are ordered case-insensitively`() {
        val library = Library.of(listOf(scannedFolder("Album", "B.mp3", "a.mp3")))

        assertEquals(listOf("a.mp3", "B.mp3"), library.songs.map { it.displayName })
    }

    @Test
    fun `songs are numbered in display order across folders`() {
        val library = Library.of(
            listOf(scannedFolder("Second", "s1.mp3", "s2.mp3"), scannedFolder("First", "f1.mp3"))
        )

        assertEquals(
            listOf("f1.mp3", "s1.mp3", "s2.mp3"),
            library.songs.map { it.displayName }
        )
        assertEquals(listOf(0), library.folders[0].songs)
        assertEquals(listOf(1, 2), library.folders[1].songs)
    }

    /**
     * Callers reach a folder by path and then use its indices, so the map has to
     * hold the very same objects as the list, not equal copies.
     */
    @Test
    fun `foldersByPath holds the same instances as folders`() {
        val library = Library.of(listOf(scannedFolder("One", "a.mp3"), scannedFolder("Two", "b.mp3")))

        for (folder in library.folders) {
            assertSame(folder, library.foldersByPath[folder.path])
        }
    }

    @Test
    fun `a folder that was scanned with no songs contributes none`() {
        val library = Library.of(listOf(scannedFolder("Empty"), scannedFolder("Full", "a.mp3")))

        assertEquals(1, library.songs.size)
        assertEquals(emptyList<Int>(), library.foldersByPath["/music/Empty"]?.songs)
    }
}
