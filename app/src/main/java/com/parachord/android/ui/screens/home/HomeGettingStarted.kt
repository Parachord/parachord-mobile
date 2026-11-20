package com.parachord.android.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.parachord.android.ui.components.ParachordIcon
import com.parachord.android.ui.components.ResolverIconSquare
import com.parachord.android.ui.components.serviceLogoVector
import com.parachord.android.ui.theme.serviceColorOrNull
import androidx.compose.foundation.Image
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import com.parachord.shared.ui.icons.ServiceLogos
import com.parachord.shared.home.SYNC_LIBRARY_SETTINGS_TAB
import com.parachord.shared.home.playlistTileDescription
import com.parachord.shared.home.playlistTileTitle
import com.parachord.shared.home.scrobblerTileDescription
import com.parachord.shared.home.scrobblerTileLabel
import com.parachord.android.ui.components.SectionHeader
import com.parachord.android.ui.components.hapticClickable
import com.parachord.android.ui.theme.FillAccent
import com.parachord.android.ui.theme.FillNeutral
import com.parachord.android.ui.theme.FillSuccess
import com.parachord.android.ui.theme.composeFill
import com.parachord.android.ui.theme.current
import com.parachord.android.ui.theme.tokenColor
import com.parachord.shared.home.GETTING_STARTED_STEPS
import com.parachord.shared.home.GettingStartedStepInfo
import com.parachord.shared.home.GettingStartedView
import com.parachord.shared.home.HomeCopy
import com.parachord.shared.home.HomeHalfCard
import com.parachord.shared.home.HomeState
import com.parachord.shared.home.StepMarkerLook
import com.parachord.shared.home.chartHalfCards
import com.parachord.shared.home.criticalHalfCards
import com.parachord.shared.home.exploreHalves
import com.parachord.shared.home.folderTileDescription
import com.parachord.shared.home.folderTileLabel
import com.parachord.shared.home.freeTileDescription
import com.parachord.shared.home.homeHalfBody
import com.parachord.shared.home.listenStepLine
import com.parachord.shared.home.openGettingStartedStep
import com.parachord.shared.home.serviceTileDescription
import com.parachord.shared.home.serviceTileLabel
import com.parachord.shared.home.serviceTileShowsBadge
import com.parachord.shared.home.stepMarkerLook
import com.parachord.shared.home.stepStateLine
import com.parachord.shared.ui.DesignTokens
import com.parachord.shared.ui.icons.AppIcon
import com.parachord.shared.ui.icons.PageIcon

// Home's getting-started layout (desktop `home-getting-started.js`, #1493),
// shown in place of the sections until setup is done or skipped: the welcome,
// one card with the three steps (the current one open, any one can be
// opened), then "Explore while you set up". What each step and tile says is
// worked out in shared code (`GettingStartedLayout.kt`, `HomeCopy`); this only
// draws it. On a phone the steps stack, each panel opening under its header,
// where desktop sets the three headers side by side above one panel. Not
// here: desktop's "Sound quality" (the phones have no such setting) and its
// note about the phone apps.

private val Gutter = DesignTokens.SCREEN_GUTTER.dp
private val OnFill = tokenColor(DesignTokens.ON_ACCENT)
private val CardShape = RoundedCornerShape(DesignTokens.RADIUS_SHEET.dp)
private val TileShape = RoundedCornerShape(DesignTokens.RADIUS_PANEL.dp)
private val MarkShape = RoundedCornerShape(DesignTokens.RADIUS_BUTTON.dp)
/** Desktop's `STEP_HEADER_HEIGHT`. */
private val StepHeaderHeight = 64.dp
/** Desktop's `TILE_HEIGHT`. */
private val TileHeight = 68.dp
private val MarkSize = 36.dp

/**
 * The layout: the welcome with "Skip setup for now", then the steps card.
 * [hasCollection] shows step 3's Collection Radio button.
 */
