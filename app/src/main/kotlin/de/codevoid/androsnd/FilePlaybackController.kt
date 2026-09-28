package de.codevoid.androsnd

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import de.codevoid.androsnd.model.Song
import de.codevoid.androsnd.model.SongMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The local-file playback subsystem: a [MediaPlayer] over SAF files, backed by
 * [PlaylistManager] for the library and cursor and [MetadataRepository] for tag/art
 * enrichment. This is the app's original playback path, relocated behind
 * [PlaybackController] with no logic change — [MusicService] still owns the shared
 * shell (MediaSession, notification, audio focus) and this controller calls back into
 * it for those.
 *
 * The MediaPlayer state machine is delicate on purpose: `isPreparing`/`playRequested`
 * latch a play issued before the player is ready, the `mediaPlayer !== mp` guards drop
 * callbacks from a superseded player, and `lastErrorTimeMs` suppresses a retry loop on
 * an unplayable file. See MusicService's history for why each exists.
 */
class FilePlaybackController(private val service: MusicService) : PlaybackController {

    companion object {
        private const val TAG = "FilePlayback"
    }

    val playlistManager = PlaylistManager(service)
    val metadataRepository = MetadataRepository(service)

    private var mediaPlayer: MediaPlayer? = null

    override var isPlaying: Boolean = false
        private set
    override var isPreparing: Boolean = false
        private set
    override var isScanning: Boolean = false
        private set
    override var currentText: SongMetadata? = null
        private set
    override var currentArt: Bitmap? = null
        private set

    override val positionMs: Int get() = mediaPlayer?.currentPosition ?: 0
    override val durationMs: Int get() = mediaPlayer?.duration ?: 0
    override val canSeek: Boolean get() = true

    override val currentSong: Song? get() = playlistManager.getCurrentSong()
    override val currentIndex: Int get() = playlistManager.currentIndex
    override val songCount: Int get() = playlistManager.songs.size
    override val isShuffleOn: Boolean get() = playlistManager.isShuffleOn

    // "The user asked for playback but it could not start yet." Only ever set while a
    // readiness event is pending, so it cannot be left standing with nothing to consume
    // it; whichever readiness point arrives first consumes it.
    private var playRequested = false
    private var lastErrorTimeMs = 0L

    override fun play() {
        if (playlistManager.songs.isEmpty()) {
            // A media button pressed during the startup scan would otherwise be
            // dropped: the library is still empty for the first seconds after launch.
            if (isScanning) playRequested = true
            return
        }

        if (mediaPlayer == null) {
            val song = playlistManager.getCurrentSong() ?: return
            val msSinceError = System.currentTimeMillis() - lastErrorTimeMs
            if (msSinceError < 1500L) {
                Log.d(TAG, "play(): suppressing auto-retry ${msSinceError}ms after last error")
                return
            }
            startPlayingSong(song)
        } else if (isPreparing) {
            playRequested = true
        } else if (!isPlaying) {
            service.requestAudioFocus()
            mediaPlayer?.start()
            setVolume(service.getAppVolumeFloat())
            isPlaying = true
            service.startProgressUpdates()
            service.updateMediaSessionMetadata()
            service.updatePlaybackState()
            service.startForegroundNotification()
            service.broadcastState()
        }
    }

    override fun pause() {
        mediaPlayer?.let {
            if (isPlaying) {
                it.pause()
                isPlaying = false
                service.stopProgressUpdates()
                service.updatePlaybackState()
                service.updateNotification()
                service.broadcastState()
            }
        }
    }

    override fun stop() {
        mediaPlayer?.let { releasePlayer(it) }
        mediaPlayer = null
        isPlaying = false
        isPreparing = false
        // Stop means stop: a play request still waiting on a readiness event would
        // otherwise survive the teardown and start the next prepared song.
        playRequested = false
        currentText = null
        currentArt = null
        service.stopProgressUpdates()
        service.publishStoppedState()
        service.broadcastState()
        service.stopForegroundNotification()
    }

    override fun next() {
        val nextIdx = playlistManager.nextQueueIndex
        if (nextIdx < 0) return
        playlistManager.setCurrentIndex(nextIdx)
        val song = playlistManager.getCurrentSong() ?: return
        playSong(song)
    }

