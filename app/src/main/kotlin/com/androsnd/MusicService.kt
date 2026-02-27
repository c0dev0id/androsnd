package com.androsnd

import android.app.Notification
import android.app.NotificationManager
import android.support.v4.media.MediaBrowserCompat
import androidx.media.MediaBrowserServiceCompat
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.annotation.MainThread
import com.androsnd.model.Song
import com.androsnd.model.SongMetadata
import java.util.concurrent.Executors

class MusicService : MediaBrowserServiceCompat() {

    companion object {
        private const val TAG = "MusicService"

        const val ACTION_PLAY = "com.androsnd.PLAY"
        const val ACTION_PAUSE = "com.androsnd.PAUSE"
        const val ACTION_STOP = "com.androsnd.STOP"
        const val ACTION_NEXT = "com.androsnd.NEXT"
        const val ACTION_PREVIOUS = "com.androsnd.PREVIOUS"
        const val ACTION_SHUFFLE = "com.androsnd.SHUFFLE"

        private const val DOUBLE_TAP_THRESHOLD = 500L
        private const val SKIP_DURATION_MS = 10000
        private const val MAX_CONSECUTIVE_ERRORS = 3
    }

    interface Listener {
        fun onStateChanged()
        fun onScanStarted()
        fun onScanCompleted()
    }

    var serviceListener: Listener? = null

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    private val binder = MusicBinder()
    lateinit var playlistManager: PlaylistManager
        private set

    private lateinit var playerController: PlayerController
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var notificationBuilder: PlaybackNotificationBuilder
    private lateinit var metadataExtractor: MetadataExtractor
    private lateinit var audioManager: AudioManager

    private val handler = Handler(Looper.getMainLooper())
    private var scanExecutor = Executors.newSingleThreadExecutor()

    var isScanning: Boolean = false
        private set
    var currentMetadata: SongMetadata? = null
        private set

    val isPlaying: Boolean get() = playerController.isPlaying

    private var lastPlayPauseTime = 0L
    private var nextPendingRunnable: Runnable? = null
    private var prevPendingRunnable: Runnable? = null
    private var consecutiveErrors = 0

    private lateinit var overlayToastManager: OverlayToastManager

    private val playerListener = object : PlayerController.Listener {
        @MainThread
        override fun onPrepared() {
            // Called when prepareSong() finishes — player is ready but not started.
            // Broadcast state immediately so the UI can show the song is prepared,
            // then extract metadata in the background to avoid blocking the main thread.
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            broadcastState()
            val song = playlistManager.getCurrentSong() ?: return
            scanExecutor.execute {
                val metadata = metadataExtractor.extract(song)
                handler.post {
                    currentMetadata = metadata
                    updateMediaSessionMetadata(metadata)
                    broadcastState()
                }
            }
        }

        @MainThread
        override fun onPreparedAndStarted() {
            consecutiveErrors = 0
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            startForegroundCompat(buildNotification())
            broadcastState()
        }

        @MainThread
        override fun onResumed() {
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            startForegroundCompat(buildNotification())
            broadcastState()
        }

        @MainThread
        override fun onPaused() {
            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
            notificationBuilder.update(
                mediaSession.sessionToken, false,
                playlistManager.getCurrentSong()?.displayName, currentMetadata
            )
            broadcastState()
        }

        @MainThread
        override fun onCompleted() {
            onTrackComplete()
        }

        @MainThread
        override fun onError() {
            currentMetadata = null
            broadcastState()
            onTrackComplete()
        }

        @MainThread
        override fun onProgressUpdate() {
            broadcastState()
        }
    }

