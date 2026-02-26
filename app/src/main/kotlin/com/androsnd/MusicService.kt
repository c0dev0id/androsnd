package com.androsnd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.app.NotificationCompat.MediaStyle
import com.androsnd.model.Song

class MusicService : Service() {

    companion object {
        const val CHANNEL_ID = "androsnd_channel"
        const val NOTIFICATION_ID = 1

        const val ACTION_PLAY = "com.androsnd.PLAY"
        const val ACTION_PAUSE = "com.androsnd.PAUSE"
        const val ACTION_STOP = "com.androsnd.STOP"
        const val ACTION_NEXT = "com.androsnd.NEXT"
        const val ACTION_PREVIOUS = "com.androsnd.PREVIOUS"
        const val ACTION_SHUFFLE = "com.androsnd.SHUFFLE"

        const val BROADCAST_STATE_CHANGED = "com.androsnd.STATE_CHANGED"
        const val EXTRA_IS_PLAYING = "is_playing"
        const val EXTRA_SONG_INDEX = "song_index"
        const val EXTRA_IS_SHUFFLE = "is_shuffle"
        const val EXTRA_POSITION = "position"
        const val EXTRA_DURATION = "duration"

        private const val DOUBLE_TAP_THRESHOLD = 1000L
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

    var isPlaying: Boolean = false
        private set

    private var lastNextTime = 0L
    private var lastPrevTime = 0L
    private var lastStopTime = 0L

    private lateinit var overlayToastManager: OverlayToastManager
    private lateinit var broadcastManager: LocalBroadcastManager

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
            playlistManager.scanFolder(savedUri)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Music Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Music playback controls"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "AndrosndSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { play() }
                override fun onPause() { pause() }
                override fun onStop() { handleStop() }
                override fun onSkipToNext() { handleNext() }
                override fun onSkipToPrevious() { handlePrevious() }
            })
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
                isPlaying = true
                startProgressUpdates()
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                startForeground(NOTIFICATION_ID, buildNotification())
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
        val now = System.currentTimeMillis()
        if (now - lastStopTime < DOUBLE_TAP_THRESHOLD) {
            lastStopTime = 0L
            val shuffleOn = playlistManager.toggleShuffle()
            val msg = if (shuffleOn) getString(R.string.shuffle_on) else getString(R.string.shuffle_off)
            overlayToastManager.showMessage(msg)
            broadcastState()
        } else {
            lastStopTime = now
            stopPlayback()
        }
    }

    private fun stopPlayback() {
        mediaPlayer?.let {
            it.stop()
            it.release()
        }
        mediaPlayer = null
        isPlaying = false
        stopProgressUpdates()
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        broadcastState()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun handleNext() {
        val now = System.currentTimeMillis()
        if (!playlistManager.isShuffleOn && now - lastNextTime < DOUBLE_TAP_THRESHOLD) {
            lastNextTime = 0L
            val song = playlistManager.nextFolder()
            if (song != null) {
                overlayToastManager.showMessage(getString(R.string.next_folder))
                playSong(song)
            }
        } else {
            lastNextTime = now
            val song = if (playlistManager.isShuffleOn) playlistManager.shuffleSong()
                       else playlistManager.nextSong()
            if (song != null) playSong(song)
        }
    }

    fun handlePrevious() {
        val now = System.currentTimeMillis()
        if (!playlistManager.isShuffleOn && now - lastPrevTime < DOUBLE_TAP_THRESHOLD) {
            lastPrevTime = 0L
            val song = playlistManager.prevFolder()
            if (song != null) {
                overlayToastManager.showMessage(getString(R.string.prev_folder))
                playSong(song)
            }
        } else {
            lastPrevTime = now
            val song = if (playlistManager.isShuffleOn) playlistManager.shuffleSong()
                       else playlistManager.prevSong()
            if (song != null) playSong(song)
        }
    }

    fun handleShuffleButton() {
        val shuffleOn = playlistManager.toggleShuffle()
        val msg = if (shuffleOn) getString(R.string.shuffle_on) else getString(R.string.shuffle_off)
        overlayToastManager.showMessage(msg)
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
                start()
                setOnCompletionListener { onTrackComplete() }
                setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            }
            isPlaying = true
            startProgressUpdates()
            updateMediaSessionMetadata(song)
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            startForeground(NOTIFICATION_ID, buildNotification())
            broadcastState()
            overlayToastManager.showSong(song, getCoverArt(song))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun onTrackComplete() {
        val song = if (playlistManager.isShuffleOn) playlistManager.shuffleSong()
                   else playlistManager.nextSong()
        if (song != null) playSong(song)
    }

    private fun requestAudioFocus() {
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

    private fun updateMediaSessionMetadata(song: Song) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(applicationContext, song.uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: song.displayName
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L

            val artBytes = retriever.embeddedPicture
            val artBitmap = if (artBytes != null) BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size) else null

            val metadata = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
                .apply { if (artBitmap != null) putBitmap(MediaMetadataCompat.METADATA_KEY_ART, artBitmap) }
                .build()
            mediaSession.setMetadata(metadata)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            retriever.release()
        }
    }

    fun getCoverArt(song: Song): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(applicationContext, song.uri)
            val artBytes = retriever.embeddedPicture
            if (artBytes != null) BitmapFactory.decodeByteArray(artBytes, 0, artBytes.size) else null
        } catch (e: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    fun getSongMetadata(song: Song): Triple<String, String, String> {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(applicationContext, song.uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: song.displayName
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            Triple(title, artist, album)
        } catch (e: Exception) {
            Triple(song.displayName, "", "")
        } finally {
            retriever.release()
        }
    }

    private fun updatePlaybackState(state: Int) {
        val position = mediaPlayer?.currentPosition?.toLong() ?: 0L
        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, position, 1f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_STOP or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
            )
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun buildNotification(): Notification {
        val song = playlistManager.getCurrentSong()
        val contentTitle = song?.displayName ?: getString(R.string.app_name)

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                "Pause",
                createServicePendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                "Play",
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
            .setContentIntent(mainIntent)
            .addAction(
                android.R.drawable.ic_media_previous, "Previous",
                createServicePendingIntent(ACTION_PREVIOUS)
            )
            .addAction(playPauseAction)
            .addAction(
                android.R.drawable.ic_media_next, "Next",
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
        progressRunnable = object : Runnable {
            override fun run() {
                broadcastState()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(progressRunnable!!)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    fun broadcastState() {
        val intent = Intent(BROADCAST_STATE_CHANGED).apply {
            putExtra(EXTRA_IS_PLAYING, isPlaying)
            putExtra(EXTRA_SONG_INDEX, playlistManager.currentIndex)
            putExtra(EXTRA_IS_SHUFFLE, playlistManager.isShuffleOn)
            putExtra(EXTRA_POSITION, mediaPlayer?.currentPosition ?: 0)
            putExtra(EXTRA_DURATION, mediaPlayer?.duration ?: 0)
        }
        broadcastManager.sendBroadcast(intent)
    }

    fun getPosition(): Int = mediaPlayer?.currentPosition ?: 0
    fun getDuration(): Int = mediaPlayer?.duration ?: 0

    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdates()
        mediaPlayer?.release()
        mediaPlayer = null
        mediaSession.release()
        overlayToastManager.dismiss()
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }
}
