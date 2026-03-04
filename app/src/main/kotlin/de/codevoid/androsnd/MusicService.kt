package de.codevoid.androsnd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.app.NotificationCompat.MediaStyle
import de.codevoid.androsnd.model.Song
import de.codevoid.androsnd.model.SongMetadata

class MusicService : MediaBrowserServiceCompat() {

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

        private const val PREFS_NAME = "androsnd_prefs"
        private const val KEY_APP_VOLUME = "app_volume"
        private const val SKIP_DURATION_MS = 10000

        private const val MEDIA_ROOT_ID = "androsnd_root"
        private const val MEDIA_FOLDER_PREFIX = "folder_"
        private const val MEDIA_SONG_PREFIX = "song_"
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

    private var artVersion = 0L

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
                    try {
                        updateMediaSessionQueue()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update media session queue", e)
                    }
                    updatePlaybackState(if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
                    broadcastState()
                }
                override fun onSkipToQueueItem(id: Long) { playSongAtIndex(id.toInt()) }
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    val index = mediaId
                        ?.removePrefix(MEDIA_SONG_PREFIX)
                        ?.toIntOrNull()
                        ?: return
                    playSongAtIndex(index)
                }
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
        setSessionToken(mediaSession.sessionToken)
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
        return BrowserRoot(MEDIA_ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        when {
            parentId == MEDIA_ROOT_ID -> {
                val folders = playlistManager.folders
                if (folders.isEmpty()) {
                    // No folder configured yet — return flat song list (or empty if none loaded)
                    val items = playlistManager.songs.mapIndexed { index, song ->
                        val desc = MediaDescriptionCompat.Builder()
                            .setMediaId("$MEDIA_SONG_PREFIX$index")
                            .setTitle(song.displayName)
                            .setSubtitle(song.folderName)
                            .setMediaUri(song.uri)
                            .build()
                        MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
                    }.toMutableList()
                    result.sendResult(items)
                } else {
                    val items = folders.mapIndexed { index, folder ->
                        val desc = MediaDescriptionCompat.Builder()
                            .setMediaId("$MEDIA_FOLDER_PREFIX$index")
                            .setTitle(folder.name)
                            .setSubtitle("${folder.songs.size} tracks")
                            .build()
                        MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
                    }.toMutableList()
                    result.sendResult(items)
                }
            }
            parentId.startsWith(MEDIA_FOLDER_PREFIX) -> {
                val folderIndex = parentId.removePrefix(MEDIA_FOLDER_PREFIX).toIntOrNull()
                val folder = folderIndex?.let { playlistManager.folders.getOrNull(it) }
                if (folder == null) {
                    result.sendResult(mutableListOf())
                    return
                }
                val items = folder.songs.map { songIndex ->
                    val song = playlistManager.songs[songIndex]
                    val desc = MediaDescriptionCompat.Builder()
                        .setMediaId("$MEDIA_SONG_PREFIX$songIndex")
                        .setTitle(song.displayName)
                        .setMediaUri(song.uri)
                        .build()
                    MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
                }.toMutableList()
                result.sendResult(items)
            }
            else -> result.sendResult(mutableListOf())
        }
    }

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
                currentMetadata?.let { updateMediaSessionMetadata(it) }
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
        try {
            updateMediaSessionQueue()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update media session queue", e)
        }
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
            artVersion++
            val metadata = extractMetadata(song)
            previousCoverArt?.recycle()
            previousCoverArt = currentMetadata?.coverArt
            currentMetadata = metadata
            updateMediaSessionMetadata(metadata)
            playlistManager.selectNextQueueSong()
            try {
                updateMediaSessionQueue()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update media session queue", e)
            }
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
            startForegroundCompat(buildNotification())
            broadcastState()
            overlayToastManager.showSong(metadata)
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
            val coverArt = retriever.embeddedPicture?.let { decodeBitmapWithSampling(it) }
            SongMetadata(title, artist, album, duration, coverArt)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract metadata for ${song.displayName}", e)
            SongMetadata(song.displayName, "", "", 0L, null)
        } finally {
            retriever.release()
        }
    }

    private fun decodeBitmapWithSampling(bytes: ByteArray, maxPx: Int = 512): android.graphics.Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        opts.inSampleSize = maxOf(1, maxOf(opts.outWidth, opts.outHeight) / maxPx)
        opts.inJustDecodeBounds = false
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun scaleBitmapForSession(bitmap: android.graphics.Bitmap, maxPx: Int = 256): android.graphics.Bitmap {
        if (bitmap.width <= maxPx && bitmap.height <= maxPx) return bitmap.copy(bitmap.config ?: android.graphics.Bitmap.Config.ARGB_8888, false)
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
            val uri = Uri.parse("content://de.codevoid.androsnd.albumart/$filename?v=$artVersion")
            contentResolver.notifyChange(uri, null)
            uri
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save album art to cache", e)
            null
        }
    }

    private fun updateMediaSessionMetadata(metadata: SongMetadata) {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, playlistManager.currentIndex.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, metadata.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, metadata.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, metadata.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, metadata.duration)
            .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, playlistManager.songs.size.toLong())
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
                PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or
                PlaybackStateCompat.ACTION_PLAY_FROM_URI
            )
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private data class NeighborInfo(
        val title: String,
        val artist: String,
        val album: String,
        val artUri: Uri?
    )

    private fun extractNeighborInfo(song: Song, artFilename: String): NeighborInfo {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(applicationContext, song.uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: song.displayName
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            val artUri = retriever.embeddedPicture?.let { bytes ->
                decodeBitmapWithSampling(bytes)?.let { original ->
                    val scaled = scaleBitmapForSession(original)
                    val uri = saveArtToFile(scaled, artFilename)
                    original.recycle()
                    scaled.recycle()
                    uri
                }
            }
            NeighborInfo(title, artist, album, artUri)
        } catch (e: Exception) {
            NeighborInfo(song.displayName, "", "", null)
        } finally {
            retriever.release()
        }
    }

    private fun updateMediaSessionQueue() {
        val songs = playlistManager.songs
        if (songs.isEmpty()) return
        val curIdx = playlistManager.currentIndex
        val nextIdx = playlistManager.nextQueueIndex
        val curSong = songs.getOrNull(curIdx) ?: return

        val curArtUri = Uri.parse("content://de.codevoid.androsnd.albumart/current_art.jpg?v=$artVersion")
            .takeIf { java.io.File(cacheDir, "album_art/current_art.jpg").exists() }
        val curDesc = MediaDescriptionCompat.Builder()
            .setMediaId(curIdx.toString())
            .setTitle(currentMetadata?.title ?: curSong.displayName)
            .setSubtitle(currentMetadata?.artist ?: "")
            .setDescription(currentMetadata?.album ?: "")
            .setMediaUri(curSong.uri)
            .apply { if (curArtUri != null) setIconUri(curArtUri) }
            .build()

        val queue = mutableListOf(MediaSessionCompat.QueueItem(curDesc, curIdx.toLong()))

        val nextSong = if (nextIdx >= 0) songs.getOrNull(nextIdx) else null
        if (nextSong != null) {
            val nextInfo = extractNeighborInfo(nextSong, "next_art.jpg")
            val nextDesc = MediaDescriptionCompat.Builder()
                .setMediaId(nextIdx.toString())
                .setTitle(nextInfo.title)
                .setSubtitle(nextInfo.artist)
                .setDescription(nextInfo.album)
                .setMediaUri(nextSong.uri)
                .apply { if (nextInfo.artUri != null) setIconUri(nextInfo.artUri) }
                .build()
            queue.add(MediaSessionCompat.QueueItem(nextDesc, nextIdx.toLong()))
        }

        mediaSession.setQueue(queue)
        mediaSession.setQueueTitle(getString(R.string.app_name))
    }

    private fun buildNotification(): Notification {
        val song = playlistManager.getCurrentSong()
        val contentTitle = currentMetadata?.title ?: song?.displayName ?: getString(R.string.app_name)

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
                    playlistManager.selectNextQueueSong()
                    try {
                        updateMediaSessionQueue()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update media session queue", e)
                    }
                    notifyChildrenChanged("androsnd_root")
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
