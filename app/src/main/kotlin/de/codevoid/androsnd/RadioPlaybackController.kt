package de.codevoid.androsnd

import android.content.Intent
import android.graphics.Bitmap
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import de.codevoid.androsnd.model.PlaylistFolder
import de.codevoid.androsnd.model.Song
import de.codevoid.androsnd.model.SongMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The internet-radio subsystem: an [ExoPlayer] streaming the station whose [Song.uri]
 * is an `http(s)://` URL, with [RadioManager] as its cursor. A live stream cannot seek
 * or report a duration, so [canSeek] is false and [positionMs]/[durationMs] stay 0; the
 * shell hides the seek bar accordingly.
 *
 * Two policies shape the streaming feel and are deliberate, not defaults:
 *
 *  - **Timeshift buffer.** `maxBufferMs` is fixed at [MAX_BUFFER_MS] (20 minutes) so the
 *    player may hoard far ahead of the playhead. The playback/rebuffer gates stay at the
 *    small defaults, so start-up and post-drop resume are fast: the server's initial burst
 *    covers them instantly. The point is [pause] — while paused the player keeps loading
 *    and consumes nothing, so the buffer grows in real time toward the cap and can then
 *    ride through a tunnel. [bufferedMs] reports how far ahead the buffer currently reaches
 *    so the shell can show a fill gauge. If the offline stretch outlasts the buffer, the
 *    stream jumps forward on reconnect rather than staying silent.
 *  - **Self-healing source.** [LiveStreamDataSource] reconnects at the live edge inside the
 *    data source, invisibly to the player, so a network change does not error the loader and
 *    the timeshift buffer survives it. It replaces a load-error/re-prepare scheme that tore
 *    the buffer down and could wedge on a live stream's non-seekable resume.
 *
 * There is no tag/art enrichment: now-playing text comes from the stream's ICY metadata,
 * de-interleaved by [LiveStreamDataSource] and delivered through [onIcyTitle]; art is a
 * generic station icon supplied by the shell.
 */
@OptIn(UnstableApi::class)
class RadioPlaybackController(private val service: MusicService) : PlaybackController {

    companion object {
        // The retention window the player is allowed to hold ahead of the playhead. It is
        // deliberately huge: while paused the player keeps loading and consumes nothing, so
        // the buffer grows in real time up to this cap — a client-side timeshift for riding
        // through a tunnel. 20 minutes is a ceiling, not an expectation; a live stream only
        // fills this fast while paused and only as long as the server keeps feeding.
        const val MAX_BUFFER_MS = 20 * 60 * 1000

        // A group's bundled logo drawable is named `logo_<slug>`: the group name lowercased,
        // every run of non-alphanumeric characters collapsed to one underscore, ends trimmed.
        // "Radio RPR1" -> "radio_rpr1", "SomaFM" -> "somafm".
        fun logoSlug(groupName: String): String =
            groupName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
    }

    val radioManager = RadioManager(service)

    private var player: ExoPlayer? = null

    // The current station's bundled group logo, materialised into the folder-art cache and
    // decoded here for the notification/session large icon. null falls back to the shell's
    // generic station icon (and for a user group with no bundled logo). currentArtFolderPath
    // is the folder the bitmap was decoded for, so a refresh can skip re-decoding when the
    // logo has not changed — stations in one group share a folder and thus a logo.
    private var currentArtBitmap: Bitmap? = null
    private var currentArtFolderPath: String? = null

