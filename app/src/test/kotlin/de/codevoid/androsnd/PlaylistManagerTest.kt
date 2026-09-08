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
 * and the shuffle flag. Restart is simulated by building a second instance, which
 * re-reads persisted state exactly as a fresh process would.
 *
 * The write half of the cursor path (moveCursorTo) is not reachable here — it needs
 * a populated library, and the only way to populate one is a real SAF tree scan.
 */
@RunWith(AndroidJUnit4::class)
class PlaylistManagerTest {

    private companion object {
        const val PREFS = "androsnd_prefs"
        const val KEY_LAST_SONG = "last_song_uri"
    }

    private lateinit var context: Context

    private fun prefs() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun uriOf(name: String) = "content://tree/document/$name"

    private fun song(name: String) = Song(
        uri = Uri.parse(uriOf(name)),
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
    fun `toggling shuffle survives a restart in both directions`() {
        val manager = PlaylistManager(context)

        manager.toggleShuffle()
        assertTrue("shuffle on should survive", PlaylistManager(context).isShuffleOn)

        manager.toggleShuffle()
        assertFalse("shuffle off should survive", PlaylistManager(context).isShuffleOn)
    }

    @Test
    fun `remembered song resolves to whatever index it now occupies`() {
        prefs().edit().putString(KEY_LAST_SONG, uriOf("c.mp3")).commit()
        val library = listOf(song("a.mp3"), song("b.mp3"), song("c.mp3"))

        assertEquals(2, PlaylistManager(context).indexOfRememberedSong(library))
    }

    @Test
    fun `no bookmark starts at the first song`() {
        val library = listOf(song("a.mp3"), song("b.mp3"))

        assertEquals(0, PlaylistManager(context).indexOfRememberedSong(library))
    }

    /**
     * Falling back is not the same as forgetting: a partial scan — unmounted card,
     * revoked SAF grant — must not be treated as proof the song is gone.
     */
    @Test
    fun `a bookmark missing from the library falls back without being erased`() {
        prefs().edit().putString(KEY_LAST_SONG, uriOf("gone.mp3")).commit()
        val library = listOf(song("a.mp3"), song("b.mp3"))

        assertEquals(0, PlaylistManager(context).indexOfRememberedSong(library))
        assertEquals(uriOf("gone.mp3"), prefs().getString(KEY_LAST_SONG, null))
    }

    @Test
    fun `an empty library starts at the first song`() {
        prefs().edit().putString(KEY_LAST_SONG, uriOf("a.mp3")).commit()

        assertEquals(0, PlaylistManager(context).indexOfRememberedSong(emptyList()))
    }

    // ── What the scanner picks up ────────────────────────────────────────────

    @Test
    fun `every documented audio format is recognised`() {
        val manager = PlaylistManager(context)

        for (ext in listOf("mp3", "ogg", "flac", "aac", "m4a", "opus")) {
            assertTrue("$ext should be playable", manager.isAudioFile("track.$ext"))
        }
    }

    @Test
    fun `audio extensions are matched regardless of case`() {
        val manager = PlaylistManager(context)

        assertTrue(manager.isAudioFile("TRACK.MP3"))
        assertTrue(manager.isAudioFile("Track.FlAc"))
    }

    @Test
    fun `non-audio files are left out of the library`() {
        val manager = PlaylistManager(context)

        assertFalse(manager.isAudioFile("liner-notes.txt"))
        assertFalse(manager.isAudioFile("cover.jpg"))
        assertFalse(manager.isAudioFile("track.wav"))
        assertFalse(manager.isAudioFile("README"))
    }

    /** Only the final extension counts — a backup of a song is not a song. */
    @Test
    fun `an audio extension that is not the last one does not count`() {
        assertFalse(PlaylistManager(context).isAudioFile("track.mp3.bak"))
    }

    @Test
    fun `the well-known cover names are recognised regardless of case`() {
        val manager = PlaylistManager(context)

        for (name in listOf("cover.jpg", "folder.png", "front.jpeg", "album.jpg", "artwork.png")) {
            assertTrue("$name should be cover art", manager.isCoverImage(name))
        }
        assertTrue(manager.isCoverImage("Cover.JPG"))
        assertTrue(manager.isCoverImage("FOLDER.PNG"))
    }

    @Test
    fun `images that are not a known cover name are ignored`() {
        val manager = PlaylistManager(context)

        assertFalse(manager.isCoverImage("albumart.jpg"))
        assertFalse(manager.isCoverImage("cover.gif"))
        assertFalse(manager.isCoverImage("band-photo.jpg"))
    }

    @Test
    fun `clearing the library keeps the bookmark`() {
        prefs().edit().putString(KEY_LAST_SONG, uriOf("a.mp3")).commit()

        PlaylistManager(context).clear()

        assertEquals(uriOf("a.mp3"), prefs().getString(KEY_LAST_SONG, null))
    }
}
