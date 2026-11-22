package com.parachord.shared.plugin

import com.parachord.shared.home.PlaybackLimit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * A status-capable plugin's `getStatus` reply (`capabilities.status`, desktop
 * `docs/architecture/plugins.md` "Status and playback limits"), parsed.
 *
 * @param signedIn the plugin's account state; null when it didn't say
 * @param playbackLimit its plan limit ([pluginStatusLimit]); null = no verdict
 * @param statusLabel / statusMessage the Settings row's own two lines
 * @param readFailed the loader's failed-read marker (`STATUS_READ_FAILED`):
 *   the plugin threw, answered nothing object-shaped, or ran past the loader's
 *   8 s budget. Never a block: a status outage must not unplug playback.
 */
data class PluginStatusEntry(
    val signedIn: Boolean? = null,
    val playbackLimit: PlaybackLimit? = null,
    val statusLabel: String? = null,
    val statusMessage: String? = null,
    val readFailed: Boolean = false,
) {
    companion object {
        /** The loader's `STATUS_READ_FAILED` (`{ failed: true }`). */
        val READ_FAILED = PluginStatusEntry(readFailed = true)
    }
}

private val statusJson = Json { ignoreUnknownKeys = true }

/**
 * Port of desktop's `pluginStatusLimit` (`src/renderer/lib/playback-limits.js`):
 * a reply's `playbackLimit`, validated field by field. A reply without one, or
 * one not shaped like an entry (`blocked` not a boolean), is no verdict (null),
 * never a guess. Non-string fields degrade to null; `searchesHeldBack` is true
 * only when the plugin says exactly `true`, and `ownedOnly` only when it says
 * exactly `true` on an unblocked entry (a blocked resolver plays nothing).
 */