@Composable
fun HomeGettingStarted(
    view: GettingStartedView,
    hasCollection: Boolean,
    onSkip: () -> Unit,
    /** Settings, on a plug-in's sheet when the id is set, else the Plug-Ins list. */
    onOpenSettings: (pluginId: String?) -> Unit,
    /** Settings on a tab ([com.parachord.shared.settings.SettingsTab]). */
    onOpenSettingsTab: (tab: String) -> Unit,
    onOpenImportPlaylist: () -> Unit,
    onOpenPlaylist: (playlistId: String) -> Unit,
    onCollectionRadio: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The open step: the current one until the person opens another.
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val open = openGettingStartedStep(picked, view.status)

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Gutter),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column {
            Text(
                text = HomeCopy.WELCOME,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = DesignTokens.textPrimary.current(),
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(4.dp))
            Text(text = HomeCopy.WELCOME_INTRO, fontSize = 14.sp, color = DesignTokens.textSecondary.current())
            TextAction(HomeCopy.SKIP_SETUP, onSkip, Modifier.padding(top = 4.dp))
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CardShape)
                .border(1.dp, DesignTokens.borderDefault.current(), CardShape)
                .background(DesignTokens.cardBg.current()),
        ) {
            GETTING_STARTED_STEPS.forEachIndexed { i, step ->
                if (i > 0) HorizontalDivider(color = DesignTokens.borderLight.current())
                val isOpen = open == step.id
                StepHeader(
                    index = i,
                    step = step,
                    view = view,
                    open = isOpen,
                    onClick = { picked = step.id },
                )
                if (isOpen) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        when (step.id) {
                            "connect" -> ConnectStep(view, onOpenSettings)
                            "music" -> MusicStep(
                                view = view,
                                onImportLink = onOpenImportPlaylist,
                                onSyncLibrary = { onOpenSettingsTab(SYNC_LIBRARY_SETTINGS_TAB) },
                                onOpenPlugin = { onOpenSettings(it) },
                            )
                            else -> ListenStep(view, hasCollection, onOpenPlaylist, onCollectionRadio, onSearch)
                        }
                    }
                }
            }
        }
    }
}

/** A step's header row: the marker, the title and "Done" / "Next" / "Not yet". Tapping opens the step. */
@Composable
private fun StepHeader(
    index: Int,
    step: GettingStartedStepInfo,
    view: GettingStartedView,
    open: Boolean,
    onClick: () -> Unit,
) {
    val s = view.status.steps.firstOrNull { it.id == step.id }
    val look = stepMarkerLook(s, view.status.current)
    val line = stepStateLine(s, view.status.current)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = StepHeaderHeight)
            .background(if (open) DesignTokens.accentSurface.current() else DesignTokens.cardBg.current())
            .semantics(mergeDescendants = true) {
                role = Role.Button
                stateDescription = if (open) HomeCopy.EXPANDED else HomeCopy.COLLAPSED
            }
            .hapticClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        StepMarker(index, look)
        Column(Modifier.weight(1f)) {
            Text(text = step.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.textPrimary.current())
            Text(text = line, fontSize = 12.sp, color = DesignTokens.textTertiary.current())
        }
    }
}

/** Desktop's `StepMarker`: a check when done, the number in the accent when current, else on grey. */
@Composable
private fun StepMarker(index: Int, look: StepMarkerLook) {
    val (bg, fg) = when (look) {
        StepMarkerLook.DONE -> FillSuccess to OnFill
        StepMarkerLook.CURRENT -> FillAccent to OnFill
        StepMarkerLook.NOT_YET -> DesignTokens.bgSecondary.current() to DesignTokens.textSecondary.current()
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(28.dp).background(bg, CircleShape).clearAndSetSemantics { },
    ) {
        if (look == StepMarkerLook.DONE) {
            ParachordIcon(AppIcon.Check, contentDescription = null, size = 16.dp, tint = fg)
        } else {
            Text(text = (index + 1).toString(), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg)
        }
    }
}

// ── Steps ────────────────────────────────────────────────────────────

@Composable
private fun ConnectStep(view: GettingStartedView, onOpenSettings: (String?) -> Unit) {
    StepLine(HomeCopy.CONNECT_LINE)
    Group(HomeCopy.GROUP_SERVICES) {
        view.services.forEach { (p, state) ->
            Tile(
                mark = { ServiceMark(p.id) },
                title = p.name,
                description = serviceTileDescription(state),
                badge = serviceTileShowsBadge(state),
                label = serviceTileLabel(p.name, state),
                onClick = { onOpenSettings(p.id) },
            )
        }
        Tile(
            mark = { IconMark(AppIcon.Plus, FillNeutral) },
            title = HomeCopy.MORE_PLUGINS,
            description = HomeCopy.BROWSE,
            onClick = { onOpenSettings(null) },
        )
    }
    val folder = view.folder
    if (folder != null || view.free.isNotEmpty()) {
        Group(HomeCopy.GROUP_NO_ACCOUNT) {
            if (folder != null) {
                Tile(
                    mark = { ServiceMark(folder.id) },
                    title = HomeCopy.MUSIC_FOLDER,
                    description = folderTileDescription(view.isOn(folder), view.folderAdded),
                    label = folderTileLabel(view.folderAdded),
                    onClick = { onOpenSettings(folder.id) },
                )
            }
            view.free.forEach { p ->
                Tile(
                    mark = { ServiceMark(p.id) },
                    title = p.name,
                    description = freeTileDescription(view.isOn(p)),
                    onClick = { onOpenSettings(p.id) },
                )
            }
        }
    }
    // Below every source: the order decides which one plays.
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, DesignTokens.borderDefault.current(), TileShape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        ParachordIcon(
            AppIcon.ListOrdered, contentDescription = null, size = 18.dp,
            tint = DesignTokens.textTertiary.current(), modifier = Modifier.padding(top = 2.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = HomeCopy.ORDER_NOTE, fontSize = 13.sp, color = DesignTokens.textSecondary.current())
            TextAction(HomeCopy.SET_THE_ORDER, onClick = { onOpenSettings(null) })
        }
    }
}

