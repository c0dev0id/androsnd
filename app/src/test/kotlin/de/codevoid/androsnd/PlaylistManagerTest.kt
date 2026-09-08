package de.codevoid.androsnd

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import de.codevoid.androsnd.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Covers the state PlaylistManager carries across a restart: the remembered song
 * and the shuffle flag.
 *
 * The write half of the cursor path (moveCursorTo) is not reachable here — it needs
 * a populated library, and the only way to populate one is a real SAF tree scan.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistManagerTest {

    private lateinit var context: Context

    private fun prefs() = context.getSharedPreferences("androsnd_prefs", Context.MODE_PRIVATE)

    private fun song(name: String) = Song(
        uri = Uri.parse("content://tree/document/$name"),
        displayName = name,
        folderPath = "/music/album",
        folderName = "album"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs().edit().clear().commit()
    }

    @Test
    fun `shuffle is off when nothing was ever stored`() {
        assertFalse(PlaylistManager(context).isShuffleOn)
    }

    @Test
    fun `shuffle is restored from prefs`() {
        prefs().edit().putBoolean("shuffle_on", true).commit()

        assertTrue(PlaylistManager(context).isShuffleOn)
    }

    @Test
    fun `toggling shuffle persists both directions`() {
        val manager = PlaylistManager(context)

        manager.toggleShuffle()
        assertTrue(manager.isShuffleOn)
        assertTrue(prefs().getBoolean("shuffle_on", false))

        manager.toggleShuffle()
        assertFalse(manager.isShuffleOn)
        assertFalse(prefs().getBoolean("shuffle_on", true))
    }

    @Test
    fun `remembered song resolves to whatever index it now occupies`() {
        prefs().edit().putString("last_song_uri", "content://tree/document/c.mp3").commit()
        val library = listOf(song("a.mp3"), song("b.mp3"), song("c.mp3"))

        assertEquals(2, PlaylistManager(context).indexOfRememberedSong(library))
    }

    @Test
    fun `no bookmark starts at the first song`() {
        val library = listOf(song("a.mp3"), song("b.mp3"))

        assertEquals(0, PlaylistManager(context).indexOfRememberedSong(library))
    }

    @Test
    fun `a bookmark that is no longer in the library starts at the first song`() {
        prefs().edit().putString("last_song_uri", "content://tree/document/gone.mp3").commit()
        val library = listOf(song("a.mp3"), song("b.mp3"))

        assertEquals(0, PlaylistManager(context).indexOfRememberedSong(library))
    }

    @Test
    fun `an empty library starts at the first song`() {
        prefs().edit().putString("last_song_uri", "content://tree/document/a.mp3").commit()

        assertEquals(0, PlaylistManager(context).indexOfRememberedSong(emptyList()))
    }

    /**
     * An empty or partial scan — unmounted card, revoked SAF grant — must not be
     * treated as proof the song is gone, or the bookmark is lost for good.
     */
    @Test
    fun `failing to resolve a bookmark does not erase it`() {
        prefs().edit().putString("last_song_uri", "content://tree/document/gone.mp3").commit()

        PlaylistManager(context).indexOfRememberedSong(emptyList())

        assertEquals(
            "content://tree/document/gone.mp3",
            prefs().getString("last_song_uri", null)
        )
    }

    @Test
    fun `clearing the library keeps the bookmark`() {
        prefs().edit().putString("last_song_uri", "content://tree/document/a.mp3").commit()

        PlaylistManager(context).clear()

        assertEquals(
            "content://tree/document/a.mp3",
            prefs().getString("last_song_uri", null)
        )
    }
}
