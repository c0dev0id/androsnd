package com.androsnd.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.androsnd.MainActivity
import com.androsnd.PlaylistManager
import com.androsnd.R
import com.androsnd.OverlayToastManager
import com.androsnd.db.AppDatabase
import com.androsnd.scanner.LibraryScanner
import java.util.concurrent.Executors

class PlayerService : MediaBrowserServiceCompat() {

    companion object {
        private const val TAG = "PlayerService"

        const val CHANNEL_ID = "androsnd_channel"
        const val NOTIFICATION_ID = 1

        const val ACTION_PLAY = "com.androsnd.PLAY"
        const val ACTION_PAUSE = "com.androsnd.PAUSE"
        const val ACTION_STOP = "com.androsnd.STOP"
        const val ACTION_NEXT = "com.androsnd.NEXT"
        const val ACTION_PREVIOUS = "com.androsnd.PREVIOUS"

        const val CUSTOM_ACTION_SCAN_FOLDER = "com.androsnd.SCAN_FOLDER"
        const val EXTRA_FOLDER_URI = "folder_uri"

        const val EVENT_SCAN_STARTED = "com.androsnd.SCAN_STARTED"
        const val EVENT_SCAN_COMPLETED = "com.androsnd.SCAN_COMPLETED"

        private const val DOUBLE_TAP_THRESHOLD = 500L
        private const val SKIP_DURATION_MS = 10000

        private val MUSIC_AUDIO_ATTRIBUTES = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
    }

    private lateinit var playerThread: HandlerThread
    private lateinit var playerHandler: Handler
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var db: AppDatabase
    lateinit var playlistManager: PlaylistManager
        private set

    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val scanExecutor = Executors.newSingleThreadExecutor()
    private var isPlayerPrepared = false
    private var isPlaying = false

    private var nextPendingRunnable: Runnable? = null
    private var prevPendingRunnable: Runnable? = null

    private lateinit var overlayToastManager: OverlayToastManager
    private lateinit var libraryScanner: LibraryScanner

    private var progressRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()

        playerThread = PlayerThread()
        playerThread.start()
        playerHandler = Handler(playerThread.looper)

        db = AppDatabase.getInstance(this)
        playlistManager = PlaylistManager(this, db)
        libraryScanner = LibraryScanner(this, db)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        overlayToastManager = OverlayToastManager(this)

        createNotificationChannel()
        initMediaSession()

        // Load previously scanned library from DB on the player thread
        playerHandler.post {
            playlistManager.loadFromDatabase()
            mainHandler.post { updateMediaSessionQueue() }
        }

