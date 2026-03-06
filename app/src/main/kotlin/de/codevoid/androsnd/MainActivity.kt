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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
import de.codevoid.androsnd.model.SongMetadata
import android.util.SparseArray
import java.util.concurrent.Executors
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.widget.LinearLayout
import android.view.Gravity
import android.widget.Toast

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
    private lateinit var metadataCounterView: TextView
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

    // Remote control navigation state
    private var navZone: NavZone = NavZone.Playlist
    private var playlistFocusPos: Int = 0
    private lateinit var volDisplay: TextView

    // ── Key mapping preset ────────────────────────────────────────────────────
    private lateinit var presetManager: RemotePresetManager
    private var activePreset: RemoteKeyPreset = RemoteKeyPreset.DMD_REMOTE_2

    // ── Wizard state ─────────────────────────────────────────────────────────
    private lateinit var wizardPanel: View
    private var wizardVisible = false
    private lateinit var wizardCapturePresetName: TextView
    private lateinit var wizardCaptureProgress: TextView
    private lateinit var wizardCaptureAction: TextView
    private lateinit var wizardCaptureCurrent: TextView
    private lateinit var wizardSkipButton: MaterialButton

    // Key capture state (used during wizard Phase 1)
    private var isCapturingKey = false
    private var captureTargetPreset: RemoteKeyPreset? = null
    private var captureActionIndex = 0
    private var captureKeycodes = IntArray(RemoteKeyPreset.ACTION_COUNT)

    // Focus frame is hidden until the first remote key is pressed
    private var hasReceivedRemoteKey = false

    // Key-repeat state for DPAD navigation (accelerating, used for up/down)
    private val navRepeatHandler = Handler(Looper.getMainLooper())
    private var navRepeatRunnable: Runnable? = null
    private var navRepeatStep = 0

    // Key-repeat state for fixed-interval actions (volume and left/right seeking)
    private val fixedRepeatHandler = Handler(Looper.getMainLooper())
    private var fixedRepeatRunnable: Runnable? = null

    private val navButtons: List<MaterialButton>
        get() = listOf(btnPrev, btnPlay, btnNext, btnStop, btnShuffle)

    private lateinit var playlistAdapter: PlaylistAdapter
    private var isUserSeekBarTouch = false
    private var lastKnownPlaylistIndex = -1
    private var lastKnownSongCount = -1
    private var metadataLoadedCount = 0

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

    sealed class NavZone {
        object Playlist : NavZone()
        data class ButtonBar(val idx: Int = 1) : NavZone()  // 1 = Play button
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
                MusicService.BROADCAST_SCAN_STARTED -> {
                    metadataLoadedCount = 0
                    metadataCounterView.visibility = View.GONE
                    showLoading()
                }
                MusicService.BROADCAST_SCAN_COMPLETED -> {
                    hideLoading()
                    updateUI()
                    val total = musicService?.playlistManager?.songs?.size ?: 0
                    if (total > 0) {
                        metadataLoadedCount = 0
                        metadataCounterView.text = getString(R.string.files_loaded, 0, total)
                        metadataCounterView.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private val metadataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MusicService.BROADCAST_METADATA_UPDATED) {
                val idx = intent.getIntExtra(MusicService.EXTRA_METADATA_SONG_INDEX, -1)
                if (idx >= 0) {
                    playlistAdapter.invalidateMetadataForSong(idx)
                }
                val svc = musicService ?: return
                if (idx == svc.playlistManager.currentIndex) {
                    updateUI()
                }
                val total = svc.playlistManager.songs.size
                metadataLoadedCount = (metadataLoadedCount + 1).coerceAtMost(total)
                if (metadataLoadedCount >= total) {
                    metadataCounterView.visibility = View.GONE
                } else {
                    metadataCounterView.text = getString(R.string.files_loaded, metadataLoadedCount, total)
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

        val wizardPanelView = findViewById<View>(R.id.wizard_panel)
        ViewCompat.setOnApplyWindowInsetsListener(wizardPanelView) { view, insets ->
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
        presetManager = RemotePresetManager(getSharedPreferences("androsnd_prefs", Context.MODE_PRIVATE))
        activePreset = presetManager.getActivePreset()
        setupSettingsPanel()
        setupWizardPanel()
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

        LocalBroadcastManager.getInstance(this).registerReceiver(
            metadataReceiver,
            IntentFilter(MusicService.BROADCAST_METADATA_UPDATED)
        )
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
        metadataCounterView = findViewById(R.id.metadata_counter)
        btnPlay = findViewById(R.id.btn_play)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        btnStop = findViewById(R.id.btn_stop)
        btnShuffle = findViewById(R.id.btn_shuffle)
        btnSettings = findViewById(R.id.btn_settings)
        contentArea = findViewById(R.id.content_area)
        settingsPanel = findViewById(R.id.settings_panel)
        settingsButtonStrokeWidth = btnSettings.strokeWidth
        volDisplay = findViewById(R.id.vol_display)

        wizardPanel             = findViewById(R.id.wizard_panel)
        wizardCapturePresetName = wizardPanel.findViewById(R.id.wizard_capture_preset_name)
        wizardCaptureProgress   = wizardPanel.findViewById(R.id.wizard_capture_progress)
        wizardCaptureAction     = wizardPanel.findViewById(R.id.wizard_capture_action)
        wizardCaptureCurrent    = wizardPanel.findViewById(R.id.wizard_capture_current)
        wizardSkipButton        = wizardPanel.findViewById(R.id.btn_wizard_skip)

        loadAccentColor()
    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            onFolderClick = { /* toggle expand */ },
            onSongClick = { index ->
                musicService?.playSongAtIndex(index)
            },
            extractMetadata = { song -> musicService?.extractMetadata(song) }
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

        if (settingsVisible || wizardVisible) {
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
        volDisplay.text = "Vol: ${sliderVolume.value.toInt()}%"
        sliderVolume.addOnChangeListener { _, value, _ ->
            val vol = value.toInt()
            labelVolume.text = "${vol}%"
            volDisplay.text = "Vol: ${vol}%"
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

        // Apply accent color to settings controls
        applyAccentToSettingsControls()

        // Select Folder
        settingsPanel.findViewById<MaterialButton>(R.id.btn_folder).setOnClickListener {
            openFolderPicker()
        }

        // Remote Control: preset toggle + map button
        val toggleRemote = settingsPanel.findViewById<MaterialButtonToggleGroup>(R.id.toggle_remote_preset)
        toggleRemote.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val useCustom = checkedId == R.id.btn_preset_custom
                presetManager.setCustomActive(useCustom)
                activePreset = presetManager.getActivePreset()
                updateMapRemoteButton()
                updateKeyAssignmentTable()
            }
        }
        toggleRemote.check(
            if (presetManager.isCustomActive()) R.id.btn_preset_custom else R.id.btn_preset_dmd
        )
        settingsPanel.findViewById<MaterialButton>(R.id.btn_map_remote).setOnClickListener {
            startRemoteCapture()
        }

        // Show Demo Popup toggle
        val toggleDemo = settingsPanel.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.toggle_demo_popup)
        toggleDemo.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                musicService?.showOverlayDemo()
            } else {
                musicService?.dismissOverlayDemo()
            }
        }

        updateFocusVisual()
    }

    private fun toggleSettings() {
        settingsVisible = !settingsVisible
        if (settingsVisible) {
            contentArea.visibility = View.GONE
            settingsPanel.visibility = View.VISIBLE
        } else {
            settingsPanel.visibility = View.GONE
            contentArea.visibility = View.VISIBLE
            val toggleDemo = settingsPanel.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.toggle_demo_popup)
            if (toggleDemo.isChecked) {
                musicService?.dismissOverlayDemo()
                toggleDemo.isChecked = false
            }
            navZone = NavZone.Playlist
        }
        updateButtonStates()
        updateFocusVisual()
    }

    private fun applyAccentToSettingsControls() {
        val accentCSL = ColorStateList.valueOf(accentColor)
        listOf(R.id.slider_volume, R.id.slider_opacity, R.id.slider_overlay_size)
            .mapNotNull { settingsPanel.findViewById<Slider>(it) }
            .forEach { slider ->
                slider.trackActiveTintList = accentCSL
                slider.thumbTintList = accentCSL
            }
        listOf(R.id.label_volume, R.id.label_opacity, R.id.label_overlay_size)
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
        settingsPanel.findViewById<MaterialButton>(R.id.btn_map_remote)?.strokeColor = accentCSL
        listOf(R.id.btn_preset_dmd, R.id.btn_preset_custom)
            .mapNotNull { settingsPanel.findViewById<MaterialButton>(it) }
            .forEach { it.strokeColor = accentCSL }
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

    // ── Remote control navigation ─────────────────────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // ── Wizard key-capture mode: intercept ALL keys ───────────────────────
        if (isCapturingKey) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
                    cancelCaptureSession()
                } else {
                    recordCapturedKey(event.keyCode)
                }
            }
            return true
        }

        // ── Reveal focus frame on first remote key press ──────────────────────
        if (!hasReceivedRemoteKey) {
            hasReceivedRemoteKey = true
            updateFocusVisual()
        }

        // ── Route through active preset key mappings ──────────────────────────
        val action = presetActionFor(event.keyCode)

        // Actions 0/1: Volume Up/Down — global, fixed-interval repeat
        if (action == 0 || action == 1) {
            val delta = if (action == 0) 5f else -5f
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                    adjustSlider(R.id.slider_volume, delta)
                    startFixedRepeat { adjustSlider(R.id.slider_volume, delta) }
                }
                KeyEvent.ACTION_UP -> cancelFixedRepeat()
            }
            return true
        }
        // Action 2: Back / Play-Pause
        if (action == 2) return handleEscape(event)

        // Actions 3/4: Up/Down — accelerating repeat
        val udAction: (() -> Boolean)? = when (action) {
            3 -> ::handleUp
            4 -> ::handleDown
            else -> null
        }
        if (udAction != null) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                    udAction()
                    startNavRepeat(udAction)
                }
                KeyEvent.ACTION_UP -> cancelNavRepeat()
            }
            return true
        }

        // Actions 5/6: Left/Right — fixed-interval repeat (seeking)
        if (action == 5 || action == 6) {
            val lrAction: () -> Boolean = if (action == 5) ::handleLeft else ::handleRight
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                    lrAction()
                    startFixedRepeat { lrAction() }
                }
                KeyEvent.ACTION_UP -> cancelFixedRepeat()
            }
            return true
        }

        // Action 7: Confirm — no View needs raw key events, so always consume
        // Also keep DPAD_CENTER and BUTTON_A as universal confirm keys regardless of preset
        if (action == 7 ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_A) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) handleConfirm()
            return true
        }

        return true  // consume any other unrecognised keys
    }

    /**
     * Returns the action index (0-7) if [keycode] is mapped in the active preset,
     * or -1 if not mapped.
     * Actions: 0=VolUp, 1=VolDown, 2=Back/Play, 3=Up, 4=Down, 5=Left, 6=Right, 7=Confirm.
     */
    private fun presetActionFor(keycode: Int): Int =
        activePreset.keycodes.indexOfFirst { it == keycode }

    private fun handleEscape(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP) {
            when {
                wizardVisible   -> closeWizard()
                settingsVisible -> toggleSettings()
                else -> {
                    navZone = when (navZone) {
                        is NavZone.Playlist  -> NavZone.ButtonBar(1)
                        is NavZone.ButtonBar -> NavZone.Playlist
                    }
                    updateFocusVisual()
                }
            }
        }
        return true
    }

    private fun startNavRepeat(action: () -> Boolean) {
        cancelNavRepeat()
        navRepeatStep = 0
        postRepeat(action, 500L)   // initial hold delay before first repeat fires
    }

    private fun postRepeat(action: () -> Boolean, delayMs: Long) {
        val r = Runnable {
            action()
            navRepeatStep++
            val next = when {
                navRepeatStep < 3 -> 300L   // slow
                navRepeatStep < 8 -> 150L   // medium
                else              -> 75L    // fast
            }
            postRepeat(action, next)
        }
        navRepeatRunnable = r
        navRepeatHandler.postDelayed(r, delayMs)
    }

    private fun cancelNavRepeat() {
        navRepeatRunnable?.let { navRepeatHandler.removeCallbacks(it) }
        navRepeatRunnable = null
        navRepeatStep = 0
    }

    private fun startFixedRepeat(action: () -> Unit) {
        cancelFixedRepeat()
        val r = object : Runnable {
            override fun run() {
                action()
                fixedRepeatHandler.postDelayed(this, 150L)
            }
        }
        fixedRepeatRunnable = r
        fixedRepeatHandler.postDelayed(r, 400L)
    }

    private fun cancelFixedRepeat() {
        fixedRepeatRunnable?.let { fixedRepeatHandler.removeCallbacks(it) }
        fixedRepeatRunnable = null
    }

    // Physical back button on other devices mirrors Button Bottom short-press
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        super.onBackPressed()
        when {
            wizardVisible   -> closeWizard()
            settingsVisible -> toggleSettings()
            else            -> musicService?.handlePlayPause()
        }
    }

    private fun handleUp(): Boolean {
        if (navZone is NavZone.Playlist && playlistFocusPos > 0) {
            playlistFocusPos--
            updateFocusVisual()
            playlistRecycler.scrollToPosition(playlistFocusPos)
        }
        return true
    }

    private fun handleDown(): Boolean {
        if (navZone is NavZone.Playlist &&
                playlistFocusPos < playlistAdapter.itemCount - 1) {
            playlistFocusPos++
            updateFocusVisual()
            playlistRecycler.scrollToPosition(playlistFocusPos)
        }
        return true
    }

    private fun handleLeft(): Boolean {
        when (val z = navZone) {
            is NavZone.Playlist  -> seekRelative(-5000)
            is NavZone.ButtonBar -> if (z.idx > 0) {
                navZone = NavZone.ButtonBar(z.idx - 1)
                updateFocusVisual()
            }
        }
        return true
    }

    private fun handleRight(): Boolean {
        when (val z = navZone) {
            is NavZone.Playlist  -> seekRelative(5000)
            is NavZone.ButtonBar -> if (z.idx < navButtons.size - 1) {
                navZone = NavZone.ButtonBar(z.idx + 1)
                updateFocusVisual()
            }
        }
        return true
    }

    private fun handleConfirm(): Boolean {
        when (val z = navZone) {
            is NavZone.Playlist  -> {
                val songIdx = playlistAdapter.getSongIndexAt(playlistFocusPos)
                if (songIdx != null) musicService?.playSongAtIndex(songIdx)
            }
            is NavZone.ButtonBar -> navButtons[z.idx].performClick()
        }
        return true
    }

    private fun seekRelative(deltaMs: Int) {
        val svc = musicService ?: return
        val newPos = (svc.getPosition() + deltaMs).coerceIn(0, svc.getDuration())
        svc.seekTo(newPos)
        progressBar.progress = newPos
        timeCurrentView.text = formatTime(newPos)
    }

    private fun updateRemotePresetToggle() {
        val toggle = settingsPanel.findViewById<MaterialButtonToggleGroup>(R.id.toggle_remote_preset)
            ?: return
        val id = if (presetManager.isCustomActive()) R.id.btn_preset_custom else R.id.btn_preset_dmd
        if (toggle.checkedButtonId != id) toggle.check(id)
    }

    private fun updateMapRemoteButton() {
        val btn = settingsPanel.findViewById<MaterialButton>(R.id.btn_map_remote) ?: return
        val customActive = presetManager.isCustomActive()
        btn.visibility = if (customActive) View.VISIBLE else View.GONE
        btn.isEnabled = customActive
    }

    private fun keyDisplayName(keycode: Int): String =
        if (keycode == KeyEvent.KEYCODE_UNKNOWN) "—"
        else KeyEvent.keyCodeToString(keycode).removePrefix("KEYCODE_")

    private fun updateKeyAssignmentTable() {
        val container = settingsPanel.findViewById<LinearLayout>(R.id.remote_key_table) ?: return
        container.removeAllViews()
        val colorLabel = ContextCompat.getColor(this, R.color.text_secondary)
        val colorValue = ContextCompat.getColor(this, R.color.text_primary)
        for (actionIdx in RemoteKeyPreset.WIZARD_ORDER) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dpToPx(2) }
            }
            val actionView = TextView(this).apply {
                text = RemoteKeyPreset.ACTION_NAMES[actionIdx]
                setTextColor(colorLabel)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val keyView = TextView(this).apply {
                text = keyDisplayName(activePreset.keycodes[actionIdx])
                setTextColor(colorValue)
                textSize = 13f
                gravity = Gravity.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(actionView)
            row.addView(keyView)
            container.addView(row)
        }
    }

    private fun adjustSlider(id: Int, delta: Float) {
        val slider = settingsPanel.findViewById<Slider>(id) ?: return
        if (!slider.isEnabled) return
        slider.value = (slider.value + delta).coerceIn(slider.valueFrom, slider.valueTo)
    }

    // ── Focus visuals ─────────────────────────────────────────────────────────

    private fun updateFocusVisual() {
        if (!hasReceivedRemoteKey) return
        playlistAdapter.focusedPos = -1
        navButtons.forEach { it.foreground = null }

        when (val z = navZone) {
            is NavZone.Playlist  -> playlistAdapter.focusedPos = playlistFocusPos
            is NavZone.ButtonBar -> navButtons[z.idx].foreground = makeFocusRing(dpToPx(8))
        }
    }

    private fun makeFocusRing(cornerRadiusPx: Int): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerRadiusPx.toFloat()
            setStroke(dpToPx(3), accentColor)
            setColor(Color.TRANSPARENT)
        }
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    // ── Key Mapping Wizard ────────────────────────────────────────────────────

    private fun setupWizardPanel() {
        wizardSkipButton.setOnClickListener { skipCurrentAction() }
    }

    private fun startRemoteCapture() {
        settingsPanel.visibility = View.GONE
        contentArea.visibility = View.GONE
        settingsVisible = false
        musicService?.dismissOverlayDemo()

        val template = presetManager.getCustomPreset()
        captureTargetPreset = template
        captureActionIndex  = 0
        captureKeycodes     = template.keycodes.copyOf()
        isCapturingKey      = true

        wizardPanel.visibility = View.VISIBLE
        wizardVisible = true
        updateCaptureScreen()
        updateButtonStates()
    }

    private fun closeWizard() {
        wizardPanel.visibility = View.GONE
        wizardVisible = false
        isCapturingKey = false
        captureTargetPreset = null

        settingsPanel.visibility = View.VISIBLE
        settingsVisible = true
        contentArea.visibility = View.GONE
        musicService?.showOverlayDemo()
        navZone = NavZone.Playlist
        updateKeyAssignmentTable()
        updateFocusVisual()
        updateButtonStates()
    }

    private fun updateCaptureScreen() {
        val target = captureTargetPreset ?: return
        val actionIdx = RemoteKeyPreset.WIZARD_ORDER[captureActionIndex]
        wizardCapturePresetName.text = target.name
        wizardCaptureProgress.text = getString(
            R.string.wizard_action_of,
            captureActionIndex + 1,
            RemoteKeyPreset.ACTION_COUNT
        )
        wizardCaptureAction.text = RemoteKeyPreset.ACTION_NAMES[actionIdx]
        wizardCaptureCurrent.text = getString(
            R.string.wizard_current_key,
            captureKeycodes[actionIdx]
        )
    }

    private fun recordCapturedKey(keycode: Int) {
        captureKeycodes[RemoteKeyPreset.WIZARD_ORDER[captureActionIndex]] = keycode
        captureActionIndex++

        if (captureActionIndex >= RemoteKeyPreset.ACTION_COUNT) {
            // All 8 actions mapped — save as the single custom preset
            val saved = RemoteKeyPreset("Custom", captureKeycodes.copyOf())
            presetManager.saveCustomPreset(saved)
            presetManager.setCustomActive(true)
            activePreset = saved
            isCapturingKey = false
            captureTargetPreset = null

            Toast.makeText(this, getString(R.string.wizard_mapping_saved), Toast.LENGTH_SHORT).show()
            closeWizard()
        } else {
            updateCaptureScreen()
        }
    }

    private fun skipCurrentAction() {
        captureActionIndex++
        if (captureActionIndex >= RemoteKeyPreset.ACTION_COUNT) {
            val saved = RemoteKeyPreset("Custom", captureKeycodes.copyOf())
            presetManager.saveCustomPreset(saved)
            presetManager.setCustomActive(true)
            activePreset = saved
            isCapturingKey = false
            captureTargetPreset = null
            Toast.makeText(this, getString(R.string.wizard_mapping_saved), Toast.LENGTH_SHORT).show()
            closeWizard()
        } else {
            updateCaptureScreen()
        }
    }

    private fun cancelCaptureSession() {
        isCapturingKey = false
        captureTargetPreset = null
        closeWizard()
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelNavRepeat()
        musicService?.setOnOverlayScaleChangedListener(null)
        musicService?.dismissOverlayDemo()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(scanReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(metadataReceiver)
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    class PlaylistAdapter(
        private val onFolderClick: (Int) -> Unit,
        private val onSongClick: (Int) -> Unit,
        private val extractMetadata: (Song) -> SongMetadata?
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
        private var songs: List<Song> = emptyList()
        private val metadataCache = SparseArray<SongMetadata>()
        private val executor = Executors.newCachedThreadPool()
        private val mainHandler = Handler(Looper.getMainLooper())
        var accentColor: Int = Color.parseColor("#F57C00")
        var focusedPos: Int = -1
            set(newPos) {
                val old = field
                field = newPos
                if (old >= 0 && old < itemCount) notifyItemChanged(old)
                if (newPos >= 0 && newPos < itemCount) notifyItemChanged(newPos)
            }

        fun getSongIndexAt(pos: Int): Int? {
            val item = items.getOrNull(pos) ?: return null
            return if (item.type == TYPE_SONG) item.songIndex else null
        }

        fun nearestFolderPos(from: Int, forward: Boolean): Int {
            val range = if (forward) (from + 1 until items.size) else (from - 1 downTo 0)
            for (i in range) if (items[i].type == TYPE_FOLDER) return i
            return from  // no adjacent folder found
        }

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
            this.songs = songs
            metadataCache.clear()
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

        fun invalidateMetadataForSong(songIndex: Int) {
            metadataCache.remove(songIndex)
            val pos = items.indexOfFirst { it.type == TYPE_SONG && it.songIndex == songIndex }
            if (pos >= 0) notifyItemChanged(pos)
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
            val isFocused = position == focusedPos
            when (holder) {
                is FolderViewHolder -> {
                    holder.name.text = item.displayName
                    holder.itemView.foreground = if (isFocused) makeFocusRingFor(holder.itemView) else null
                    holder.itemView.setOnClickListener { onFolderClick(item.folderIndex) }
                }
                is SongViewHolder -> {
                    val cached = metadataCache[item.songIndex]
                    if (cached != null) {
                        bindSongMetadata(holder, cached)
                    } else {
                        holder.name.text = item.displayName
                        holder.subtitle.text = ""
                        holder.cover.setImageBitmap(null)
                        val song = songs.getOrNull(item.songIndex)
                        if (song != null) {
                            val idx = item.songIndex
                            executor.execute {
                                val meta = extractMetadata(song)
                                    ?: SongMetadata(song.displayName, "", "", 0L, null)
                                mainHandler.post {
                                    metadataCache.put(idx, meta)
                                    val pos = items.indexOfFirst { it.songIndex == idx }
                                    if (pos >= 0) notifyItemChanged(pos)
                                }
                            }
                        }
                    }
                    val isSelected = item.songIndex == currentSongIndex
                    holder.itemView.isSelected = isSelected
                    holder.itemView.setBackgroundColor(when {
                        isSelected -> accentColor
                        isFocused  -> Color.argb(77, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                        else       -> Color.TRANSPARENT
                    })
                    holder.itemView.foreground = if (isFocused) makeFocusRingFor(holder.itemView) else null
                    holder.itemView.setOnClickListener { onSongClick(item.songIndex) }
                }
            }
        }

        private fun bindSongMetadata(holder: SongViewHolder, meta: SongMetadata) {
            holder.name.text = if (meta.artist.isNotEmpty()) "${meta.artist} - ${meta.title}" else meta.title
            val dur = formatDuration(meta.duration)
            holder.subtitle.text = listOfNotNull(
                meta.album.takeIf { it.isNotEmpty() },
                dur.takeIf { it.isNotEmpty() }
            ).joinToString("  •  ")
            holder.cover.setImageBitmap(meta.coverArt)
        }

        private fun formatDuration(ms: Long): String {
            if (ms <= 0L) return ""
            val totalSec = ms / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            return "%d:%02d".format(min, sec)
        }

        private fun makeFocusRingFor(view: View): GradientDrawable {
            val dp = view.resources.displayMetrics.density
            return GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * dp
                setStroke((3f * dp + 0.5f).toInt(), accentColor)
                setColor(Color.TRANSPARENT)
            }
        }

        class FolderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.folder_name)
        }

        class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.song_name)
            val subtitle: TextView = view.findViewById(R.id.song_subtitle)
            val cover: ImageView = view.findViewById(R.id.song_cover)
        }
    }
}