    override fun previous() {
        if (playlistManager.isShuffleOn) {
            val song = playlistManager.shuffleSong()
            if (song != null) playSong(song)
            return
        }
        val song = playlistManager.prevSong()
        if (song != null) playSong(song)
    }

    override fun toggleShuffle() {
        playlistManager.toggleShuffle()
        service.publishShuffleMode()
        playlistManager.selectNextQueueSong()
        service.serviceScope.launch {
            try { service.updateMediaSessionQueue() }
            catch (e: Exception) { Log.w(TAG, "Failed to update media session queue", e) }
        }
        service.broadcastState()
    }

    override fun playAt(index: Int) {
        playlistManager.setCurrentIndex(index)
        val song = playlistManager.getCurrentSong() ?: return
        playSong(song)
    }

    override fun playSong(song: Song) {
        mediaPlayer?.let { releasePlayer(it) }
        mediaPlayer = null
        isPlaying = false
        isPreparing = false
        service.stopProgressUpdates()
        startPlayingSong(song)
    }

    override fun prepare() {
        if (playlistManager.songs.isEmpty()) return
        val song = playlistManager.getCurrentSong() ?: return
        if (mediaPlayer == null) {
            startPlayingSong(song, autoStart = false)
        } else if (!isPreparing) {
            // Already loaded — confirm current state so the system doesn't wait.
            service.updatePlaybackState()
        }
    }

    override fun seekTo(ms: Int) {
        val duration = mediaPlayer?.duration ?: 0
        val clamped = ms.coerceIn(0, if (duration > 0) duration else 0)
        mediaPlayer?.seekTo(clamped)
        service.updatePlaybackState()
        service.broadcastState()
    }

    override fun setVolume(v: Float) {
        mediaPlayer?.setVolume(v, v)
    }

    override fun release() {
        service.stopProgressUpdates()
        mediaPlayer?.release()
        mediaPlayer = null
        currentText = null
        currentArt = null
        metadataRepository.close()
    }

