package com.parachord.shared.repository

import com.parachord.shared.api.AchordionClient
import com.parachord.shared.api.Announcement
import com.parachord.shared.platform.Log
import com.parachord.shared.platform.currentTimeMillis
import com.parachord.shared.store.KvStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Repository for Achordion's announcements feed.
 *
 * The server hosts a validated list at `/api/announcements`; clients fetch the
 * full feed and apply local filters (surface match, semver range, expiry,
 * dismissal). Visible announcements flow out via [visibleAnnouncements] for
 * Compose collection on the home screen.
 *
 * **Cadence:** [refreshNow] from `ParachordApplication.onCreate` (cold start),
 * [refreshIfStale] from `MainActivity.onResume` gated to a 6-hour minimum
 * between successful fetches. The gate is persisted in [KvStore] so it
 * survives process kills.
 *
 * **Dismissal:** persistent — once dismissed, an announcement stays dismissed
 * until it's removed from the server feed (or the user reinstalls the app).
 * Backed by a CSV-encoded set in [KvStore] under [KEY_DISMISSED_IDS] since
 * [KvStore] doesn't expose a native string-set API.
 *
 * **Telemetry:** `view` fires once per session per id (in-memory dedup);
 * `dismiss` and `cta-click` fire every time. All telemetry is fire-and-forget
 * via the internal supervised scope — failures are swallowed inside
 * [AchordionClient.trackAnnouncementEvent].
 */
class AnnouncementsRepository(
    private val achordionClient: AchordionClient,
    private val kvStore: KvStore,
    private val appVersion: String,
) {
    companion object {
        private const val TAG = "AnnouncementsRepo"
        const val KEY_LAST_FETCHED_MS = "announcements_last_fetched_ms"
        const val KEY_DISMISSED_IDS = "announcements_dismissed_ids"
        const val REFRESH_GATE_MS = 6L * 60 * 60 * 1000 // 6h
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fetchMutex = Mutex()
    private val viewedThisSession = mutableSetOf<String>()
    private val viewedMutex = Mutex()

    private val _visibleAnnouncements = MutableStateFlow<List<Announcement>>(emptyList())
    val visibleAnnouncements: StateFlow<List<Announcement>> = _visibleAnnouncements.asStateFlow()

    /** Always fetches — bypasses the 6h staleness gate. Used on cold start. */
    suspend fun refreshNow() {
        fetchAndPublish()
    }

    /** Fetches only if the last successful fetch was ≥ [REFRESH_GATE_MS] ago. */
    suspend fun refreshIfStale() {
        val lastFetched = kvStore.getLong(KEY_LAST_FETCHED_MS, 0L)
        if (currentTimeMillis() - lastFetched < REFRESH_GATE_MS) return
        fetchAndPublish()
    }

    /**
     * Mark an announcement as dismissed. Persists to KvStore, removes from the
     * visible list, and fires the dismiss telemetry event.
     */
    suspend fun dismiss(id: String) {
        val dismissed = readDismissed().toMutableSet()
        if (dismissed.add(id)) {
            writeDismissed(dismissed)
        }
        _visibleAnnouncements.value = _visibleAnnouncements.value.filter { it.id != id }
        scope.launch { achordionClient.trackAnnouncementEvent(id, "dismiss") }
    }

    /** Fire a view event once per session per id. Safe to call repeatedly. */
    fun trackView(id: String) {
        scope.launch {
            val already = viewedMutex.withLock {
                if (id in viewedThisSession) {
                    true
                } else {
                    viewedThisSession.add(id)
                    false
                }
            }
            if (!already) achordionClient.trackAnnouncementEvent(id, "view")
        }
    }

    /** Fire a cta-click event. */
    fun trackCtaClick(id: String) {
        scope.launch { achordionClient.trackAnnouncementEvent(id, "cta-click") }
    }

    private suspend fun fetchAndPublish() = fetchMutex.withLock {
        val raw = achordionClient.listAnnouncements()
        val filtered = filterForClient(raw)
        _visibleAnnouncements.value = filtered
        kvStore.setLong(KEY_LAST_FETCHED_MS, currentTimeMillis())
        Log.d(TAG, "Fetched ${raw.size} announcements, ${filtered.size} visible after filter")
    }

    /**
     * Filter the server feed to what's visible on this client right now.
     *
     * 1. Surface match — null/empty surfaces means "all"; otherwise must contain "parachord".
     * 2. Semver range — fail-open on unparseable bounds (don't crash on bad input).
     * 3. Not expired — `expiresAt` ISO-8601 in the past → filtered out.
     * 4. Not dismissed — id in [KEY_DISMISSED_IDS] CSV → filtered out.
     */
    suspend fun filterForClient(items: List<Announcement>): List<Announcement> {
        val now = Clock.System.now()
        val dismissed = readDismissed()
        return items.filter { item ->
            // 1. Surface match
            val surfaces = item.surfaces
            if (!(surfaces.isNullOrEmpty() || "parachord" in surfaces)) return@filter false

            // 2. Version range (fail-open on unparseable)
            if (item.minVersion != null) {
                val cmp = compareSemverOrNull(appVersion, item.minVersion)
                if (cmp != null && cmp < 0) return@filter false
            }
            if (item.maxVersion != null) {
                val cmp = compareSemverOrNull(appVersion, item.maxVersion)
                if (cmp != null && cmp > 0) return@filter false
            }

            // 3. Expiry
            if (item.expiresAt != null) {
                val expiry = runCatching { Instant.parse(item.expiresAt) }.getOrNull()
                if (expiry != null && now >= expiry) return@filter false
            }

            // 4. Dismissed
            if (item.id in dismissed) return@filter false

            true
        }
    }

    private fun readDismissed(): Set<String> = kvStore.getStringSetCsv(KEY_DISMISSED_IDS)

    private suspend fun writeDismissed(set: Set<String>) {
        kvStore.setStringSetCsv(KEY_DISMISSED_IDS, set)
    }
}

/**
 * Numeric semver comparator. Returns negative / zero / positive in the
 * conventional way, or `null` if either side can't be parsed (caller
 * should treat null as "no constraint" — fail-open).
 *
 * Accepts leading `v` and pre-release tags (everything after the first
 * `-` is dropped). Missing components are zero-padded (e.g. `"1"` →
 * `1.0.0`).
 */
fun compareSemverOrNull(a: String, b: String): Int? {
    val aParts = a.removePrefix("v").substringBefore('-').split(".").mapNotNull { it.toIntOrNull() }
    val bParts = b.removePrefix("v").substringBefore('-').split(".").mapNotNull { it.toIntOrNull() }
    if (aParts.isEmpty() || bParts.isEmpty()) return null
    for (i in 0 until maxOf(aParts.size, bParts.size)) {
        val ap = aParts.getOrElse(i) { 0 }
        val bp = bParts.getOrElse(i) { 0 }
        if (ap != bp) return ap.compareTo(bp)
    }
    return 0
}
