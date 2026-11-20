package com.parachord.android.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ArtPlayButton
import com.parachord.android.ui.components.FaceAwareImage
import com.parachord.android.ui.components.ParachordIcon
import com.parachord.android.ui.components.SectionHeader
import com.parachord.android.ui.components.hapticClickable
import com.parachord.android.ui.components.hapticCombinedClickable
import com.parachord.android.ui.screens.discover.ArtPlayInset
import com.parachord.android.ui.screens.discover.CardText
import com.parachord.android.ui.screens.discover.RecCardSkeleton
import com.parachord.android.ui.screens.discover.RecCardWidth
import com.parachord.android.ui.theme.FillAccent
import com.parachord.android.ui.theme.composeFill
import com.parachord.android.ui.theme.composeGradient
import com.parachord.android.ui.theme.current
import com.parachord.android.ui.theme.releaseTypeColor
import com.parachord.android.ui.theme.serviceColorOrNull
import com.parachord.android.ui.theme.tokenColor
import com.parachord.shared.home.AskField
import com.parachord.shared.home.HOME_HALF_ROW_CARDS
import com.parachord.shared.home.HomeCopy
import com.parachord.shared.home.HomeHalfBody
import com.parachord.shared.home.HomeHalfCard
import com.parachord.shared.home.HomeState
import com.parachord.shared.home.chartHalfCards
import com.parachord.shared.home.concertArtist
import com.parachord.shared.home.concertPhotoUrl
import com.parachord.shared.home.concertDateBadge
import com.parachord.shared.home.concertPlaceLine
import com.parachord.shared.home.concertTickets
import com.parachord.shared.home.concertVenue
import com.parachord.shared.home.criticalHalfCards
import com.parachord.shared.home.freshHalfCards
import com.parachord.shared.home.homeHalfBody
import com.parachord.shared.repository.ConcertEvent
import com.parachord.shared.ui.DesignTokens
import com.parachord.shared.ui.icons.AppIcon
import com.parachord.shared.ui.icons.PageIcon

// The lower half of desktop's Home (`home-sections.js`): the Fresh Drops /
// Critical Darlings / Pop of the Tops halves, Concerts near you and Ask
// Shuffleupagus. What each shows is worked out in shared code
// (`HomeSession`, `HomeLower.kt`, `HomePage.kt`); these only draw it.

private val Gutter = DesignTokens.SCREEN_GUTTER.dp
private val OnFill = tokenColor(DesignTokens.ON_ACCENT)
private val PanelShape = RoundedCornerShape(DesignTokens.RADIUS_SHEET.dp)
/** Space between a half's cards (desktop's `HOME_ROW_GAP`). */
private val HalfCardGap = 16.dp
private val HalfBodyPadding = 16.dp
/** Desktop's `CONCERT_ART`. */
private val ConcertArt = 72.dp

// ── Fresh Drops / Critical Darlings / Pop of the Tops ────────────────

/**
 * The halves (desktop `FreshCriticalRow`), stacked full width on a phone:
 * Fresh Drops, Critical Darlings, and Pop of the Tops while Fresh Drops has
 * nothing ([HomeState.halves]). A half with no cards yet shows its skeleton;
 * Pop of the Tops' failed load shows the error with Try again.
 */
@Composable
fun HomeHalvesSection(
    state: HomeState,
    onSeeAllFresh: () -> Unit,
    onSeeAllCritical: () -> Unit,
    onSeeAllCharts: () -> Unit,
    onOpen: (HomeHalfCard) -> Unit,
    onPlay: (HomeHalfCard) -> Unit,
    onLongPress: (HomeHalfCard) -> Unit,
    onRetryCharts: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val halves = state.halves
    if (!halves.fresh && !halves.critical && !halves.charts) return
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Gutter),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (halves.fresh) {
            val cards = freshHalfCards(state.fresh.items)
            HomeHalf(
                page = DesignTokens.Page.FRESH_DROPS, icon = PageIcon.FRESH_DROPS, title = HomeCopy.FRESH_DROPS,
                cards = cards, body = homeHalfBody(cards.size, state.fresh.loading, failed = false),
                onSeeAll = onSeeAllFresh, onOpen = onOpen, onPlay = onPlay, onLongPress = onLongPress,
            )
        }
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
            HomeHalf(
                page = DesignTokens.Page.POP_OF_THE_TOPS, icon = PageIcon.POP_OF_THE_TOPS, title = HomeCopy.POP_OF_THE_TOPS,
                cards = cards, body = homeHalfBody(cards.size, state.charts.loading, state.charts.failed),
                onSeeAll = onSeeAllCharts, onOpen = onOpen, onPlay = onPlay, onLongPress = onLongPress,
                onRetry = onRetryCharts,
            )
        }
    }
}