    /**
     * Tear down a player from any state. `stop()` is only legal once the player is
     * prepared — calling it during `prepareAsync()` throws, and since `release()` used
     * to follow it as a separate statement, the native player leaked. Swallow the
     * illegal-state case so `release()` always runs.
     */
    private fun releasePlayer(player: MediaPlayer) {
        try {
            player.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "stop() on a player that was not in a stoppable state", e)
        }
        player.release()
    }

    private fun startPlayingSong(song: Song, autoStart: Boolean = true) {
        val player = MediaPlayer()
        mediaPlayer = player
        isPreparing = true
        try {
            if (autoStart) service.requestAudioFocus()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            player.setDataSource(service.applicationContext, song.uri)
            player.setOnCompletionListener { mp ->
                if (mediaPlayer !== mp) return@setOnCompletionListener
                next()
            }
            player.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                // A superseded player must not touch isPreparing — the player that
                // replaced it may still be preparing, and clearing the flag here would
                // let play() call start() on it in an illegal state.
                if (mediaPlayer !== mp) return@setOnErrorListener true
                isPreparing = false
                playRequested = false
                lastErrorTimeMs = System.currentTimeMillis()
                mp.release()
                mediaPlayer = null
                isPlaying = false
                service.stopProgressUpdates()
                service.publishStoppedState()
                service.updateNotification()
                service.broadcastState()
                true
            }
            player.setWakeMode(service.applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            player.setOnPreparedListener { mp ->
                // Guard before clearing the flag: a late callback from a discarded
                // player must not report that the current one is done preparing.
                if (mediaPlayer !== mp) return@setOnPreparedListener
                isPreparing = false
                val vol = service.getAppVolumeFloat()
                mp.setVolume(vol, vol)
                val shouldPlay = autoStart || playRequested
                playRequested = false
                if (shouldPlay) {
                    if (!autoStart) service.requestAudioFocus()
                    mp.start()
                    isPlaying = true
                    service.startProgressUpdates()
                    service.updatePlaybackState()
                    service.startForegroundNotification()
                    service.broadcastState()
                    playlistManager.selectNextQueueSong()
                } else {
                    service.updatePlaybackState()
                    service.broadcastState()
                }
                service.serviceScope.launch {
                    // Both fetches suspend, and the user may skip to another song
                    // meanwhile. Re-check ownership after each one, or this song's title
                    // and cover overwrite whatever is actually playing now.
                    val meta = withContext(Dispatchers.IO) { metadataRepository.fetchText(song) }
                    if (mediaPlayer !== mp) return@launch
                    val art  = withContext(Dispatchers.IO) { metadataRepository.loadCurrentArt(song) }
                    if (mediaPlayer !== mp) return@launch
                    currentText = meta
                    currentArt  = art
                    service.updateMediaSessionMetadata()
                    service.updateNotification()
                    val overlayEnabled = service.getSharedPreferences("androsnd_prefs", Context.MODE_PRIVATE)
                        .getBoolean("overlay_enabled", true)
                    if (overlayEnabled) {
                        service.overlayToastManager.showSong(meta, art)
                    }
                    service.broadcastState()
                }
            }
            player.prepareAsync()
        } catch (e: Exception) {
            isPreparing = false
            playRequested = false
            lastErrorTimeMs = System.currentTimeMillis()
            Log.e(TAG, "Failed to start playing ${song.displayName}", e)
        }
    }

    fun scan(uri: Uri) {
        playRequested = false
        mediaPlayer?.let { releasePlayer(it) }
        mediaPlayer = null
        isPlaying = false
        service.stopProgressUpdates()
        metadataRepository.cancelEnrichment()
        playlistManager.clear()
        currentText = null
        currentArt = null

        isScanning = true
        service.broadcastManager.sendBroadcast(Intent(MusicService.BROADCAST_SCAN_STARTED))

        service.serviceScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    playlistManager.scanFolder(uri) { count ->
                        service.broadcastManager.sendBroadcast(Intent(MusicService.BROADCAST_SCAN_PROGRESS).apply {
                            putExtra(MusicService.EXTRA_SCAN_SONG_COUNT, count)
                        })
                    }
                }.onFailure { Log.e(TAG, "Scan failed", it) }
            }
            isScanning = false
            playlistManager.selectNextQueueSong()
            service.broadcastManager.sendBroadcast(Intent(MusicService.BROADCAST_SCAN_COMPLETED))
            service.broadcastState()

            if (playRequested) {
                playRequested = false
                play()
            } else {
                // Describe the restored song to the session, so a headset or head unit
                // has something to show and act on before anything plays.
                service.updateMediaSessionMetadata()
                service.updatePlaybackState()
            }

            metadataRepository.startEnrichment(
                scope         = service.serviceScope,
                songs         = playlistManager.songs,
                foldersByPath = playlistManager.foldersByPath,
                currentIdx    = playlistManager.currentIndex,
                onTextReady   = { idx, meta ->
                    if (idx == playlistManager.currentIndex && currentText == null) {
                        currentText = meta
                        service.updateMediaSessionMetadata()
                        service.broadcastState()
                    }
                    service.broadcastManager.sendBroadcast(Intent(MusicService.BROADCAST_METADATA_UPDATED).apply {
                        putExtra(MusicService.EXTRA_METADATA_SONG_INDEX, idx)
                        putExtra(MusicService.EXTRA_METADATA_TITLE,    meta.title)
                        putExtra(MusicService.EXTRA_METADATA_ARTIST,   meta.artist)
                        putExtra(MusicService.EXTRA_METADATA_ALBUM,    meta.album)
                        putExtra(MusicService.EXTRA_METADATA_DURATION, meta.duration)
                    })
                },
                onArtReady = { folderPath ->
                    if (playlistManager.getCurrentSong()?.folderPath == folderPath) {
                        service.serviceScope.launch {
                            currentArt = withContext(Dispatchers.IO) {
                                BitmapFactory.decodeFile(
                                    metadataRepository.artFileForFolder(folderPath).absolutePath)
                            }
                            service.updateNotification()
                            service.updateMediaSessionMetadata()
                        }
                    }
                    service.broadcastManager.sendBroadcast(Intent(MusicService.BROADCAST_ART_UPDATED).apply {
                        putExtra(MusicService.EXTRA_ART_FOLDER_PATH, folderPath)
                    })
                },
                onComplete = {
                    service.broadcastManager.sendBroadcast(Intent(MusicService.BROADCAST_ENRICHMENT_COMPLETE))
                }
            )
        }
    }
}
