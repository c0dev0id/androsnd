package com.androsnd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.Uri
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.Executors
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.app.NotificationCompat.MediaStyle
import com.androsnd.model.Song
import com.androsnd.model.SongMetadata

class MusicService : Service() {

    companion object {
        private const val TAG = "MusicService"

        const val CHANNEL_ID = "androsnd_channel"
        const val NOTIFICATION_ID = 1

        const val ACTION_PLAY = "com.androsnd.PLAY"
        const val ACTION_PAUSE = "com.androsnd.PAUSE"
        const val ACTION_STOP = "com.androsnd.STOP"
        const val ACTION_NEXT = "com.androsnd.NEXT"
        const val ACTION_PREVIOUS = "com.androsnd.PREVIOUS"
        const val ACTION_SHUFFLE = "com.androsnd.SHUFFLE"

        const val BROADCAST_STATE_CHANGED = "com.androsnd.STATE_CHANGED"
        const val BROADCAST_SCAN_STARTED = "com.androsnd.SCAN_STARTED"
        const val BROADCAST_SCAN_COMPLETED = "com.androsnd.SCAN_COMPLETED"

        private const val PREFS_NAME = "androsnd_prefs"
        private const val KEY_APP_VOLUME = "app_volume"
        private const val KEY_DOUBLE_TAP_TIMEOUT = "double_tap_ms"
        private const val DEFAULT_DOUBLE_TAP_MS = 500
        private const val SKIP_DURATION_MS = 10000
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    private val binder = MusicBinder()
    lateinit var playlistManager: PlaylistManager
        private set

    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null
    private val scanExecutor = Executors.newSingleThreadExecutor()

    var isPlaying: Boolean = false
        private set
    var isScanning: Boolean = false
        private set
    var currentMetadata: SongMetadata? = null
        private set
    private var previousCoverArt: android.graphics.Bitmap? = null

    private var lastPlayPauseTime = 0L
    private var nextPendingRunnable: Runnable? = null
    private var prevPendingRunnable: Runnable? = null

    private lateinit var overlayToastManager: OverlayToastManager
    private lateinit var broadcastManager: LocalBroadcastManager

    private val doubleTapThreshold: Long
        get() = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_DOUBLE_TAP_TIMEOUT, DEFAULT_DOUBLE_TAP_MS).toLong()

