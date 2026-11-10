package com.parachord.android.ui.screens.chat

import com.parachord.shared.ui.UiMessages
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.ai.ChatCardEnricher
import com.parachord.android.ai.ChatRole
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.icons.ParachordIcons

// ── Card Data Model ─────────────────────────────────────────────────

/** Parsed card from AI response text. */
private sealed class ChatCard {
    data class Track(val title: String, val artist: String, val album: String) : ChatCard()
    data class Album(val title: String, val artist: String) : ChatCard()
    data class Artist(val name: String) : ChatCard()
}

/** A segment of parsed message content — either plain text or a card. */
private sealed class MessageSegment {
    data class PlainText(val text: String) : MessageSegment()
    data class CardSegment(val card: ChatCard) : MessageSegment()
}

/** Card syntax regex: {{type|field1|field2|...}} */
private val CARD_REGEX = Regex("""\{\{(track|artist|album)\|([^}]+?)\}\}""")

/**
 * Fallback: convert bold artist names (**Name**) to card syntax when AI models
 * don't use the card format. Matches the desktop's convertPlainTextToCards().
 *
 * Also converts patterns like:
 * - "Song Title" by Artist Name → track card
 * - "Song Title" by **Artist** from "Album" → track card
 */
private val BOLD_ARTIST_REGEX = Regex("""\*\*([^*]{2,40})\*\*""")
private val QUOTED_TRACK_BY_REGEX = Regex(""""([^"]+)"\s+by\s+([^,.\n]+?)(?:\s+from\s+(?:the\s+album\s+)?"?([^".\n]+?)"?)?(?=[,.\n]|$)""", RegexOption.IGNORE_CASE)

/**
 * Pre-process text: convert plain-text artist/track mentions to card syntax
 * when the AI didn't use the proper format.
 */
private fun convertPlainTextToCards(text: String): String {
    var result = text

    // Convert "Song Title" by Artist (from "Album") → track card
    result = QUOTED_TRACK_BY_REGEX.replace(result) { match ->
        val title = match.groupValues[1].trim()
        val artist = match.groupValues[2].trim().removeSuffix("**").removePrefix("**")
        val album = match.groupValues[3].trim()
        if (album.isNotBlank()) {
            "{{track|$title|$artist|$album}}"
        } else {
            "{{track|$title|$artist|}}"
        }
    }

    // Convert remaining **Bold Name** → artist card (only if not already inside a card)
    result = BOLD_ARTIST_REGEX.replace(result) { match ->
        val name = match.groupValues[1].trim()
        val before = result.substring(0, match.range.first)
        // Don't convert if it's inside a card already or looks like a non-artist label
        if (before.endsWith("{{") || before.endsWith("|") ||
            name.lowercase().let { it.startsWith("note") || it.startsWith("tip") || it.startsWith("important") }
        ) {
            match.value // leave as-is
        } else {
            "{{artist|$name}}"
        }
    }

    return result
}

/** Parse a message string into segments of text and cards. */
private fun parseMessage(text: String): List<MessageSegment> {
    // First pass: convert plain-text mentions to card syntax
    val enrichedText = convertPlainTextToCards(text)

    val segments = mutableListOf<MessageSegment>()
    var lastIndex = 0

    for (match in CARD_REGEX.findAll(enrichedText)) {
        // Add plain text before the card
        if (match.range.first > lastIndex) {
            segments.add(MessageSegment.PlainText(enrichedText.substring(lastIndex, match.range.first)))
        }

        val type = match.groupValues[1]
        val fields = match.groupValues[2].split("|").map { it.trim() }

        val card = when (type) {
            "track" -> {
                val title = fields.getOrElse(0) { "" }
                val artist = fields.getOrElse(1) { "" }
                val album = fields.getOrElse(2) { "" }
                ChatCard.Track(title, artist, album)
            }
            "album" -> {
                val title = fields.getOrElse(0) { "" }
                val artist = fields.getOrElse(1) { "" }
                ChatCard.Album(title, artist)
            }
            "artist" -> {
                val name = fields.getOrElse(0) { "" }
                ChatCard.Artist(name)
            }
            else -> null
        }

        if (card != null) {
            segments.add(MessageSegment.CardSegment(card))
        }

        lastIndex = match.range.last + 1
    }

    // Remaining text after last card
    if (lastIndex < enrichedText.length) {
        segments.add(MessageSegment.PlainText(enrichedText.substring(lastIndex)))
    }

    return segments
}

// ── Main Screen ─────────────────────────────────────────────────────

