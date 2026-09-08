package de.codevoid.androsnd

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the scanner picks up. Getting either set wrong drops files from the library
 * silently, so the negative cases matter as much as the positive ones.
 *
 * Pure naming rules, so this runs as plain JUnit — no Android environment.
 */
class MediaFileNamesTest {

    @Test
    fun `every documented audio format is recognised`() {
        for (ext in listOf("mp3", "ogg", "flac", "aac", "m4a", "opus")) {
            assertTrue("$ext should be playable", MediaFileNames.isAudio("track.$ext"))
        }
    }

    @Test
    fun `audio extensions are matched regardless of case`() {
        assertTrue(MediaFileNames.isAudio("TRACK.MP3"))
        assertTrue(MediaFileNames.isAudio("Track.FlAc"))
    }

    @Test
    fun `non-audio files are left out of the library`() {
        assertFalse(MediaFileNames.isAudio("liner-notes.txt"))
        assertFalse(MediaFileNames.isAudio("cover.jpg"))
        assertFalse(MediaFileNames.isAudio("track.wav"))
        assertFalse(MediaFileNames.isAudio("README"))
    }

    @Test
    fun `an audio extension that is not the last one does not count`() {
        assertFalse(MediaFileNames.isAudio("track.mp3.bak"))
    }

    @Test
    fun `the well-known cover names are recognised regardless of case`() {
        for (name in listOf("cover.jpg", "folder.png", "front.jpeg", "album.jpg", "artwork.png")) {
            assertTrue("$name should be cover art", MediaFileNames.isCover(name))
        }
        assertTrue(MediaFileNames.isCover("Cover.JPG"))
        assertTrue(MediaFileNames.isCover("FOLDER.PNG"))
    }

    @Test
    fun `images that are not a known cover name are ignored`() {
        assertFalse(MediaFileNames.isCover("albumart.jpg"))
        assertFalse(MediaFileNames.isCover("cover.gif"))
        assertFalse(MediaFileNames.isCover("band-photo.jpg"))
    }
}
