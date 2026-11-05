package com.parachord.shared.share

import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.platform.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** What a playlist share should do with its ListenBrainz link. */
sealed interface PlaylistShareTarget {
    /** The link would 404 for everyone. */
    data object Gone : PlaylistShareTarget

    /** The link would only open for its owner, so ask before making it public. */
    data class Private(val mbid: String) : PlaylistShareTarget

    data class Ok(val mbid: String) : PlaylistShareTarget
}

/**
 * Asks ListenBrainz before a playlist's link is handed out (#527). A port of desktop's
 * `playlistShareTarget` and its prompt (parachord#1265). Pushed mirrors are created
 * private, so without this most shared links wouldn't open for the person they're
 * sent to, and one deleted since the last sync wouldn't open for anyone.
 *
 * Only a clear answer changes anything: being offline, rate limited or slow never
 * stops a share. Mobile has no equivalent of desktop's `remote-deleted` shortcut,
 * since it detaches a dead mirror instead of flagging it (`DeadMirrorReconcile`).
 */
class PlaylistShareCheck(
    private val client: ListenBrainzClient,
    private val statusTimeoutMs: Long = 4_000L,
    private val token: suspend () -> String?,
) {
    suspend fun target(mbid: String): PlaylistShareTarget {
        val token = token()?.takeIf { it.isNotBlank() } ?: return PlaylistShareTarget.Ok(mbid)
        val status = withTimeoutOrNull(statusTimeoutMs) { client.playlistStatus(mbid, token) }
        return when {
            status?.exists == false -> PlaylistShareTarget.Gone
            status?.isPublic == false -> PlaylistShareTarget.Private(mbid)
            else -> PlaylistShareTarget.Ok(mbid)
        }
    }

    /** Makes the playlist public. Returns what went wrong, or null when it worked. */
    suspend fun makePublic(mbid: String): String? {
        val token = token()?.takeIf { it.isNotBlank() } ?: return "Not connected to ListenBrainz"
        return try {
            client.setPlaylistPublic(mbid, isPublic = true, token = token)
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't make $mbid public: ${e.message}")
            e.message ?: "unknown error"
        }
    }

    companion object {
        private const val TAG = "PlaylistShareCheck"

        const val GONE_MESSAGE = "This playlist is no longer on ListenBrainz, so its link would not open."
        const val PRIVATE_TITLE = "Make this playlist public?"
        const val PRIVATE_CONFIRM = "Make Public & Share"

        fun privateMessage(name: String): String =
            "\"$name\" is private on ListenBrainz, so its link would only open for you. If you make it public, " +
                "anyone with the link can open it, and it will appear on your ListenBrainz profile."

        fun makePublicFailed(error: String): String = "Could not make this playlist public: $error"
    }
}
