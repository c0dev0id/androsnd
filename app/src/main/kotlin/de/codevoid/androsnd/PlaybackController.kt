package de.codevoid.androsnd

import android.graphics.Bitmap
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
