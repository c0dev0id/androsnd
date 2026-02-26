package com.androsnd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import com.androsnd.model.SongMetadata

class OverlayToastManager(private val context: Context) {

    companion object {
        private const val TAG = "OverlayToastManager"
        private const val ANIMATION_DURATION_MS = 400L
        private const val DISPLAY_DURATION_MS = 2000L
        private const val OVERLAY_TOP_OFFSET = 100
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var currentView: View? = null
    private var dismissRunnable: Runnable? = null

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
            val view = inflater.inflate(R.layout.overlay_toast, null)

            view.findViewById<TextView>(R.id.overlay_title).text = title
            view.findViewById<TextView>(R.id.overlay_artist).text = artist
            view.findViewById<TextView>(R.id.overlay_album).text = album
            val coverView = view.findViewById<ImageView>(R.id.overlay_cover)
            if (cover != null) {
                coverView.setImageBitmap(cover)
            } else {
                coverView.setImageResource(android.R.drawable.ic_media_play)
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = OVERLAY_TOP_OFFSET
            }

            windowManager.addView(view, params)
            currentView = view

            view.alpha = 0f
            view.animate()
                .alpha(1f)
                .setDuration(ANIMATION_DURATION_MS)
                .withEndAction {
                    dismissRunnable = Runnable {
                        view.animate()
                            .alpha(0f)
                            .setDuration(ANIMATION_DURATION_MS)
                            .withEndAction { removeView(view) }
                            .start()
                    }
                    handler.postDelayed(dismissRunnable!!, DISPLAY_DURATION_MS)
                }
                .start()
        }
    }

    private fun removeView(view: View) {
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove overlay view", e)
        }
        if (currentView == view) currentView = null
    }

    fun dismiss() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        currentView?.let {
            it.animate().cancel()
            removeView(it)
        }
    }
}
