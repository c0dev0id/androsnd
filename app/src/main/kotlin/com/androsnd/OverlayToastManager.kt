package com.androsnd

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.androsnd.model.SongMetadata

class OverlayToastManager(private val context: Context) {

    companion object {
        private const val TAG = "OverlayToastManager"
        private const val ANIMATION_DURATION_MS = 400L
        private const val DISPLAY_DURATION_MS = 2000L
        private const val OVERLAY_TOP_OFFSET = 100
        private const val PREFS_NAME = "androsnd_prefs"
        private const val KEY_OVERLAY_X = "overlay_x"
        private const val KEY_OVERLAY_Y = "overlay_y"
        private const val KEY_OVERLAY_SCALE = "overlay_scale"
        private const val KEY_OVERLAY_CUSTOM_POS = "overlay_custom_pos"
        private const val MIN_OVERLAY_SCALE = 0.5f
        private const val MAX_OVERLAY_SCALE = 3.0f
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var currentView: View? = null
    private var currentParams: WindowManager.LayoutParams? = null
    private var dismissRunnable: Runnable? = null

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var savedX: Int = prefs.getInt(KEY_OVERLAY_X, 0)
    private var savedY: Int = prefs.getInt(KEY_OVERLAY_Y, OVERLAY_TOP_OFFSET)
    private var savedScale: Float = prefs.getFloat(KEY_OVERLAY_SCALE, 1f)
    private var hasCustomPosition: Boolean = prefs.getBoolean(KEY_OVERLAY_CUSTOM_POS, false)

    private var isDragging = false
    private var naturalWidth = 0
    private var naturalHeight = 0

    fun showSong(metadata: SongMetadata) {
        showOverlay(metadata.title, metadata.artist, metadata.album, metadata.coverArt)
    }

    fun showMessage(message: String) {
        showOverlay(message, "", "", null)
    }

    private fun showOverlay(title: String, artist: String, album: String, cover: Bitmap?) {
        if (!Settings.canDrawOverlays(context)) return

        handler.post {
            dismiss()

            val inflater = LayoutInflater.from(context)
            val content = inflater.inflate(R.layout.overlay_toast, null)

            content.findViewById<TextView>(R.id.overlay_title).text = title
            content.findViewById<TextView>(R.id.overlay_artist).text = artist
            content.findViewById<TextView>(R.id.overlay_album).text = album
            val coverView = content.findViewById<ImageView>(R.id.overlay_cover)
            if (cover != null) {
                coverView.setImageBitmap(cover)
            } else {
                coverView.setImageResource(android.R.drawable.ic_media_play)
            }

            content.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            naturalWidth = content.measuredWidth
            naturalHeight = content.measuredHeight

            content.pivotX = 0f
            content.pivotY = 0f
            content.scaleX = savedScale
            content.scaleY = savedScale

            val container = FrameLayout(context).apply {
                clipChildren = false
                clipToPadding = false
            }
            container.addView(content, FrameLayout.LayoutParams(naturalWidth, naturalHeight))

            val scaledW = (naturalWidth * savedScale).toInt().coerceAtLeast(1)
            val scaledH = (naturalHeight * savedScale).toInt().coerceAtLeast(1)

            val params = WindowManager.LayoutParams(
                scaledW,
                scaledH,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                if (hasCustomPosition) {
                    gravity = Gravity.TOP or Gravity.START
                    x = savedX
                    y = savedY
                } else {
                    gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                    y = OVERLAY_TOP_OFFSET
                }
            }

            windowManager.addView(container, params)
            currentView = container
            currentParams = params

            setupTouchHandling(container, content, params)

            container.alpha = 0f
            container.animate()
                .alpha(1f)
                .setDuration(ANIMATION_DURATION_MS)
                .withEndAction {
                    scheduleDismiss(container)
                }
                .start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchHandling(
        container: View,
        content: View,
        params: WindowManager.LayoutParams
    ) {
        val scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    savedScale = (savedScale * detector.scaleFactor).coerceIn(MIN_OVERLAY_SCALE, MAX_OVERLAY_SCALE)
                    content.scaleX = savedScale
                    content.scaleY = savedScale
                    val scaledW = (naturalWidth * savedScale).toInt().coerceAtLeast(1)
                    val scaledH = (naturalHeight * savedScale).toInt().coerceAtLeast(1)
                    params.width = scaledW
                    params.height = scaledH
                    try {
                        windowManager.updateViewLayout(container, params)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to update layout during scale", e)
                    }
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    savePrefs()
                }
            }
        )

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        container.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = true
                    cancelDismissTimer()

                    if (params.gravity != (Gravity.TOP or Gravity.START)) {
                        val loc = IntArray(2)
                        container.getLocationOnScreen(loc)
                        params.gravity = Gravity.TOP or Gravity.START
                        params.x = loc[0]
                        params.y = loc[1]
                        try {
                            windowManager.updateViewLayout(container, params)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update layout on drag start", e)
                        }
                    }

                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!scaleDetector.isInProgress) {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        try {
                            windowManager.updateViewLayout(container, params)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update layout during drag", e)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isDragging = false
                    savedX = params.x
                    savedY = params.y
                    hasCustomPosition = true
                    savePrefs()
                    scheduleDismiss(container)
                    true
                }
                else -> true
            }
        }
    }

    private fun cancelDismissTimer() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
    }

    private fun scheduleDismiss(view: View) {
        cancelDismissTimer()
        dismissRunnable = Runnable {
            view.alpha = 1f
            view.animate()
                .alpha(0f)
                .setDuration(ANIMATION_DURATION_MS)
                .withEndAction { removeView(view) }
                .start()
        }
        handler.postDelayed(dismissRunnable!!, DISPLAY_DURATION_MS)
    }

    private fun savePrefs() {
        prefs.edit()
            .putInt(KEY_OVERLAY_X, savedX)
            .putInt(KEY_OVERLAY_Y, savedY)
            .putFloat(KEY_OVERLAY_SCALE, savedScale)
            .putBoolean(KEY_OVERLAY_CUSTOM_POS, hasCustomPosition)
            .apply()
    }

    private fun removeView(view: View) {
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove overlay view", e)
        }
        if (currentView == view) {
            currentView = null
            currentParams = null
        }
    }

    fun dismiss() {
        cancelDismissTimer()
        currentView?.let {
            removeView(it)
        }
    }
}
