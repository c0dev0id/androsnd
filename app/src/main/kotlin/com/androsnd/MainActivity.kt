package com.androsnd

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.androsnd.db.AppDatabase
import com.androsnd.db.entity.FolderEntity
import com.androsnd.db.entity.SongEntity
import com.androsnd.player.PlayerService
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var coverArt: ImageView
    private lateinit var songTitle: TextView
    private lateinit var songArtist: TextView
    private lateinit var songAlbum: TextView
    private lateinit var progressBar: SeekBar
    private lateinit var timeCurrentView: TextView
    private lateinit var timeTotalView: TextView
    private lateinit var playlistRecycler: RecyclerView
    private lateinit var loadingIndicator: View
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnShuffle: MaterialButton

    private lateinit var playlistAdapter: PlaylistAdapter
    private var isUserSeekBarTouch = false
    private var lastPlayPauseTime = 0L
    private var pendingOpenUri: Uri? = null

    private val dbExecutor = Executors.newSingleThreadExecutor()

    private lateinit var mediaBrowser: MediaBrowserCompat
    private var mediaController: MediaControllerCompat? = null

    private val controllerCallback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            updatePlaybackUI(state)
        }
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            updateMetadataUI(metadata)
        }
        override fun onQueueChanged(queue: MutableList<MediaSessionCompat.QueueItem>?) {
            reloadLibrary()
        }
        override fun onSessionEvent(event: String?, extras: Bundle?) {
            when (event) {
                PlayerService.EVENT_SCAN_STARTED -> showLoading()
                PlayerService.EVENT_SCAN_COMPLETED -> {
                    hideLoading()
                    reloadLibrary()
                }
            }
        }
    }

    private val connectionCallback = object : MediaBrowserCompat.ConnectionCallback() {
        override fun onConnected() {
            val token = mediaBrowser.sessionToken
            val controller = MediaControllerCompat(this@MainActivity, token)
            MediaControllerCompat.setMediaController(this@MainActivity, controller)
            mediaController = controller
            controller.registerCallback(controllerCallback)

            updatePlaybackUI(controller.playbackState)
            updateMetadataUI(controller.metadata)
            reloadLibrary()

            if (pendingOpenUri != null) {
                playOpenWithUri()
            }
        }
        override fun onConnectionFailed() {}
        override fun onConnectionSuspended() {
            mediaController?.unregisterCallback(controllerCallback)
            mediaController = null
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val bundle = Bundle().apply { putString(PlayerService.EXTRA_FOLDER_URI, it.toString()) }
            mediaController?.transportControls?.sendCustomAction(PlayerService.CUSTOM_ACTION_SCAN_FOLDER, bundle)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val buttonBar = findViewById<View>(R.id.button_bar)
        ViewCompat.setOnApplyWindowInsetsListener(buttonBar) { view, insets ->
            val navBarInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val extraPaddingPx = (8 * view.resources.displayMetrics.density).toInt()
            view.setPadding(
                view.paddingLeft, view.paddingTop,
                view.paddingRight, navBarInsets.bottom + extraPaddingPx
            )
            insets
        }

        bindViews()
        setupRecyclerView()
        setupButtons()
        requestPermissionsIfNeeded()
        requestBatteryOptimizationExemption()
        requestOverlayPermission()

        mediaBrowser = MediaBrowserCompat(
            this,
            ComponentName(this, PlayerService::class.java),
            connectionCallback,
            null
        )

        handleOpenWithIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        if (!mediaBrowser.isConnected) {
            mediaBrowser.connect()
        }
    }

    override fun onStop() {
        super.onStop()
        mediaController?.unregisterCallback(controllerCallback)
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaController?.unregisterCallback(controllerCallback)
        mediaBrowser.disconnect()
        dbExecutor.shutdown()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOpenWithIntent(intent)
    }

    private fun handleOpenWithIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data ?: return
            pendingOpenUri = uri
            if (mediaController != null) playOpenWithUri()
        }
    }

    private fun playOpenWithUri() {
        val uri = pendingOpenUri ?: return
        pendingOpenUri = null
        mediaController?.transportControls?.playFromUri(uri, null)
    }

    private fun bindViews() {
        coverArt = findViewById(R.id.cover_art)
        songTitle = findViewById(R.id.song_title)
        songArtist = findViewById(R.id.song_artist)
        songAlbum = findViewById(R.id.song_album)
        progressBar = findViewById(R.id.progress_bar)
        timeCurrentView = findViewById(R.id.time_current)
        timeTotalView = findViewById(R.id.time_total)
        playlistRecycler = findViewById(R.id.playlist_recycler)
        loadingIndicator = findViewById(R.id.loading_indicator)
        btnPlay = findViewById(R.id.btn_play)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        btnStop = findViewById(R.id.btn_stop)
        btnShuffle = findViewById(R.id.btn_shuffle)
    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            onFolderClick = {},
            onSongClick = { songId ->
                mediaController?.transportControls?.skipToQueueItem(songId)
            }
        )
        playlistRecycler.layoutManager = LinearLayoutManager(this)
        playlistRecycler.adapter = playlistAdapter
    }

    private fun setupButtons() {
        btnPlay.setOnClickListener {
            val now = System.currentTimeMillis()
            if (now - lastPlayPauseTime < 500L) {
                lastPlayPauseTime = 0L
                val currentMode = mediaController?.shuffleMode ?: PlaybackStateCompat.SHUFFLE_MODE_NONE
                val newMode = if (currentMode == PlaybackStateCompat.SHUFFLE_MODE_NONE)
                    PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE
                mediaController?.transportControls?.setShuffleMode(newMode)
            } else {
                lastPlayPauseTime = now
                val state = mediaController?.playbackState?.state
                if (state == PlaybackStateCompat.STATE_PLAYING) {
                    mediaController?.transportControls?.pause()
                } else {
                    mediaController?.transportControls?.play()
                }
            }
        }
        btnPrev.setOnClickListener { mediaController?.transportControls?.skipToPrevious() }
        btnNext.setOnClickListener { mediaController?.transportControls?.skipToNext() }
        btnStop.setOnClickListener { mediaController?.transportControls?.stop() }
        btnShuffle.setOnClickListener {
            val currentMode = mediaController?.shuffleMode ?: PlaybackStateCompat.SHUFFLE_MODE_NONE
            val newMode = if (currentMode == PlaybackStateCompat.SHUFFLE_MODE_NONE)
                PlaybackStateCompat.SHUFFLE_MODE_ALL else PlaybackStateCompat.SHUFFLE_MODE_NONE
            mediaController?.transportControls?.setShuffleMode(newMode)
        }

        progressBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) timeCurrentView.text = formatTime(progress)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isUserSeekBarTouch = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                if (sb != null) mediaController?.transportControls?.seekTo(sb.progress.toLong())
                isUserSeekBarTouch = false
            }
        })

        findViewById<View>(R.id.btn_folder)?.setOnClickListener { openFolderPicker() }
    }

    private fun openFolderPicker() {
        folderPickerLauncher.launch(null)
    }

    private fun updatePlaybackUI(state: PlaybackStateCompat?) {
        val isPlaying = state?.state == PlaybackStateCompat.STATE_PLAYING
        val shuffleOn = mediaController?.shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE

        btnPlay.isSelected = isPlaying
        btnShuffle.isSelected = shuffleOn
        btnPlay.setIconResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play)

        if (!isUserSeekBarTouch) {
            val pos = state?.position?.toInt() ?: 0
            val dur = mediaController?.metadata?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION)?.toInt() ?: 0
            progressBar.max = if (dur > 0) dur else 100
            progressBar.progress = pos
            timeCurrentView.text = formatTime(pos)
            timeTotalView.text = formatTime(dur)
        }
    }

    private fun updateMetadataUI(metadata: MediaMetadataCompat?) {
        if (metadata == null) {
            songTitle.text = getString(R.string.no_song)
            songArtist.text = ""
            songAlbum.text = ""
            coverArt.setImageResource(android.R.drawable.ic_media_play)
            return
        }
        songTitle.text = metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE) ?: getString(R.string.no_song)
        songArtist.text = metadata.getString(MediaMetadataCompat.METADATA_KEY_ARTIST) ?: ""
        songAlbum.text = metadata.getString(MediaMetadataCompat.METADATA_KEY_ALBUM) ?: ""

        val artUri = metadata.getString(MediaMetadataCompat.METADATA_KEY_ART_URI)
        if (artUri != null) {
            val filePath = artUri.removePrefix("file://")
            dbExecutor.execute {
                val bitmap = try { BitmapFactory.decodeFile(filePath) } catch (e: Exception) { null }
                runOnUiThread {
                    if (bitmap != null) coverArt.setImageBitmap(bitmap)
                    else coverArt.setImageResource(android.R.drawable.ic_media_play)
                }
            }
        } else {
            coverArt.setImageResource(android.R.drawable.ic_media_play)
        }

        val dur = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION).toInt()
        timeTotalView.text = formatTime(dur)
    }

    private fun reloadLibrary() {
        dbExecutor.execute {
            val db = AppDatabase.getInstance(this)
            val folders = db.folderDao().getAllOrdered()
            val songs = db.songDao().getAllOrdered()
            val activeQueueId = mediaController?.playbackState?.activeQueueItemId ?: -1L

            runOnUiThread {
                playlistAdapter.submitData(folders, songs, activeQueueId)
                if (folders.isEmpty() && songs.isEmpty()) {
                    openFolderPicker()
                }
            }
        }
    }

    private fun showLoading() {
        loadingIndicator.visibility = View.VISIBLE
        playlistRecycler.visibility = View.GONE
    }

    private fun hideLoading() {
        loadingIndicator.visibility = View.GONE
        playlistRecycler.visibility = View.VISIBLE
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return "%d:%02d".format(minutes, seconds)
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
    }

    private fun requestBatteryOptimizationExemption() {
        val prefs = getSharedPreferences("androsnd_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_requested", false)) return
        prefs.edit().putBoolean("battery_requested", true).apply()
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    class PlaylistAdapter(
        private val onFolderClick: (Long) -> Unit,
        private val onSongClick: (Long) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            const val TYPE_FOLDER = 0
            const val TYPE_SONG = 1
        }

        private data class ListItem(
            val type: Int,
            val id: Long = -1,
            val displayName: String = ""
        )

        private val items = mutableListOf<ListItem>()
        private var activeQueueId: Long = -1L

        fun submitData(folders: List<FolderEntity>, songs: List<SongEntity>, activeQueueItemId: Long) {
            items.clear()
            activeQueueId = activeQueueItemId
            for (folder in folders) {
                items.add(ListItem(TYPE_FOLDER, id = folder.id, displayName = folder.name))
                val folderSongs = songs.filter { it.folderId == folder.id }
                for (song in folderSongs) {
                    items.add(ListItem(TYPE_SONG, id = song.id, displayName = song.displayName))
                }
            }
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) = items[position].type
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_FOLDER) {
                FolderViewHolder(inflater.inflate(R.layout.item_playlist_folder, parent, false))
            } else {
                SongViewHolder(inflater.inflate(R.layout.item_playlist_song, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            when (holder) {
                is FolderViewHolder -> {
                    holder.name.text = item.displayName
                    holder.itemView.setOnClickListener { onFolderClick(item.id) }
                }
                is SongViewHolder -> {
                    holder.name.text = item.displayName
                    holder.itemView.isSelected = item.id == activeQueueId
                    holder.itemView.setOnClickListener { onSongClick(item.id) }
                }
            }
        }

        class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.folder_name)
        }

        class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.song_name)
        }
    }
}
