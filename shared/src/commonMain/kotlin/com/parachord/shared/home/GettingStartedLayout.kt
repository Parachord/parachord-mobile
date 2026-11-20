package com.parachord.shared.home

// What the getting-started layout draws, a port of desktop's
// `src/renderer/components/home-getting-started.js` (#1493): the three steps,
// which one is open, and what each tile in a step says. The words are in
// [HomeCopy]; each app only draws them.

/** A step of the layout: its id (as [GettingStartedStep.id]) and title. */
data class GettingStartedStepInfo(val id: String, val title: String)

/** Desktop's `STEPS`, in order. */
val GETTING_STARTED_STEPS: List<GettingStartedStepInfo> = listOf(
    GettingStartedStepInfo("connect", HomeCopy.STEP_CONNECT),
    GettingStartedStepInfo("music", HomeCopy.STEP_MUSIC),
    GettingStartedStepInfo("listen", HomeCopy.STEP_LISTEN),
)

/** The open step: the one the person picked, else the current one (the first not done), else the first. */
fun openGettingStartedStep(picked: String?, status: GettingStartedStatus): String =
    picked ?: status.current ?: GETTING_STARTED_STEPS.first().id

/** A step's marker: a check when done, its number in the accent when current, else its number on grey. */
enum class StepMarkerLook { DONE, CURRENT, NOT_YET }

fun stepMarkerLook(step: GettingStartedStep?, current: String?): StepMarkerLook = when {
    step?.done == true -> StepMarkerLook.DONE
    step != null && step.id == current -> StepMarkerLook.CURRENT
    else -> StepMarkerLook.NOT_YET
}

/** The line under a step's title: "Done", "Next" or "Not yet". */
fun stepStateLine(step: GettingStartedStep?, current: String?): String = when (stepMarkerLook(step, current)) {
    StepMarkerLook.DONE -> HomeCopy.STEP_DONE
    StepMarkerLook.CURRENT -> HomeCopy.STEP_NEXT
    StepMarkerLook.NOT_YET -> HomeCopy.STEP_NOT_YET
}

/** A connected service shows "Connected" as a badge with a check; the other states are plain text. */
fun serviceTileShowsBadge(s: ServiceState): Boolean = s.state == "connected"

/** A service tile's description: "Connected", the limit or setup label, or "Connect". */
fun serviceTileDescription(s: ServiceState): String = when (s.state) {
    "connected" -> HomeCopy.CONNECTED
    "limited", "setup" -> s.label ?: HomeCopy.CONNECT
    else -> HomeCopy.CONNECT
}

/** A service tile's accessibility label (desktop's `aria-label`). */
fun serviceTileLabel(name: String, s: ServiceState): String = when (s.state) {
    "connect" -> "${HomeCopy.CONNECT} $name"
    "setup" -> "Set up $name: ${(s.label ?: HomeCopy.SET_UP).lowercase()}"
    "connected" -> "$name: connected"
    else -> "$name: ${s.label ?: s.state}"
}

/**
 * The music folder tile's description. Desktop says "On · N folders"; the
 * phones scan the device's music rather than keep folders, so once there are
 * local files it reads "On" alone, and "· Add a folder" while there are none.
 */
fun folderTileDescription(on: Boolean, folderAdded: Boolean): String {
    val state = if (on) HomeCopy.ON else HomeCopy.OFF
    return if (folderAdded) state else "$state · ${HomeCopy.ADD_A_FOLDER}"
}

/** The music folder tile's accessibility label. */
fun folderTileLabel(folderAdded: Boolean): String =
    if (folderAdded) HomeCopy.MUSIC_FOLDER else HomeCopy.ADD_A_MUSIC_FOLDER

/** A free plug-in's tile: "On" or "Off". */
fun freeTileDescription(on: Boolean): String = if (on) HomeCopy.ON else HomeCopy.OFF

/** Step 3's line: with music to play, or without. */
fun listenStepLine(hasMusic: Boolean): String =
    if (hasMusic) HomeCopy.LISTEN_LINE_WITH_MUSIC else HomeCopy.LISTEN_LINE_EMPTY

/**
 * "Explore while you set up" (desktop `ExploreRow`): Critical Darlings and Pop
 * of the Tops, never Fresh Drops. Each shows with cards or while loading, and a
 * failed Pop of the Tops keeps its half with Try again.
 */
fun exploreHalves(
    criticalCount: Int,
    criticalLoading: Boolean,
    chartsCount: Int,
    chartsLoading: Boolean,
    chartsFailed: Boolean,
): HomeHalves = HomeHalves(
    fresh = false,
    critical = criticalCount > 0 || criticalLoading,
    charts = chartsCount > 0 || chartsLoading || chartsFailed,
)