    private fun getAppVolumeFloat(): Float {
        val pct = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_APP_VOLUME, 100)
        return pct / 100f
    }

    fun applyAppVolume() {
        val vol = getAppVolumeFloat()
        mediaPlayer?.setVolume(vol, vol)
    }

    fun updateOverlayScale(scale: Float) {
        overlayToastManager.updateScale(scale)
    }

    fun showOverlayDemo() {
        overlayToastManager.showDemo()
    }

    fun dismissOverlayDemo() {
        overlayToastManager.dismiss()
    }

    fun updateOverlayOpacity(opacity: Int) {
        overlayToastManager.updateOpacity(opacity)
    }

    override fun onCreate() {
        super.onCreate()
        playlistManager = PlaylistManager(this)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        broadcastManager = LocalBroadcastManager.getInstance(this)
        overlayToastManager = OverlayToastManager(this)

        createNotificationChannel()
        initMediaSession()

        val savedUri = playlistManager.loadSavedFolder()
        if (savedUri != null) {
            scanFolderAsync(savedUri)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun initMediaSession() {
        val sessionActivity = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSessionCompat(this, "AndrosndSession").apply {
            setSessionActivity(sessionActivity)
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
                    updatePlaybackState(if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
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
                    val song = Song(uri = uri, displayName = uri.lastPathSegment ?: "Unknown", folderPath = "", folderName = "")
                    playSong(song)
                }
                override fun onPrepare() {
                    if (playlistManager.songs.isEmpty()) return
                    val song = playlistManager.getCurrentSong() ?: return
                    if (mediaPlayer == null) {
                        try {
                            mediaPlayer = MediaPlayer().apply {
                                setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                        .setUsage(AudioAttributes.USAGE_MEDIA)
                                        .build()
                                )
                                setDataSource(applicationContext, song.uri)
                                prepare()
                                val vol = getAppVolumeFloat()
                                setVolume(vol, vol)
                                setOnCompletionListener { onTrackComplete() }
                                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                            }
                            val metadata = extractMetadata(song)
                            currentMetadata = metadata
                            updateMediaSessionMetadata(metadata)
                            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                            broadcastState()
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to prepare ${song.displayName}", e)
                        }
                    }
                }
            })
            isActive = true
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call startForeground() promptly to avoid ForegroundServiceDidNotStartInTimeException
        if (intent?.action == null || intent.action !in listOf(
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

    override fun onBind(intent: Intent?): IBinder = binder

    fun play() {
        if (playlistManager.songs.isEmpty()) return

        if (mediaPlayer == null) {
            val song = playlistManager.getCurrentSong() ?: return
            startPlayingSong(song)
        } else {
            if (!isPlaying) {
                mediaPlayer?.start()
                applyAppVolume()
                isPlaying = true
                startProgressUpdates()
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                startForegroundCompat(buildNotification())
                broadcastState()
            }
        }
    }

    fun pause() {
        mediaPlayer?.let {
            if (isPlaying) {
                it.pause()
                isPlaying = false
                stopProgressUpdates()
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                updateNotification()
                broadcastState()
            }
        }
    }

    fun handleStop() {
        stopPlayback()
    }

    fun handlePlayPause() {
        val now = System.currentTimeMillis()
        if (now - lastPlayPauseTime < doubleTapThreshold) {
            lastPlayPauseTime = 0L
            playlistManager.toggleShuffle()
            mediaSession.setShuffleMode(if (playlistManager.isShuffleOn) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
            broadcastState()
        } else {
            lastPlayPauseTime = now
            if (isPlaying) pause() else play()
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.let {
            it.stop()
            it.release()
        }
        mediaPlayer = null
        isPlaying = false
        currentMetadata?.coverArt?.recycle()
        previousCoverArt?.recycle()
        currentMetadata = null
        previousCoverArt = null
        stopProgressUpdates()
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        broadcastState()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun handleNext() {
        if (playlistManager.isShuffleOn) {
            val song = playlistManager.shuffleSong()
            if (song != null) playSong(song)
            return
        }

        val pending = nextPendingRunnable
        if (pending != null) {
            handler.removeCallbacks(pending)
            nextPendingRunnable = null
            val song = playlistManager.nextFolder()
            if (song != null) {
                playSong(song)
            }
        } else {
            val runnable = Runnable {
                nextPendingRunnable = null
                val song = playlistManager.nextSong()
                if (song != null) playSong(song)
            }
            nextPendingRunnable = runnable
            handler.postDelayed(runnable, doubleTapThreshold)
        }
    }

    fun handlePrevious() {
        if (playlistManager.isShuffleOn) {
            val song = playlistManager.shuffleSong()
            if (song != null) playSong(song)
            return
        }

        val pending = prevPendingRunnable
        if (pending != null) {
            handler.removeCallbacks(pending)
            prevPendingRunnable = null
            val song = playlistManager.prevFolder()
            if (song != null) {
                playSong(song)
            }
        } else {
            val runnable = Runnable {
                prevPendingRunnable = null
                val song = playlistManager.prevSong()
                if (song != null) playSong(song)
            }
            prevPendingRunnable = runnable
            handler.postDelayed(runnable, doubleTapThreshold)
        }
    }

    fun handleShuffleButton() {
        playlistManager.toggleShuffle()
        mediaSession.setShuffleMode(if (playlistManager.isShuffleOn) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
        broadcastState()
    }

    fun seekTo(positionMs: Int) {
        val duration = mediaPlayer?.duration ?: 0
        val clamped = positionMs.coerceIn(0, if (duration > 0) duration else 0)
        mediaPlayer?.seekTo(clamped)
        updatePlaybackState(if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
        broadcastState()
    }

    fun playSong(song: Song) {
        mediaPlayer?.let {
            it.stop()
            it.release()
        }
        mediaPlayer = null
        isPlaying = false
        stopProgressUpdates()
        startPlayingSong(song)
    }

    fun playSongAtIndex(index: Int) {
        playlistManager.setCurrentIndex(index)
        val song = playlistManager.getCurrentSong() ?: return
        playSong(song)
    }

    private fun startPlayingSong(song: Song) {
        try {
            requestAudioFocus()
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(applicationContext, song.uri)
                prepare()
                val vol = getAppVolumeFloat()
                setVolume(vol, vol)
                start()
                setOnCompletionListener { onTrackComplete() }
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            }
            isPlaying = true
            startProgressUpdates()
            val metadata = extractMetadata(song)
            previousCoverArt?.recycle()
            previousCoverArt = currentMetadata?.coverArt
            currentMetadata = metadata
            updateMediaSessionMetadata(metadata)
            updateMediaSessionQueue()
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            startForegroundCompat(buildNotification())
            broadcastState()
            overlayToastManager.showSong(metadata)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playing ${song.displayName}", e)
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
                    val song = playlistManager.shuffleSong()
                    if (song != null) playSong(song)
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

    private fun requestAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                    AudioManager.AUDIOFOCUS_GAIN -> play()
                }
            }
            .build()
        audioFocusRequest = focusRequest
        audioManager.requestAudioFocus(focusRequest)
    }

    fun extractMetadata(song: Song): SongMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(applicationContext, song.uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: song.displayName
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val artBytes = retriever.embeddedPicture
            val coverArt = if (artBytes != null) BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size) else null
            SongMetadata(title, artist, album, duration, coverArt)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract metadata for ${song.displayName}", e)
            SongMetadata(song.displayName, "", "", 0L, null)
        } finally {
            retriever.release()
        }
    }

    private fun scaleBitmapForSession(bitmap: android.graphics.Bitmap, maxPx: Int = 256): android.graphics.Bitmap {
        if (bitmap.width <= maxPx && bitmap.height <= maxPx) return bitmap
        val scale = maxPx.toFloat() / maxOf(bitmap.width, bitmap.height)
        return android.graphics.Bitmap.createScaledBitmap(
            bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true
        )
    }

    private fun saveArtToFile(bitmap: android.graphics.Bitmap, filename: String): Uri? {
        return try {
            val dir = java.io.File(cacheDir, "album_art").also { it.mkdirs() }
            val file = java.io.File(dir, filename)
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            Uri.parse("content://com.androsnd.albumart/$filename")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save album art to cache", e)
            null
        }
    }

    private fun extractCoverArt(song: Song): android.graphics.Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(applicationContext, song.uri)
            val bytes = retriever.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun updateMediaSessionMetadata(metadata: SongMetadata) {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, playlistManager.currentIndex.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, metadata.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, metadata.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, metadata.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, metadata.duration)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, metadata.title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, metadata.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, metadata.album)
        if (metadata.coverArt != null) {
            val scaled = scaleBitmapForSession(metadata.coverArt)
            val uri = saveArtToFile(scaled, "current_art.jpg")
            if (uri != null) {
                val uriStr = uri.toString()
                builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, uriStr)
                builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, uriStr)
                builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, uriStr)
            }
            // Fallback bitmap for clients that do not load URIs (e.g. older lock screens)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, scaled)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, scaled)
        }
        mediaSession.setMetadata(builder.build())
    }

    private fun updatePlaybackState(state: Int) {
        val position = mediaPlayer?.currentPosition?.toLong() ?: 0L
        val speed = if (isPlaying) 1f else 0f
        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, position, speed)
            .setActiveQueueItemId(playlistManager.currentIndex.toLong())
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
        val songs = playlistManager.songs
        val curIdx = playlistManager.currentIndex
        val prevIdx = if (songs.size > 1) (curIdx - 1 + songs.size) % songs.size else -1
        val nextIdx = if (songs.size > 1) (curIdx + 1) % songs.size else -1

        // current_art.jpg is written by updateMediaSessionMetadata(); read it as a URI here
        val curArtUri = Uri.parse("content://com.androsnd.albumart/current_art.jpg")
            .takeIf { java.io.File(cacheDir, "album_art/current_art.jpg").exists() }
        val prevArtUri = if (prevIdx >= 0)
            extractCoverArt(songs[prevIdx])?.let { saveArtToFile(scaleBitmapForSession(it), "prev_art.jpg") }
        else null
        val nextArtUri = if (nextIdx >= 0)
            extractCoverArt(songs[nextIdx])?.let { saveArtToFile(scaleBitmapForSession(it), "next_art.jpg") }
        else null

        val queue = songs.mapIndexed { index, song ->
            val artUri = when (index) {
                curIdx  -> curArtUri
                prevIdx -> prevArtUri
                nextIdx -> nextArtUri
                else    -> null
            }
            val desc = MediaDescriptionCompat.Builder()
                .setMediaId(index.toString())
                .setTitle(song.displayName)
                .setMediaUri(song.uri)
                .apply { if (artUri != null) setIconUri(artUri) }
                .build()
            MediaSessionCompat.QueueItem(desc, index.toLong())
        }
        mediaSession.setQueue(queue)
        mediaSession.setQueueTitle(getString(R.string.app_name))
    }

    private fun buildNotification(): Notification {
        val song = playlistManager.getCurrentSong()
        val contentTitle = song?.displayName ?: getString(R.string.app_name)

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_action_pause),
                createServicePendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                getString(R.string.notification_action_play),
                createServicePendingIntent(ACTION_PLAY)
            )
        }

        val mediaStyle = MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        val mainIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText(getString(R.string.app_name))
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(currentMetadata?.coverArt)
            .setContentIntent(mainIntent)
            .addAction(
                android.R.drawable.ic_media_previous, getString(R.string.notification_action_previous),
                createServicePendingIntent(ACTION_PREVIOUS)
            )
            .addAction(playPauseAction)
            .addAction(
                android.R.drawable.ic_media_next, getString(R.string.notification_action_next),
                createServicePendingIntent(ACTION_NEXT)
            )
            .setStyle(mediaStyle)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createServicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, MusicService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        val runnable = object : Runnable {
            override fun run() {
                broadcastState()
                handler.postDelayed(this, 1000)
            }
        }
        progressRunnable = runnable
        handler.post(runnable)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    fun broadcastState() {
        broadcastManager.sendBroadcast(Intent(BROADCAST_STATE_CHANGED))
    }

    fun scanFolderAsync(uri: Uri) {
        mediaPlayer?.let { it.stop(); it.release() }
        mediaPlayer = null
        isPlaying = false
        stopProgressUpdates()

        isScanning = true
        broadcastManager.sendBroadcast(Intent(BROADCAST_SCAN_STARTED))
        scanExecutor.execute {
            try {
                playlistManager.scanFolder(uri)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to scan folder", e)
            } finally {
                handler.post {
                    isScanning = false
                    updateMediaSessionQueue()
                    broadcastManager.sendBroadcast(Intent(BROADCAST_SCAN_COMPLETED))
                    broadcastState()
                }
            }
        }
    }

    fun getPosition(): Int = mediaPlayer?.currentPosition ?: 0
    fun getDuration(): Int = mediaPlayer?.duration ?: 0

    override fun onDestroy() {
        super.onDestroy()
        nextPendingRunnable?.let { handler.removeCallbacks(it) }
        prevPendingRunnable?.let { handler.removeCallbacks(it) }
        stopProgressUpdates()
        mediaPlayer?.release()
        mediaPlayer = null
        currentMetadata?.coverArt?.recycle()
        previousCoverArt?.recycle()
        currentMetadata = null
        previousCoverArt = null
        mediaSession.release()
        overlayToastManager.dismiss()
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        scanExecutor.shutdown()
    }
}