/**
 * One half (desktop `homeHalf`): a panel with a hero band in the page's
 * gradient (the title uppercase, light, 0.2em, white, the page's icon before
 * it, "See all" on the right), then three album cards, a skeleton, or the
 * error.
 */
@Composable
fun HomeHalf(
    page: DesignTokens.Page,
    icon: PageIcon,
    title: String,
    cards: List<HomeHalfCard>,
    body: HomeHalfBody,
    onSeeAll: () -> Unit,
    onOpen: (HomeHalfCard) -> Unit,
    onPlay: (HomeHalfCard) -> Unit,
    onLongPress: (HomeHalfCard) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .border(1.dp, DesignTokens.borderDefault.current(), PanelShape)
            .background(DesignTokens.bgPrimary.current()),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(page.composeGradient))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                ParachordIcon(icon.icon, contentDescription = null, size = 16.dp, tint = OnFill)
                Text(
                    text = title.uppercase(),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 0.2.em,
                    color = OnFill,
                    modifier = Modifier.semantics { heading() },
                )
            }
            Text(
                text = HomeCopy.SEE_ALL,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = OnFill,
                maxLines = 1,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .semantics {
                        role = Role.Button
                        contentDescription = HomeCopy.seeAllIn(title)
                    }
                    .hapticClickable(onClick = onSeeAll),
            )
        }
        // The body: the three cards in a scrolling row at the Home rows'
        // card width (a phone can't fit three of desktop's grid columns),
        // their skeletons at the same width, or the error.
        if (body == HomeHalfBody.ERROR) {
            Box(Modifier.fillMaxWidth().padding(HalfBodyPadding)) {
                HalfError(title = HomeCopy.CHARTS_ERROR_TITLE, onRetry = onRetry)
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(HalfBodyPadding),
                horizontalArrangement = Arrangement.spacedBy(HalfCardGap),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (body == HomeHalfBody.LOADING) {
                    items(HOME_HALF_ROW_CARDS, key = { "skeleton-$it" }) { RecCardSkeleton(lines = 1, width = RecCardWidth) }
                } else {
                    items(cards, key = { it.key }) { card ->
                        HalfAlbumCard(card, RecCardWidth, { onOpen(card) }, { onPlay(card) }, { onLongPress(card) })
                    }
                }
            }
        }
    }
}

/**
 * A half's album card: cover with the play disc, Fresh Drops' release-type
 * badge top-right (the type's colour at 90%, white text, as desktop's
 * `releaseBadge`), Critical Darlings' review badge bottom-left, then title
 * and artist.
 */
@Composable
private fun HalfAlbumCard(card: HomeHalfCard, width: Dp, onOpen: () -> Unit, onPlay: () -> Unit, onLongPress: () -> Unit) {
    Column(
        modifier = Modifier
            .width(width)
            .hapticCombinedClickable(onClick = onOpen, onLongClick = onLongPress),
    ) {
        Box {
            AlbumArtCard(
                artworkUrl = card.imageUrl,
                size = width,
                cornerRadius = DesignTokens.RADIUS_ART.dp,
                placeholderName = card.title,
                contentDescription = null,
                artist = card.artist,
                album = card.title,
            )
            card.releaseType?.let { label ->
                Text(
                    text = label,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = OnFill,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(DesignTokens.RADIUS_BADGE.dp))
                        .background(releaseTypeColor(card.releaseTypeKey).copy(alpha = 0.9f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            card.review?.let { review ->
                ReasonBadge(review, Modifier.align(Alignment.BottomStart), label = HomeCopy.WHAT_THE_CRITICS_SAID)
            }
            ArtPlayButton(card.title, onPlay, Modifier.align(Alignment.BottomEnd).padding(ArtPlayInset))
        }
        Spacer(Modifier.height(8.dp))
        CardText(card.title, primary = true)
        CardText(card.artist)
    }
}

/** A half's compact error (desktop `ErrorState compact`): what failed, why, and Try again. */
@Composable
private fun HalfError(title: String, onRetry: (() -> Unit)?) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = DesignTokens.textPrimary.current())
        Text(HomeCopy.CHARTS_ERROR_BODY, fontSize = 13.sp, color = DesignTokens.textSecondary.current())
        if (onRetry != null) FilledButton(HomeCopy.TRY_AGAIN, onRetry)
    }
}