@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onNavigateToArtist: (artistName: String) -> Unit = {},
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    onPlayTrack: (title: String, artist: String) -> Unit = { _, _ -> },
    viewModel: ChatViewModel = koinViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val progressText by viewModel.progressText.collectAsStateWithLifecycle()
    val availableProviders by viewModel.availableProviders.collectAsStateWithLifecycle()
    val selectedProviderId by viewModel.selectedProviderId.collectAsStateWithLifecycle()

    val hasConfiguredProvider = availableProviders.any { it.isConfigured }
    val displayMessages = messages.filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }

    // Swipe right to dismiss (chat slides in from right, swipe right to close)
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 80.dp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(onBack) {
                var accumulated = 0f
                detectHorizontalDragGestures(
                    onDragStart = { accumulated = 0f },
                    onDragEnd = {
                        if (accumulated > dismissThresholdPx) {
                            onBack()
                        }
                        accumulated = 0f
                    },
                    onDragCancel = { accumulated = 0f },
                    onHorizontalDrag = { _, dragAmount ->
                        accumulated += dragAmount
                    },
                )
            },
    ) {
        // Top Bar
        ChatTopBar(
            selectedProviderId = selectedProviderId,
            availableProviders = availableProviders,
            hasMessages = displayMessages.isNotEmpty(),
            onBack = onBack,
            onSelectProvider = viewModel::selectProvider,
            onClearChat = viewModel::clearChat,
        )

        if (!hasConfiguredProvider) {
            NoProvidersContent(modifier = Modifier.weight(1f))
        } else {
            MessageArea(
                messages = displayMessages,
                isLoading = isLoading,
                progressText = progressText,
                onNavigateToArtist = onNavigateToArtist,
                onNavigateToAlbum = onNavigateToAlbum,
                onPlayTrack = onPlayTrack,
                cardEnricher = viewModel.cardEnricher,
                modifier = Modifier.weight(1f),
            )

            ChatInput(
                isLoading = isLoading,
                onSend = viewModel::sendMessage,
            )
        }
    }
}

// ── Top Bar ─────────────────────────────────────────────────────────

@Composable
private fun ChatTopBar(
    selectedProviderId: String?,
    availableProviders: List<com.parachord.android.ai.AiProviderInfo>,
    hasMessages: Boolean,
    onBack: () -> Unit,
    onSelectProvider: (String) -> Unit,
    onClearChat: () -> Unit,
) {
    var providerMenuExpanded by remember { mutableStateOf(false) }
    val selectedProvider = availableProviders.find { it.id == selectedProviderId }

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = "Back",
                )
            }

            Icon(
                imageVector = ParachordIcons.Shuffleupagus,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Shuffleupagus",
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.weight(1f))

            if (hasMessages) {
                IconButton(onClick = onClearChat) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear chat",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (availableProviders.isNotEmpty()) {
                Box {
                    TextButton(onClick = { providerMenuExpanded = true }) {
                        Text(
                            text = selectedProvider?.name ?: "Select",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    DropdownMenu(
                        expanded = providerMenuExpanded,
                        onDismissRequest = { providerMenuExpanded = false },
                    ) {
                        availableProviders.forEach { provider ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (provider.isConfigured) {
                                            provider.name
                                        } else {
                                            "${provider.name} (not configured)"
                                        },
                                        color = if (provider.isConfigured) {
                                            if (provider.id == selectedProviderId) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            }
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        },
                                    )
                                },
                                onClick = {
                                    if (provider.isConfigured) {
                                        onSelectProvider(provider.id)
                                        providerMenuExpanded = false
                                    }
                                },
                                enabled = provider.isConfigured,
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── No Providers State ──────────────────────────────────────────────

@Composable
private fun NoProvidersContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = UiMessages.NO_AI_PROVIDERS,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = UiMessages.NO_AI_PROVIDERS_HINT,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

// ── Message Area ────────────────────────────────────────────────────

@Composable
private fun MessageArea(
    messages: List<com.parachord.android.ai.ChatMessage>,
    isLoading: Boolean,
    progressText: String?,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String, String) -> Unit,
    onPlayTrack: (String, String) -> Unit,
    cardEnricher: ChatCardEnricher,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty() && !isLoading) {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = ParachordIcons.Shuffleupagus,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Ask me to play something, build a playlist, or control your music.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 48.dp),
            )
        }
    } else {
        val listState = rememberLazyListState()
        val itemCount = messages.size + if (isLoading) 1 else 0

        LaunchedEffect(itemCount) {
            if (itemCount > 0) {
                listState.animateScrollToItem(itemCount - 1)
            }
        }

        val screenWidth = LocalConfiguration.current.screenWidthDp.dp
        val maxBubbleWidth = screenWidth * 0.85f

        LazyColumn(
            modifier = modifier.fillMaxWidth(),
            state = listState,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(messages, key = { System.identityHashCode(it) }) { message ->
                when (message.role) {
                    ChatRole.USER -> UserMessageBubble(
                        text = message.content,
                        maxWidth = maxBubbleWidth,
                    )
                    ChatRole.ASSISTANT -> AssistantMessageBubble(
                        text = message.content,
                        maxWidth = maxBubbleWidth,
                        onNavigateToArtist = onNavigateToArtist,
                        onNavigateToAlbum = onNavigateToAlbum,
                        onPlayTrack = onPlayTrack,
                        cardEnricher = cardEnricher,
                    )
                    else -> {}
                }
            }

            if (isLoading) {
                item {
                    LoadingBubble(
                        progressText = progressText,
                        maxWidth = maxBubbleWidth,
                    )
                }
            }
        }
    }
}

// ── Message Bubbles ─────────────────────────────────────────────────

