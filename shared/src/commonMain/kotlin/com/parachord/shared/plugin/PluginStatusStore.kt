package com.parachord.shared.plugin

import com.parachord.shared.home.PlaybackLimit
import com.parachord.shared.platform.Log
import com.parachord.shared.settings.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * The status-capable plugins' replies (`capabilities.status`, desktop's
 * `pluginStatuses`), for both apps. Each plugin is read through the loader's
 * `getStatus` ([PluginManager.getStatus]); a throw, a timeout or the loader's
 * failed marker is a failed read ([PluginStatusEntry.READ_FAILED]), never a
 * block. Read at app start (once the plugins load), when Settings or a
 * plugin's sheet opens, and after Sign in / Sign out.
 *
 * [notPlaying] is the one "not playing" list (shared [notPlayingIds]): the
 * plan-blocked plugins and the ones whose "Play from" is off. Source selection,
 * badges, the priority numbers and Home read it.
 *
 * After each successful read, a plan change runs [limitTransition] against the
 * resolver's remembered state ([getLimitState] / [setLimitState], desktop's
 * `playback_limit_blocked:<id>`): an upgrade asks the resolver again
 * ([clearResolver] with `sourcesToo = false`: its misses go), and entering a
 * purchases-only plan drops every saved source of it (`sourcesToo = true`).
 * A failed read changes nothing.
 */
