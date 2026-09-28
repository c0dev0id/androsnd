package de.codevoid.androsnd

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.codevoid.androsnd.model.RadioStation
import de.codevoid.androsnd.model.StationGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The cursor over the station library. A stub repository supplies a known set of groups
 * so the ordering, wrap-around and bookmark can be asserted without the bundled JSON or a
 * network. Robolectric is needed only for SharedPreferences and Uri.parse.
 */
@RunWith(AndroidJUnit4::class)
class RadioManagerTest {

    private lateinit var context: Context

    // "Alpha" sorts before "Beta" (case-sensitive folders); stations sort case-insensitively.
    // Resulting song order: A1(0), A2(1), B1(2).
    private class StubRepository(context: Context) : StationRepository(context) {
        override fun load(): List<StationGroup> = listOf(
            StationGroup("Alpha", listOf(
                RadioStation("A1", "http://a1"),
                RadioStation("A2", "http://a2")
            )),
            StationGroup("Beta", listOf(RadioStation("B1", "http://b1")))
        )
    }

    private fun newManager() = RadioManager(context, StubRepository(context))

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("androsnd_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Test
    fun `load builds the station library in display order`() {
        val mgr = newManager()
        mgr.load()

        assertEquals(listOf("A1", "A2", "B1"), mgr.songs.map { it.displayName })
        assertEquals(listOf("Alpha", "Beta"), mgr.folders.map { it.name })
        assertEquals(0, mgr.currentIndex)
    }

    @Test
    fun `next wraps around the end`() {
        val mgr = newManager()
        mgr.load()

        assertEquals("A2", mgr.nextSong()?.displayName)
        assertEquals("B1", mgr.nextSong()?.displayName)
        assertEquals("A1", mgr.nextSong()?.displayName)
    }

    @Test
    fun `previous wraps around the start`() {
        val mgr = newManager()
        mgr.load()

        assertEquals("B1", mgr.prevSong()?.displayName)
    }

    @Test
    fun `sequential next-queue points at the following station`() {
        val mgr = newManager()
        mgr.load()

        assertFalse(mgr.isShuffleOn)
        assertEquals(1, mgr.nextQueueIndex)
    }

    @Test
    fun `shuffle toggle persists across managers`() {
        newManager().toggleShuffle()

        val reloaded = newManager()
        assertTrue(reloaded.isShuffleOn)
    }

    @Test
    fun `the last played station is remembered by uri`() {
        val first = newManager()
        first.load()
        first.setCurrentIndex(2)   // B1

        val reopened = newManager()
        reopened.load()
        assertEquals(2, reopened.currentIndex)
        assertEquals("B1", reopened.getCurrentSong()?.displayName)
    }
}
