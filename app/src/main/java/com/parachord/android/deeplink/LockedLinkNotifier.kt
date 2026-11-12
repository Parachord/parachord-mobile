package com.parachord.android.deeplink

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import com.parachord.android.R
import com.parachord.android.ui.MainActivity
import com.parachord.shared.deeplink.DeepLinkAction

/**
 * A play link that arrives with the phone locked can't play: Android treats
 * Parachord as a background app then, so it refuses the playback foreground,
 * cuts the app's network and ignores its audio focus request (seen on a Pixel
 * 9a, Android 17: `AudioHardening focus request … ignored`). A local file sat
 * paused at 0:00.
 *
 * So the link isn't played. A notification says what it would play; tapping it
 * (after unlocking) opens Parachord with the same link, which then plays as
 * any link does. A newer locked link replaces the notification.
 */
class LockedLinkNotifier(private val context: Context) {

    companion object {
        private const val TAG = "LockedLink"
        private const val CHANNEL_ID = "locked_links"
        private const val NOTIFICATION_ID = 4711
    }

    /** The phone is locked (the lock screen is up). */
    fun isLocked(): Boolean =
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

    /** Post "Unlock to play …" for [uri]; tapping it opens the link again. */
    fun notifyUnlockToPlay(uri: Uri, action: DeepLinkAction) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Links to play", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A link that arrived while the phone was locked"
            },
        )
        val open = Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(
            context, NOTIFICATION_ID, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Unlock to play")
            .setContentText(describe(action))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        try {
            nm.notify(NOTIFICATION_ID, notification)
            Log.d(TAG, "Phone locked: asked to unlock to play $uri")
        } catch (e: SecurityException) {
            // Notifications not allowed: nothing to show.
            Log.w(TAG, "Couldn't post the unlock-to-play notification: ${e.message}")
        }
    }

    /** What the link would play, for the notification. */
    internal fun describe(action: DeepLinkAction): String = when (action) {
        is DeepLinkAction.Play -> "${action.title} by ${action.artist}"
        is DeepLinkAction.PlayPlaylist -> action.title?.takeIf { it.isNotBlank() } ?: named(action.input) ?: "A playlist"
        is DeepLinkAction.PlayAlbum -> named(action.input)?.let { "The album $it" } ?: "An album"
        is DeepLinkAction.PlayRadio -> "A radio station"
        is DeepLinkAction.ListenAlong -> "Listen along with ${action.user}"
        else -> "A track"
    }

    /** "Title by Artist" from the link's own fields or its only track; null when it doesn't say. */
    private fun named(input: com.parachord.shared.deeplink.ProtocolPlayInput): String? {
        input.tracks?.singleOrNull()?.let { t ->
            if (t.title.isNotBlank()) return if (t.artist.isNullOrBlank()) t.title else "${t.title} by ${t.artist}"
        }
        val title = input.title?.takeIf { it.isNotBlank() } ?: return null
        return if (input.artist.isNullOrBlank()) title else "$title by ${input.artist}"
    }
}
