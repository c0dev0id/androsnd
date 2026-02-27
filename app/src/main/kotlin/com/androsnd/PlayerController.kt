package com.androsnd

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.MainThread
import com.androsnd.model.Song

class PlayerController(
    private val context: Context,
    private val audioManager: AudioManager
) {

    companion object {
        private const val TAG = "PlayerController"

        val MUSIC_AUDIO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .build()
    }

    interface Listener {
        @MainThread fun onPrepared()
        @MainThread fun onPreparedAndStarted()
        @MainThread fun onResumed()
        @MainThread fun onPaused()
        @MainThread fun onCompleted()
        @MainThread fun onError()
        @MainThread fun onProgressUpdate()
    }

    var listener: Listener? = null

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    var isPlayerPrepared: Boolean = false
        private set

    /** True when a MediaPlayer instance has been created (even if still preparing). */
    val isActive: Boolean get() = mediaPlayer != null

    private var wasPlayingBeforeFocusLoss = false
    private var mediaPlayer: MediaPlayer? = null
    private var audioFocusRequest: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    private var progressRunnable: Runnable? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                wasPlayingBeforeFocusLoss = false
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                wasPlayingBeforeFocusLoss = isPlaying
                pause()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                mediaPlayer?.setVolume(0.3f, 0.3f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                mediaPlayer?.setVolume(1f, 1f)
                if (wasPlayingBeforeFocusLoss) resume()
            }
        }
    }

    /** Prepares [song] and auto-starts playback. Calls [Listener.onPreparedAndStarted] when ready. */
    @MainThread
    fun startSong(song: Song) {
        releasePlayer()
        requestAudioFocus()
        try {
            val mp = createMediaPlayer(song) { player ->
                isPlayerPrepared = true
                player.start()
                isPlaying = true
                startProgressUpdates()
                listener?.onPreparedAndStarted()
            }
            mp.setOnErrorListener { player, what, extra ->
                Log.e(TAG, "MediaPlayer error: what=$what extra=$extra for ${song.displayName}; skipping track")
                handler.post {
                    if (mediaPlayer == player) {
                        releasePlayer()
                        listener?.onError()
                    }
                }
                true // Return true to mark error as handled and prevent onCompletion from also firing
            }
            mediaPlayer = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start playing ${song.displayName}", e)
            releasePlayer()
            listener?.onError()
        }
    }

    /** Prepares [song] without starting playback. Calls [Listener.onPrepared] when ready. */
    @MainThread
    fun prepareSong(song: Song) {
        if (isActive) return
        try {
            val mp = createMediaPlayer(song) { _ ->
                isPlayerPrepared = true
                listener?.onPrepared()
            }
            mediaPlayer = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare ${song.displayName}", e)
            releasePlayer()
        }
    }

    /**
     * Creates and configures a [MediaPlayer] with common settings (audio attributes, data source,
     * wake lock, completion listener). The caller-supplied [onPrepared] callback is invoked when
     * the player finishes preparing — only if the player is still the active instance.
     */
    private fun createMediaPlayer(
        song: Song,
        onPrepared: (MediaPlayer) -> Unit
    ): MediaPlayer {
        val mp = MediaPlayer()
        mp.setAudioAttributes(MUSIC_AUDIO_ATTRIBUTES)
        mp.setDataSource(context, song.uri)
        mp.setOnPreparedListener { player ->
            if (mediaPlayer == player) {
                onPrepared(player)
            }
        }
        mp.setOnCompletionListener { player ->
            if (player == mediaPlayer) {
                listener?.onCompleted()
            }
        }
        mp.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        return mp
    }

    /** Resumes playback if player is prepared but not playing. */
    @MainThread
    fun resume() {
        if (!isPlaying && isPlayerPrepared) {
            mediaPlayer?.start()
            isPlaying = true
            startProgressUpdates()
            listener?.onResumed()
        }
    }

    @MainThread
    fun pause() {
        mediaPlayer?.let {
            if (isPlaying) {
                it.pause()
                isPlaying = false
                stopProgressUpdates()
                listener?.onPaused()
            }
        }
    }

    @MainThread
    fun seekTo(positionMs: Int) {
        if (!isPlayerPrepared) return
        val duration = mediaPlayer?.duration ?: 0
        val clamped = positionMs.coerceIn(0, if (duration > 0) duration else 0)
        mediaPlayer?.seekTo(clamped)
    }

    fun getPosition(): Int = mediaPlayer?.currentPosition ?: 0
    fun getDuration(): Int = mediaPlayer?.duration ?: 0

    /** Releases the MediaPlayer and resets player state. Does not abandon audio focus. */
    fun releasePlayer() {
        mediaPlayer?.setOnCompletionListener(null)
        mediaPlayer?.setOnErrorListener(null)
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
        isPlayerPrepared = false
        stopProgressUpdates()
    }

    /** Fully releases all resources including audio focus. Call from onDestroy. */
    fun release() {
        releasePlayer()
        abandonAudioFocus()
    }

    fun requestAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(MUSIC_AUDIO_ATTRIBUTES)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()
        audioFocusRequest = focusRequest
        audioManager.requestAudioFocus(focusRequest)
    }

    fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    private fun startProgressUpdates() {
        stopProgressUpdates()
        progressRunnable = object : Runnable {
            override fun run() {
                listener?.onProgressUpdate()
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(progressRunnable!!)
    }

    private fun stopProgressUpdates() {
        progressRunnable?.let { handler.removeCallbacks(it) }
        progressRunnable = null
    }
}
