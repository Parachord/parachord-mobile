package com.parachord.shared.home

import com.parachord.shared.plugin.PluginManager.PluginInfo
import com.parachord.shared.plugin.needsAccount
import kotlinx.serialization.Serializable

// The model behind Home's getting-started layout, a port of desktop's
// `src/renderer/lib/getting-started.js` (#1493): which plug-ins step 1 offers,
// which of the three steps are done, and whether the layout shows at all.
//
// Not ported, because the manifest parsing doesn't read what they need
// (`PluginConfigField` has no required / advanced / default):
//  - `requiredSetting` (Spotify's required Client ID): the call site passes
//    the field's label to [serviceState] as `requiredLabels` instead;
//  - `settingsMissing` can't skip advanced fields or fields with a default,
//    so it counts every configurable field as one the user fills in;
//  - `scrobblerPlugins` is a one-line `capabilities["scrobbling"]` filter and
//    `needsAccount` follows `requiresAuth` only (desktop also reads authType).

const val LOCAL_FILES_ID = "localfiles"

/** A plug-in plays here when it resolves tracks and plays them in the app. */
fun playsHere(p: PluginInfo): Boolean {
    val c = p.capabilities
    return c["resolve"] == true && (c["stream"] == true || c["embedPlayer"] == true)
}

/** Step 1's tiles: services that need signing in, free plug-ins that play here, the music folder. */
data class StepOnePlugins(
    val services: List<PluginInfo>,
    val free: List<PluginInfo>,
    val folder: PluginInfo?,
)

fun stepOnePlugins(plugins: List<PluginInfo>): StepOnePlugins {
    val list = plugins.filter(::playsHere)
    return StepOnePlugins(
        services = list.filter { it.id != LOCAL_FILES_ID && needsAccount(it) },
        free = list.filter { it.id != LOCAL_FILES_ID && !needsAccount(it) },
        folder = list.firstOrNull { it.id == LOCAL_FILES_ID },
    )
}

/**
 * A plug-in whose settings panel needs values and has none saved yet (`saved`,
 * its config entry). Every configurable field counts as required here; see the
 * file note.
 */
fun settingsMissing(p: PluginInfo, saved: Map<String, String>?): Boolean {
    if (p.configFields.isEmpty()) return false
    return p.configFields.none { f -> saved?.get(f.key)?.trim()?.isNotEmpty() == true }
}

/** A plan limit on a service (Amazon Music's Prime). */
data class PlaybackLimit(val blocked: Boolean, val label: String? = null)

/** `state` is `connected`, `limited`, `connect` or `setup`. */
data class ServiceState(val state: String, val label: String?)

/**
 * A service tile's state. `accounts` maps a plug-in id to whether the host
 * knows it is signed in; one the host has no state for counts as connected
 * once it is on and playing (`playbackResolvers`). `setupNeeded` is true while
 * a required setting isn't saved: the tile reads `setup` unless the host knows
 * the account is signed in. `requiredLabels` names that setting by plug-in id.
 */
fun serviceState(
    p: PluginInfo,
    accounts: Map<String, Boolean> = emptyMap(),
    setupNeeded: Map<String, Boolean> = emptyMap(),
    playbackResolvers: List<String> = emptyList(),
    playbackLimits: Map<String, PlaybackLimit> = emptyMap(),
    requiredLabels: Map<String, String> = emptyMap(),
): ServiceState {
    val id = p.id
    val limit = playbackLimits[id]
    val known = accounts.containsKey(id)
    val signedIn = if (known) accounts[id] == true else id in playbackResolvers
    if (setupNeeded[id] == true && !(known && accounts[id] == true)) {
        val field = requiredLabels[id]
        return ServiceState("setup", if (field != null) "Add your $field" else "Set up")
    }
    if (!signedIn) return ServiceState("connect", null)
    if (limit != null && limit.blocked) return ServiceState("limited", limit.label ?: "Can’t play on this plan")
    if (id !in playbackResolvers) return ServiceState("connect", null)
    return ServiceState("connected", null)
}

/** Something to play from: a service that needs an account is signed in and playing. */
fun serviceConnected(
    services: List<PluginInfo>,
    accounts: Map<String, Boolean> = emptyMap(),
    setupNeeded: Map<String, Boolean> = emptyMap(),
    playbackResolvers: List<String> = emptyList(),
    playbackLimits: Map<String, PlaybackLimit> = emptyMap(),
    requiredLabels: Map<String, String> = emptyMap(),
): Boolean = services.any {
    serviceState(it, accounts, setupNeeded, playbackResolvers, playbackLimits, requiredLabels).state == "connected"
}

/** Scrobbling counts as connected once any scrobbling plug-in has an account name saved. */
fun scrobblerConnected(usernames: Collection<String?>): Boolean = usernames.any { !it.isNullOrEmpty() }

data class GettingStartedStep(val id: String, val done: Boolean)

data class GettingStartedStatus(
    val steps: List<GettingStartedStep>,
    /** The first step not done, null when all are. */
    val current: String?,
    val allDone: Boolean,
    val done: Int,
)

fun gettingStartedSteps(
    connected: Boolean = false,
    folderAdded: Boolean = false,
    hasMusic: Boolean = false,
    scrobbling: Boolean = false,
    played: Boolean = false,
): GettingStartedStatus {
    val steps = listOf(
        GettingStartedStep("connect", connected || folderAdded),
        GettingStartedStep("music", hasMusic || scrobbling),
        GettingStartedStep("listen", played),
    )
    val next = steps.firstOrNull { !it.done }
    return GettingStartedStatus(steps, next?.id, next == null, steps.count { it.done })
}

/**
 * The remembered state (`home_getting_started`). `active`: the layout is on.
 * `played`: something has played since it came on. `skipped`: "Skip setup for
 * now" was used, so the "Finish setting up" pill offers the steps until done.
 */
@Serializable
data class GettingStartedState(
    val active: Boolean = false,
    val played: Boolean = false,
    val skipped: Boolean = false,
)

/**
 * A saved state wins. With none, desktop uses its tutorial flag, which mobile
 * lacks (the one deliberate divergence): the layout is on for a profile that
 * has nothing to play from yet, off for one that already connected a service
 * or has music.
 */
fun initialGettingStarted(saved: GettingStartedState?, connectDone: Boolean, musicDone: Boolean): GettingStartedState =
    saved ?: GettingStartedState(active = !connectDone && !musicDone)

/** The pill's "Finish getting started": skipped, and steps still open. */
fun gettingStartedLeft(state: GettingStartedState?, status: GettingStartedStatus?): Boolean =
    state != null && state.skipped && !state.active && status != null && !status.allDone

fun showGettingStarted(state: GettingStartedState?, status: GettingStartedStatus?): Boolean =
    state != null && state.active && status != null && !status.allDone
