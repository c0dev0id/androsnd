package com.androsnd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.MediaMetadataRetriever
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ImageView
import android.widget.TextView
import com.androsnd.model.Song

class OverlayToastManager(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())
    private var currentView: View? = null
    private var dismissRunnable: Runnable? = null

    fun showSong(song: Song, cover: Bitmap?) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, song.uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: song.displayName
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: ""
            val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: ""
            showOverlay(title, artist, album, cover)
        } catch (e: Exception) {
            showOverlay(song.displayName, "", "", cover)
        } finally {
            retriever.release()
        }
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
                y = 100
            }

            windowManager.addView(view, params)
            currentView = view

            val fadeIn = AlphaAnimation(0f, 1f).apply { duration = 400 }
            view.startAnimation(fadeIn)

            dismissRunnable = Runnable {
                val fadeOut = AlphaAnimation(1f, 0f).apply {
                    duration = 400
                    setAnimationListener(object : Animation.AnimationListener {
                        override fun onAnimationStart(a: Animation?) {}
                        override fun onAnimationRepeat(a: Animation?) {}
                        override fun onAnimationEnd(a: Animation?) { removeView(view) }
                    })
                }
                view.startAnimation(fadeOut)
            }
            handler.postDelayed(dismissRunnable!!, 2000)
        }
    }

    private fun removeView(view: View) {
        try {
            windowManager.removeView(view)
        } catch (_: Exception) {}
        if (currentView == view) currentView = null
    }

    fun dismiss() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        currentView?.let { removeView(it) }
    }
}
