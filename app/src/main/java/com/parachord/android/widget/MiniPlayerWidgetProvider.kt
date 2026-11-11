package com.parachord.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.View
import com.parachord.shared.platform.Log
import android.widget.RemoteViews
import com.parachord.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.net.URL

/**
 * AppWidgetProvider for the Parachord mini player home screen widget.
 *
 * Shows album art, track title, artist, play/pause, skip previous/next,
 * and a progress bar — matching the in-app mini player style. With nothing
 * playing it shows the Parachord logo in the art slot and placeholder bars
 * where the title and artist go.
 *
 * Button presses are sent as broadcast intents that this receiver handles
 * directly by forwarding media commands to the MediaSession via
 * [MiniPlayerWidgetUpdater].
 */
class MiniPlayerWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "MiniPlayerWidget"

        const val ACTION_PLAY_PAUSE = "com.parachord.android.widget.PLAY_PAUSE"
        const val ACTION_SKIP_NEXT = "com.parachord.android.widget.SKIP_NEXT"
        const val ACTION_SKIP_PREVIOUS = "com.parachord.android.widget.SKIP_PREVIOUS"

        /** Build the RemoteViews for the widget with the given state. */
        fun buildRemoteViews(
            context: Context,
            title: String?,
            artist: String?,
            isPlaying: Boolean,
            progress: Float,
            artworkBitmap: Bitmap? = null,
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_mini_player)

            // Nothing playing: the Parachord logo and placeholder bars
            // instead of a blank cover and "Not playing".
            val idle = title == null
            views.setViewVisibility(R.id.widget_idle_info, if (idle) View.VISIBLE else View.GONE)
            views.setViewVisibility(R.id.widget_track_info, if (idle) View.GONE else View.VISIBLE)

            // Track info
            views.setTextViewText(R.id.widget_title, title ?: "")
            views.setTextViewText(R.id.widget_artist, artist ?: "")

            // Play/pause icon
            views.setImageViewResource(
                R.id.widget_play_pause,
                if (isPlaying) R.drawable.ic_widget_pause else R.drawable.ic_widget_play,
            )

            // Progress bar (0–1000 scale)
            views.setProgressBar(
                R.id.widget_progress,
                1000,
                (progress.coerceIn(0f, 1f) * 1000).toInt(),
                false,
            )

            // Album artwork
            when {
                idle -> views.setImageViewResource(R.id.widget_artwork, R.drawable.widget_art_logo)
                artworkBitmap != null -> views.setImageViewBitmap(R.id.widget_artwork, artworkBitmap)
                else -> views.setImageViewResource(R.id.widget_artwork, R.drawable.widget_art_placeholder)
            }

            // Button intents
            views.setOnClickPendingIntent(
                R.id.widget_play_pause,
                actionPendingIntent(context, ACTION_PLAY_PAUSE),
            )
            views.setOnClickPendingIntent(
                R.id.widget_skip_next,
                actionPendingIntent(context, ACTION_SKIP_NEXT),
            )
            views.setOnClickPendingIntent(
                R.id.widget_skip_previous,
                actionPendingIntent(context, ACTION_SKIP_PREVIOUS),
            )

            // Tap anywhere else opens the app
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launchIntent != null) {
                val launchPi = PendingIntent.getActivity(
                    context, 0, launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
                views.setOnClickPendingIntent(R.id.widget_root, launchPi)
            }

            return views
        }

        private fun actionPendingIntent(context: Context, action: String): PendingIntent {
            val intent = Intent(context, MiniPlayerWidgetProvider::class.java).apply {
                this.action = action
            }
            return PendingIntent.getBroadcast(
                context, action.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // Push current state to all widget instances
        for (id in appWidgetIds) {
            val views = buildRemoteViews(
                context,
                title = MiniPlayerWidgetUpdater.lastTitle,
                artist = MiniPlayerWidgetUpdater.lastArtist,
                isPlaying = MiniPlayerWidgetUpdater.lastIsPlaying,
                progress = MiniPlayerWidgetUpdater.lastProgress,
                artworkBitmap = MiniPlayerWidgetUpdater.lastArtworkBitmap,
            )
            appWidgetManager.updateAppWidget(id, views)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)

        when (intent.action) {
            ACTION_PLAY_PAUSE -> {
                Log.d(TAG, "Widget: play/pause")
                sendMediaAction(context, "play_pause")
            }
            ACTION_SKIP_NEXT -> {
                Log.d(TAG, "Widget: skip next")
                sendMediaAction(context, "skip_next")
            }
            ACTION_SKIP_PREVIOUS -> {
                Log.d(TAG, "Widget: skip previous")
                sendMediaAction(context, "skip_previous")
            }
        }
    }

    /**
     * Send a media action broadcast that [MiniPlayerWidgetUpdater] picks up
     * to forward to the PlaybackController (which has Koin injection).
     */
    private fun sendMediaAction(context: Context, action: String) {
        val intent = Intent("com.parachord.android.WIDGET_MEDIA_ACTION").apply {
            putExtra("action", action)
            setPackage(context.packageName)
        }
        context.sendBroadcast(intent)
    }
}
