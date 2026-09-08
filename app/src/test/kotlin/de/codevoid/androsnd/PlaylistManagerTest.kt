package de.codevoid.androsnd

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.codevoid.androsnd.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The cursor and what survives a restart. A restart is simulated by building a
 * second PlaylistManager over the same prefs and rescanning, which is exactly what
 * a fresh process does.
 *
 * The library arrives from a [LibraryScanner], so a stub one puts a known library
 * in place without a Storage Access Framework tree — that is what makes the cursor
 * write path reachable at all.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistManagerTest {

    private class StubScanner(private var found: List<ScannedFolder>) : LibraryScanner {
        override fun scan(treeUri: Uri, onProgress: ((Int) -> Unit)?) = found
    }

    private lateinit var context: Context

    private val tree: Uri = Uri.parse("content://tree/music")

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

    /** A manager over the same prefs — i.e. what the next app start would build. */
    private fun managerOver(vararg folders: ScannedFolder) =
        PlaylistManager(context, StubScanner(folders.toList()))

    private fun scanned(vararg folders: ScannedFolder) =
        managerOver(*folders).also { it.scanFolder(tree) }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("androsnd_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // ── Where playback resumes ───────────────────────────────────────────────

    @Test
    fun `a first scan starts at the first song`() {
        val manager = scanned(folder("Album", "a.mp3", "b.mp3"))

        assertEquals(0, manager.currentIndex)
        assertEquals("a.mp3", manager.getCurrentSong()?.displayName)
    }

    @Test
    fun `the song being played is picked up again after a restart`() {
        scanned(folder("Album", "a.mp3", "b.mp3", "c.mp3")).setCurrentIndex(2)

        val restarted = scanned(folder("Album", "a.mp3", "b.mp3", "c.mp3"))

        assertEquals(2, restarted.currentIndex)
        assertEquals("c.mp3", restarted.getCurrentSong()?.displayName)
    }

    /**
     * The bookmark is a URI rather than an index precisely for this: a library that
     * grew in front of the remembered song renumbers every song after it.
     */
    @Test
    fun `the remembered song is found again at its new index`() {
        scanned(folder("Beta", "b1.mp3", "b2.mp3")).setCurrentIndex(1)

        val grown = scanned(folder("Alpha", "a1.mp3"), folder("Beta", "b1.mp3", "b2.mp3"))

        assertEquals("b2.mp3", grown.getCurrentSong()?.displayName)
        assertEquals(2, grown.currentIndex)
    }

    /**
     * A scan that cannot see the song is not proof it is gone — an unmounted card or
     * a revoked grant looks the same — so the bookmark has to outlive the fallback.
     */
    @Test
    fun `a bookmark survives a scan that cannot find it`() {
        scanned(folder("Beta", "b1.mp3", "b2.mp3")).setCurrentIndex(1)

        val withoutIt = scanned(folder("Alpha", "a1.mp3"))
        assertEquals(0, withoutIt.currentIndex)

        val withItAgain = scanned(folder("Beta", "b1.mp3", "b2.mp3"))
        assertEquals("b2.mp3", withItAgain.getCurrentSong()?.displayName)
    }

    @Test
    fun `clearing the library keeps the bookmark`() {
        val manager = scanned(folder("Album", "a.mp3", "b.mp3"))
        manager.setCurrentIndex(1)

        manager.clear()

        assertNull(manager.getCurrentSong())
        assertEquals("b.mp3", scanned(folder("Album", "a.mp3", "b.mp3")).getCurrentSong()?.displayName)
    }

    @Test
    fun `an out-of-range index leaves the cursor alone`() {
        val manager = scanned(folder("Album", "a.mp3", "b.mp3"))

        manager.setCurrentIndex(9)

        assertEquals(0, manager.currentIndex)
    }

    // ── Moving through the library ───────────────────────────────────────────

    @Test
    fun `next wraps around at the end of the library`() {
        val manager = scanned(folder("Album", "a.mp3", "b.mp3"))

        assertEquals("b.mp3", manager.nextSong()?.displayName)
        assertEquals("a.mp3", manager.nextSong()?.displayName)
    }

    @Test
    fun `previous wraps around at the start of the library`() {
        val manager = scanned(folder("Album", "a.mp3", "b.mp3"))

        assertEquals("b.mp3", manager.prevSong()?.displayName)
    }

    @Test
    fun `an empty library has nowhere to move`() {
        val manager = scanned()

        assertNull(manager.nextSong())
        assertNull(manager.prevSong())
        assertNull(manager.shuffleSong())
        assertNull(manager.getCurrentSong())
    }

    @Test
    fun `a song maps back to the folder holding it`() {
        val manager = scanned(folder("Alpha", "a1.mp3"), folder("Beta", "b1.mp3", "b2.mp3"))

        assertEquals(0, manager.getFolderIndexForSong(0))
        assertEquals(1, manager.getFolderIndexForSong(1))
        assertEquals(1, manager.getFolderIndexForSong(2))
    }

    // ── The pre-selected next track ──────────────────────────────────────────

    @Test
    fun `the queued song is the one after the current one`() {
        val manager = scanned(folder("Album", "a.mp3", "b.mp3", "c.mp3"))

        manager.selectNextQueueSong()

        assertEquals(1, manager.nextQueueIndex)
    }

    @Test
    fun `the queue wraps to the first song at the end of the library`() {
        val manager = scanned(folder("Album", "a.mp3", "b.mp3"))
        manager.setCurrentIndex(1)

        manager.selectNextQueueSong()

        assertEquals(0, manager.nextQueueIndex)
    }

    @Test
    fun `an empty library queues nothing`() {
        val manager = scanned()

        manager.selectNextQueueSong()

        assertEquals(-1, manager.nextQueueIndex)
    }

    @Test
    fun `shuffle never queues the song already playing`() {
        val manager = scanned(folder("Album", "a.mp3", "b.mp3", "c.mp3", "d.mp3"))
        manager.toggleShuffle()

        repeat(100) {
            manager.selectNextQueueSong()
            assertNotEquals(manager.currentIndex, manager.nextQueueIndex)
        }
    }

    @Test
    fun `shuffle with a single song queues that song`() {
        val manager = scanned(folder("Album", "only.mp3"))
        manager.toggleShuffle()

        manager.selectNextQueueSong()

        assertEquals(0, manager.nextQueueIndex)
    }

    // ── Shuffle ──────────────────────────────────────────────────────────────

    @Test
    fun `shuffle is off when nothing was ever stored`() {
        assertFalse(managerOver().isShuffleOn)
    }

    @Test
    fun `toggling shuffle survives a restart in both directions`() {
        val manager = managerOver()

        manager.toggleShuffle()
        assertTrue("shuffle on should survive", managerOver().isShuffleOn)

        manager.toggleShuffle()
        assertFalse("shuffle off should survive", managerOver().isShuffleOn)
    }

    // ── The picked folder ────────────────────────────────────────────────────

    @Test
    fun `the scanned folder is remembered for the next start`() {
        scanned(folder("Album", "a.mp3"))

        assertEquals(tree, managerOver().loadSavedFolder())
    }

    @Test
    fun `no folder is remembered before the first scan`() {
        assertNull(managerOver().loadSavedFolder())
    }
}