/** A fill-accent button with white text (design system: never white on the theme accent). */
@Composable
private fun FilledButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, pill: Boolean = false, enabled: Boolean = true) {
    val shape = if (pill) CircleShape else RoundedCornerShape(DesignTokens.RADIUS_BUTTON.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(shape)
            .background(FillAccent)
            .semantics { role = Role.Button }
            .then(if (enabled) Modifier.hapticClickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = OnFill, maxLines = 1)
    }
}

// ── Concerts near you ────────────────────────────────────────────────

/**
 * "Concerts near you" (desktop `ConcertsRow`): up to four shows from the
 * collection's artists near the saved location, one card a line on a phone.
 */
@Composable
fun HomeConcertsSection(
    state: HomeState,
    onSeeAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Nothing until there are shows (desktop's `ConcertsRow` returns null with none).
    if (!state.sections.concerts || state.concerts.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = HomeCopy.CONCERTS_TITLE,
            subtitle = HomeCopy.CONCERTS_SUBTITLE,
            page = DesignTokens.Page.CONCERTS,
            onSeeAll = onSeeAll,
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Gutter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.concerts.forEachIndexed { i, event ->
                key(event.id.ifBlank { "concert-$i" }) { HomeConcertCard(event) }
            }
        }
    }
}

/**
 * A show (desktop `ConcertCard`): the 72 dp artist photo with the date badge
 * in its bottom-left corner, the artist, the venue ("Venue TBA") and the
 * place line, and Tickets. The card itself isn't clickable: Tickets is its
 * only action.
 */
@Composable
fun HomeConcertCard(event: ConcertEvent, modifier: Modifier = Modifier) {
    val artist = concertArtist(event)
    val place = concertPlaceLine(event)
    val tickets = concertTickets(event)
    val date = concertDateBadge(event.date)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(PanelShape)
            .border(1.dp, DesignTokens.borderDefault.current(), PanelShape)
            .padding(12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(ConcertArt)
                .clip(RoundedCornerShape(DesignTokens.RADIUS_CARD.dp))
                .background(DesignTokens.bgSecondary.current()),
        ) {
            val photo = concertPhotoUrl(event)
            val placeholder = @Composable {
                ParachordIcon(AppIcon.Music, contentDescription = null, size = 24.dp, tint = DesignTokens.textTertiary.current())
            }
            if (photo != null) {
                FaceAwareImage(
                    imageUrl = photo,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    error = placeholder,
                )
            } else {
                placeholder()
            }
            if (date != null) {
                Text(
                    text = date.uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.04.em,
                    color = OnFill,
                    maxLines = 1,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(DesignTokens.RADIUS_BADGE.dp))
                        .background(DesignTokens.Page.CONCERTS.composeFill)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                artist, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                color = DesignTokens.textPrimary.current(), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                concertVenue(event), fontSize = 12.sp,
                color = DesignTokens.textSecondary.current(), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (place.isNotEmpty()) {
                Text(
                    place, fontSize = 12.sp,
                    color = DesignTokens.textSecondary.current(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (tickets.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 4.dp), contentAlignment = Alignment.CenterEnd) {
                    TicketsButton(artist, tickets)
                }
            }
        }
    }
}

/**
 * Tickets (desktop `TicketsButton`, small, in the Concerts page's colour):
 * one seller opens its page; several open a menu naming each seller, with
 * its colour's dot.
 */
@Composable
private fun TicketsButton(artist: String, tickets: List<com.parachord.shared.home.TicketLink>) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val several = tickets.size > 1
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(RoundedCornerShape(DesignTokens.RADIUS_BUTTON.dp))
                .background(DesignTokens.Page.CONCERTS.composeFill)
                .semantics {
                    role = Role.Button
                    contentDescription = HomeCopy.ticketsFor(artist)
                    if (several) stateDescription = if (expanded) "Expanded" else "Collapsed"
                }
                .hapticClickable {
                    if (several) expanded = true else openHttpLink(context, tickets.first().url)
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(HomeCopy.TICKETS, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnFill, maxLines = 1)
            if (several) ParachordIcon(AppIcon.ChevronDown, contentDescription = null, size = 16.dp, tint = OnFill)
        }
        if (several) {
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                containerColor = DesignTokens.bgElevated.current(),
            ) {
                tickets.forEach { t ->
                    val dot = serviceColorOrNull(t.source) ?: DesignTokens.textTertiary.current()
                    DropdownMenuItem(
                        leadingIcon = { Box(Modifier.size(8.dp).clip(CircleShape).background(dot)) },
                        text = { Text(t.label, fontSize = 14.sp, color = DesignTokens.textPrimary.current()) },
                        onClick = {
                            expanded = false
                            openHttpLink(context, t.url)
                        },
                    )
                }
            }
        }
    }
}