        // Re-scan saved folder if DB is empty
        val savedUri = playlistManager.loadSavedFolderUri()
        if (savedUri != null) {
            playerHandler.post {
                if (playlistManager.songs.isEmpty()) {
                    mainHandler.post { scanFolderAsync(savedUri) }
                }
            }
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
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, "AndrosndSession")
        mediaSession.setCallback(object : MediaSessionCompat.Callback() {
            override fun onPlay() { play() }
            override fun onPause() { pause() }
            override fun onStop() { stopPlayback() }
            override fun onSkipToNext() { handleNext() }
            override fun onSkipToPrevious() { handlePrevious() }
            override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
            override fun onFastForward() { seekTo(getPosition() + SKIP_DURATION_MS) }
            override fun onRewind() { seekTo(maxOf(0, getPosition() - SKIP_DURATION_MS)) }
            override fun onSkipToQueueItem(id: Long) {
                val idx = playlistManager.songs.indexOfFirst { it.id == id }
                if (idx >= 0) playSongAtIndex(idx)
            }
            override fun onSetShuffleMode(shuffleMode: Int) {
                val shouldShuffle = shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE
                if (playlistManager.isShuffleOn != shouldShuffle) playlistManager.toggleShuffle()
                mediaSession.setShuffleMode(shuffleMode)
                updatePlaybackState(if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
            }
            override fun onSetRepeatMode(repeatMode: Int) {
                playlistManager.setRepeatMode(repeatMode)
                mediaSession.setRepeatMode(repeatMode)
                updatePlaybackState(if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
            }
            override fun onPlayFromUri(uri: Uri?, extras: Bundle?) {
                uri ?: return
                playSongAtUri(uri)
            }
            override fun onCustomAction(action: String?, extras: Bundle?) {
                if (action == CUSTOM_ACTION_SCAN_FOLDER) {
                    val uriString = extras?.getString(EXTRA_FOLDER_URI) ?: return
                    val uri = Uri.parse(uriString)
                    mainHandler.post { scanFolderAsync(uri) }
                }
            }
            override fun onPrepare() {
                if (playlistManager.songs.isEmpty()) return
                val entity = playlistManager.getCurrentSong() ?: return
                if (mediaPlayer == null) {
                    try {
                        val mp = MediaPlayer()
                        mp.setAudioAttributes(MUSIC_AUDIO_ATTRIBUTES)
                        mp.setDataSource(applicationContext, Uri.parse(entity.uri))
                        mp.setOnErrorListener { _, what, extra ->
                            Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                            true
                        }
                        mp.setOnCompletionListener { player ->
                            if (mediaPlayer == player) onTrackComplete()
                        }
                        mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
                        mp.prepare()
                        isPlayerPrepared = true
                        mediaPlayer = mp
                        updateMediaSessionMetadata(entity)
                        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to prepare ${entity.displayName}", e)
                    }
                }
            }
        }, playerHandler)
        mediaSession.isActive = true
        sessionToken = mediaSession.sessionToken
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == null || intent.action !in listOf(
                ACTION_PLAY, ACTION_PAUSE, ACTION_STOP, ACTION_NEXT, ACTION_PREVIOUS
            )
        ) {
            startForegroundCompat(buildNotification())
        }
        when (intent?.action) {
            ACTION_PLAY -> playerHandler.post { play() }
            ACTION_PAUSE -> playerHandler.post { pause() }
            ACTION_STOP -> playerHandler.post { stopPlayback() }
            ACTION_NEXT -> playerHandler.post { handleNext() }
            ACTION_PREVIOUS -> playerHandler.post { handlePrevious() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?) = super.onBind(intent)

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        return BrowserRoot(getString(R.string.app_name), null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.detach()
        scanExecutor.execute {
            val songs = db.songDao().getAllOrdered()
            val folders = db.folderDao().getAllOrdered()
            val items = mutableListOf<MediaBrowserCompat.MediaItem>()
            for (folder in folders) {
                val folderDesc = MediaDescriptionCompat.Builder()
                    .setMediaId("folder_${folder.id}")
                    .setTitle(folder.name)
                    .build()
                items.add(MediaBrowserCompat.MediaItem(folderDesc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE))
                val folderSongs = songs.filter { it.folderId == folder.id }
                for (song in folderSongs) {
                    val desc = MediaDescriptionCompat.Builder()
                        .setMediaId(song.id.toString())
                        .setTitle(song.title)
                        .setSubtitle(song.artist)
                        .setMediaUri(Uri.parse(song.uri))
                        .build()
                    items.add(MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE))
                }
            }
            result.sendResult(items)
        }
    }

    // ----- Playback methods (all run on player thread) -----

    private fun play() {
        if (playlistManager.songs.isEmpty()) return
        if (mediaPlayer == null) {
            val entity = playlistManager.getCurrentSong() ?: return
            startPlayingEntity(entity)
        } else if (!isPlaying) {
            mediaPlayer?.start()
            isPlaying = true
            startProgressUpdates()
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            startForegroundCompat(buildNotification())
        }
    }

    private fun pause() {
        mediaPlayer?.let {
            if (isPlaying) {
                it.pause()
                isPlaying = false
                stopProgressUpdates()
                updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                updateNotification()
            }
        }
    }

    private fun stopPlayback() {
        val mp = mediaPlayer
        mediaPlayer = null
        mp?.release()
        isPlaying = false
        isPlayerPrepared = false
        stopProgressUpdates()
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        mainHandler.post { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun handleNext() {
        if (playlistManager.isShuffleOn) {
            val entity = playlistManager.shuffleSong()
            if (entity != null) startPlayingEntity(entity)
            return
        }
        val pending = nextPendingRunnable
        if (pending != null) {
            playerHandler.removeCallbacks(pending)
            nextPendingRunnable = null
            val entity = playlistManager.nextFolder()
            if (entity != null) startPlayingEntity(entity)
        } else {
            val runnable = Runnable {
                nextPendingRunnable = null
                val entity = playlistManager.nextSong()
                if (entity != null) startPlayingEntity(entity)
            }
            nextPendingRunnable = runnable
            playerHandler.postDelayed(runnable, DOUBLE_TAP_THRESHOLD)
        }
    }

    private fun handlePrevious() {
        if (playlistManager.isShuffleOn) {
            val entity = playlistManager.shuffleSong()
            if (entity != null) startPlayingEntity(entity)
            return
        }
        val pending = prevPendingRunnable
        if (pending != null) {
            playerHandler.removeCallbacks(pending)
            prevPendingRunnable = null
            val entity = playlistManager.prevFolder()
            if (entity != null) startPlayingEntity(entity)
        } else {
            val runnable = Runnable {
                prevPendingRunnable = null
                val entity = playlistManager.prevSong()
                if (entity != null) startPlayingEntity(entity)
            }
            prevPendingRunnable = runnable
            playerHandler.postDelayed(runnable, DOUBLE_TAP_THRESHOLD)
        }
    }

    private fun seekTo(positionMs: Int) {
        if (!isPlayerPrepared) return
        val duration = mediaPlayer?.duration ?: 0
        val clamped = positionMs.coerceIn(0, if (duration > 0) duration else 0)
        mediaPlayer?.seekTo(clamped)
        updatePlaybackState(if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
    }

    private fun playSongAtIndex(index: Int) {
        playlistManager.setCurrentIndex(index)
        val entity = playlistManager.getCurrentSong() ?: return
        startPlayingEntity(entity)
    }

    private fun playSongAtUri(uri: Uri) {
        val idx = playlistManager.songs.indexOfFirst { it.uri == uri.toString() }
        if (idx >= 0) {
            playSongAtIndex(idx)
        } else {
            val entity = com.androsnd.db.entity.SongEntity(
                uri = uri.toString(),
                displayName = uri.lastPathSegment ?: "Unknown",
                title = uri.lastPathSegment ?: "Unknown",
                artist = "",
                album = "",
                duration = 0L,
                folderId = -1,
                sortOrder = 0,
                coverArtPath = null
            )
            startPlayingEntity(entity)
        }
    }

    private fun startPlayingEntity(entity: com.androsnd.db.entity.SongEntity) {
        val oldPlayer = mediaPlayer
        mediaPlayer = null
        isPlaying = false
        isPlayerPrepared = false
        stopProgressUpdates()
        oldPlayer?.release()

        try {
            requestAudioFocus()
            val mp = MediaPlayer()
            mp.setAudioAttributes(MUSIC_AUDIO_ATTRIBUTES)
            mp.setDataSource(applicationContext, Uri.parse(entity.uri))
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra for ${entity.displayName}")
                true
            }
            mp.setOnPreparedListener { player ->
                if (mediaPlayer == player) {
                    isPlayerPrepared = true
                    player.start()
                    isPlaying = true
                    startProgressUpdates()
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    startForegroundCompat(buildNotification())
                }
            }
            mp.setOnCompletionListener { player ->
                if (mediaPlayer == player) onTrackComplete()
            }
            mp.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            mp.prepareAsync()
            mediaPlayer = mp
            updateMediaSessionMetadata(entity)
            mainHandler.post { overlayToastManager.showSong(entity.title, entity.artist, entity.album, entity.coverArtPath) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playing ${entity.displayName}", e)
        }
    }

    private fun onTrackComplete() {
        when (playlistManager.repeatMode) {
            PlaybackStateCompat.REPEAT_MODE_ONE -> {
                val entity = playlistManager.getCurrentSong()
                if (entity != null) startPlayingEntity(entity)
            }
            PlaybackStateCompat.REPEAT_MODE_NONE -> {
                if (playlistManager.isShuffleOn) {
                    val entity = playlistManager.shuffleSong()
                    if (entity != null) startPlayingEntity(entity)
                } else {
                    val isLast = playlistManager.currentIndex >= playlistManager.songs.size - 1
                    if (!isLast) {
                        val entity = playlistManager.nextSong()
                        if (entity != null) startPlayingEntity(entity)
                    } else {
                        stopPlayback()
                    }
                }
            }
            else -> {
                val entity = if (playlistManager.isShuffleOn) playlistManager.shuffleSong()
                             else playlistManager.nextSong()
                if (entity != null) startPlayingEntity(entity)
            }
        }
    }

    private fun requestAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(MUSIC_AUDIO_ATTRIBUTES)
            .setOnAudioFocusChangeListener { focusChange ->
                playerHandler.post {
                    when (focusChange) {
                        AudioManager.AUDIOFOCUS_LOSS -> pause()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> pause()
                        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mediaPlayer?.setVolume(0.3f, 0.3f)
                        AudioManager.AUDIOFOCUS_GAIN -> {
                            mediaPlayer?.setVolume(1f, 1f)
                            if (mediaPlayer != null && isPlayerPrepared && !isPlaying) play()
                        }
                    }
                }
            }
            .build()
        audioFocusRequest = focusRequest
        audioManager.requestAudioFocus(focusRequest)
    }

    private fun updateMediaSessionMetadata(entity: com.androsnd.db.entity.SongEntity) {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, entity.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, entity.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, entity.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, entity.duration)
        if (entity.coverArtPath != null) {
            builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, "file://${entity.coverArtPath}")
        }
        mediaSession.setMetadata(builder.build())
    }

    private fun updatePlaybackState(state: Int) {
        val position = mediaPlayer?.currentPosition?.toLong() ?: 0L
        val speed = if (isPlaying) 1f else 0f
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
        val queue = playlistManager.songs.map { entity ->
            val description = MediaDescriptionCompat.Builder()
                .setTitle(entity.title)
                .setMediaUri(Uri.parse(entity.uri))
                .build()
            MediaSessionCompat.QueueItem(description, entity.id)
        }
        mediaSession.setQueue(queue)
    }

    private fun buildNotification(): Notification {
        val entity = playlistManager.getCurrentSong()
        val contentTitle = entity?.title ?: getString(R.string.app_name)
        val contentText = entity?.artist?.takeIf { it.isNotEmpty() } ?: getString(R.string.app_name)

        val coverBitmap = entity?.coverArtPath?.let { path ->
            try { BitmapFactory.decodeFile(path) } catch (e: Exception) { null }
        }

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause, getString(R.string.notification_action_pause),
                createServicePendingIntent(ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play, getString(R.string.notification_action_play),
                createServicePendingIntent(ACTION_PLAY)
            )
        }

        val mediaStyle = MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        val mainIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setLargeIcon(coverBitmap)
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
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun createServicePendingIntent(action: String): PendingIntent {
        val intent = Intent(this, PlayerService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressRunnable = object : Runnable {
            override fun run() {
                updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                playerHandler.postDelayed(this, 1000)
            }
        }
        playerHandler.post(progressRunnable!!)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { playerHandler.removeCallbacks(it) }
        progressRunnable = null
    }

    fun scanFolderAsync(uri: Uri) {
        // Release any active player
        playerHandler.post {
            val mp = mediaPlayer
            mediaPlayer = null
            isPlaying = false
            isPlayerPrepared = false
            stopProgressUpdates()
            mp?.release()
            updatePlaybackState(PlaybackStateCompat.STATE_NONE)
        }

        mediaSession.sendSessionEvent(EVENT_SCAN_STARTED, null)
        playlistManager.saveFolderUri(uri)

        scanExecutor.execute {
            try {
                libraryScanner.scan(uri)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to scan folder", e)
            } finally {
                playerHandler.post {
                    playlistManager.loadFromDatabase()
                    updateMediaSessionQueue()
                    notifyChildrenChanged(getString(R.string.app_name))
                    mediaSession.sendSessionEvent(EVENT_SCAN_COMPLETED, null)
                }
            }
        }
    }

    private fun getPosition(): Int = mediaPlayer?.currentPosition ?: 0

    override fun onDestroy() {
        super.onDestroy()
        nextPendingRunnable?.let { playerHandler.removeCallbacks(it) }
        prevPendingRunnable?.let { playerHandler.removeCallbacks(it) }
        stopProgressUpdates()
        mediaPlayer?.release()
        mediaPlayer = null
        mediaSession.release()
        overlayToastManager.dismiss()
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        playerThread.quit()
        scanExecutor.shutdown()
    }
}
