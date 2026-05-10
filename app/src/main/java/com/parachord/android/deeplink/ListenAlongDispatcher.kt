package com.parachord.android.deeplink

import com.parachord.shared.deeplink.DeepLinkAction
import com.parachord.shared.deeplink.ProtocolPlayTeardown
import com.parachord.shared.model.Friend
import com.parachord.shared.platform.Log
import com.parachord.shared.repository.FriendsRepository

/**
 * Result of a `parachord://listen-along` dispatch. Mirrors the result
 * shape of [PlayRadioResult] / [ProtocolPlayResult] — the
 * [DeepLinkViewModel] turns these into toasts and emits a
 * [DeepLinkNavEvent.StartListenAlong] for [Started].
 */
sealed class ListenAlongResult {
    /**
     * Found a Friend (saved OR transient). [friend] is the entity to
     * pass to `MainViewModel.startListenAlong`. The toast surface uses
     * `friend.displayName` directly — there's no separate field because
     * the VM never reads anything else.
     */
    data class Started(val friend: Friend) : ListenAlongResult()

    /**
     * Calm "they're not on the air" outcome — user is reachable but
     * has nothing currently playing. Caller surfaces as
     * `"<user> is not currently listening on <service>."`.
     */
    data class NotPlaying(val username: String, val service: String) : ListenAlongResult()

    /** Hard failure — bad service, network error before we got an answer. */
    data class Failed(val reason: String) : ListenAlongResult()
}

/**
 * Orchestrator for `parachord://listen-along?service=…&user=…` (issue
 * #121, Phase 3 Task 7).
 *
 * Strategy:
 *
 * 1. Validate the service against the supported set
 *    (`listenbrainz` | `lastfm`). Anything else returns [ListenAlongResult.Failed].
 * 2. Try a local-friends lookup via
 *    [FriendsRepository.findByServiceAndUsername] (case-insensitive on
 *    username). On hit, run the listen-along handover teardown and
 *    return [ListenAlongResult.Started] with the saved Friend.
 * 3. On miss, fetch the user's now-playing via
 *    [FriendsRepository.fetchTransientFriendNowPlaying]. If they're
 *    actively scrobbling, build a transient Friend (id
 *    `transient:{service}:{user}`, `transient = true`) and return
 *    [ListenAlongResult.Started].
 * 4. If they're not currently listening, return
 *    [ListenAlongResult.NotPlaying] — caller surfaces a calm "not
 *    currently listening" toast rather than an error.
 *
 * **Why a "handover" teardown, not the standard one?** The full
 * [ProtocolPlayTeardown.prepareForNewPlayback] runs three steps:
 * exit-spinoff, stop-listen-along, clear-queue. Using it here would
 * stop a possibly-active listen-along loop right before
 * `MainViewModel.startListenAlong` (which itself starts by calling
 * `stopListenAlong(silent=true)`) — a stop+start race where the
 * dying loop's last poll tick can clobber the new friend's track.
 * [ProtocolPlayTeardown.prepareForListenAlongHandover] runs only steps
 * 1 and 3, leaving the listen-along stop to `startListenAlong`.
 */
class ListenAlongDispatcher(
    private val friendsRepository: FriendsRepository,
    private val teardown: ProtocolPlayTeardown,
) {
    suspend fun dispatch(action: DeepLinkAction.ListenAlong): ListenAlongResult {
        // Validate service. The deeplink parser doesn't gate this — the
        // string from the URL flows through unchanged — so this is the
        // first place where bogus values are caught.
        if (action.service !in SUPPORTED_SERVICES) {
            Log.w(TAG, "Unknown service: ${action.service}")
            return ListenAlongResult.Failed("Unknown service: ${action.service}")
        }

        if (action.user.isBlank()) {
            return ListenAlongResult.Failed("Username is required")
        }

        // 1. Local lookup. Case-insensitive — see
        //    findByServiceAndUsername KDoc.
        val saved = try {
            friendsRepository.findByServiceAndUsername(action.service, action.user)
        } catch (e: Exception) {
            Log.w(TAG, "Local friend lookup failed", e)
            null
        }

        if (saved != null) {
            teardown.prepareForListenAlongHandover()
            return ListenAlongResult.Started(saved)
        }

        // 2. Transient fallback. The repo method returns null on both
        //    "not currently playing" and "API failed" — we treat both
        //    as NotPlaying so the user gets a calm message instead of
        //    a scary error. (A genuinely failed request usually means
        //    the user has zero recent activity anyway, since the API
        //    endpoints we hit don't 5xx for valid users in practice.)
        val transient = try {
            friendsRepository.fetchTransientFriendNowPlaying(action.service, action.user)
        } catch (e: Exception) {
            Log.w(TAG, "Transient now-playing fetch failed", e)
            return ListenAlongResult.Failed("Failed to fetch ${action.user}'s now-playing")
        }

        if (transient == null) {
            return ListenAlongResult.NotPlaying(action.user, action.service)
        }

        teardown.prepareForListenAlongHandover()
        return ListenAlongResult.Started(transient)
    }

    companion object {
        private const val TAG = "ListenAlongDispatcher"
        private val SUPPORTED_SERVICES = setOf("listenbrainz", "lastfm")
    }
}