    override var isPlaying: Boolean = false
        private set
    override val isPreparing: Boolean
        get() = player?.playbackState == Player.STATE_BUFFERING
    override val isScanning: Boolean = false
    override var currentText: SongMetadata? = null
        private set
    override val currentArt: Bitmap? get() = currentArtBitmap
    override val positionMs: Int = 0
    override val durationMs: Int = 0
    override val canSeek: Boolean = false
    override val bufferedMs: Int get() = player?.totalBufferedDuration?.toInt() ?: 0

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
        materializeLogos()
    }

    // Renders each group's bundled logo (a `logo_<slug>` drawable, keyed by group name)
    // into the folder-art cache so the grid, now-playing panel, notification and Auto pick
    // it up through the same artFileForFolder path as file covers. Runs off the main thread
    // because load() is called on it; a group with no matching drawable is left to the
    // generic icon fallback.
    private fun materializeLogos() {
        val folders = radioManager.folders.toList()
        service.serviceScope.launch {
            val written = withContext(Dispatchers.IO) {
                folders.mapNotNull { folder ->
                    val resId = logoResId(folder.name)
                    if (resId != 0 && service.metadataRepository.cacheDrawableArt(folder.path, resId))
                        folder.path
                    else null
                }
            }
            for (path in written) {
                service.broadcastManager.sendBroadcast(
                    Intent(MusicService.BROADCAST_ART_UPDATED)
                        .putExtra(MusicService.EXTRA_ART_FOLDER_PATH, path))
            }
            refreshCurrentArt()
        }
    }

    private fun logoResId(groupName: String): Int {
        val slug = logoSlug(groupName)
        if (slug.isEmpty()) return 0
        return service.resources.getIdentifier("logo_$slug", "drawable", service.packageName)
    }

    // Decodes the current station's cached logo for the notification/session large icon and
    // publishes it as the current-art file. File-only (publishFolderArt opens no retriever),
    // so it never touches the live stream URI. Skips the decode when the logo is already
    // loaded, and — because the decode is async — drops its result if the station changed
    // while it ran, so a slow load cannot overwrite a newer station's art.
    private fun refreshCurrentArt() {
        val folderPath = radioManager.getCurrentSong()?.folderPath ?: return
        if (folderPath == currentArtFolderPath && currentArtBitmap != null) return
        service.serviceScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                service.metadataRepository.publishFolderArt(folderPath)
            }
            if (radioManager.getCurrentSong()?.folderPath != folderPath) return@launch
            currentArtBitmap = bmp
            currentArtFolderPath = if (bmp != null) folderPath else null
            service.updateMediaSessionMetadata()
            service.updateNotification()
        }
    }

    override fun play() {
        val station = radioManager.getCurrentSong() ?: return
        if (currentText == null) currentText = stationText(station)
        refreshCurrentArt()
        service.requestAudioFocus()
        val p = ensurePlayer()
        if (p.currentMediaItem == null) {
            p.setMediaItem(MediaItem.fromUri(station.uri))
            p.prepare()
        }
        p.volume = service.getAppVolumeFloat()
        p.playWhenReady = true
        // The buffer keeps filling while paused, so the fill gauge must keep ticking then
        // too. Radio has no other periodic pulse, so drive the tick from here and leave it
        // running through pause; only stop()/release() halt it.
        service.startProgressUpdates()
    }

    override fun pause() {
        // Do not clear playWhenReady's loading: ExoPlayer keeps filling the buffer while
        // paused, which is the timeshift window the user is hoarding. The tick stays running.
        player?.playWhenReady = false
    }

    override fun stop() {
        currentText = null
        currentArtBitmap = null
        currentArtFolderPath = null
        service.stopProgressUpdates()
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
        service.stopProgressUpdates()
        player?.release()
        player = null
        currentText = null
        currentArtBitmap = null
        currentArtFolderPath = null
        isPlaying = false
    }

    private fun switchToCurrent() {
        val station = radioManager.getCurrentSong() ?: return
        switchTo(station)
    }

    private fun switchTo(station: Song) {
        currentText = stationText(station)
        refreshCurrentArt()
        service.requestAudioFocus()
        val p = ensurePlayer()
        p.setMediaItem(MediaItem.fromUri(station.uri))
        p.prepare()
        p.volume = service.getAppVolumeFloat()
        p.playWhenReady = true
        service.startProgressUpdates()
        radioManager.selectNextQueueSong()
        service.updateMediaSessionMetadata()
        service.updatePlaybackState()
        service.startForegroundNotification()
        service.broadcastState()
    }

    private fun ensurePlayer(): ExoPlayer = player ?: buildPlayer().also { player = it }

    private fun buildPlayer(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            // min/max size the retention window. max is set to MAX_BUFFER_MS so the player
            // may hoard up to 20 minutes ahead of the playhead; while paused it keeps loading
            // and consumes nothing, so the buffer grows in real time toward that cap. The
            // playback/rebuffer gates stay at the small defaults, so start-up and post-drop
            // resume are fast regardless: the server's initial burst covers them instantly.
            // prioritizeTimeOverSizeThresholds makes the time cap govern rather than a byte
            // estimate, which otherwise stops loading long before 20 minutes of audio.
            .setBufferDurationsMs(
                DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val dataSourceFactory = LiveStreamDataSource.Factory { title ->
            service.serviceScope.launch { onIcyTitle(title) }
        }
        val sourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
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

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            service.updatePlaybackState()
            if (playing) service.startForegroundNotification() else service.updateNotification()
            service.broadcastState()
        }
    }

    private fun stationText(station: Song): SongMetadata =
        SongMetadata(title = station.displayName, artist = "", album = "", duration = 0L)

    // Called on the main thread with each StreamTitle de-interleaved by LiveStreamDataSource.
    // A live stream re-sends the same title on a timer, and an empty title falls back to the
    // station name, so only refresh the session, notification and UI when the text changes.
    private fun onIcyTitle(icy: String) {
        val station = radioManager.getCurrentSong() ?: return
        val next = if (icy.isNotEmpty()) {
            SongMetadata(title = icy, artist = station.displayName, album = "", duration = 0L)
        } else {
            stationText(station)
        }
        if (next == currentText) return
        currentText = next
        service.updateMediaSessionMetadata()
        service.updateNotification()
        service.broadcastState()
    }
}
