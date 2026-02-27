package com.androsnd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.androsnd.model.SongMetadata

class PlaybackNotificationBuilder(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "androsnd_channel"
        const val NOTIFICATION_ID = 1
    }

    fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    fun build(
        sessionToken: MediaSessionCompat.Token,
        isPlaying: Boolean,
        currentSongName: String?,
        currentMetadata: SongMetadata?
    ): Notification {
        val contentTitle = currentSongName ?: context.getString(R.string.app_name)
        val contentText = currentMetadata?.artist?.takeIf { it.isNotEmpty() }
            ?: context.getString(R.string.app_name)

        val playPauseAction = if (isPlaying) {
            NotificationCompat.Action(
                android.R.drawable.ic_media_pause,
                context.getString(R.string.notification_action_pause),
                createServicePendingIntent(MusicService.ACTION_PAUSE)
            )
        } else {
            NotificationCompat.Action(
                android.R.drawable.ic_media_play,
                context.getString(R.string.notification_action_play),
                createServicePendingIntent(MusicService.ACTION_PLAY)
            )
        }

        val mediaStyle = MediaStyle()
            .setMediaSession(sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        val mainIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(contentTitle)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(mainIntent)
            .addAction(
                android.R.drawable.ic_media_previous, context.getString(R.string.notification_action_previous),
                createServicePendingIntent(MusicService.ACTION_PREVIOUS)
            )
            .addAction(playPauseAction)
            .addAction(
                android.R.drawable.ic_media_next, context.getString(R.string.notification_action_next),
                createServicePendingIntent(MusicService.ACTION_NEXT)
            )
            .setStyle(mediaStyle)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    fun update(
        sessionToken: MediaSessionCompat.Token,
        isPlaying: Boolean,
        currentSongName: String?,
        currentMetadata: SongMetadata?
    ) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, build(sessionToken, isPlaying, currentSongName, currentMetadata))
    }

    private fun createServicePendingIntent(action: String): PendingIntent {
        val intent = Intent(context, MusicService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            context, action.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
