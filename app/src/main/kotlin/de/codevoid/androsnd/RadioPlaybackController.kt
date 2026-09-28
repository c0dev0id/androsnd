package de.codevoid.androsnd

import android.content.Context
import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import de.codevoid.androsnd.model.PlaylistFolder
import de.codevoid.androsnd.model.Song
import de.codevoid.androsnd.model.SongMetadata
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The internet-radio subsystem: an [ExoPlayer] streaming the station whose [Song.uri]
 * is an `http(s)://` URL, with [RadioManager] as its cursor. A live stream cannot seek
 * or report a duration, so [canSeek] is false and [positionMs]/[durationMs] stay 0; the
 * shell hides the seek bar accordingly.
 *
 * Two policies shape the streaming feel and are deliberate, not defaults:
 *
 *  - **Prebuffer.** The user's `radio_buffer_seconds` sizes only the retention window
 *    (`minBufferMs`/`maxBufferMs`) — how much audio the player holds ahead of the
 *    playhead to ride through a dropout. The playback/rebuffer gates stay at the small
 *    defaults, so start-up and post-drop resume are fast regardless of the setting. A
 *    live stream can only be fed as far ahead as the server's initial burst — after
 *    that it arrives at the encode rate — so real ride-through is capped by that burst,
 *    not by this number. The value is fixed at construction, so a change rebuilds the
 *    player.
 *  - **Gentle reconnect.** [GentleReconnectPolicy] retries a load once immediately, then
 *    every 5s, effectively forever, so a jittery link is not hammered. [onPlayerError]
 *    re-prepares after 5s to cover errors that bypass the load policy.
 *
 * There is no tag/art enrichment: now-playing text comes from the stream's ICY metadata
 * ([onMediaMetadataChanged]); art is a generic station icon supplied by the shell.
 */
@OptIn(UnstableApi::class)
class RadioPlaybackController(private val service: MusicService) : PlaybackController {

    companion object {
        const val KEY_BUFFER_SECONDS = "radio_buffer_seconds"
        const val DEFAULT_BUFFER_SECONDS = 5
    }

    val radioManager = RadioManager(service)

    private var player: ExoPlayer? = null
    // "The user wants sound." Kept across a stream error so the reconnect knows whether
    // to resume playing once it re-prepares.
    private var wantPlayback = false

    override var isPlaying: Boolean = false
        private set
    override val isPreparing: Boolean
        get() = player?.playbackState == Player.STATE_BUFFERING
    override val isScanning: Boolean = false
    override var currentText: SongMetadata? = null
        private set
    override val currentArt: Bitmap? get() = null
    override val positionMs: Int = 0
    override val durationMs: Int = 0
    override val canSeek: Boolean = false

    override val songs: List<Song> get() = radioManager.songs
    override val folders: List<PlaylistFolder> get() = radioManager.folders
    override val currentSong: Song? get() = radioManager.getCurrentSong()
    override val currentIndex: Int get() = radioManager.currentIndex
    override val songCount: Int get() = radioManager.songs.size
    override val isShuffleOn: Boolean get() = radioManager.isShuffleOn
    override fun folderIndexForSong(songIndex: Int): Int =
        radioManager.getFolderIndexForSong(songIndex)

    /** Rebuilds the station library. Call when this controller becomes active. */
    fun load() {
        radioManager.load()
    }

    override fun play() {
        val station = radioManager.getCurrentSong() ?: return
        wantPlayback = true
        service.requestAudioFocus()
        val p = ensurePlayer()
        if (p.currentMediaItem == null) {
            p.setMediaItem(MediaItem.fromUri(station.uri))
            p.prepare()
        }
        p.volume = service.getAppVolumeFloat()
        p.playWhenReady = true
    }

    override fun pause() {
        wantPlayback = false
        player?.playWhenReady = false
    }

    override fun stop() {
        wantPlayback = false
        currentText = null
        // Release rather than merely stop: a stopped ExoPlayer still holds a native
        // decoder slot, and stop() is also how a mode switch frees this subsystem so the
        // other player is not competing for slots. play()/switchTo() rebuild on demand.
        player?.release()
        player = null
        isPlaying = false
        service.publishStoppedState()
        service.broadcastState()
        service.stopForegroundNotification()
    }

    override fun next() {
        val idx = radioManager.nextQueueIndex
        if (idx < 0) return
        radioManager.setCurrentIndex(idx)
        switchToCurrent()
    }

