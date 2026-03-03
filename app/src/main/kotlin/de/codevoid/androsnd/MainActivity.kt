package de.codevoid.androsnd

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
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
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import de.codevoid.androsnd.model.PlaylistFolder
import de.codevoid.androsnd.model.Song
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

class MainActivity : AppCompatActivity() {

    private var musicService: MusicService? = null
    private var isBound = false

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

    private lateinit var btnSettings: MaterialButton
    private lateinit var contentArea: View
    private lateinit var settingsPanel: View
    private var settingsVisible = false
    private var settingsButtonStrokeWidth = 0

    private lateinit var playlistAdapter: PlaylistAdapter
    private var isUserSeekBarTouch = false
    private var lastKnownPlaylistIndex = -1
    private var lastKnownSongCount = -1

    private var accentColor: Int = Color.parseColor("#F57C00")
    private val inactiveColor: Int = Color.parseColor("#444444")

    companion object {
        private val ACCENT_COLORS = mapOf(
            "orange" to Color.parseColor("#F57C00"),
            "blue" to Color.parseColor("#2196F3"),
            "green" to Color.parseColor("#4CAF50")
        )
        private val ACCENT_NAMES = listOf("Orange", "Blue", "Green")
        private val ACCENT_KEYS = listOf("orange", "blue", "green")
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val musicBinder = binder as? MusicService.MusicBinder ?: return
            musicService = musicBinder.getService()
            isBound = true
            if (musicService?.isScanning == true) {
                showLoading()
            } else {
                updateUI()
                if (musicService?.playlistManager?.songs?.isEmpty() == true) {
                    openFolderPicker()
                }
            }
            // Set up overlay scale listener for settings panel
            val sliderSize = settingsPanel.findViewById<Slider>(R.id.slider_overlay_size)
            musicService?.setOnOverlayScaleChangedListener { scale ->
                val aligned = alignToSliderStep(scale, 0.5f, 3.0f, 0.1f)
                if (sliderSize.value != aligned) {
                    sliderSize.value = aligned
                }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isBound = false
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MusicService.BROADCAST_STATE_CHANGED) {
                updateUI()
            }
        }
    }

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                MusicService.BROADCAST_SCAN_STARTED -> showLoading()
                MusicService.BROADCAST_SCAN_COMPLETED -> {
                    hideLoading()
                    updateUI()
                }
            }
        }
    }

    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            musicService?.scanFolderAsync(it)
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateUI()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val contentAreaView = findViewById<View>(R.id.content_area)
        ViewCompat.setOnApplyWindowInsetsListener(contentAreaView) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            view.setPadding(
                view.paddingLeft, statusBarInsets.top,
                view.paddingRight, view.paddingBottom
            )
            insets
        }

        val settingsPanelView = findViewById<View>(R.id.settings_panel)
        ViewCompat.setOnApplyWindowInsetsListener(settingsPanelView) { view, insets ->
            val statusBarInsets = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val basePadding = (24 * view.resources.displayMetrics.density).toInt()
            view.setPadding(
                view.paddingLeft, statusBarInsets.top + basePadding,
                view.paddingRight, view.paddingBottom
            )
            insets
        }

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
        setupSettingsPanel()
        applyAccentColor()
        requestPermissionsIfNeeded()
        requestBatteryOptimizationExemption()
        requestOverlayPermission()

        val serviceIntent = Intent(this, MusicService::class.java)
        startForegroundService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        LocalBroadcastManager.getInstance(this).registerReceiver(
            stateReceiver,
            IntentFilter(MusicService.BROADCAST_STATE_CHANGED)
        )

        val scanFilter = IntentFilter().apply {
            addAction(MusicService.BROADCAST_SCAN_STARTED)
            addAction(MusicService.BROADCAST_SCAN_COMPLETED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(scanReceiver, scanFilter)
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
        btnSettings = findViewById(R.id.btn_settings)
        contentArea = findViewById(R.id.content_area)
        settingsPanel = findViewById(R.id.settings_panel)
        settingsButtonStrokeWidth = btnSettings.strokeWidth

        loadAccentColor()
    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            onFolderClick = { /* toggle expand */ },
            onSongClick = { index ->
                musicService?.playSongAtIndex(index)
            }
        )
        playlistRecycler.layoutManager = LinearLayoutManager(this)
        playlistRecycler.adapter = playlistAdapter
    }

    private fun setupButtons() {
        btnPlay.setOnClickListener {
            musicService?.handlePlayPause()
        }
        btnPrev.setOnClickListener { musicService?.handlePrevious() }
        btnNext.setOnClickListener { musicService?.handleNext() }
        btnStop.setOnClickListener { musicService?.handleStop() }
        btnShuffle.setOnClickListener { musicService?.handleShuffleButton() }

        progressBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    timeCurrentView.text = formatTime(progress)
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar?) { isUserSeekBarTouch = true }
            override fun onStopTrackingTouch(sb: SeekBar?) {
                musicService?.seekTo(sb?.progress ?: 0)
                isUserSeekBarTouch = false
            }
        })

        btnSettings.setOnClickListener { toggleSettings() }
    }

    private fun loadAccentColor() {
        val prefs = getSharedPreferences("androsnd_prefs", Context.MODE_PRIVATE)
        val key = prefs.getString("accent_color", "orange") ?: "orange"
        accentColor = ACCENT_COLORS[key] ?: ACCENT_COLORS["orange"]!!
    }

    private fun applyAccentColor() {
        val accentCSL = ColorStateList.valueOf(accentColor)

        // SeekBar
        progressBar.progressTintList = accentCSL
        progressBar.thumbTintList = accentCSL

        // Outlined buttons (settings)
        btnSettings.strokeColor = accentCSL

        // Playlist adapter
        playlistAdapter.accentColor = accentColor
        playlistAdapter.notifyDataSetChanged()

        // Settings panel controls
        applyAccentToSettingsControls()
    }

    private fun updateButtonStates() {
        val svc = musicService
        val isPlaying = svc?.isPlaying == true
        val isShuffleOn = svc?.playlistManager?.isShuffleOn == true

        btnPlay.backgroundTintList = ColorStateList.valueOf(if (isPlaying) accentColor else inactiveColor)
        btnShuffle.backgroundTintList = ColorStateList.valueOf(if (isShuffleOn) accentColor else inactiveColor)

        if (settingsVisible) {
            btnSettings.backgroundTintList = ColorStateList.valueOf(accentColor)
            btnSettings.strokeWidth = 0
        } else {
            btnSettings.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            btnSettings.strokeWidth = settingsButtonStrokeWidth
            btnSettings.strokeColor = ColorStateList.valueOf(accentColor)
        }
    }

    private fun openFolderPicker() {
        folderPickerLauncher.launch(null)
    }

    private fun alignToSliderStep(value: Float, min: Float, max: Float, step: Float): Float {
        val clamped = value.coerceIn(min, max)
        if (step <= 0f) return clamped
        val steps = Math.round((clamped - min) / step)
        return (min + steps * step).coerceIn(min, max)
    }

    private fun setupSettingsPanel() {
        val prefs = getSharedPreferences("androsnd_prefs", Context.MODE_PRIVATE)

        // Accent Color
        val toggleAccent = settingsPanel.findViewById<MaterialButtonToggleGroup>(R.id.toggle_accent_settings)
        val savedKey = prefs.getString("accent_color", "orange") ?: "orange"
        val initialCheckedId = when (savedKey) {
            "orange" -> R.id.btn_accent_orange
            "blue" -> R.id.btn_accent_blue
            "green" -> R.id.btn_accent_green
            else -> R.id.btn_accent_orange
        }
        toggleAccent.check(initialCheckedId)
        toggleAccent.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val key = when (checkedId) {
                    R.id.btn_accent_orange -> "orange"
                    R.id.btn_accent_blue -> "blue"
                    R.id.btn_accent_green -> "green"
                    else -> "orange"
                }
                accentColor = ACCENT_COLORS[key] ?: ACCENT_COLORS["orange"]!!
                prefs.edit().putString("accent_color", key).apply()
                applyAccentColor()
                updateButtonStates()
            }
        }

        // App Volume
        val sliderVolume = settingsPanel.findViewById<Slider>(R.id.slider_volume)
        val labelVolume = settingsPanel.findViewById<TextView>(R.id.label_volume)
        val currentVolume = prefs.getInt("app_volume", 100)
        sliderVolume.value = alignToSliderStep(currentVolume.toFloat(), 0f, 100f, 1f)
        labelVolume.text = "${sliderVolume.value.toInt()}%"
        sliderVolume.addOnChangeListener { _, value, _ ->
            val vol = value.toInt()
            labelVolume.text = "${vol}%"
            prefs.edit().putInt("app_volume", vol).apply()
            musicService?.applyAppVolume()
        }

        // Overlay Opacity
        val sliderOpacity = settingsPanel.findViewById<Slider>(R.id.slider_opacity)
        val labelOpacity = settingsPanel.findViewById<TextView>(R.id.label_opacity)
        val currentOpacity = prefs.getInt("overlay_opacity", 80)
        sliderOpacity.value = alignToSliderStep(currentOpacity.toFloat(), 0f, 100f, 1f)
        labelOpacity.text = "${sliderOpacity.value.toInt()}%"
        sliderOpacity.addOnChangeListener { _, value, _ ->
            val opacity = value.toInt()
            labelOpacity.text = "${opacity}%"
            prefs.edit().putInt("overlay_opacity", opacity).apply()
            musicService?.updateOverlayOpacity(opacity)
        }

        // Overlay Size
        val sliderSize = settingsPanel.findViewById<Slider>(R.id.slider_overlay_size)
        val labelSize = settingsPanel.findViewById<TextView>(R.id.label_overlay_size)
        val currentScale = alignToSliderStep(prefs.getFloat("overlay_scale", 1.0f), 0.5f, 3.0f, 0.1f)
        sliderSize.value = currentScale
        labelSize.text = "%.1fx".format(currentScale)
        sliderSize.addOnChangeListener { _, value, _ ->
            labelSize.text = "%.1fx".format(value)
            prefs.edit().putFloat("overlay_scale", value).apply()
            musicService?.updateOverlayScale(value)
        }

        // Double-Tap Speed slider
        val sliderDoubleTap = settingsPanel.findViewById<Slider>(R.id.slider_double_tap)
        val labelDoubleTap = settingsPanel.findViewById<TextView>(R.id.label_double_tap)
        val currentTimeout = alignToSliderStep(prefs.getInt("double_tap_ms", 500).toFloat(), 300f, 2000f, 50f)
        sliderDoubleTap.value = currentTimeout
        labelDoubleTap.text = "${currentTimeout.toInt()}ms"
        sliderDoubleTap.addOnChangeListener { _, value, _ ->
            val ms = value.toInt()
            labelDoubleTap.text = "${ms}ms"
            prefs.edit().putInt("double_tap_ms", ms).apply()
        }

        // Double-Tap Actions toggle
        val switchDoubleTap = settingsPanel.findViewById<MaterialSwitch>(R.id.switch_double_tap)
        val doubleTapSliderGroup = settingsPanel.findViewById<View>(R.id.double_tap_slider_group)
        val doubleTapEnabled = prefs.getBoolean("double_tap_enabled", true)
        switchDoubleTap.isChecked = doubleTapEnabled
        doubleTapSliderGroup.alpha = if (doubleTapEnabled) 1.0f else 0.38f
        sliderDoubleTap.isEnabled = doubleTapEnabled

        switchDoubleTap.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("double_tap_enabled", isChecked).apply()
            doubleTapSliderGroup.alpha = if (isChecked) 1.0f else 0.38f
            sliderDoubleTap.isEnabled = isChecked
        }

        // Apply accent color to settings controls
        applyAccentToSettingsControls()

        // Select Folder
        settingsPanel.findViewById<MaterialButton>(R.id.btn_folder).setOnClickListener {
            openFolderPicker()
        }
    }

    private fun toggleSettings() {
        settingsVisible = !settingsVisible
        if (settingsVisible) {
            contentArea.visibility = View.GONE
            settingsPanel.visibility = View.VISIBLE
            musicService?.showOverlayDemo()
        } else {
            settingsPanel.visibility = View.GONE
            contentArea.visibility = View.VISIBLE
            musicService?.dismissOverlayDemo()
        }
        updateButtonStates()
    }

    private fun applyAccentToSettingsControls() {
        val accentCSL = ColorStateList.valueOf(accentColor)
        listOf(R.id.slider_volume, R.id.slider_opacity, R.id.slider_overlay_size, R.id.slider_double_tap)
            .mapNotNull { settingsPanel.findViewById<Slider>(it) }
            .forEach { slider ->
                slider.trackActiveTintList = accentCSL
                slider.thumbTintList = accentCSL
            }
        listOf(R.id.label_volume, R.id.label_opacity, R.id.label_overlay_size, R.id.label_double_tap)
            .mapNotNull { settingsPanel.findViewById<TextView>(it) }
            .forEach { label ->
                label.setTextColor(accentColor)
            }
        listOf(R.id.btn_accent_orange, R.id.btn_accent_blue, R.id.btn_accent_green)
            .mapNotNull { settingsPanel.findViewById<MaterialButton>(it) }
            .forEach { btn ->
                btn.strokeColor = accentCSL
            }
        settingsPanel.findViewById<MaterialButton>(R.id.btn_folder)?.strokeColor = accentCSL
        settingsPanel.findViewById<MaterialSwitch>(R.id.switch_double_tap)?.let { sw ->
            sw.thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accentColor, Color.parseColor("#888888"))
            )
            sw.trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.argb(128, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)), Color.parseColor("#555555"))
            )
        }
    }

    private fun requestPermissionsIfNeeded() {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (perms.isNotEmpty()) permissionLauncher.launch(perms.toTypedArray())
    }

    private fun requestBatteryOptimizationExemption() {
        val prefs = getSharedPreferences("androsnd_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("battery_requested", false)) return
        prefs.edit().putBoolean("battery_requested", true).apply()

        val pm = getSystemService(android.os.PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private fun updateUI() {
        val svc = musicService ?: return
        if (svc.isScanning) return
        val pm = svc.playlistManager
        val song = pm.getCurrentSong()

        btnPlay.setIconResource(if (svc.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        updateButtonStates()

        if (song != null) {
            val metadata = svc.currentMetadata
            if (metadata != null) {
                songTitle.text = metadata.title
                songArtist.text = metadata.artist
                songAlbum.text = metadata.album

                if (metadata.coverArt != null) {
                    coverArt.setImageBitmap(metadata.coverArt)
                } else {
                    coverArt.setImageResource(android.R.drawable.ic_media_play)
                }
            }
        } else {
            songTitle.text = getString(R.string.no_song)
            songArtist.text = ""
            songAlbum.text = ""
            coverArt.setImageResource(android.R.drawable.ic_media_play)
        }

        if (!isUserSeekBarTouch) {
            val pos = svc.getPosition()
            val dur = svc.getDuration()
            progressBar.max = if (dur > 0) dur else 100
            progressBar.progress = pos
            timeCurrentView.text = formatTime(pos)
            timeTotalView.text = formatTime(dur)
        }

        updatePlaylist()
    }

    private fun updatePlaylist() {
        val svc = musicService ?: return
        val pm = svc.playlistManager
        if (pm.currentIndex == lastKnownPlaylistIndex && pm.songs.size == lastKnownSongCount) return
        if (pm.songs.size == lastKnownSongCount && pm.currentIndex != lastKnownPlaylistIndex) {
            lastKnownPlaylistIndex = pm.currentIndex
            playlistAdapter.updateCurrentIndex(pm.currentIndex)
        } else {
            lastKnownPlaylistIndex = pm.currentIndex
            lastKnownSongCount = pm.songs.size
            playlistAdapter.submitData(pm.folders, pm.songs, pm.currentIndex)
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

    override fun onDestroy() {
        super.onDestroy()
        musicService?.setOnOverlayScaleChangedListener(null)
        musicService?.dismissOverlayDemo()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(scanReceiver)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    class PlaylistAdapter(
        private val onFolderClick: (Int) -> Unit,
        private val onSongClick: (Int) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        companion object {
            const val TYPE_FOLDER = 0
            const val TYPE_SONG = 1
        }

        private data class ListItem(
            val type: Int,
            val folderIndex: Int = -1,
            val songIndex: Int = -1,
            val displayName: String = ""
        )

        private val items = mutableListOf<ListItem>()
        private var currentSongIndex = -1
        var accentColor: Int = Color.parseColor("#F57C00")

        fun updateCurrentIndex(newIndex: Int) {
            val oldIndex = currentSongIndex
            currentSongIndex = newIndex
            items.forEachIndexed { pos, item ->
                if (item.type == TYPE_SONG && (item.songIndex == oldIndex || item.songIndex == newIndex)) {
                    notifyItemChanged(pos)
                }
            }
        }

        fun submitData(folders: List<PlaylistFolder>, songs: List<Song>, currentIdx: Int) {
            items.clear()
            currentSongIndex = currentIdx
            for ((fi, folder) in folders.withIndex()) {
                items.add(ListItem(TYPE_FOLDER, folderIndex = fi, displayName = folder.name))
                for (si in folder.songs) {
                    val song = songs.getOrNull(si) ?: continue
                    items.add(ListItem(TYPE_SONG, songIndex = si, displayName = song.displayName))
                }
            }
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int) = items[position].type
        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_FOLDER) {
                val view = inflater.inflate(R.layout.item_playlist_folder, parent, false)
                FolderViewHolder(view)
            } else {
                val view = inflater.inflate(R.layout.item_playlist_song, parent, false)
                SongViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            when (holder) {
                is FolderViewHolder -> {
                    holder.name.text = item.displayName
                    holder.itemView.setOnClickListener { onFolderClick(item.folderIndex) }
                }
                is SongViewHolder -> {
                    holder.name.text = item.displayName
                    val isSelected = item.songIndex == currentSongIndex
                    holder.itemView.isSelected = isSelected
                    holder.itemView.setBackgroundColor(if (isSelected) accentColor else Color.TRANSPARENT)
                    holder.itemView.setOnClickListener { onSongClick(item.songIndex) }
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