@Composable
private fun UserMessageBubble(text: String, maxWidth: Dp) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = 16.dp,
                        bottomEnd = 4.dp,
                    ),
                )
                .background(MaterialTheme.colorScheme.primary)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun AssistantMessageBubble(
    text: String,
    maxWidth: Dp,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String, String) -> Unit,
    onPlayTrack: (String, String) -> Unit,
    cardEnricher: ChatCardEnricher,
) {
    val segments = remember(text) { parseMessage(text) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = ParachordIcons.Shuffleupagus,
            contentDescription = null,
            modifier = Modifier
                .size(20.dp)
                .padding(top = 2.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = 4.dp,
                        bottomEnd = 16.dp,
                    ),
                )
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column {
                segments.forEach { segment ->
                    when (segment) {
                        is MessageSegment.PlainText -> {
                            val trimmed = segment.text.trim()
                            if (trimmed.isNotEmpty()) {
                                Text(
                                    text = trimmed,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        is MessageSegment.CardSegment -> {
                            Spacer(modifier = Modifier.height(4.dp))
                            ChatCardRow(
                                card = segment.card,
                                onNavigateToArtist = onNavigateToArtist,
                                onNavigateToAlbum = onNavigateToAlbum,
                                onPlayTrack = onPlayTrack,
                                cardEnricher = cardEnricher,
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                        }
                    }
                }
            }
        }
    }
}

// ── Chat Card ───────────────────────────────────────────────────────

@Composable
private fun ChatCardRow(
    card: ChatCard,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String, String) -> Unit,
    onPlayTrack: (String, String) -> Unit,
    cardEnricher: ChatCardEnricher,
) {
    // Fetch artwork URL asynchronously from metadata providers
    var artworkUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(card) {
        artworkUrl = when (card) {
            is ChatCard.Track -> cardEnricher.getTrackArtwork(card.title, card.artist, card.album)
            is ChatCard.Album -> cardEnricher.getAlbumArtwork(card.title, card.artist)
            is ChatCard.Artist -> cardEnricher.getArtistImage(card.name)
        }
    }

    val cardBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    val placeholderName = when (card) {
        is ChatCard.Track -> card.artist
        is ChatCard.Album -> card.artist
        is ChatCard.Artist -> card.name
    }
    val isArtist = card is ChatCard.Artist

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(cardBg)
            .clickable {
                when (card) {
                    is ChatCard.Track -> onPlayTrack(card.title, card.artist)
                    is ChatCard.Album -> onNavigateToAlbum(card.title, card.artist)
                    is ChatCard.Artist -> onNavigateToArtist(card.name)
                }
            }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Thumbnail — real artwork via AlbumArtCard (circular for artists)
        AlbumArtCard(
            artworkUrl = artworkUrl,
            size = 40.dp,
            cornerRadius = if (isArtist) 20.dp else 4.dp,
            elevation = 0.dp,
            placeholderName = placeholderName,
        )

        Spacer(modifier = Modifier.width(10.dp))

        // Text content
        Column(modifier = Modifier.weight(1f)) {
            when (card) {
                is ChatCard.Track -> {
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildAnnotatedString {
                            append(card.artist)
                            if (card.album.isNotBlank()) {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))) {
                                    append(" \u2022 ${card.album}")
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                is ChatCard.Album -> {
                    Text(
                        text = card.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = card.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                is ChatCard.Artist -> {
                    Text(
                        text = card.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Type badge
        val badgeText = when (card) {
            is ChatCard.Track -> "TRACK"
            is ChatCard.Album -> "ALBUM"
            is ChatCard.Artist -> "ARTIST"
        }
        Text(
            text = badgeText,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )

        // Play button for tracks and albums
        if (card is ChatCard.Track || card is ChatCard.Album) {
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    modifier = Modifier.size(16.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

// ── Loading Bubble ──────────────────────────────────────────────────

@Composable
private fun LoadingBubble(progressText: String?, maxWidth: Dp) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = ParachordIcons.Shuffleupagus,
            contentDescription = null,
            modifier = Modifier
                .size(20.dp)
                .padding(top = 2.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = 4.dp,
                        bottomEnd = 16.dp,
                    ),
                )
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column {
                BouncingDots()
                if (progressText != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = progressText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BouncingDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")

    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(20.dp),
    ) {
        repeat(3) { index ->
            val offsetY by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -6f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 400,
                        delayMillis = index * 150,
                        easing = LinearEasing,
                    ),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot_$index",
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .offset(y = offsetY.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)),
            )
        }
    }
}

// ── Chat Input ──────────────────────────────────────────────────────

@Composable
private fun ChatInput(
    isLoading: Boolean,
    onSend: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = {
                Text(
                    text = "Ask your DJ...",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            singleLine = true,
            enabled = !isLoading,
            shape = RoundedCornerShape(24.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        IconButton(
            onClick = {
                val msg = text.trim()
                if (msg.isNotBlank()) {
                    onSend(msg)
                    text = ""
                }
            },
            enabled = text.isNotBlank() && !isLoading,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (text.isNotBlank() && !isLoading) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    },
                ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (text.isNotBlank() && !isLoading) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
        }
    }
}
