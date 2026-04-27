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
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.GridLayoutManager
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
    private lateinit var timeRemainingView: TextView
    private lateinit var volSlider: Slider
    private lateinit var volLabel: TextView
    private lateinit var playlistRecycler: RecyclerView
    private lateinit var loadingIndicator: View
    private lateinit var btnPlay: MaterialButton
    private lateinit var btnPrev: MaterialButton
    private lateinit var btnNext: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var btnShuffle: MaterialButton
    private lateinit var btnFolder: MaterialButton

    private lateinit var btnSettings: MaterialButton
    private lateinit var contentArea: View
    private lateinit var settingsPanel: View
    private lateinit var folderBrowserPanel: View
    private lateinit var folderGridRecycler: RecyclerView
    private val prefs by lazy { getSharedPreferences("androsnd_prefs", Context.MODE_PRIVATE) }
    private var settingsVisible = false
    private var folderBrowserVisible = false
    private var folderGridFocusPos: Int = 0
    private var settingsButtonStrokeWidth = 0
    private lateinit var loadingText: android.widget.TextView
    private var loadingTotal = 0
    private var loadingCount = 0

    // Remote control navigation state
    private var playlistFocusPos: Int = 0
    private var buttonBarFocusIdx: Int = 1   // 1 = Play button

    // Focus frame is hidden until the first remote key is pressed
    private var hasReceivedRemoteKey = false

    // Suppress duplicate remote key events when the DMD broadcast and
    // dispatchKeyEvent both deliver the same press (per-keycode, ~100 ms window).
    private val lastRemoteDownAtMs = HashMap<Int, Long>()
    private val lastRemoteUpAtMs = HashMap<Int, Long>()
    private val remoteDedupWindowMs = 100L

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

    // Key-repeat state for fixed-interval lever actions (volume)
    private val fixedRepeatHandler = Handler(Looper.getMainLooper())
    private var fixedRepeatRunnable: Runnable? = null

    private val navButtons: List<MaterialButton>
        get() = listOf(btnPrev, btnPlay, btnNext, btnStop, btnShuffle)

    private lateinit var playlistAdapter: PlaylistAdapter
    private lateinit var folderGridAdapter: FolderGridAdapter
    private var lastKnownPlaylistIndex = -1
    private var lastKnownSongCount = -1

    private val folderBrowserCols = 4

    private val accentColor: Int = Color.parseColor("#00B4FF")
    private val inactiveColor: Int = Color.parseColor("#2A2F45")
    private var pendingFolderUri: Uri? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val musicBinder = binder as? MusicService.MusicBinder ?: return
            musicService = musicBinder.getService()
            isBound = true
            val pending = pendingFolderUri
            if (pending != null) {
                pendingFolderUri = null
                musicService?.scanFolderAsync(pending)
                showLoading()
            } else if (musicService?.isScanning == true) {
                showLoading()
            } else {
                updateUI()
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
                    playlistAdapter.submitData(emptyList(), emptyList(), -1)
                    showLoading()
                    loadingText.text = "Scanning..."
                }
                MusicService.BROADCAST_SCAN_PROGRESS -> {
                    val count = intent.getIntExtra(MusicService.EXTRA_SCAN_SONG_COUNT, 0)
                    loadingText.text = "Scanning: $count songs found"
                }
                MusicService.BROADCAST_SCAN_COMPLETED -> {
                    val svc = musicService ?: return
                    val pm = svc.playlistManager
                    loadingTotal = pm.songs.size
                    loadingCount = 0
                    loadingText.text = "Loading 0/$loadingTotal"
                    // Keep spinner visible — enrichment starts now
                    // Guard updatePlaylist() from calling submitData() until enrichment completes
                    lastKnownSongCount = pm.songs.size
                    lastKnownPlaylistIndex = pm.currentIndex
                }
            }
        }
    }

    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val metadataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MusicService.BROADCAST_METADATA_UPDATED) return
            loadingCount++
            loadingText.text = "Loading $loadingCount/$loadingTotal"
            val idx = intent.getIntExtra(MusicService.EXTRA_METADATA_SONG_INDEX, -1)
            val svc = musicService ?: return
            if (idx == svc.playlistManager.currentIndex) updateUI()
        }
    }

    private val enrichmentCompleteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != MusicService.BROADCAST_ENRICHMENT_COMPLETE) return
            val svc = musicService ?: return
            val pm = svc.playlistManager
            hideLoading()
            lastKnownSongCount = pm.songs.size
            lastKnownPlaylistIndex = pm.currentIndex
            playlistAdapter.submitData(pm.folders, pm.songs, pm.currentIndex)
            folderGridAdapter.submitData(pm.folders)
            updateUI()
        }
    }

    private val artReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val folderPath = intent.getStringExtra(MusicService.EXTRA_ART_FOLDER_PATH) ?: return
            playlistAdapter.invalidateArtForFolder(folderPath)
            folderGridAdapter.invalidateArtForFolder(folderPath)
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
            val svc = musicService
            if (svc != null) {
                svc.scanFolderAsync(it)
            } else {
                pendingFolderUri = it
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        updateUI()
    }

    private fun hideNavigationBar() {
        WindowInsetsControllerCompat(window, window.decorView).let { ctrl ->
            ctrl.hide(WindowInsetsCompat.Type.navigationBars())
            ctrl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideNavigationBar()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideNavigationBar()
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
            addAction(MusicService.BROADCAST_SCAN_PROGRESS)
            addAction(MusicService.BROADCAST_SCAN_COMPLETED)
        }
        LocalBroadcastManager.getInstance(this).registerReceiver(scanReceiver, scanFilter)

        LocalBroadcastManager.getInstance(this).registerReceiver(
            metadataReceiver,
            IntentFilter(MusicService.BROADCAST_METADATA_UPDATED)
        )
        LocalBroadcastManager.getInstance(this).registerReceiver(
            enrichmentCompleteReceiver,
            IntentFilter(MusicService.BROADCAST_ENRICHMENT_COMPLETE)
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
        timeRemainingView = findViewById(R.id.time_remaining)
        volSlider = findViewById(R.id.vol_slider)
        volLabel = findViewById(R.id.vol_label)
        playlistRecycler = findViewById(R.id.playlist_recycler)
        loadingIndicator = findViewById(R.id.loading_indicator)
        loadingText = loadingIndicator.findViewById(R.id.loading_text)
        btnPlay = findViewById(R.id.btn_play)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)
        btnStop = findViewById(R.id.btn_stop)
        btnShuffle = findViewById(R.id.btn_shuffle)
        btnFolder = findViewById(R.id.btn_folder)
        btnSettings = findViewById(R.id.btn_settings)
        contentArea = findViewById(R.id.content_area)
        settingsPanel = findViewById(R.id.settings_panel)
        folderBrowserPanel = findViewById(R.id.folder_browser_panel)
        folderGridRecycler = folderBrowserPanel.findViewById(R.id.folder_grid_recycler)
        settingsButtonStrokeWidth = btnSettings.strokeWidth

        // Size the rotated volume slider to span the container's full height
        val volContainer = findViewById<View>(R.id.vol_slider_container)
        volContainer.post {
            val h = volContainer.height
            if (h > 0) {
                val params = volSlider.layoutParams
                params.width = h - 2 * volLabel.height
                volSlider.layoutParams = params
            }
        }

        volSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            setAppVolume(value)
        }

    }

    private fun setupRecyclerView() {
        playlistAdapter = PlaylistAdapter(
            onFolderClick = { folderIndex ->
                val svc = musicService ?: return@PlaylistAdapter
                val firstSong = svc.playlistManager.folders.getOrNull(folderIndex)?.songs?.firstOrNull()
                if (firstSong != null) svc.playSongAtIndex(firstSong)
            },
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

        folderGridAdapter = FolderGridAdapter(
            onClick = { folderIndex -> playFolderAndClose(folderIndex) }
        )
        folderGridAdapter.getArtFile = { folder ->
            musicService?.metadataRepository?.artFileForFolder(folder.path)
        }
        folderGridAdapter.accentColor = accentColor
        folderGridRecycler.layoutManager = GridLayoutManager(this, folderBrowserCols)
        folderGridRecycler.adapter = folderGridAdapter
    }

    private fun setupButtons() {
        btnPlay.setOnClickListener {
            musicService?.handlePlayPause()
        }
        btnPrev.setOnClickListener { musicService?.handlePrevious() }
        btnNext.setOnClickListener { musicService?.handleNext() }
        btnStop.setOnClickListener { musicService?.handleStop() }
        btnShuffle.setOnClickListener { musicService?.handleShuffleButton() }
        btnFolder.setOnClickListener { toggleFolderBrowser() }
        btnSettings.setOnClickListener { toggleSettings() }
    }

    private fun applyAccentColor() {
        val accentCSL = ColorStateList.valueOf(accentColor)

        // Volume slider
        volSlider.trackActiveTintList = accentCSL
        volSlider.thumbTintList = accentCSL
        volLabel.setTextColor(accentColor)

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
        btnShuffle.iconTint = ColorStateList.valueOf(if (isShuffleOn) Color.parseColor("#0B0F1A") else Color.WHITE)

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

        // Volume — init from prefs directly (no settings slider)
        val currentVolume = prefs.getInt("app_volume", 100)
        val initialVol = alignToSliderStep(currentVolume.toFloat(), 0f, 100f, 1f)
        volSlider.value = initialVol
        volLabel.text = "${initialVol.toInt()}%"

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

        // Show Now Playing Overlay toggle
        val toggleShowOverlay = settingsPanel.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.toggle_show_overlay)
        toggleShowOverlay.isChecked = prefs.getBoolean("overlay_enabled", true)
        toggleShowOverlay.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("overlay_enabled", isChecked).apply()
        }

        updateFocusVisual()
    }

    // ── Update checker ────────────────────────────────────────────────────────

    private fun showUpdateDialog() {
        val installedVersion = updateChecker.installedVersion()

        // Show a lightweight "Checking…" dialog while the network call runs
        val checkingDialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_dialog_title)
            .setMessage(R.string.update_checking)
            .setCancelable(false)
            .create()
        checkingDialog.show()

        updateChecker.fetchRelease(
            includePrerelease = updateChecker.isNightlyBuild(),
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
            settingsPanel.visibility = View.VISIBLE
        } else {
            settingsPanel.visibility = View.GONE
            val toggleDemo = settingsPanel.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.toggle_demo_popup)
            if (toggleDemo.isChecked) {
                musicService?.dismissOverlayDemo()
                toggleDemo.isChecked = false
            }
            buttonBarFocusIdx = 1
        }
        updateButtonStates()
        updateFocusVisual()
    }

    private fun applyAccentToSettingsControls() {
        val accentCSL = ColorStateList.valueOf(accentColor)
        listOf(R.id.slider_opacity, R.id.slider_overlay_size)
            .mapNotNull { settingsPanel.findViewById<Slider>(it) }
            .forEach { slider ->
                slider.trackActiveTintList = accentCSL
                slider.thumbTintList = accentCSL
            }
        listOf(R.id.label_opacity, R.id.label_overlay_size)
            .mapNotNull { settingsPanel.findViewById<TextView>(it) }
            .forEach { label ->
                label.setTextColor(accentColor)
            }
        listOf(R.id.btn_folder, R.id.btn_check_update)
            .mapNotNull { settingsPanel.findViewById<MaterialButton>(it) }
            .forEach { btn ->
                btn.strokeColor = accentCSL
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
        if (perms.isNotEmpty()) {
            permissionLauncher.launch(perms.toTypedArray())
        }
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

        val pos = svc.getPosition()
        val dur = svc.getDuration()
        val remaining = if (dur > 0) (dur - pos).coerceAtLeast(0) else 0
        timeRemainingView.text = if (dur > 0) "-${formatTime(remaining)}" else ""

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
        setAppVolume(volSlider.value + delta)
        startFixedRepeat { setAppVolume(volSlider.value + delta) }
    }

    private fun onRemoteKeyDown(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        val prev = lastRemoteDownAtMs[keyCode]
        if (prev != null && now - prev < remoteDedupWindowMs) return
        lastRemoteDownAtMs[keyCode] = now
        if (!hasReceivedRemoteKey) {
            hasReceivedRemoteKey = true
            updateFocusVisual()
        }
        if (folderBrowserVisible) {
            handleFolderBrowserKeyDown(keyCode)
            return
        }
        when (keyCode) {
            KeyEvent.KEYCODE_F6 -> adjustVolume(5f)
            KeyEvent.KEYCODE_F7 -> adjustVolume(-5f)
            // ESCAPE is handled on key-up only (toggling on release avoids state thrash on hold)
            KeyEvent.KEYCODE_DPAD_UP    -> handleUp()
            KeyEvent.KEYCODE_DPAD_DOWN  -> handleDown()
            KeyEvent.KEYCODE_DPAD_LEFT  -> handleLeft()
            KeyEvent.KEYCODE_DPAD_RIGHT -> handleRight()
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A   -> handleConfirm()
        }
    }

    private fun onRemoteKeyUp(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        val prev = lastRemoteUpAtMs[keyCode]
        if (prev != null && now - prev < remoteDedupWindowMs) return
        lastRemoteUpAtMs[keyCode] = now
        when (keyCode) {
            KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F7 -> cancelFixedRepeat()
            KeyEvent.KEYCODE_ESCAPE -> {
                when {
                    folderBrowserVisible -> closeFolderBrowser()
                    settingsVisible -> toggleSettings()
                    else -> moveTaskToBack(true)
                }
            }
        }
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
            folderBrowserVisible -> closeFolderBrowser()
            settingsVisible -> toggleSettings()
            else            -> musicService?.handlePlayPause()
        }
        // Do not call super — this is an always-foreground automotive app;
        // back should never finish the activity.
    }

    private fun handleUp(): Boolean {
        if (playlistFocusPos > 0) {
            playlistFocusPos--
            updateFocusVisual()
            playlistRecycler.scrollToPosition(playlistFocusPos)
        }
        return true
    }

    private fun handleDown(): Boolean {
        if (playlistFocusPos < playlistAdapter.itemCount - 1) {
            playlistFocusPos++
            updateFocusVisual()
            playlistRecycler.scrollToPosition(playlistFocusPos)
        }
        return true
    }

    private fun handleLeft(): Boolean {
        if (buttonBarFocusIdx > 0) {
            buttonBarFocusIdx--
            updateFocusVisual()
        }
        return true
    }

    private fun handleRight(): Boolean {
        if (buttonBarFocusIdx < navButtons.size - 1) {
            buttonBarFocusIdx++
            updateFocusVisual()
        }
        return true
    }

    private fun handleConfirm(): Boolean {
        val btn = navButtons[buttonBarFocusIdx]
        if (btn == btnPlay) {
            val focusedSongIdx = playlistAdapter.getSongIndexAt(playlistFocusPos)
            if (focusedSongIdx != null &&
                    focusedSongIdx != musicService?.playlistManager?.currentIndex) {
                musicService?.playSongAtIndex(focusedSongIdx)
                return true
            }
        }
        btn.performClick()
        return true
    }

    // ── Folder browser ────────────────────────────────────────────────────────

    private fun toggleFolderBrowser() {
        if (folderBrowserVisible) closeFolderBrowser() else openFolderBrowser()
    }

    private fun openFolderBrowser() {
        val pm = musicService?.playlistManager
        val folders = pm?.folders.orEmpty()
        folderGridAdapter.submitData(folders)
        val initial = pm?.let { it.getFolderIndexForSong(it.currentIndex) }?.takeIf { it >= 0 } ?: 0
        folderGridFocusPos = if (folders.isNotEmpty()) initial.coerceIn(0, folders.size - 1) else 0
        folderGridAdapter.focusedPos = folderGridFocusPos
        (folderGridRecycler.layoutManager as? GridLayoutManager)
            ?.scrollToPositionWithOffset(folderGridFocusPos, 0)
        folderBrowserVisible = true
        folderBrowserPanel.visibility = View.VISIBLE
    }

    private fun closeFolderBrowser() {
        folderBrowserVisible = false
        folderBrowserPanel.visibility = View.GONE
        folderGridAdapter.focusedPos = -1
    }

    private fun playFolderAndClose(folderIndex: Int) {
        val svc = musicService ?: return
        val firstSong = svc.playlistManager.folders.getOrNull(folderIndex)?.songs?.firstOrNull()
        if (firstSong != null) svc.playSongAtIndex(firstSong)
        closeFolderBrowser()
    }

    private fun handleFolderBrowserKeyDown(keyCode: Int) {
        val count = folderGridAdapter.itemCount
        if (count == 0) {
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_BUTTON_A -> closeFolderBrowser()
            }
            return
        }
        val pos = folderGridFocusPos.coerceIn(0, count - 1)
        val cols = folderBrowserCols
        val col = pos % cols
        var next = pos
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> if (col > 0) next = pos - 1
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (col < cols - 1 && pos + 1 < count) next = pos + 1
            KeyEvent.KEYCODE_DPAD_UP -> if (pos - cols >= 0) next = pos - cols
            KeyEvent.KEYCODE_DPAD_DOWN -> if (pos + cols < count) next = pos + cols
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                playFolderAndClose(pos)
                return
            }
            KeyEvent.KEYCODE_F6 -> { adjustVolume(5f); return }
            KeyEvent.KEYCODE_F7 -> { adjustVolume(-5f); return }
            else -> return
        }
        if (next != pos) {
            folderGridFocusPos = next
            folderGridAdapter.focusedPos = next
            folderGridRecycler.smoothScrollToPosition(next)
        }
    }

    private fun setAppVolume(vol: Float) {
        val clamped = vol.coerceIn(volSlider.valueFrom, volSlider.valueTo)
        val pct = clamped.toInt()
        prefs.edit().putInt("app_volume", pct).apply()
        volSlider.value = clamped
        volLabel.text = "${pct}%"
        musicService?.applyAppVolume()
    }

    // ── Focus visuals ─────────────────────────────────────────────────────────

    private fun updateFocusVisual() {
        if (!hasReceivedRemoteKey) return
        playlistAdapter.focusedPos = playlistFocusPos
        navButtons.forEachIndexed { idx, btn ->
            btn.foreground = if (idx == buttonBarFocusIdx) makeFocusRing(dpToPx(8)) else null
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
        cancelFixedRepeat()
        playlistAdapter.release()
        folderGridAdapter.release()
        musicService?.setOnOverlayScaleChangedListener(null)
        musicService?.dismissOverlayDemo()
        activityScope.cancel()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(scanReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(metadataReceiver)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(enrichmentCompleteReceiver)
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
        private var folders: List<PlaylistFolder> = emptyList()
        private val songMetadataMap = HashMap<Int, SongMetadata>()
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

        fun getFolderFirstSongAt(pos: Int): Int? {
            val item = items.getOrNull(pos) ?: return null
            if (item.type != TYPE_FOLDER) return null
            return folders.getOrNull(item.folderIndex)?.songs?.firstOrNull()
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
            adapterScope.cancel()
            adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            items.clear()
            songIndexToItemPos.clear()
            this.songs = songs.toList()   // defensive copy — adapter owns its own snapshot
            this.folders = folders.toList()
            songMetadataMap.clear()
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

        fun applyTextMetadata(songIndex: Int, meta: SongMetadata) {
            songMetadataMap[songIndex] = meta
            val pos = items.indexOfFirst { it.type == TYPE_SONG && it.songIndex == songIndex }
            if (pos >= 0) notifyItemChanged(pos)
        }


        fun release() {
            adapterScope.cancel()
        }

        fun invalidateArtForFolder(folderPath: String) {
            val folderIdx = folders.indexOfFirst { it.path == folderPath }
            if (folderIdx < 0) return
            val pos = items.indexOfFirst { it.type == TYPE_FOLDER && it.folderIndex == folderIdx }
            if (pos >= 0) notifyItemChanged(pos)
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
                    val firstSong = folders.getOrNull(item.folderIndex)?.songs?.firstOrNull()?.let { songs.getOrNull(it) }
                    holder.artJob?.cancel()
                    holder.artJob = if (firstSong != null) adapterScope.launch {
                        val bmp = withContext(Dispatchers.IO) {
                            val file = getArtFile?.invoke(firstSong)?.takeIf { it.exists() }
                                ?: return@withContext null
                            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                                inSampleSize = 2
                            })
                        }
                        holder.cover.setImageBitmap(bmp)
                    } else null
                }
                is SongViewHolder -> {
                    val songIndex = item.songIndex
                    val song = songs.getOrNull(songIndex)
                    val cached = songMetadataMap[songIndex]
                    if (cached != null) {
                        bindSongText(holder, cached)
                    } else {
                        holder.name.text = item.displayName
                        holder.duration.text = ""
                        if (song != null) {
                            adapterScope.launch {
                                val meta = withContext(Dispatchers.IO) { getTextMetadata?.invoke(song) }
                                if (meta != null) applyTextMetadata(songIndex, meta)
                            }
                        }
                    }

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
            if (holder is FolderViewHolder) {
                holder.artJob?.cancel()
                (holder.cover.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.recycle()
                holder.cover.setImageDrawable(null)
            }
        }

        private fun bindSongText(holder: SongViewHolder, meta: SongMetadata) {
            holder.name.text = if (meta.artist.isNotEmpty()) "${meta.artist} - ${meta.title}" else meta.title
            holder.duration.text = formatDuration(meta.duration)
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
            val cover: ImageView = view.findViewById(R.id.folder_cover)
            var artJob: Job? = null
        }

        class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.song_name)
            val duration: TextView = view.findViewById(R.id.song_duration)
        }
    }

    class FolderGridAdapter(
        private val onClick: (Int) -> Unit
    ) : RecyclerView.Adapter<FolderGridAdapter.GridViewHolder>() {

        private var folders: List<PlaylistFolder> = emptyList()
        private var adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var getArtFile: ((PlaylistFolder) -> File?)? = null
        var accentColor: Int = Color.parseColor("#00B4FF")
        var focusedPos: Int = -1
            set(newPos) {
                val old = field
                field = newPos
                if (old >= 0 && old < itemCount) notifyItemChanged(old)
                if (newPos >= 0 && newPos < itemCount) notifyItemChanged(newPos)
            }

        fun submitData(folders: List<PlaylistFolder>) {
            adapterScope.cancel()
            adapterScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
            this.folders = folders.toList()
            notifyDataSetChanged()
        }

        fun release() {
            adapterScope.cancel()
        }

        fun invalidateArtForFolder(folderPath: String) {
            val idx = folders.indexOfFirst { it.path == folderPath }
            if (idx >= 0) notifyItemChanged(idx)
        }

        override fun getItemCount(): Int = folders.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GridViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_folder_grid, parent, false)
            return GridViewHolder(view)
        }

        override fun onBindViewHolder(holder: GridViewHolder, position: Int) {
            val folder = folders.getOrNull(position) ?: return
            holder.name.text = folder.name
            val isFocused = position == focusedPos
            holder.itemView.foreground = if (isFocused) makeFocusRingFor(holder.itemView) else null
            holder.itemView.setOnClickListener { onClick(position) }

            holder.artJob?.cancel()
            holder.cover.setImageDrawable(null)
            holder.artJob = adapterScope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    val file = getArtFile?.invoke(folder)?.takeIf { it.exists() }
                        ?: return@withContext null
                    BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                        inSampleSize = 2
                    })
                }
                holder.cover.setImageBitmap(bmp)
            }
        }

        override fun onViewRecycled(holder: GridViewHolder) {
            super.onViewRecycled(holder)
            holder.artJob?.cancel()
            (holder.cover.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.recycle()
            holder.cover.setImageDrawable(null)
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

        class GridViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.folder_grid_name)
            val cover: ImageView = view.findViewById(R.id.folder_grid_cover)
            var artJob: Job? = null
        }
    }
}