fun pluginStatusLimit(status: JsonElement?): PlaybackLimit? {
    val limit = (status as? JsonObject)?.get("playbackLimit") as? JsonObject ?: return null
    val blocked = (limit["blocked"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
    fun str(key: String): String? =
        (limit[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotEmpty() }
    val held = (limit["searchesHeldBack"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true
    val owned = (limit["ownedOnly"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true && !blocked
    return PlaybackLimit(
        blocked = blocked,
        label = str("label"),
        planKey = str("planKey"),
        message = str("message"),
        searchesHeldBack = held,
        ownedOnly = owned,
    )
}

/**
 * A reply as the loader returns it through the poll (JSON text), parsed. Null
 * [raw] is no reply (the plugin has no status). Anything that isn't an object,
 * and the loader's `{ failed: true }`, is [PluginStatusEntry.READ_FAILED].
 */
fun parsePluginStatus(raw: String?): PluginStatusEntry? {
    if (raw == null) return null
    val element = try { statusJson.parseToJsonElement(raw) } catch (_: Exception) { return PluginStatusEntry.READ_FAILED }
    val obj = element as? JsonObject ?: return PluginStatusEntry.READ_FAILED
    if ((obj["failed"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == true) return PluginStatusEntry.READ_FAILED
    fun str(key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    return PluginStatusEntry(
        signedIn = (obj["signedIn"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull,
        playbackLimit = pluginStatusLimit(obj),
        statusLabel = str("statusLabel"),
        statusMessage = str("statusMessage"),
    )
}

/**
 * Port of `pluginStatusLimits`: every reply's limit, with the no-verdict ones
 * dropped, so a plugin whose read failed never overrides another verdict.
 */
fun pluginStatusLimits(statuses: Map<String, PluginStatusEntry?>): Map<String, PlaybackLimit> =
    statuses.mapNotNull { (id, s) -> s?.playbackLimit?.let { id to it } }.toMap()

/** The Settings account row's two lines ([pluginStatusRow]). */
data class PluginStatusRow(val label: String, val message: String) {
    /** The row's status line: `label · message`, or the label alone. */
    val line: String get() = if (message.isNotEmpty()) "$label · $message" else label
}

/**
 * Port of `pluginStatusRow`: the reply's `statusLabel`, else `signedIn`'s
 * wording, else "Checking…" (no reply yet). A failed read says what happened
 * and what to do. `statusMessage` is the second line, or `""`.
 */
fun pluginStatusRow(status: PluginStatusEntry?): PluginStatusRow {
    if (status?.readFailed == true) {
        return PluginStatusRow(
            label = "Couldn’t check",
            message = "Parachord couldn’t check this plug-in’s status. Try opening this panel again.",
        )
    }
    val label = status?.statusLabel?.takeIf { it.isNotEmpty() }
        ?: when (status?.signedIn) {
            true -> "Signed in"
            false -> "Not signed in"
            null -> "Checking…"
        }
    return PluginStatusRow(label, status?.statusMessage ?: "")
}

/** Port of `planBlockedIds`: the ids whose plan can't play, sorted. */
fun planBlockedIds(limits: Map<String, PlaybackLimit?>): List<String> =
    limits.filter { it.value?.blocked == true }.keys.sorted()

/**
 * Port of `notPlayingIds`: the plug-ins that are on but don't play, the
 * plan-blocked ([blockedIds]) and the ones whose "Play from" switch is off
 * ([playbackOffIds], `playback_off_resolvers`). One list, so source selection,
 * badges and the priority numbers follow one rule. Sorted; [blockedIds] itself
 * when nothing is switched off.
 */
fun notPlayingIds(blockedIds: List<String>, playbackOffIds: Collection<String>): List<String> {
    if (playbackOffIds.isEmpty()) return blockedIds
    return (blockedIds + playbackOffIds).toSet().sorted()
}

/** A tile's status line and its longer explanation ([tileStatus]). */
data class TileStatus(val statusLabel: String?, val statusTitle: String?)

/**
 * Port of `tileStatus`: a plan limit's label (it wins: what a blocked plan
 * stops, or what an [PlaybackLimit.ownedOnly] one still plays), else "Search
 * and links only" when the user switched "Play from" off, else nothing.
 * Mobile addition: a plugin this host can't play from at all
 * ([hostUnplayableIds], shared [hostCannotPlay]) reads "Search and links
 * only" with its own reason, after a blocked plan's label.
 */
fun tileStatus(
    limits: Map<String, PlaybackLimit?>,
    id: String,
    playbackOffIds: Collection<String> = emptyList(),
    hostUnplayableIds: Collection<String> = emptyList(),
): TileStatus {
    val limit = limits[id]
    if (limit != null && limit.blocked) return TileStatus(limit.label ?: "No playback", limit.message)
    if (id in hostUnplayableIds) return TileStatus(HOST_CANNOT_PLAY_LABEL, HOST_CANNOT_PLAY_MESSAGE)
    if (limit != null && limit.ownedOnly) return TileStatus(limit.label ?: "Purchases only", limit.message)
    if (id in playbackOffIds) return TileStatus("Search and links only", "Playing from it is off in its settings")
    return TileStatus(null, null)
}

/** Port of `limitMessage`: the settings panel's explanation for a blocked resolver, or null. */
fun limitMessage(limits: Map<String, PlaybackLimit?>, id: String): String? =
    limits[id]?.takeIf { it.blocked }?.message

/**
 * What [limitTransition] says to do: ask the resolver again ([reresolve]),
 * drop all its saved sources first ([purge]), and the state to remember
 * ([store]; null = leave what's remembered).
 */
data class LimitTransition(val reresolve: Boolean, val purge: Boolean, val store: String?)

/** The remembered states: blocked, purchases only, playable. */
const val LIMIT_STATE_BLOCKED = "1"
const val LIMIT_STATE_OWNED = "owned"
const val LIMIT_STATE_PLAYABLE = "0"

/**
 * Port of desktop's `limitTransition` (`playback-limits.js`, #1494). [lastStored]
 * is what was remembered for the resolver ([LIMIT_STATE_BLOCKED],
 * [LIMIT_STATE_OWNED], [LIMIT_STATE_PLAYABLE], or null never seen). No reading
 * ([limit] null) changes nothing.
 * - into playable from blocked or owned-only (an upgrade): ask again, when the
 *   searches were held back (`searchesHeldBack`);
 * - into owned-only from anything else: purge (a source found on another plan
 *   may be a track the account doesn't own) and so ask again;
 * - into blocked: nothing; the resolver leaves playback whole.
 */
fun limitTransition(lastStored: String?, limit: PlaybackLimit?): LimitTransition {
    if (limit == null) return LimitTransition(reresolve = false, purge = false, store = null)
    val now = when {
        limit.blocked -> LIMIT_STATE_BLOCKED
        limit.ownedOnly -> LIMIT_STATE_OWNED
        else -> LIMIT_STATE_PLAYABLE
    }
    val purge = now == LIMIT_STATE_OWNED && lastStored != LIMIT_STATE_OWNED
    val upgraded = now == LIMIT_STATE_PLAYABLE &&
        (lastStored == LIMIT_STATE_BLOCKED || lastStored == LIMIT_STATE_OWNED) && limit.searchesHeldBack
    return LimitTransition(reresolve = purge || upgraded, purge = purge, store = now)
}

/** Port of `limitStorageKey`: where a resolver's last state is remembered (desktop's key). */
fun limitStorageKey(resolverId: String): String = "playback_limit_blocked:$resolverId"

/**
 * One track's saved sources with [resolverId]'s cleared for a plan change
 * (desktop's `clearResolverMisses`): its misses (`noMatch`) always, its found
 * sources too when [dropFound] (the purge). The same list when nothing goes.
 */
fun clearResolverSources(
    sources: List<com.parachord.shared.resolver.ResolvedSource>,
    resolverId: String,
    dropFound: Boolean,
): List<com.parachord.shared.resolver.ResolvedSource> {
    if (sources.none { it.resolver == resolverId && (dropFound || it.noMatch) }) return sources
    return sources.filterNot { it.resolver == resolverId && (dropFound || it.noMatch) }
}

/**
 * Whether a track with [sources] should ask [resolverId] again after the
 * clear: it holds no found source of that resolver (desktop zeroes such a
 * track's timestamp so it resolves again).
 */
fun asksResolverAgain(sources: List<com.parachord.shared.resolver.ResolvedSource>, resolverId: String): Boolean =
    sources.none { it.resolver == resolverId && !it.noMatch }
