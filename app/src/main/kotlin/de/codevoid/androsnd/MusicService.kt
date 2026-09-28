package de.codevoid.androsnd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media.session.MediaButtonReceiver
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.media.app.NotificationCompat.MediaStyle
import de.codevoid.androsnd.model.PlaylistFolder
import de.codevoid.androsnd.model.Song
import de.codevoid.androsnd.model.SongMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The playback shell. Owns the single [MediaSessionCompat], the foreground
 * notification and audio focus/ducking, and holds one active [PlaybackController]
 * ([FilePlaybackController] for local files today; the radio subsystem is a second
 * controller). Every command — binder, MediaSession callback, media button — is routed
 * to [active], and every state view (session metadata, notification, queue) is rebuilt
 * from [active] rather than from fields of its own.
 *
 * The file-specific getters ([playlistManager], [isPlaying], [getPosition]…) delegate
 * to the controller so [MainActivity] and the adapters keep their existing reach into
 * the service.
 */
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
        const val BROADCAST_SCAN_PROGRESS = "de.codevoid.androsnd.SCAN_PROGRESS"

        const val EXTRA_SCAN_SONG_COUNT = "scan_song_count"
        const val EXTRA_METADATA_SONG_INDEX = "song_index"
        const val EXTRA_METADATA_TITLE    = "meta_title"
        const val EXTRA_METADATA_ARTIST   = "meta_artist"
        const val EXTRA_METADATA_ALBUM    = "meta_album"
        const val EXTRA_METADATA_DURATION = "meta_duration"
        const val EXTRA_ART_FOLDER_PATH   = "art_folder_path"

        private const val PREFS_NAME = "androsnd_prefs"
        private const val KEY_APP_VOLUME = "app_volume"
        private const val KEY_PLAYER_MODE = "player_mode"
        const val MODE_FILE = "file"
        const val MODE_RADIO = "radio"
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

    private lateinit var fileController: FilePlaybackController
    private lateinit var radioController: RadioPlaybackController
    private lateinit var active: PlaybackController

    // The file library scan is kicked off lazily — at startup if that is the saved mode,
    // otherwise the first time the user toggles into file mode.
    private var fileLibraryLoaded = false

    // File-specific reach kept for MainActivity and the adapters. Both come from the
    // file controller; the radio subsystem does not use them.
    val playlistManager: PlaylistManager get() = fileController.playlistManager
    val metadataRepository: MetadataRepository get() = fileController.metadataRepository

    fun isRadioMode(): Boolean = active === radioController

    // The right pane and folder browser bind whichever library is active. All of these
    // route through the active controller so remote focus follows what actually plays.
    val activeSongs: List<Song> get() = active.songs
    val activeFolders: List<PlaylistFolder> get() = active.folders
    val activeCurrentIndex: Int get() = active.currentIndex
    fun activeCurrentSong(): Song? = active.currentSong
    fun activeFolderIndexForSong(songIndex: Int): Int = active.folderIndexForSong(songIndex)

    /** Adds a user radio stream to the station library and rebinds if radio is active. */
    fun addRadioStation(name: String, url: String) {
        radioController.radioManager.addStation(name, url)
        if (isRadioMode()) {
            updateMediaSessionMetadata()
            broadcastState()
        }
    }

    internal val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    val isPlaying: Boolean get() = active.isPlaying
    val isPreparing: Boolean get() = active.isPreparing
    val isScanning: Boolean get() = active.isScanning
    val isShuffleOn: Boolean get() = active.isShuffleOn
    val currentTextMetadata: SongMetadata? get() = active.currentText

    private var isDucking = false

    internal lateinit var overlayToastManager: OverlayToastManager
        private set
    internal lateinit var broadcastManager: LocalBroadcastManager
        private set

    internal fun getAppVolumeFloat(): Float {
        val pct = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_APP_VOLUME, 100)
        return pct / 100f
    }

    fun applyAppVolume() {
        active.setVolume(getAppVolumeFloat())
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
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        broadcastManager = LocalBroadcastManager.getInstance(this)
        overlayToastManager = OverlayToastManager(this)
        fileController = FilePlaybackController(this)
        radioController = RadioPlaybackController(this)
        val savedMode = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PLAYER_MODE, MODE_FILE)
        active = if (savedMode == MODE_RADIO) radioController else fileController

        createNotificationChannel()
        initMediaSession()

        // Populate only the active subsystem at startup; the other library loads lazily
        // the first time the user toggles into it (see setPlayerMode).
        if (active === radioController) {
            radioController.load()
        } else {
            val savedUri = playlistManager.loadSavedFolder()
            if (savedUri != null) scanFolderAsync(savedUri)
        }
    }

    /**
     * Swaps the active subsystem. The outgoing one is stopped (which releases its player,
     * freeing native decoder slots so the two do not compete) and the incoming one's
     * library is loaded on first use. Does not autoplay — the user tunes in explicitly.
     */
    fun setPlayerMode(radio: Boolean) {
        val target: PlaybackController = if (radio) radioController else fileController
        if (target === active) return
        active.stop()
        active = target
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PLAYER_MODE, if (radio) MODE_RADIO else MODE_FILE)
            .apply()
        if (radio) {
            radioController.load()
        } else if (!fileLibraryLoaded) {
            playlistManager.loadSavedFolder()?.let { scanFolderAsync(it) }
        }
        publishShuffleMode()
        updateMediaSessionMetadata()
        updatePlaybackState()
        updateNotification()
        broadcastState()
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
                    // toggleShuffle() on the active controller republishes shuffle mode,
                    // reselects the next queue song and (for files) rebuilds the queue.
                    if (active.isShuffleOn != shouldShuffle) active.toggleShuffle()
                    this@MusicService.mediaSession.setShuffleMode(shuffleMode)
                    updatePlaybackState()
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
                    active.prepare()
                }
            })
            isActive = true
        }
        setSessionToken(mediaSession.sessionToken)

        // A session whose PlaybackState was never set sits in STATE_NONE, and the
        // system will not nominate such a session as the media button session — so a
        // headset's PLAY went nowhere until the app had been played once by hand.
        updatePlaybackState()
        publishShuffleMode()
    }

    /** Mirrors the restored/toggled shuffle state onto the session. */
    internal fun publishShuffleMode() {
        mediaSession.setShuffleMode(
            if (active.isShuffleOn) PlaybackStateCompat.SHUFFLE_MODE_ALL
            else PlaybackStateCompat.SHUFFLE_MODE_NONE
        )
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    internal fun startForegroundNotification() {
        startForegroundCompat(buildNotification())
    }

    internal fun stopForegroundNotification() {
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Delivered by MediaButtonReceiver for the manifest's MEDIA_BUTTON filter:
        // unpacks the KeyEvent and dispatches it to the session callback. Dispatched
        // before startForeground() so a headset press is not held up by building a
        // notification; the service may still be background here (stop() drops it),
        // so startForeground has to follow either way.
        if (intent != null && intent.action == Intent.ACTION_MEDIA_BUTTON) {
            MediaButtonReceiver.handleIntent(mediaSession, intent)
            startForegroundCompat(buildNotification())
            return START_STICKY
        }
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

    // --- Commands: every one routes to the active controller. ---

    fun play() = active.play()

    fun pause() = active.pause()

    fun handleStop() = active.stop()

    fun handlePlayPause() {
        if (active.isPlaying) active.pause() else active.play()
    }

    fun handleNext() = active.next()

    fun handlePrevious() = active.previous()

    fun handleShuffleButton() = active.toggleShuffle()

    fun seekTo(positionMs: Int) = active.seekTo(positionMs)

    fun playSong(song: Song) = active.playSong(song)

    fun playSongAtIndex(index: Int) = active.playAt(index)

    fun scanFolderAsync(uri: Uri) {
        fileLibraryLoaded = true
        fileController.scan(uri)
    }

    internal fun requestAudioFocus() {
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
                    AudioManager.AUDIOFOCUS_LOSS -> active.pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> active.pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        isDucking = true
                        active.setVolume(getAppVolumeFloat() * 0.2f)
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        if (isDucking) {
                            isDucking = false
                            applyAppVolume()
                        } else {
                            active.play()
                        }
                    }
                }
            }
            .build()
        audioFocusRequest = focusRequest
        audioManager.requestAudioFocus(focusRequest)
    }

    internal fun updateMediaSessionMetadata() {
        val song = active.currentSong ?: return
        val meta = active.currentText
        val title    = meta?.title    ?: song.displayName
        val artist   = meta?.artist   ?: ""
        val album    = meta?.album    ?: ""
        val duration = meta?.duration ?: 0L

        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, active.currentIndex.toString())
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, active.songCount.toLong())
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, artist)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, album)
        val art = active.currentArt
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

    internal fun publishStoppedState() = updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)

    /**
     * Pass a state only for STOPPED, which the fields cannot express — everything else
     * is implied by isPlaying.
     *
     * Not wired into broadcastState(), tempting as that is: broadcastState() doubles as
     * the 1 s progress tick, so publishing the whole session view from there would
     * re-send the metadata — album-art bitmap included — every second.
     *
     * Seek/fast-forward/rewind are advertised only when the active controller can
     * seek, so the radio subsystem does not offer scrubbing on a live stream.
     */
    internal fun updatePlaybackState(
        state: Int = if (active.isPlaying) PlaybackStateCompat.STATE_PLAYING
                     else PlaybackStateCompat.STATE_PAUSED
    ) {
        val position = active.positionMs.toLong()
        val speed = if (active.isPlaying) 1f else 0f
        var actions =
            PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE or
            PlaybackStateCompat.ACTION_PREPARE or
            PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM or
            PlaybackStateCompat.ACTION_PLAY_FROM_URI or
            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
        if (active.canSeek) {
            actions = actions or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_FAST_FORWARD or
                PlaybackStateCompat.ACTION_REWIND
        }
        val playbackState = PlaybackStateCompat.Builder()
            .setState(state, position, speed)
            .setActiveQueueItemId(active.currentIndex.toLong())
            .setActions(actions)
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

    internal suspend fun updateMediaSessionQueue() {
        val songs = playlistManager.songs
        if (songs.isEmpty()) return
        val curIdx = playlistManager.currentIndex
        val curSong = songs.getOrNull(curIdx) ?: return
        val isShuffleOn = playlistManager.isShuffleOn
        val nextIdx = if (isShuffleOn) playlistManager.nextQueueIndex else -1
        val nextSong = if (nextIdx >= 0) songs.getOrNull(nextIdx) else null

        // All file/DB I/O in one IO-dispatched block before touching MediaSession.
        data class IoData(val curArtUri: Uri?, val nextInfo: NeighborInfo?, val allArtUris: List<Uri?>)
        val io = withContext(Dispatchers.IO) {
            val curArt = java.io.File(cacheDir, "album_art/current_art.jpg")
                .takeIf { it.exists() }
                ?.let { Uri.parse("content://de.codevoid.androsnd.albumart/current_art.jpg") }
            val nextInfo = nextSong?.let { extractNeighborInfo(it) }
            val allArt = if (!isShuffleOn) songs.indices.map { songArtUri(it) } else emptyList()
            IoData(curArt, nextInfo, allArt)
        }

        val curDesc = MediaDescriptionCompat.Builder()
            .setMediaId(curIdx.toString())
            .setTitle(active.currentText?.title ?: curSong.displayName)
            .setSubtitle(active.currentText?.artist ?: "")
            .setDescription(active.currentText?.album ?: "")
            .setMediaUri(curSong.uri)
            .apply { if (io.curArtUri != null) setIconUri(io.curArtUri) }
            .build()

        val queue: List<MediaSessionCompat.QueueItem>
        if (isShuffleOn) {
            val q = mutableListOf(MediaSessionCompat.QueueItem(curDesc, curIdx.toLong()))
            if (nextSong != null && io.nextInfo != null) {
                val nextDesc = MediaDescriptionCompat.Builder()
                    .setMediaId(nextIdx.toString())
                    .setTitle(io.nextInfo.title)
                    .setSubtitle(io.nextInfo.artist)
                    .setDescription(io.nextInfo.album)
                    .setMediaUri(nextSong.uri)
                    .apply { if (io.nextInfo.artUri != null) setIconUri(io.nextInfo.artUri) }
                    .build()
                q.add(MediaSessionCompat.QueueItem(nextDesc, nextIdx.toLong()))
            }
            queue = q
        } else {
            queue = songs.mapIndexed { idx, song ->
                if (idx == curIdx) {
                    MediaSessionCompat.QueueItem(curDesc, idx.toLong())
                } else {
                    val desc = MediaDescriptionCompat.Builder()
                        .setMediaId(idx.toString())
                        .setTitle(song.displayName)
                        .setMediaUri(song.uri)
                        .apply { io.allArtUris.getOrNull(idx)?.let { setIconUri(it) } }
                        .build()
                    MediaSessionCompat.QueueItem(desc, idx.toLong())
                }
            }
        }

        mediaSession.setQueue(queue)
        mediaSession.setQueueTitle(getString(R.string.app_name))
    }

    private fun buildNotification(): Notification {
        val song = active.currentSong
        val contentTitle = active.currentText?.title ?: song?.displayName ?: getString(R.string.app_name)

        val playPauseAction = if (active.isPlaying) {
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
            .setLargeIcon(active.currentArt)
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

    internal fun updateNotification() {
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

    internal fun startProgressUpdates() {
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

    internal fun stopProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }

    internal fun broadcastState() {
        broadcastManager.sendBroadcast(Intent(BROADCAST_STATE_CHANGED))
    }

    fun getPosition(): Int = active.positionMs
    fun getDuration(): Int = active.durationMs
    fun getBufferedMs(): Int = active.bufferedMs

    override fun onDestroy() {
        super.onDestroy()
        stopProgressUpdates()
        fileController.release()
        radioController.release()
        mediaSession.release()
        overlayToastManager.dismiss()
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        serviceScope.cancel()
    }
}
