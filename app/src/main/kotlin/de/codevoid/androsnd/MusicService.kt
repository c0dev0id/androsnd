package de.codevoid.androsnd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        const val BROADCAST_METADATA_UPDATED = "de.codevoid.androsnd.METADATA_UPDATED"
        const val BROADCAST_ART_UPDATED = "de.codevoid.androsnd.ART_UPDATED"
        const val BROADCAST_ENRICHMENT_COMPLETE = "de.codevoid.androsnd.ENRICHMENT_COMPLETE"

        const val EXTRA_METADATA_SONG_INDEX = "song_index"
        const val EXTRA_METADATA_TITLE    = "meta_title"
        const val EXTRA_METADATA_ARTIST   = "meta_artist"
        const val EXTRA_METADATA_ALBUM    = "meta_album"
        const val EXTRA_METADATA_DURATION = "meta_duration"
        const val EXTRA_ART_FOLDER_PATH   = "art_folder_path"

        private const val PREFS_NAME = "androsnd_prefs"
        private const val KEY_APP_VOLUME = "app_volume"
        private const val SKIP_DURATION_MS = 10000

        private const val MEDIA_ROOT_ID = "androsnd_root"
        private const val MEDIA_FOLDER_PREFIX = "folder_"
        private const val MEDIA_SONG_PREFIX = "song_"

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
    var isPreparing: Boolean = false
        private set
    var isScanning: Boolean = false
        private set
    var currentTextMetadata: SongMetadata? = null
        private set
    private var currentArtBitmap: Bitmap? = null
    private var pendingPlayAfterPrepare = false
    private var isDucking = false
    private var lastErrorTimeMs = 0L

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
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    play()
                }
                override fun onPrepare() {
                    if (playlistManager.songs.isEmpty()) return
                    val song = playlistManager.getCurrentSong() ?: return
                    if (mediaPlayer == null) {
                        startPlayingSong(song, autoStart = false)
                    } else if (!isPreparing) {
                        // Already loaded — confirm current state so the system doesn't wait
                        updatePlaybackState(if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
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

    private fun songArtUri(songIndex: Int): Uri? {
        val song = playlistManager.songs.getOrNull(songIndex) ?: return null
        val f = metadataRepository.artFileForFolder(song.folderPath)
        return if (f.exists()) Uri.parse("content://de.codevoid.androsnd.albumart/${f.name}") else null
    }

    private fun buildSongExtras(song: Song): Bundle {
        return Bundle().apply {
            putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration)
        }
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
                            .setExtras(buildSongExtras(song))
                            .apply { songArtUri(index)?.let { setIconUri(it) } }
                            .build()
                        MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
                    }.toMutableList()
                    result.sendResult(items)
                } else {
                    val items = folders.mapIndexed { index, folder ->
                        val firstSongArtUri = folder.songs.firstOrNull()?.let { songArtUri(it) }
                        val desc = MediaDescriptionCompat.Builder()
                            .setMediaId("$MEDIA_FOLDER_PREFIX$index")
                            .setTitle(folder.name)
                            .setSubtitle("${folder.songs.size} tracks")
                            .apply { firstSongArtUri?.let { setIconUri(it) } }
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
                        .setExtras(buildSongExtras(song))
                        .apply { songArtUri(songIndex)?.let { setIconUri(it) } }
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
            val msSinceError = System.currentTimeMillis() - lastErrorTimeMs
            if (msSinceError < 1500L) {
                Log.d(TAG, "play(): suppressing auto-retry ${msSinceError}ms after last error")
                return
            }
            startPlayingSong(song)
        } else if (isPreparing) {
            pendingPlayAfterPrepare = true
        } else if (!isPlaying) {
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
        isPreparing = false
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
        isPreparing = false
        stopProgressUpdates()
        startPlayingSong(song)
    }

    fun playSongAtIndex(index: Int) {
        playlistManager.setCurrentIndex(index)
        val song = playlistManager.getCurrentSong() ?: return
        playSong(song)
    }

    private fun startPlayingSong(song: Song, autoStart: Boolean = true) {
        val player = MediaPlayer()
        mediaPlayer = player
        isPreparing = true
        try {
            if (autoStart) requestAudioFocus()
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            player.setDataSource(applicationContext, song.uri)
            player.setOnCompletionListener { mp ->
                if (mediaPlayer !== mp) return@setOnCompletionListener
                onTrackComplete()
            }
            player.setOnErrorListener { mp, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                if (mediaPlayer !== mp) {
                    isPreparing = false
                    return@setOnErrorListener true
                }
                isPreparing = false
                pendingPlayAfterPrepare = false
                lastErrorTimeMs = System.currentTimeMillis()
                mp.release()
                mediaPlayer = null
                isPlaying = false
                stopProgressUpdates()
                updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
                updateNotification()
                broadcastState()
                true
            }
            player.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            player.setOnPreparedListener { mp ->
                isPreparing = false
                if (mediaPlayer !== mp) return@setOnPreparedListener
                val vol = getAppVolumeFloat()
                mp.setVolume(vol, vol)
                val shouldPlay = autoStart || pendingPlayAfterPrepare
                pendingPlayAfterPrepare = false
                if (shouldPlay) {
                    if (!autoStart) requestAudioFocus()
                    mp.start()
                    isPlaying = true
                    startProgressUpdates()
                    updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
                    startForegroundCompat(buildNotification())
                    broadcastState()
                    playlistManager.selectNextQueueSong()
                } else {
                    updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
                    broadcastState()
                }
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
            }
            player.prepareAsync()
        } catch (e: Exception) {
            isPreparing = false
            pendingPlayAfterPrepare = false
            lastErrorTimeMs = System.currentTimeMillis()
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
                PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or
                PlaybackStateCompat.ACTION_PREPARE or
                PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or
                PlaybackStateCompat.ACTION_PLAY_FROM_URI or
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
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

    private fun extractNeighborInfo(song: Song): NeighborInfo {
        val meta = metadataRepository.db.get(song.uri.toString(), song.lastModified)
        val artFile = metadataRepository.artFileForFolder(song.folderPath)
        val artUri = if (artFile.exists())
            Uri.parse("content://de.codevoid.androsnd.albumart/${artFile.name}")
        else null
        return NeighborInfo(
            meta?.title ?: song.displayName,
            meta?.artist ?: "",
            meta?.album ?: "",
            artUri
        )
    }

    private fun updateMediaSessionQueue() {
        val songs = playlistManager.songs
        if (songs.isEmpty()) return
        val curIdx = playlistManager.currentIndex
        val curSong = songs.getOrNull(curIdx) ?: return

        val curArtUri = Uri.parse("content://de.codevoid.androsnd.albumart/current_art.jpg")
            .takeIf { java.io.File(cacheDir, "album_art/current_art.jpg").exists() }
        val curDesc = MediaDescriptionCompat.Builder()
            .setMediaId(curIdx.toString())
            .setTitle(currentTextMetadata?.title ?: curSong.displayName)
            .setSubtitle(currentTextMetadata?.artist ?: "")
            .setDescription(currentTextMetadata?.album ?: "")
            .setMediaUri(curSong.uri)
            .apply { if (curArtUri != null) setIconUri(curArtUri) }
            .build()

        val queue: List<MediaSessionCompat.QueueItem>
        if (playlistManager.isShuffleOn) {
            val nextIdx = playlistManager.nextQueueIndex
            val nextSong = if (nextIdx >= 0) songs.getOrNull(nextIdx) else null
            val q = mutableListOf(MediaSessionCompat.QueueItem(curDesc, curIdx.toLong()))
            if (nextSong != null) {
                val nextInfo = extractNeighborInfo(nextSong)
                val nextDesc = MediaDescriptionCompat.Builder()
                    .setMediaId(nextIdx.toString())
                    .setTitle(nextInfo.title)
                    .setSubtitle(nextInfo.artist)
                    .setDescription(nextInfo.album)
                    .setMediaUri(nextSong.uri)
                    .apply { if (nextInfo.artUri != null) setIconUri(nextInfo.artUri) }
                    .build()
                q.add(MediaSessionCompat.QueueItem(nextDesc, nextIdx.toLong()))
            }
            queue = q
        } else {
            // Shuffle off: full playlist — current song with rich metadata, others with displayName only (no I/O)
            queue = songs.mapIndexed { idx, song ->
                if (idx == curIdx) {
                    MediaSessionCompat.QueueItem(curDesc, idx.toLong())
                } else {
                    val desc = MediaDescriptionCompat.Builder()
                        .setMediaId(idx.toString())
                        .setTitle(song.displayName)
                        .setMediaUri(song.uri)
                        .apply { songArtUri(idx)?.let { setIconUri(it) } }
                        .build()
                    MediaSessionCompat.QueueItem(desc, idx.toLong())
                }
            }
        }

        mediaSession.setQueue(queue)
        mediaSession.setQueueTitle(getString(R.string.app_name))
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
                },
            onComplete = {
                broadcastManager.sendBroadcast(Intent(BROADCAST_ENRICHMENT_COMPLETE))
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
