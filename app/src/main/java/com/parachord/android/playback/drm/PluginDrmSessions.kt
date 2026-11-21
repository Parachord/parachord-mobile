package com.parachord.android.playback.drm

import android.os.SystemClock
import com.parachord.shared.platform.Log
import com.parachord.shared.playback.drm.DrmFamilyTable
import com.parachord.shared.playback.drm.PlaybackQualitySettings
import com.parachord.shared.playback.drm.PluginDrmError
import com.parachord.shared.playback.drm.PluginDrmExtras
import com.parachord.shared.playback.drm.PluginPlayerEventTracker
import com.parachord.shared.plugin.PluginManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.ConcurrentHashMap

/** Debug-aid log tag for the plugin Widevine engine (`adb logcat -s PluginDrm`). Never logs content. */
internal const val DRM_TAG = "PluginDrm"

/**
 * The JS side of the plugin-supplied Widevine path (the bootstrap's
 * `__pcDrm`), behind an interface so the engine can be tested with a fake.
 * Every answer is the raw reply JSON, or null with no answer.
 */
interface PluginDrmJs {
    suspend fun open(resolverId: String, trackJson: String, budgetMs: Long): String?
    suspend fun license(sessionId: String, challengeB64: String, budgetMs: Long): String?
    suspend fun event(sessionId: String, eventJson: String)
    suspend fun close(sessionId: String)
}

/** [PluginDrmJs] through [PluginManager]'s unique-key poll. */
class PluginManagerDrmJs(private val pluginManager: PluginManager) : PluginDrmJs {
    override suspend fun open(resolverId: String, trackJson: String, budgetMs: Long): String? =
        pluginManager.openDrmSession(resolverId, trackJson, budgetMs)

    override suspend fun license(sessionId: String, challengeB64: String, budgetMs: Long): String? =
        pluginManager.relayDrmLicense(sessionId, challengeB64, budgetMs)

    override suspend fun event(sessionId: String, eventJson: String) =
        pluginManager.sendDrmPlayerEvent(sessionId, eventJson)

    override suspend fun close(sessionId: String) = pluginManager.closeDrmSession(sessionId)
}

/** The `parachord-drm://<sessionId>` URI a plugin DRM [androidx.media3.common.MediaItem] carries. */
object PluginDrmUris {
    const val SCHEME = "parachord-drm"
    private const val PREFIX = "$SCHEME://"
    private val SESSION_ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun uriFor(sessionId: String): String = PREFIX + sessionId

    /** The session a URI names, or null for any other URI (local files, streams, the silence loop). */
    fun sessionIdOf(uri: String?): String? {
        if (uri == null || !uri.startsWith(PREFIX)) return null
        return uri.removePrefix(PREFIX).takeIf { SESSION_ID.matches(it) }
    }
}

/**
 * The open plugin DRM sessions (process singleton): session id → the
 * manifest text, the resolver, the quality settings read at the play's start
 * and what the telemetry needs. Opened by [PluginDrmHandler] before the play;
 * read by the media source factory, the licence callback and the track
 * selector, which can't receive callbacks through a MediaItem (it crosses the
 * MediaController). A session closes when the player leaves it, or when a
 * newer one opens while it never reached the player.
 *
 * Also the one codec-family table ([families]), an account fact cleared on
 * sign-out.
 */
class PluginDrmSessions(
    private val js: PluginDrmJs,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {
    class Session(
        val id: String,
        val resolverId: String,
        val trackId: String,
        val manifest: String,
        val extras: PluginDrmExtras,
        val quality: PlaybackQualitySettings,
        val albumAsin: String?,
        val durationMs: Long?,
        val openedAtMs: Long,
    ) {
        /** The codec family whose key was last asked for (the refused family, if a licence is refused). */
        @Volatile var requestedFamily: String? = null

        /** The last licence refusal, for the controller's error handling. */
        @Volatile var lastLicenseError: PluginDrmError? = null

        internal val tracker = PluginPlayerEventTracker()
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    /** The session the player is on now (set by the telemetry listener). */
    @Volatile var activeOnPlayer: String? = null
        private set

    /** The newest session opened, which the next track selection is for. */
    @Volatile var newest: String? = null
        private set

    val families = DrmFamilyTable()

    // One lane, so a session's events reach the plugin in order and before its close.
    private val lane = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (job in lane) {
                try {
                    job()
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.w(DRM_TAG, "Session work failed: ${e.message?.take(120)}")
                }
            }
        }
    }

    fun get(id: String?): Session? = id?.let { sessions[it] }

    /** Registers [session]; any older session the player isn't on is closed (it never played). */
    fun register(session: Session) {
        sessions[session.id] = session
        newest = session.id
        for (id in sessions.keys.toList()) {
            if (id != session.id && id != activeOnPlayer) close(id, positionMs = 0)
        }
    }

    /**
     * The quality and blocked families for the session the player is on, for
     * the track selector; null while the player is on anything else (a local
     * file, a stream, the silence loop), which keeps ExoPlayer's own choice.
     */
    fun selectionPolicy(): SelectionPolicy? {
        val session = get(activeOnPlayer) ?: return null
        return SelectionPolicy(session.id, session.quality, synchronized(families) { families.blockedFamilies })
    }

    data class SelectionPolicy(val sessionId: String, val quality: PlaybackQualitySettings, val blockedFamilies: Set<String>)

    /** Records a licence refusal for [id], for the controller to read when the play fails. */
    fun recordLicenseFailure(id: String, error: PluginDrmError) {
        get(id)?.lastLicenseError = error
    }

    /**
     * Latches [id]'s refused family from its last licence refusal (a
     * per-content refusal for a known family): true when a new family was
     * dropped, worth one retry without it; false when the retry already ran.
     */
    fun latchRefusedFamily(id: String): Boolean {
        val session = get(id) ?: return false
        val error = session.lastLicenseError ?: return false
        return synchronized(families) { families.recordLicenseRefusal(error, session.requestedFamily) }
    }

    /** Sign-out: the next account's plan may cover the refused families. */
    fun clearPlanBlocks() = synchronized(families) { families.clearPlanBlocks() }

    /** The player moved onto [id] (or off every session, with null). Leaving a session closes it. */
    fun onPlayerSession(id: String?, lastPositionMs: Long) {
        val previous = activeOnPlayer
        if (previous == id) return
        activeOnPlayer = id
        if (previous != null) close(previous, lastPositionMs)
    }

    /** Sends [event] to [id]'s plugin, if it's an event the tracker produced. */
    fun send(id: String, event: JsonObject?) {
        if (event == null) return
        val type = (event["type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        Log.d(DRM_TAG, "Player event $type for $id")
        lane.trySend { js.event(id, event.toString()) }
    }

    /** Emits `stopped` if the session played, then drops the plugin's context. */
    fun close(id: String, positionMs: Long) {
        val session = sessions.remove(id) ?: return
        if (newest == id) newest = null
        session.tracker.onStopped(positionMs)?.let { send(id, it) }
        lane.trySend { js.close(id) }
        Log.d(DRM_TAG, "Session $id closed")
    }

    /** Milliseconds since the session opened, for `play-started`'s `initialDelaySeconds`. */
    fun sinceOpenedMs(session: Session): Long = (clock() - session.openedAtMs).coerceAtLeast(0)

    fun now(): Long = clock()
}