@Composable
private fun MusicStep(
    view: GettingStartedView,
    onImportLink: () -> Unit,
    onSyncLibrary: () -> Unit,
    onOpenPlugin: (String) -> Unit,
) {
    StepLine(HomeCopy.MUSIC_LINE)
    Group(HomeCopy.GROUP_PLAYLISTS) {
        Tile(
            mark = { IconMark(PageIcon.PLAYLISTS.icon, DesignTokens.Page.PLAYLISTS.composeFill) },
            title = HomeCopy.IMPORT_A_PLAYLIST,
            description = HomeCopy.IMPORT_A_PLAYLIST_SUB,
            onClick = onImportLink,
        )
        Tile(
            mark = { IconMark(PageIcon.COLLECTION.icon, DesignTokens.Page.COLLECTION.composeFill) },
            title = HomeCopy.SYNC_YOUR_LIBRARY,
            description = HomeCopy.SYNC_YOUR_LIBRARY_SUB,
            onClick = onSyncLibrary,
        )
    }
    if (view.scrobblers.isNotEmpty()) {
        Group(HomeCopy.GROUP_LISTENING_HISTORY) {
            view.scrobblers.forEach { (p, connected) ->
                Tile(
                    mark = { ServiceMark(p.id) },
                    title = p.name,
                    description = scrobblerTileDescription(connected),
                    badge = connected,
                    label = scrobblerTileLabel(p.name, connected),
                    onClick = { onOpenPlugin(p.id) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ListenStep(
    view: GettingStartedView,
    hasCollection: Boolean,
    onOpenPlaylist: (String) -> Unit,
    onCollectionRadio: () -> Unit,
    onSearch: () -> Unit,
) {
    StepLine(listenStepLine(view.hasMusic))
    if (view.playlists.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            view.playlists.forEach { p ->
                Tile(
                    mark = { IconMark(PageIcon.PLAYLISTS.icon, DesignTokens.Page.PLAYLISTS.composeFill) },
                    title = playlistTileTitle(p),
                    description = playlistTileDescription(p),
                    onClick = { onOpenPlaylist(p.id) },
                )
            }
        }
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hasCollection) CollectionRadioButton(onCollectionRadio)
        // Desktop's secondary button (`button.js`): rounded-lg, an outline in
        // line-strong, the Search icon and its label in medium weight.
        Surface(
            onClick = onSearch,
            shape = RoundedCornerShape(DesignTokens.RADIUS_BUTTON.dp),
            color = DesignTokens.cardBg.current(),
            contentColor = DesignTokens.textPrimary.current(),
            border = BorderStroke(1.dp, DesignTokens.borderStrong.current()),
            modifier = Modifier.heightIn(min = 40.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                ParachordIcon(AppIcon.Search, contentDescription = null, size = 16.dp)
                Spacer(Modifier.width(8.dp))
                Text(text = HomeCopy.SEARCH, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

// ── Pieces ───────────────────────────────────────────────────────────

@Composable
private fun StepLine(text: String) {
    Text(text = text, fontSize = 14.sp, color = DesignTokens.textSecondary.current())
}

/** A group of tiles under its label (12, semibold, uppercase, tertiary), one tile per line on a phone. */
@Composable
private fun Group(label: String, content: @Composable () -> Unit) {
    Column {
        Text(
            text = label.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.05.em,
            color = DesignTokens.textTertiary.current(),
            modifier = Modifier.padding(bottom = 8.dp).semantics { heading() },
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

/**
 * Desktop's `OptionTile`: a bordered choice with its mark, title and
 * description. [badge] draws the description as the accent "Connected" badge.
 */
@Composable
private fun Tile(
    mark: @Composable () -> Unit,
    title: String,
    description: String,
    onClick: () -> Unit,
    badge: Boolean = false,
    label: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = TileHeight)
            .clip(TileShape)
            .border(1.dp, DesignTokens.borderDefault.current(), TileShape)
            // One label for the whole tile, so the logo's own description
            // (a raw plug-in id) isn't read.
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = label ?: "$title, $description"
            }
            .hapticClickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        mark()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.textPrimary.current())
            if (badge) ConnectedBadge(description)
            else Text(text = description, fontSize = 12.sp, color = DesignTokens.textSecondary.current())
        }
    }
}

/** Desktop's accent `Badge` with a check. */
@Composable
private fun ConnectedBadge(text: String) {
    val accent = DesignTokens.accent.current()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .background(DesignTokens.accentSurface.current(), RoundedCornerShape(DesignTokens.RADIUS_PILL.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        ParachordIcon(AppIcon.Check, contentDescription = null, size = 12.dp, tint = accent)
        Text(text = text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = accent)
    }
}

/**
 * A service's logo on its own colour (desktop `ServiceMark`): the plug-in's
 * colour, else the neutral fill; its logo in white (a wide one, Libre.fm's
 * wordmark, at 30 dp), else the Music icon.
 */
@Composable
private fun ServiceMark(id: String) {
    val color = serviceColorOrNull(id)
    val key = id.lowercase()
    // SoundCloud's and Subsonic's logos are bitmaps, drawn by the source icon.
    if (color != null && (key == "soundcloud" || key == "subsonic")) {
        Box(Modifier.size(MarkSize).clip(MarkShape)) { ResolverIconSquare(resolver = id, size = MarkSize) }
        return
    }
    val spec = remember(key) { ServiceLogos.badgeOrLarge(key) }
    val logo = remember(key) { serviceLogoVector(key, OnFill) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(MarkSize).clip(MarkShape).background(color ?: FillNeutral),
    ) {
        if (logo != null && spec != null) {
            val wide = spec.height > 0f && spec.width / spec.height > 1.5f
            Image(
                painter = rememberVectorPainter(logo),
                contentDescription = null,
                modifier = Modifier.size(if (wide) 30.dp else 22.dp),
            )
        } else {
            ParachordIcon(AppIcon.Music, contentDescription = null, size = 18.dp, tint = OnFill)
        }
    }
}

/** A page's icon in white on its fill (desktop `IconMark`). */
@Composable
private fun IconMark(icon: AppIcon, fill: Color) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(MarkSize).background(fill, MarkShape)) {
        ParachordIcon(icon, contentDescription = null, size = 18.dp, tint = OnFill)
    }
}

/** Desktop's small text button: accent, 13, semibold. */
@Composable
private fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .semantics { role = Role.Button }
            .hapticClickable(onClick = onClick),
    ) {
        Text(text = text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.accent.current())
    }
}

// ── Explore while you set up ─────────────────────────────────────────

/**
 * Desktop's `ExploreRow`: the section header, then the Critical Darlings and
 * Pop of the Tops halves, stacked full width ([exploreHalves]; a failed Pop
 * of the Tops keeps its half with Try again).
 */
@Composable
fun HomeExploreSection(
    state: HomeState,
    onSeeAllCritical: () -> Unit,
    onSeeAllCharts: () -> Unit,
    onOpen: (HomeHalfCard) -> Unit,
    onPlay: (HomeHalfCard) -> Unit,
    onLongPress: (HomeHalfCard) -> Unit,
    onRetryCharts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val halves = exploreHalves(
        criticalCount = state.critical.items.size,
        criticalLoading = state.critical.loading,
        chartsCount = state.charts.items.size,
        chartsLoading = state.charts.loading,
        chartsFailed = state.charts.failed,
    )
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = HomeCopy.EXPLORE_TITLE, subtitle = HomeCopy.EXPLORE_SUBTITLE, horizontalPadding = Gutter)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (halves.critical) {
                val cards = criticalHalfCards(state.critical.items)
                HomeHalf(
                    page = DesignTokens.Page.CRITICAL_DARLINGS, icon = PageIcon.CRITICAL_DARLINGS, title = HomeCopy.CRITICAL_DARLINGS,
                    cards = cards, body = homeHalfBody(cards.size, state.critical.loading, failed = false),
                    onSeeAll = onSeeAllCritical, onOpen = onOpen, onPlay = onPlay, onLongPress = onLongPress,
                )
            }
            if (halves.charts) {
                val cards = chartHalfCards(state.charts.items)
                val body = homeHalfBody(cards.size, state.charts.loading, state.charts.failed)
                HomeHalf(
                    page = DesignTokens.Page.POP_OF_THE_TOPS, icon = PageIcon.POP_OF_THE_TOPS, title = HomeCopy.POP_OF_THE_TOPS,
                    cards = cards, body = body,
                    onSeeAll = onSeeAllCharts, onOpen = onOpen, onPlay = onPlay, onLongPress = onLongPress,
                    onRetry = onRetryCharts,
                )
            }
        }
    }
}