class PluginStatusStore(
    /** The ids of the loaded plugins with `capabilities.status`. */
    private val statusPluginIds: suspend () -> List<String>,
    /** One plugin's reply JSON ([PluginManager.getStatus]); null = no status. May throw. */
    private val readStatus: suspend (id: String) -> String?,
    /** A plugin's `signIn` / `signOut` ([PluginManager.callSessionMethod]). May throw. */
    private val callSession: suspend (id: String, method: String, timeoutMs: Long) -> String?,
    private val getPlaybackOff: suspend () -> Set<String>,
    playbackOffFlow: Flow<Set<String>>,
    scope: CoroutineScope,
    /** The resolver's last remembered plan state ([limitStorageKey]); null = never seen. */
    private val getLimitState: suspend (id: String) -> String? = { null },
    private val setLimitState: suspend (id: String, state: String) -> Unit = { _, _ -> },
    /**
     * Clear a resolver's saved sources so it's asked again: its misses, and
     * with `sourcesToo` its found sources as well. The platform's resolver cache.
     */
    private val clearResolver: suspend (id: String, sourcesToo: Boolean) -> Unit = { _, _ -> },
) {
    private val _statuses = MutableStateFlow<Map<String, PluginStatusEntry>>(emptyMap())
    /** Plugin id to its last reply. A plugin not read yet has no entry ("Checking…"). */
    val statuses: StateFlow<Map<String, PluginStatusEntry>> = _statuses.asStateFlow()

    /** The plugins' plan limits ([pluginStatusLimits]: no-verdict replies dropped). */
    val limits: StateFlow<Map<String, PlaybackLimit>> = _statuses
        .map { pluginStatusLimits(it) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    /** The plug-ins that are on but don't play: [notPlayingIds] of the plan blocks and `playback_off_resolvers`. */
    val notPlaying: StateFlow<List<String>> = combine(limits, playbackOffFlow) { l, off ->
        notPlayingIds(planBlockedIds(l), off)
    }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** [notPlaying] as it stands now, reading the switch from storage (not a flow's last value). */
    suspend fun notPlayingNow(): List<String> =
        notPlayingIds(planBlockedIds(pluginStatusLimits(_statuses.value)), getPlaybackOff())

    private val inFlightMutex = Mutex()
    private val inFlight = mutableSetOf<String>()

    /** Reads every status-capable plugin, at once. */
    suspend fun refreshAll() {
        val ids = try { statusPluginIds() } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Couldn't list the status plugins: ${e.message}")
            return
        }
        coroutineScope { ids.map { async { refresh(it) } }.awaitAll() }
    }

    /** Reads one plugin's status; a read already running for it is not doubled. */
    suspend fun refresh(id: String) {
        val start = inFlightMutex.withLock { inFlight.add(id) }
        if (!start) return
        try {
            val entry = try {
                parsePluginStatus(readStatus(id))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // The message only: a plugin error can carry its config.
                Log.w(TAG, "Status read failed for $id: ${e.message?.take(120)}")
                PluginStatusEntry.READ_FAILED
            }
            _statuses.update { if (entry == null) it - id else it + (id to entry) }
            if (entry != null && !entry.readFailed) applyLimitTransition(id, entry.playbackLimit)
        } finally {
            inFlightMutex.withLock { inFlight.remove(id) }
        }
    }

    /** [limitTransition] for one fresh reading, and its effects. Never throws. */
    private suspend fun applyLimitTransition(id: String, limit: PlaybackLimit?) {
        if (limit == null) return
        try {
            val t = limitTransition(getLimitState(id), limit)
            if (t.purge) {
                Log.d(TAG, "$id plays only owned tracks (${limit.planKey}); dropping its saved sources and searching again")
                clearResolver(id, true)
            } else if (t.reresolve) {
                Log.d(TAG, "$id can play now (${limit.planKey}); searching it again")
                clearResolver(id, false)
            }
            t.store?.let { setLimitState(id, it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Plan change for $id not applied: ${e.message?.take(120)}")
        }
    }

    /** What a Sign in / Sign out did, with the words to show. */
    data class SessionResult(val success: Boolean, val message: String)

    /**
     * The plugin's `signIn()`. It waits for the person (up to [SIGN_IN_TIMEOUT_MS]),
     * then the status is read again.
     */
    suspend fun signIn(id: String, name: String): SessionResult {
        val reply = runSession(id, "signIn", SIGN_IN_TIMEOUT_MS)
        refresh(id)
        val signedIn = _statuses.value[id]?.signedIn == true
        return when {
            signedIn -> SessionResult(true, "Signed in to $name")
            reply.error != null -> SessionResult(false, reply.error)
            else -> SessionResult(false, "Could not sign in to $name")
        }
    }

    /** The plugin's `signOut()`, then the status is read again. Desktop's wording. */
    suspend fun signOut(id: String, name: String): SessionResult {
        val reply = runSession(id, "signOut", SIGN_OUT_TIMEOUT_MS)
        refresh(id)
        return if (reply.success) SessionResult(true, "Signed out of $name")
        else SessionResult(false, reply.error ?: "Could not sign out of $name")
    }

    private data class SessionReply(val success: Boolean, val error: String?)

    private suspend fun runSession(id: String, method: String, timeoutMs: Long): SessionReply {
        val raw = try {
            callSession(id, method, timeoutMs)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "$method failed for $id: ${e.message?.take(120)}")
            return SessionReply(false, null)
        }
        val obj = raw?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
        val success = (obj?.get("success") as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
        val error = (obj?.get("error") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
        return SessionReply(success, error)
    }

    companion object {
        private const val TAG = "PluginStatusStore"
        private val json = Json { ignoreUnknownKeys = true }
        /** Sign in waits for the person (desktop's sign-in window has no limit; the bridge's own is 10 min). */
        const val SIGN_IN_TIMEOUT_MS = 10L * 60 * 1000
        const val SIGN_OUT_TIMEOUT_MS = 30_000L

        /** The store over [pluginManager] and [settingsStore], as both apps wire it. */
        fun create(
            pluginManager: PluginManager,
            settingsStore: SettingsStore,
            scope: CoroutineScope,
            clearResolver: suspend (id: String, sourcesToo: Boolean) -> Unit = { _, _ -> },
        ): PluginStatusStore =
            PluginStatusStore(
                statusPluginIds = { pluginManager.ensureInitialized(); pluginManager.statusPlugins().map { it.id } },
                readStatus = { pluginManager.getStatus(it) },
                callSession = { id, method, timeout -> pluginManager.callSessionMethod(id, method, timeout) },
                getPlaybackOff = { settingsStore.getPlaybackOffResolvers() },
                playbackOffFlow = settingsStore.getPlaybackOffResolversFlow(),
                scope = scope,
                getLimitState = { settingsStore.getPlaybackLimitState(it) },
                setLimitState = { id, state -> settingsStore.setPlaybackLimitState(id, state) },
                clearResolver = clearResolver,
            )
    }
}
