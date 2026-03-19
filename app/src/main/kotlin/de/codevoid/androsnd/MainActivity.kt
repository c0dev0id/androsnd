package de.codevoid.androsnd

import android.annotation.SuppressLint
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
import android.graphics.BitmapFactory
import de.codevoid.androsnd.model.PlaylistFolder
import de.codevoid.androsnd.model.Song
import de.codevoid.androsnd.model.SongMetadata
import com.google.android.material.button.MaterialButton
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.slider.Slider
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.widget.LinearLayout
import android.view.Gravity
import android.app.AlertDialog
import android.app.DownloadManager
import android.widget.ProgressBar

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

    // Focus frame is hidden until the first remote key is pressed
    private var hasReceivedRemoteKey = false

    // ── andRemote2 broadcast receiver ────────────────────────────────────────
    private val remoteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val press = intent.getIntExtra("key_press", -1)
            val release = intent.getIntExtra("key_release", -1)
            if (press != -1) onRemoteKeyDown(press)
            if (release != -1) onRemoteKeyUp(release)
        }
    }

    private lateinit var updateChecker: UpdateChecker

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

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val metadataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == MusicService.BROADCAST_METADATA_UPDATED) {
                val idx      = intent.getIntExtra(MusicService.EXTRA_METADATA_SONG_INDEX, -1)
                val title    = intent.getStringExtra(MusicService.EXTRA_METADATA_TITLE)   ?: return
                val artist   = intent.getStringExtra(MusicService.EXTRA_METADATA_ARTIST)  ?: ""
                val album    = intent.getStringExtra(MusicService.EXTRA_METADATA_ALBUM)   ?: ""
                val duration = intent.getLongExtra(MusicService.EXTRA_METADATA_DURATION,  0L)
                if (idx >= 0) playlistAdapter.applyTextMetadata(idx, SongMetadata(title, artist, album, duration))
                val svc = musicService ?: return
                if (idx == svc.playlistManager.currentIndex) updateUI()
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

    private val artReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val folderPath = intent.getStringExtra(MusicService.EXTRA_ART_FOLDER_PATH) ?: return
            playlistAdapter.invalidateArtForFolder(folderPath)
            val cur = musicService?.playlistManager?.getCurrentSong() ?: return
            if (cur.folderPath == folderPath) updateNowPlayingArt(folderPath)
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

        updateChecker = UpdateChecker(this)
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

        LocalBroadcastManager.getInstance(this).registerReceiver(
            metadataReceiver,
            IntentFilter(MusicService.BROADCAST_METADATA_UPDATED)
        )
        LocalBroadcastManager.getInstance(this).registerReceiver(
            artReceiver,
            IntentFilter(MusicService.BROADCAST_ART_UPDATED)
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

        loadAccentColor()
    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            onFolderClick = { /* toggle expand */ },
            onSongClick = { index ->
                musicService?.playSongAtIndex(index)
            }
        )
        playlistAdapter.getTextMetadata = { song ->
            musicService?.metadataRepository?.db?.get(song.uri.toString(), song.lastModified)
        }
        playlistAdapter.getArtFile = { song ->
            musicService?.metadataRepository?.artFileForFolder(song.folderPath)
        }
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

        // Check for Updates
        settingsPanel.findViewById<MaterialButton>(R.id.btn_check_update).setOnClickListener {
            showUpdateDialog()
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

    // ── Update checker ────────────────────────────────────────────────────────

    private fun showUpdateDialog() {
        val installedVersion = updateChecker.installedVersion()
        val isDevInstall = updateChecker.isDevVersion()

        // Show a lightweight "Checking…" dialog while the network call runs
        val checkingDialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_dialog_title)
            .setMessage(R.string.update_checking)
            .setCancelable(false)
            .create()
        checkingDialog.show()

        updateChecker.fetchRelease(
            includePrerelease = isDevInstall,
            onResult = { release ->
                checkingDialog.dismiss()
                showUpdateResultDialog(installedVersion, release)
            },
            onError = { errorMsg ->
                checkingDialog.dismiss()
                AlertDialog.Builder(this)
                    .setTitle(R.string.update_dialog_title)
                    .setMessage(getString(R.string.update_error, errorMsg))
                    .setPositiveButton(R.string.update_btn_close, null)
                    .show()
            }
        )
    }

    private fun showUpdateResultDialog(
        installedVersion: String,
        release: UpdateChecker.ReleaseInfo
    ) {
        val availableVersion = release.apkVersion ?: release.tagName
        val hasUpdate = updateChecker.isNewer(availableVersion, installedVersion)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad * 2, pad, pad * 2, pad / 2)
        }

        layout.addView(TextView(this).apply {
            text = if (hasUpdate) getString(R.string.update_available)
                   else getString(R.string.update_no_update)
            textSize = 15f
            setTextColor(if (hasUpdate) accentColor else currentTextColor())
        })

        // Only show installed version if it's meaningful (not the default placeholder)
        if (installedVersion != "1.0" && installedVersion != "dev" && installedVersion != "unknown") {
            layout.addView(TextView(this).apply {
                text = getString(R.string.update_installed_version, installedVersion)
                textSize = 14f
                val topPad = (8 * resources.displayMetrics.density).toInt()
                setPadding(0, topPad, 0, 0)
            })
        }

        layout.addView(TextView(this).apply {
            text = getString(R.string.update_available_version, availableVersion)
            textSize = 14f
        })

        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.update_dialog_title)
            .setView(layout)
            .setCancelable(true)

        if (release.apkDownloadUrl != null) {
            builder.setPositiveButton(R.string.update_btn_update, null)
        }
        builder.setNegativeButton(R.string.update_btn_close, null)

        val dialog = builder.create()

        // Handle the positive button manually to avoid auto-dismiss before we're ready
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                dialog.dismiss()
                showDownloadProgressDialog(release)
            }
        }

        dialog.show()
    }

    private fun currentTextColor(): Int {
        val attrs = intArrayOf(android.R.attr.textColorPrimary)
        val ta = obtainStyledAttributes(attrs)
        val color = ta.getColor(0, android.graphics.Color.WHITE)
        ta.recycle()
        return color
    }

    private fun showDownloadProgressDialog(release: UpdateChecker.ReleaseInfo) {
        val apkUrl = release.apkDownloadUrl ?: return
        val fileName = "androsnd-${release.tagName}.apk"

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad * 2, pad, pad * 2, pad)
        }

        layout.addView(TextView(this).apply {
            text = getString(R.string.update_downloading)
            textSize = 14f
        })

        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            val topPad = (12 * resources.displayMetrics.density).toInt()
            setPadding(0, topPad, 0, 0)
            layout.addView(this)
        }

        val tvPercent = TextView(this).apply {
            text = "0%"
            textSize = 13f
            gravity = Gravity.END
            layout.addView(this)
        }

        var downloadId = -1L
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_dialog_title)
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton(R.string.update_btn_cancel) { _, _ ->
                if (downloadId >= 0) updateChecker.cancelDownload(downloadId)
            }
            .create()

        dialog.show()

        // Check install-unknown-apps permission before starting download
        if (!packageManager.canRequestPackageInstalls()) {
            dialog.dismiss()
            AlertDialog.Builder(this)
                .setTitle(R.string.update_dialog_title)
                .setMessage(R.string.update_install_unavailable)
                .setPositiveButton("Open Settings") { _, _ ->
                    val intent = android.content.Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${packageName}")
                    )
                    startActivity(intent)
                }
                .setNegativeButton(R.string.update_btn_cancel, null)
                .show()
            return
        }

        downloadId = updateChecker.downloadApk(
            url = apkUrl,
            fileName = fileName,
            onProgress = { percent ->
                progressBar.progress = percent
                tvPercent.text = "$percent%"
            },
            onComplete = { id ->
                dialog.dismiss()
                updateChecker.installApk(id)
            },
            onError = { errorMsg ->
                dialog.dismiss()
                AlertDialog.Builder(this)
                    .setTitle(R.string.update_dialog_title)
                    .setMessage(getString(R.string.update_error, errorMsg))
                    .setPositiveButton(R.string.update_btn_close, null)
                    .show()
            }
        )
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
        settingsPanel.findViewById<MaterialButton>(R.id.btn_check_update)?.strokeColor = accentCSL
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
            val metadata = svc.currentTextMetadata
            if (metadata != null) {
                songTitle.text = metadata.title
                songArtist.text = metadata.artist
                songAlbum.text = metadata.album
            }
            updateNowPlayingArt(song.folderPath)
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

    private fun updateNowPlayingArt(folderPath: String) {
        activityScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                musicService?.metadataRepository?.artFileForFolder(folderPath)
                    ?.takeIf { it.exists() }
                    ?.let { BitmapFactory.decodeFile(it.absolutePath) }
            }
            if (bmp != null) coverArt.setImageBitmap(bmp)
            else coverArt.setImageResource(android.R.drawable.ic_media_play)
        }
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
            val maxPos = (playlistAdapter.itemCount - 1).coerceAtLeast(0)
            if (playlistFocusPos > maxPos) playlistFocusPos = maxPos
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

    // ── Remote control key routing (shared by dispatchKeyEvent + broadcast) ──

    private val remoteKeyCodes = setOf(
        KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_ESCAPE,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BUTTON_A
    )

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode !in remoteKeyCodes) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) onRemoteKeyDown(event.keyCode)
            KeyEvent.ACTION_UP -> onRemoteKeyUp(event.keyCode)
        }
        return true
    }

    private fun adjustVolume(delta: Float) {
        adjustSlider(R.id.slider_volume, delta)
        startFixedRepeat { adjustSlider(R.id.slider_volume, delta) }
    }

    private fun onRemoteKeyDown(keyCode: Int) {
        if (!hasReceivedRemoteKey) {
            hasReceivedRemoteKey = true
            updateFocusVisual()
        }
        when (keyCode) {
            KeyEvent.KEYCODE_F6 -> adjustVolume(5f)
            KeyEvent.KEYCODE_F7 -> adjustVolume(-5f)
            // ESCAPE is handled on key-up only (toggling on release avoids state thrash on hold)
            KeyEvent.KEYCODE_DPAD_UP    -> { handleUp(); startNavRepeat(::handleUp) }
            KeyEvent.KEYCODE_DPAD_DOWN  -> { handleDown(); startNavRepeat(::handleDown) }
            KeyEvent.KEYCODE_DPAD_LEFT  -> { handleLeft(); startFixedRepeat { handleLeft() } }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { handleRight(); startFixedRepeat { handleRight() } }
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A   -> handleConfirm()
        }
    }

    private fun onRemoteKeyUp(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F7 -> cancelFixedRepeat()
            KeyEvent.KEYCODE_ESCAPE -> {
                when {
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
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> cancelNavRepeat()
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> cancelFixedRepeat()
        }
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
    @SuppressLint("MissingSuperCall") // Intentional: automotive app, back must never finish the activity
    override fun onBackPressed() {
        when {
            settingsVisible -> toggleSettings()
            else            -> musicService?.handlePlayPause()
        }
        // Do not call super — this is an always-foreground automotive app;
        // back should never finish the activity.
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

    override fun onResume() {
        super.onResume()
        ContextCompat.registerReceiver(
            this, remoteReceiver,
            IntentFilter("com.thorkracing.wireddevices.keypress"),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(remoteReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        cancelNavRepeat()
        playlistAdapter.release()
        musicService?.setOnOverlayScaleChangedListener(null)
        musicService?.dismissOverlayDemo()
        activityScope.cancel()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(scanReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(metadataReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(artReceiver)
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
        private val songIndexToItemPos = HashMap<Int, Int>()
        private var currentSongIndex = -1
        private var songs: List<Song> = emptyList()
        private val metadataCache = android.util.LruCache<Int, SongMetadata>(500)
        private var dataVersion = 0
        private var adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var getTextMetadata: ((Song) -> SongMetadata?)? = null
        var getArtFile: ((Song) -> File?)? = null
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
            dataVersion++
            adapterScope.cancel()
            adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            items.clear()
            songIndexToItemPos.clear()
            this.songs = songs.toList()   // defensive copy — adapter owns its own snapshot
            metadataCache.evictAll()
            currentSongIndex = currentIdx
            for ((fi, folder) in folders.withIndex()) {
                items.add(ListItem(TYPE_FOLDER, folderIndex = fi, displayName = folder.name))
                for (si in folder.songs) {
                    val song = songs.getOrNull(si) ?: continue
                    songIndexToItemPos[si] = items.size
                    items.add(ListItem(TYPE_SONG, songIndex = si, displayName = song.displayName))
                }
            }
            notifyDataSetChanged()
        }

<<<<<<< HEAD
        fun invalidateMetadataForSong(songIndex: Int) {
            metadataCache.remove(songIndex)
            val pos = songIndexToItemPos[songIndex] ?: -1
            if (pos >= 0) notifyItemChanged(pos)
        }

        fun release() {
            executor.shutdown()
=======
        fun applyTextMetadata(songIndex: Int, meta: SongMetadata) {
            metadataCache.put(songIndex, meta)
            val pos = items.indexOfFirst { it.type == TYPE_SONG && it.songIndex == songIndex }
            if (pos >= 0) notifyItemChanged(pos)
        }

        fun invalidateArtForFolder(folderPath: String) {
            items.forEachIndexed { pos, item ->
                if (item.type == TYPE_SONG && songs.getOrNull(item.songIndex)?.folderPath == folderPath)
                    notifyItemChanged(pos)
            }
>>>>>>> 8d8e2c8 (Replace MetadataCache+executors with SQLite+coroutines pipeline)
        }

        override fun getItemViewType(position: Int) = items.getOrNull(position)?.type ?: TYPE_SONG
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
            val item = items.getOrNull(position) ?: return
            val isFocused = position == focusedPos
            when (holder) {
                is FolderViewHolder -> {
                    holder.name.text = item.displayName
                    holder.itemView.foreground = if (isFocused) makeFocusRingFor(holder.itemView) else null
                    holder.itemView.setOnClickListener { onFolderClick(item.folderIndex) }
                }
                is SongViewHolder -> {
                    val songIndex = item.songIndex
                    val song = songs.getOrNull(songIndex)

                    // Text
                    val cached = metadataCache.get(songIndex)
                    if (cached != null) {
                        bindSongText(holder, cached)
                    } else {
                        holder.name.text = item.displayName
                        holder.subtitle.text = ""
                        if (song != null) {
<<<<<<< HEAD
                            val idx = item.songIndex
                            val capturedVersion = dataVersion
                            executor.execute {
                                val meta = extractMetadata(song)
                                    ?: SongMetadata(song.displayName, "", "", 0L, null)
                                mainHandler.post {
                                    if (dataVersion != capturedVersion) return@post
                                    metadataCache.put(idx, meta)
                                    val pos = songIndexToItemPos[idx] ?: -1
                                    if (pos >= 0) notifyItemChanged(pos)
                                }
=======
                            adapterScope.launch {
                                val meta = withContext(Dispatchers.IO) { getTextMetadata?.invoke(song) }
                                if (meta != null) applyTextMetadata(songIndex, meta)
>>>>>>> 8d8e2c8 (Replace MetadataCache+executors with SQLite+coroutines pipeline)
                            }
                        }
                    }

                    // Art — per-ViewHolder job, cancelled on recycle
                    holder.artJob?.cancel()
                    holder.artJob = if (song != null) adapterScope.launch {
                        val bmp = withContext(Dispatchers.IO) {
                            val file = getArtFile?.invoke(song)?.takeIf { it.exists() }
                                ?: return@withContext null
                            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                                inSampleSize = 2  // 256px stored → ~128px display
                            })
                        }
                        holder.cover.setImageBitmap(bmp)
                    } else null

                    val isSelected = songIndex == currentSongIndex
                    holder.itemView.isSelected = isSelected
                    holder.itemView.setBackgroundColor(when {
                        isSelected -> accentColor
                        isFocused  -> Color.argb(77, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
                        else       -> Color.TRANSPARENT
                    })
                    holder.itemView.foreground = if (isFocused) makeFocusRingFor(holder.itemView) else null
                    holder.itemView.setOnClickListener { onSongClick(songIndex) }
                }
            }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            super.onViewRecycled(holder)
            (holder as? SongViewHolder)?.artJob?.cancel()
        }

        private fun bindSongText(holder: SongViewHolder, meta: SongMetadata) {
            holder.name.text = if (meta.artist.isNotEmpty()) "${meta.artist} - ${meta.title}" else meta.title
            val dur = formatDuration(meta.duration)
            holder.subtitle.text = listOfNotNull(
                meta.album.takeIf { it.isNotEmpty() },
                dur.takeIf { it.isNotEmpty() }
            ).joinToString("  •  ")
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
            var artJob: Job? = null
        }
    }
}