    override fun onCreate() {
        super.onCreate()
        playlistManager = PlaylistManager(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        playerController = PlayerController(this, audioManager)
        playerController.listener = playerListener
        notificationBuilder = PlaybackNotificationBuilder(this)
        metadataExtractor = MetadataExtractor(this)
        overlayToastManager = OverlayToastManager(this)

        notificationBuilder.createNotificationChannel()
        initMediaSession()

        val savedUri = playlistManager.loadSavedFolder()
        if (savedUri != null) {
            if (!playlistManager.hasFolderPermission()) {
                Log.w(TAG, "Persisted URI permission lost for $savedUri; skipping load")
                playlistManager.clearScanCache()
            } else if (playlistManager.loadScanCache() && playlistManager.isCacheValid()) {
                serviceListener?.onScanCompleted()
                broadcastState()
            } else {
                playlistManager.clearScanCache()
                scanFolderAsync(savedUri)
            }
        }
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "AndrosndSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onStop() { handleStop() }
                override fun onSkipToNext() { handleNext() }
                override fun onSkipToPrevious() { handlePrevious() }
                override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
                override fun onFastForward() { seekTo(getPosition() + SKIP_DURATION_MS) }
                override fun onRewind() { seekTo(maxOf(0, getPosition() - SKIP_DURATION_MS)) }
                override fun onSetShuffleMode(shuffleMode: Int) {
                    val shouldShuffle = shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE
                    if (playlistManager.isShuffleOn != shouldShuffle) {
                        playlistManager.toggleShuffle()
                    }
                    this@MusicService.mediaSession.setShuffleMode(shuffleMode)
                    updatePlaybackState(if (playerController.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
                    broadcastState()
                }
                override fun onSetRepeatMode(repeatMode: Int) {
                    playlistManager.setRepeatMode(repeatMode)
                    this@MusicService.mediaSession.setRepeatMode(repeatMode)
                    broadcastState()
                }
                override fun onSkipToQueueItem(id: Long) { playSongAtIndex(id.toInt()) }
                override fun onPlayFromUri(uri: Uri?, extras: Bundle?) {
                    uri ?: return
                    val song = Song(uri = uri, displayName = uri.lastPathSegment ?: "Unknown")
                    playSong(song)
                }
                override fun onPrepare() {
                    if (playlistManager.songs.isEmpty()) return
                    val song = playlistManager.getCurrentSong() ?: return
                    playerController.prepareSong(song)
                }
            })
            isActive = true
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(PlaybackNotificationBuilder.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(PlaybackNotificationBuilder.NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground() promptly to avoid ForegroundServiceDidNotStartInTimeException
        if (intent?.action == null || intent.action !in setOf(
                ACTION_PLAY, ACTION_PAUSE, ACTION_STOP, ACTION_NEXT, ACTION_PREVIOUS, ACTION_SHUFFLE
        )) {
            startForegroundCompat(buildNotification())
        }
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_STOP -> handleStop()
            ACTION_NEXT -> handleNext()
            ACTION_PREVIOUS -> handlePrevious()
            ACTION_SHUFFLE -> handleShuffleButton()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == SERVICE_INTERFACE) {
            return super.onBind(intent)
        }
        return binder
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        return BrowserRoot("root", null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>
    ) {
        result.sendResult(emptyList())
    }

    @MainThread
    fun play() {
        if (playlistManager.songs.isEmpty()) return

        if (!playerController.isActive) {
            val song = playlistManager.getCurrentSong() ?: return
            startPlayingSong(song)
        } else if (!playerController.isPlaying && playerController.isPlayerPrepared) {
            playerController.resume()
        }
    }

    @MainThread
    fun pause() {
        playerController.pause()
    }

    // Wrapper kept for API consistency with handleNext()/handlePrevious().
    fun handleStop() {
        stopPlayback()
    }

    fun handlePlayPause() {
        val now = System.currentTimeMillis()
        // Double-tap within DOUBLE_TAP_THRESHOLD ms toggles shuffle instead of play/pause.
        if (now - lastPlayPauseTime < DOUBLE_TAP_THRESHOLD) {
            lastPlayPauseTime = 0L
            playlistManager.toggleShuffle()
            mediaSession.setShuffleMode(if (playlistManager.isShuffleOn) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
            broadcastState()
        } else {
            lastPlayPauseTime = now
            if (playerController.isPlaying) pause() else play()
        }
    }

    private fun stopPlayback() {
        playerController.releasePlayer()
        currentMetadata = null
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        broadcastState()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun handleNext() {
        handleSkip(
            getPending = { nextPendingRunnable },
            setPending = { nextPendingRunnable = it },
            onDoubleTap = { playlistManager.nextFolder() },
            onSingleTap = { playlistManager.nextSong() }
        )
    }

    fun handlePrevious() {
        handleSkip(
            getPending = { prevPendingRunnable },
            setPending = { prevPendingRunnable = it },
            onDoubleTap = { playlistManager.prevFolder() },
            onSingleTap = { playlistManager.prevSong() }
        )
    }

    /**
     * Shared double-tap handler for next/previous skip actions.
     * A single tap fires [onSingleTap] after a delay; a second tap within
     * [DOUBLE_TAP_THRESHOLD] cancels the pending single-tap and fires [onDoubleTap] instead.
     */
    private inline fun handleSkip(
        getPending: () -> Runnable?,
        crossinline setPending: (Runnable?) -> Unit,
        crossinline onDoubleTap: () -> Song?,
        crossinline onSingleTap: () -> Song?
    ) {
        if (playlistManager.isShuffleOn) {
            val song = playlistManager.shuffleSong()
            if (song != null) playSong(song)
            return
        }

        val pending = getPending()
        if (pending != null) {
            handler.removeCallbacks(pending)
            setPending(null)
            val song = onDoubleTap()
            if (song != null) playSong(song)
        } else {
            val runnable = Runnable {
                setPending(null)
                val song = onSingleTap()
                if (song != null) playSong(song)
            }
            setPending(runnable)
            handler.postDelayed(runnable, DOUBLE_TAP_THRESHOLD)
        }
    }

    fun handleShuffleButton() {
        playlistManager.toggleShuffle()
        mediaSession.setShuffleMode(if (playlistManager.isShuffleOn) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
        broadcastState()
    }

    @MainThread
    fun seekTo(positionMs: Int) {
        playerController.seekTo(positionMs)
        updatePlaybackState(if (playerController.isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
        broadcastState()
    }

    fun playSong(song: Song) {
        playerController.releasePlayer()
        startPlayingSong(song)
    }

    fun playSongAtIndex(index: Int) {
        playlistManager.setCurrentIndex(index)
        val song = playlistManager.getCurrentSong() ?: return
        playSong(song)
    }

    private fun startPlayingSong(song: Song) {
        // Use a placeholder immediately so UI shows the song name before metadata is ready
        val placeholder = SongMetadata(song.displayName, "", "", 0L, null)
        currentMetadata = placeholder
        playerController.startSong(song)
        updateMediaSessionMetadata(placeholder)
        broadcastState()
        overlayToastManager.showSong(placeholder)
        // Extract real metadata in the background to avoid blocking the main thread
        scanExecutor.execute {
            val metadata = metadataExtractor.extract(song)
            handler.post {
                if (currentMetadata == placeholder) {
                    currentMetadata = metadata
                    updateMediaSessionMetadata(metadata)
                    broadcastState()
                    overlayToastManager.showSong(metadata)
                }
            }
        }
    }

    private fun onTrackComplete() {
        when (playlistManager.repeatMode) {
            PlaybackStateCompat.REPEAT_MODE_ONE -> {
                val song = playlistManager.getCurrentSong()
                if (song != null) playSong(song)
            }
            PlaybackStateCompat.REPEAT_MODE_NONE -> {
                if (playlistManager.isShuffleOn) {
                    val song = playlistManager.shuffleSongNoRepeat()
                    if (song != null) playSong(song) else stopPlayback()
                } else {
                    val isLast = playlistManager.currentIndex >= playlistManager.songs.size - 1
                    if (!isLast) {
                        val song = playlistManager.nextSong()
                        if (song != null) playSong(song)
                    } else {
                        stopPlayback()
                    }
                }
            }
            else -> {
                val song = if (playlistManager.isShuffleOn) playlistManager.shuffleSong()
                           else playlistManager.nextSong()
                if (song != null) playSong(song)
            }
        }
    }

    private fun updateMediaSessionMetadata(metadata: SongMetadata) {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, metadata.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, metadata.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, metadata.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, metadata.duration)
        if (metadata.coverArt != null) {
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, metadata.coverArt)
        }
        mediaSession.setMetadata(builder.build())
    }

    private fun updatePlaybackState(state: Int) {
        val position = playerController.getPosition().toLong()
        val speed = if (playerController.isPlaying) 1f else 0f
        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, position, speed)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND or
                PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or
                PlaybackStateCompat.ACTION_PREPARE or
                PlaybackStateCompat.ACTION_SET_REPEAT_MODE or
                PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or
                PlaybackStateCompat.ACTION_PLAY_FROM_URI
            )
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun updateMediaSessionQueue() {
        val queue = playlistManager.songs.mapIndexed { index, song ->
            val description = MediaDescriptionCompat.Builder()
                .setTitle(song.displayName)
                .setMediaUri(song.uri)
                .build()
            MediaSessionCompat.QueueItem(description, index.toLong())
        }
        mediaSession.setQueue(queue)
    }

    private fun buildNotification() = notificationBuilder.build(
        mediaSession.sessionToken,
        playerController.isPlaying,
        playlistManager.getCurrentSong()?.displayName,
        currentMetadata
    )

    @MainThread
    fun broadcastState() {
        serviceListener?.onStateChanged()
    }

    fun scanFolderAsync(uri: Uri) {
        playerController.releasePlayer()

        playlistManager.clearScanCache()
        isScanning = true
        serviceListener?.onScanStarted()
        if (scanExecutor.isShutdown) {
            scanExecutor = Executors.newSingleThreadExecutor()
        }
        scanExecutor.execute {
            try {
                playlistManager.scanFolder(uri)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to scan folder", e)
            } finally {
                handler.post {
                    isScanning = false
                    updateMediaSessionQueue()
                    serviceListener?.onScanCompleted()
                    broadcastState()
                }
            }
        }
    }

    fun getPosition(): Int = playerController.getPosition()
    fun getDuration(): Int = playerController.getDuration()

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (!playerController.isPlaying) {
            stopPlayback()
            stopSelf()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        nextPendingRunnable?.let { handler.removeCallbacks(it) }
        prevPendingRunnable?.let { handler.removeCallbacks(it) }
        playerController.release()
        mediaSession.release()
        overlayToastManager.dismiss()
        scanExecutor.shutdown()
    }
}
