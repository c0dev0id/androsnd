package de.codevoid.androsnd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.app.NotificationCompat.MediaStyle
import de.codevoid.androsnd.model.Song
import de.codevoid.androsnd.model.SongMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MusicService : Service() {

    companion object {
        private const val TAG = "MusicService"

        const val CHANNEL_ID = "androsnd_channel"
        const val NOTIFICATION_ID = 1

        const val ACTION_PLAY = "de.codevoid.androsnd.PLAY"
        const val ACTION_PAUSE = "de.codevoid.androsnd.PAUSE"
        const val ACTION_STOP = "de.codevoid.androsnd.STOP"
        const val ACTION_NEXT = "de.codevoid.androsnd.NEXT"
        const val ACTION_PREVIOUS = "de.codevoid.androsnd.PREVIOUS"
        const val ACTION_SHUFFLE = "de.codevoid.androsnd.SHUFFLE"

        const val BROADCAST_STATE_CHANGED = "de.codevoid.androsnd.STATE_CHANGED"
        const val BROADCAST_SCAN_STARTED = "de.codevoid.androsnd.SCAN_STARTED"
        const val BROADCAST_SCAN_COMPLETED = "de.codevoid.androsnd.SCAN_COMPLETED"
        const val BROADCAST_METADATA_UPDATED = "de.codevoid.androsnd.METADATA_UPDATED"
        const val BROADCAST_ART_UPDATED = "de.codevoid.androsnd.ART_UPDATED"

        const val EXTRA_METADATA_SONG_INDEX = "song_index"
        const val EXTRA_METADATA_TITLE    = "meta_title"
        const val EXTRA_METADATA_ARTIST   = "meta_artist"
        const val EXTRA_METADATA_ALBUM    = "meta_album"
        const val EXTRA_METADATA_DURATION = "meta_duration"
        const val EXTRA_ART_FOLDER_PATH   = "art_folder_path"

        private const val PREFS_NAME = "androsnd_prefs"
        private const val KEY_APP_VOLUME = "app_volume"
        private const val SKIP_DURATION_MS = 10000

        private val NOTIFICATION_ACTIONS = setOf(
            ACTION_PLAY, ACTION_PAUSE, ACTION_STOP, ACTION_NEXT, ACTION_PREVIOUS, ACTION_SHUFFLE
        )
    }

    inner class MusicBinder : Binder() {
        fun getService(): MusicService = this@MusicService
    }

    private val binder = MusicBinder()
    lateinit var playlistManager: PlaylistManager
        private set
    lateinit var metadataRepository: MetadataRepository
        private set

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    var isPlaying: Boolean = false
        private set
    var isScanning: Boolean = false
        private set
    var currentTextMetadata: SongMetadata? = null
        private set
    private var currentArtBitmap: Bitmap? = null
    private var isDucking = false

    private lateinit var overlayToastManager: OverlayToastManager
    private lateinit var broadcastManager: LocalBroadcastManager

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

    fun setOnOverlayScaleChangedListener(listener: ((Float) -> Unit)?) {
        overlayToastManager.onScaleChanged = listener
    }

    fun showOverlayDemo() {
        overlayToastManager.showDemo()
    }

    fun dismissOverlayDemo() {
        overlayToastManager.dismissDemo()
    }

    fun updateOverlayOpacity(opacity: Int) {
        overlayToastManager.updateOpacity(opacity)
    }

    override fun onCreate() {
        super.onCreate()
        playlistManager = PlaylistManager(this)
        metadataRepository = MetadataRepository(this)
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
                    playlistManager.selectNextQueueSong()
                    updatePlaybackState(if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
                    broadcastState()
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
                            updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                            broadcastState()
                            serviceScope.launch {
                                val meta = withContext(Dispatchers.IO) { metadataRepository.fetchText(song) }
                                currentTextMetadata = meta
                                updateMediaSessionMetadata()
                                broadcastState()
                            }
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
        if (intent?.action == null || intent.action !in NOTIFICATION_ACTIONS) {
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
                requestAudioFocus()
                mediaPlayer?.start()
                applyAppVolume()
                isPlaying = true
                startProgressUpdates()
                updateMediaSessionMetadata()
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
        if (isPlaying) pause() else play()
    }

    private fun stopPlayback() {
        mediaPlayer?.let {
            it.stop()
            it.release()
        }
        mediaPlayer = null
        isPlaying = false
        currentTextMetadata = null
        currentArtBitmap = null
        stopProgressUpdates()
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        broadcastState()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun handleNext() {
        playNextQueueSong()
    }

    private fun playNextQueueSong() {
        val nextIdx = playlistManager.nextQueueIndex
        if (nextIdx < 0) return
        playlistManager.setCurrentIndex(nextIdx)
        val song = playlistManager.getCurrentSong() ?: return
        playSong(song)
    }

    fun handlePrevious() {
        if (playlistManager.isShuffleOn) {
            val song = playlistManager.shuffleSong()
            if (song != null) playSong(song)
            return
        }
        val song = playlistManager.prevSong()
        if (song != null) playSong(song)
    }

    fun handleShuffleButton() {
        playlistManager.toggleShuffle()
        mediaSession.setShuffleMode(if (playlistManager.isShuffleOn) PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE)
        playlistManager.selectNextQueueSong()
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
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            startForegroundCompat(buildNotification())
            broadcastState()
            playlistManager.selectNextQueueSong()
            serviceScope.launch {
                val meta = withContext(Dispatchers.IO) { metadataRepository.fetchText(song) }
                val art  = withContext(Dispatchers.IO) { metadataRepository.loadCurrentArt(song) }
                currentTextMetadata = meta
                currentArtBitmap    = art
                updateMediaSessionMetadata()
                updateNotification()
                overlayToastManager.showSong(meta, art)
                broadcastState()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playing ${song.displayName}", e)
        }
    }

    private fun onTrackComplete() {
        playNextQueueSong()
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
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        isDucking = true
                        val duckedVol = getAppVolumeFloat() * 0.2f
                        mediaPlayer?.setVolume(duckedVol, duckedVol)
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (isDucking) {
                            isDucking = false
                            applyAppVolume()
                        } else {
                            play()
                        }
                    }
                }
            }
            .build()
        audioFocusRequest = focusRequest
        audioManager.requestAudioFocus(focusRequest)
    }

    private fun updateMediaSessionMetadata() {
        val song = playlistManager.getCurrentSong() ?: return
        val meta = currentTextMetadata
        val title    = meta?.title    ?: song.displayName
        val artist   = meta?.artist   ?: ""
        val album    = meta?.album    ?: ""
        val duration = meta?.duration ?: 0L

        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, playlistManager.currentIndex.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, playlistManager.songs.size.toLong())
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, album)
        val art = currentArtBitmap
        if (art != null) {
            val uriStr = "content://de.codevoid.androsnd.albumart/current_art.jpg"
            builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, uriStr)
            builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, uriStr)
            builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, uriStr)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
            builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
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
                PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE
            )
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun buildNotification(): Notification {
        val song = playlistManager.getCurrentSong()
        val contentTitle = currentTextMetadata?.title ?: song?.displayName ?: getString(R.string.app_name)

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
            .setLargeIcon(currentArtBitmap)
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
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, buildNotification())
        }
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
        metadataRepository.cancelEnrichment()

        isScanning = true
        broadcastManager.sendBroadcast(Intent(BROADCAST_SCAN_STARTED))

        serviceScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { playlistManager.scanFolder(uri) }
                    .onFailure { Log.e(TAG, "Scan failed", it) }
            }
            isScanning = false
            playlistManager.selectNextQueueSong()
            broadcastManager.sendBroadcast(Intent(BROADCAST_SCAN_COMPLETED))
            broadcastState()

            metadataRepository.startEnrichment(
                scope         = serviceScope,
                songs         = playlistManager.songs,
                foldersByPath = playlistManager.foldersByPath,
                currentIdx    = playlistManager.currentIndex,
                onTextReady   = { idx, meta ->
                    if (idx == playlistManager.currentIndex && currentTextMetadata == null) {
                        currentTextMetadata = meta
                        broadcastState()
                    }
                    broadcastManager.sendBroadcast(Intent(BROADCAST_METADATA_UPDATED).apply {
                        putExtra(EXTRA_METADATA_SONG_INDEX, idx)
                        putExtra(EXTRA_METADATA_TITLE,    meta.title)
                        putExtra(EXTRA_METADATA_ARTIST,   meta.artist)
                        putExtra(EXTRA_METADATA_ALBUM,    meta.album)
                        putExtra(EXTRA_METADATA_DURATION, meta.duration)
                    })
                },
                onArtReady = { folderPath ->
                    if (playlistManager.getCurrentSong()?.folderPath == folderPath) {
                        serviceScope.launch {
                            currentArtBitmap = withContext(Dispatchers.IO) {
                                BitmapFactory.decodeFile(
                                    metadataRepository.artFileForFolder(folderPath).absolutePath)
                            }
                            updateNotification()
                            updateMediaSessionMetadata()
                        }
                    }
                    broadcastManager.sendBroadcast(Intent(BROADCAST_ART_UPDATED).apply {
                        putExtra(EXTRA_ART_FOLDER_PATH, folderPath)
                    })
                }
            )
        }
    }

    fun getPosition(): Int = mediaPlayer?.currentPosition ?: 0
    fun getDuration(): Int = mediaPlayer?.duration ?: 0

    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdates()
        mediaPlayer?.release()
        mediaPlayer = null
        currentTextMetadata = null
        currentArtBitmap = null
        mediaSession.release()
        overlayToastManager.dismiss()
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        serviceScope.cancel()
        metadataRepository.close()
    }
}