    override fun previous() {
        val station = if (radioManager.isShuffleOn) radioManager.shuffleSong()
                      else radioManager.prevSong()
        if (station != null) switchTo(station)
    }

    override fun toggleShuffle() {
        radioManager.toggleShuffle()
        service.publishShuffleMode()
        radioManager.selectNextQueueSong()
        service.broadcastState()
    }

    override fun playAt(index: Int) {
        radioManager.setCurrentIndex(index)
        switchToCurrent()
    }

    override fun playSong(song: Song) {
        switchTo(song)
    }

    override fun prepare() {
        val station = radioManager.getCurrentSong() ?: return
        val p = ensurePlayer()
        if (p.currentMediaItem == null) {
            p.setMediaItem(MediaItem.fromUri(station.uri))
            p.prepare()
        }
        service.updatePlaybackState()
    }

    /** A live stream has no seek. */
    override fun seekTo(ms: Int) = Unit

    override fun setVolume(v: Float) {
        player?.volume = v
    }

    override fun release() {
        player?.release()
        player = null
        currentText = null
        isPlaying = false
    }

    private fun switchToCurrent() {
        val station = radioManager.getCurrentSong() ?: return
        switchTo(station)
    }

    private fun switchTo(station: Song) {
        wantPlayback = true
        currentText = null
        service.requestAudioFocus()
        val p = ensurePlayer()
        p.setMediaItem(MediaItem.fromUri(station.uri))
        p.prepare()
        p.volume = service.getAppVolumeFloat()
        p.playWhenReady = true
        radioManager.selectNextQueueSong()
        service.updateMediaSessionMetadata()
        service.updatePlaybackState()
        service.startForegroundNotification()
        service.broadcastState()
    }

    private fun ensurePlayer(): ExoPlayer = player ?: buildPlayer().also { player = it }

    private fun buildPlayer(): ExoPlayer {
        val bufferMs = bufferSeconds() * 1000
        val loadControl = DefaultLoadControl.Builder()
            // min/max size the retention window: hold up to the user's buffer-seconds of
            // audio, so a dropout rides through as long as the server actually fed us that
            // much ahead. A live stream trickles at the encode rate after its initial
            // burst, so occupancy is really capped by that burst, not by this number.
            // The playback/rebuffer gates stay at the small defaults: they decide how much
            // must be buffered before playback starts or resumes, and the burst covers them
            // instantly — tying them to buffer-seconds is what made a large value stall on
            // tune-in while the buffer trickled up to the gate.
            .setBufferDurationsMs(
                bufferMs,
                maxOf(bufferMs, DefaultLoadControl.DEFAULT_MAX_BUFFER_MS),
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()
        val sourceFactory = DefaultMediaSourceFactory(service)
            .setLoadErrorHandlingPolicy(GentleReconnectPolicy())
        return ExoPlayer.Builder(service)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(sourceFactory)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                /* handleAudioFocus = */ false
            )
            .build()
            .apply { addListener(playerListener) }
    }

    private fun bufferSeconds(): Int =
        service.getSharedPreferences("androsnd_prefs", Context.MODE_PRIVATE)
            .getInt(KEY_BUFFER_SECONDS, DEFAULT_BUFFER_SECONDS)

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            service.updatePlaybackState()
            if (playing) service.startForegroundNotification() else service.updateNotification()
            service.broadcastState()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            val icy = mediaMetadata.title?.toString()?.trim()
            val station = radioManager.getCurrentSong()?.displayName ?: ""
            val next = if (!icy.isNullOrEmpty()) {
                SongMetadata(title = icy, artist = station, album = "", duration = 0L)
            } else {
                SongMetadata(title = station, artist = "", album = "", duration = 0L)
            }
            // A live stream re-sends the same ICY title on a timer; only refresh the
            // session, notification and UI when the text actually changes.
            if (next == currentText) return
            currentText = next
            service.updateMediaSessionMetadata()
            service.updateNotification()
            service.broadcastState()
        }

        override fun onPlayerError(error: PlaybackException) {
            // The load policy retries transient source errors transparently; this only
            // fires for errors that reached the player. Re-prepare after a calm delay
            // rather than tight-looping.
            service.serviceScope.launch {
                delay(5000)
                val p = player ?: return@launch
                p.prepare()
                if (wantPlayback) p.playWhenReady = true
            }
        }
    }

    @UnstableApi
    private class GentleReconnectPolicy : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
            if (loadErrorInfo.errorCount <= 1) 0L else 5000L

        override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE
    }
}