// ── Ask Shuffleupagus ────────────────────────────────────────────────

/** The Ask field survives recomposition and configuration changes; a new example replaces it only while untouched. */
private val AskFieldSaver = listSaver<AskField, Any>(
    save = { listOf(it.text, it.example, it.touched) },
    restore = { AskField(it[0] as String, it[1] as String, it[2] as Boolean) },
)

/**
 * "Ask Shuffleupagus" (desktop `AskShuffleupagus`): the mark and the title,
 * what Shuffleupagus is, then a field seeded with [example] (muted until
 * edited) and Ask, with the caption below. Ask sends the trimmed, capped
 * prompt to [onAsk] (no confirmation) and is disabled while the field is
 * blank. With no AI plug-in ([chatAvailable] false) it offers "Set up
 * Shuffleupagus" instead of the field.
 */
@Composable
fun HomeAsk(
    example: String,
    chatAvailable: Boolean?,
    onAsk: (String) -> Unit,
    onSetUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Gutter)
            .clip(PanelShape)
            .border(1.dp, DesignTokens.borderDefault.current(), PanelShape)
            .background(DesignTokens.bgSecondary.current())
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReasonMark(outlined = true)
            Text(
                HomeCopy.ASK_TITLE, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                color = DesignTokens.textPrimary.current(),
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(HomeCopy.ASK_EXPLANATION, fontSize = 13.sp, lineHeight = 20.sp, color = DesignTokens.textSecondary.current())
        if (chatAvailable == false) {
            FilledButton(HomeCopy.SET_UP_SHUFFLEUPAGUS, onSetUp)
        } else {
            AskForm(example, onAsk)
        }
    }
}

@Composable
private fun AskForm(example: String, onAsk: (String) -> Unit) {
    var field by rememberSaveable(stateSaver = AskFieldSaver) { mutableStateOf(AskField.seeded(example)) }
    LaunchedEffect(example) { field = field.withExample(example) }
    val submit = { field.prompt?.let(onAsk); Unit }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The field full width and Ask under it: desktop's form wraps the
        // button below the field at a phone's width.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = field.text,
                onValueChange = { field = field.edited(it) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 16.sp,
                    color = if (field.muted) DesignTokens.textTertiary.current() else DesignTokens.textPrimary.current(),
                ),
                shape = RoundedCornerShape(DesignTokens.RADIUS_ART.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = HomeCopy.ASK_FIELD_LABEL },
            )
            FilledButton(HomeCopy.ASK_BUTTON, submit, pill = true, enabled = field.prompt != null)
        }
        Text(HomeCopy.ASK_CAPTION, fontSize = 12.sp, color = DesignTokens.textSecondary.current())
    }
}
