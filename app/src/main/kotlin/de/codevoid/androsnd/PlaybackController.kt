package de.codevoid.androsnd

import android.graphics.Bitmap
import de.codevoid.androsnd.model.PlaylistFolder
import de.codevoid.androsnd.model.Song
import de.codevoid.androsnd.model.SongMetadata

/**
 * One playback subsystem. [MusicService] owns exactly one active controller and
 * routes every command (binder, MediaSession callback, media button) to it, while
 * keeping the shared shell — the single MediaSession, the foreground notification and
 * audio focus — to itself and rebuilding it from whichever controller is active.
 *
 * The file subsystem ([FilePlaybackController]) drives a [MediaPlayer] over local SAF
 * files with tag/art enrichment; the radio subsystem streams and reports [canSeek] =
 * false, [positionMs]/[durationMs] = 0. State is read back through the properties
 * rather than pushed, so the shell can render either subsystem uniformly.
 */
interface PlaybackController {

    val isPlaying: Boolean
    val isPreparing: Boolean
    val isScanning: Boolean
    val currentText: SongMetadata?
    val currentArt: Bitmap?
    val positionMs: Int
    val durationMs: Int
    val canSeek: Boolean
    /** Audio buffered ahead of the playhead, in ms. Files: 0 (nothing to gauge). */
    val bufferedMs: Int

    /** This subsystem's library, bound by the right pane and folder browser. */
    val songs: List<Song>
    val folders: List<PlaylistFolder>
    /** The entry the cursor sits on, for the session/notification fallback text. */
    val currentSong: Song?
    /** Cursor position, used as the MediaSession media id / active queue item. */
    val currentIndex: Int
    /** Size of this subsystem's library, for the session's track count. */
    val songCount: Int
    val isShuffleOn: Boolean
    /** The folder holding [songIndex], for scrolling the list to the playing song. */
    fun folderIndexForSong(songIndex: Int): Int

    fun play()
    fun pause()
    fun stop()
    fun next()
    fun previous()
    fun toggleShuffle()
    fun playAt(index: Int)
    fun playSong(song: Song)
    /** Load the current entry without starting playback (MediaSession onPrepare). */
    fun prepare()
    fun seekTo(ms: Int)
    fun setVolume(v: Float)
    fun release()
}
