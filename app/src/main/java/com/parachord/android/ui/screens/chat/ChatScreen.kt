package com.parachord.android.ui.screens.chat

import com.parachord.android.ui.theme.FillAccent
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
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.ai.ChatCardEnricher
import com.parachord.android.ai.ChatRole
import com.parachord.shared.ai.ChatBlock
import com.parachord.shared.ai.ChatCardRef
import com.parachord.shared.ai.ChatContent
import com.parachord.shared.ai.ChatInline
import com.parachord.shared.format.countOf
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.icons.ParachordIcons

// ── Message layout ──────────────────────────────────────────────────
//
// The parsing and the punctuation rules around cards are the shared
// ChatContent (desktop's renderChatContent); this file only draws its blocks.

// ── Main Screen ─────────────────────────────────────────────────────

@Composable
fun ChatScreen(
    onBack: () -> Unit,
    onNavigateToArtist: (artistName: String) -> Unit = {},
    onNavigateToAlbum: (albumTitle: String, artistName: String) -> Unit = { _, _ -> },
    onPlayTrack: (title: String, artist: String) -> Unit = { _, _ -> },
    onNavigateToPlaylist: (playlistId: String) -> Unit = {},
    /** A kept `parachord://` link in a message: `DeepLinkViewModel.handleUri`. */
    onOpenInAppLink: (url: String) -> Unit = {},
    viewModel: ChatViewModel = koinViewModel(),
) {
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val progressText by viewModel.progressText.collectAsStateWithLifecycle()
    val availableProviders by viewModel.availableProviders.collectAsStateWithLifecycle()
    val selectedProviderId by viewModel.selectedProviderId.collectAsStateWithLifecycle()

    // Open while this screen is started: a chat screen left in the back stack
    // (under an artist page it opened) doesn't send a prompt queued for a new one.
    LifecycleStartEffect(viewModel) {
        viewModel.setChatOpen(true)
        onStopOrDispose { viewModel.setChatOpen(false) }
    }

    // Playlist cards: open a saved playlist, offer to save one the model
    // never saved, or say there's nothing to open (shared ChatPlaylistCards).
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(viewModel) {
        launch { viewModel.playlistToOpen.collect { onNavigateToPlaylist(it) } }
        launch {
            viewModel.notice.collect {
                android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
    val playlistOffer by viewModel.playlistOffer.collectAsStateWithLifecycle()
    playlistOffer?.let { offer ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = viewModel::dismissPlaylistOffer,
            title = { Text(offer.card.name) },
            text = { Text(offer.message) },
            confirmButton = { TextButton(onClick = viewModel::saveOfferedPlaylist) { Text("Save") } },
            dismissButton = { TextButton(onClick = viewModel::dismissPlaylistOffer) { Text("Cancel") } },
        )
    }

    val hasConfiguredProvider = availableProviders.any { it.isConfigured }
    val displayMessages = messages.filter { it.role == ChatRole.USER || it.role == ChatRole.ASSISTANT }

    // Swipe right to dismiss (chat slides in from right, swipe right to close)
    val density = LocalDensity.current
    val dismissThresholdPx = with(density) { 80.dp.toPx() }

    // Chat links (shared ChatContent keeps only openable ones): a parachord://
    // link goes through the app's own link handling, an http(s) one opens in
    // the browser, and nothing else is opened.
    val linkHandler = remember(context, onOpenInAppLink) {
        object : androidx.compose.ui.platform.UriHandler {
            override fun openUri(uri: String) {
                if (!ChatContent.isOpenableLink(uri)) return
                if (ChatContent.isInAppLink(uri)) {
                    onOpenInAppLink(uri)
                    return
                }
                try {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                            .addCategory(android.content.Intent.CATEGORY_BROWSABLE),
                    )
                } catch (_: android.content.ActivityNotFoundException) {
                    android.widget.Toast.makeText(context, "No app can open this link", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalUriHandler provides linkHandler) {
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
                    onOpenPlaylistCard = viewModel::openPlaylistCard,
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
    onOpenPlaylistCard: (ChatCardRef.Playlist, String) -> Unit,
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
                        onOpenPlaylistCard = onOpenPlaylistCard,
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
                .background(FillAccent)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
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
    onOpenPlaylistCard: (ChatCardRef.Playlist, String) -> Unit,
    cardEnricher: ChatCardEnricher,
) {
    // Messages saved before replies were converted still get desktop's
    // plain-text fallback (it leaves a message that has cards alone).
    val blocks = remember(text) { ChatContent.blocks(ChatContent.convertPlainTextToCards(text)) }
    val cardRow: @Composable (ChatCardRef) -> Unit = { card ->
        ChatCardRow(
            card = card,
            onNavigateToArtist = onNavigateToArtist,
            onNavigateToAlbum = onNavigateToAlbum,
            onPlayTrack = onPlayTrack,
            onOpenPlaylist = { onOpenPlaylistCard(it, text) },
            cardEnricher = cardEnricher,
        )
    }

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
                blocks.forEachIndexed { index, block ->
                    when (block) {
                        is ChatBlock.Header -> Text(
                            text = block.text,
                            fontSize = when (block.level) { 1 -> 18.sp; 2 -> 16.sp; 3, 4 -> 14.sp; else -> 13.sp },
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = if (index > 0) 12.dp else 0.dp, bottom = 4.dp),
                        )
                        is ChatBlock.CardLine -> Box(Modifier.padding(vertical = 4.dp)) { cardRow(block.card) }
                        is ChatBlock.ListItem -> Row(
                            modifier = Modifier.padding(
                                top = if (index == 0) 0.dp else if (block.isCard) 4.dp else 12.dp,
                            ),
                            // A card item centres its number on the card; a text
                            // item keeps the line's top alignment (desktop).
                            verticalAlignment = if (block.isCard) Alignment.CenterVertically else Alignment.Top,
                        ) {
                            Text(
                                text = "${block.number}.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.widthIn(min = 20.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            // The item's own 4 dp gap spaces a card item; no second padding.
                            Column(Modifier.weight(1f)) { ChatInlines(block.inlines, cardRow, cardPadding = 0.dp) }
                        }
                        ChatBlock.Blank -> Spacer(Modifier.height(8.dp))
                        is ChatBlock.Line -> ChatInlines(block.inlines, cardRow)
                    }
                }
            }
        }
    }
}

/**
 * One line's runs: text, links, bold, italic and code flow together; a card
 * breaks the line and is drawn as its own row.
 */
@Composable
private fun ChatInlines(
    inlines: List<ChatInline>,
    cardRow: @Composable (ChatCardRef) -> Unit,
    cardPadding: Dp = 4.dp,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeBackground = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    val runs = mutableListOf<List<ChatInline>>()
    var current = mutableListOf<ChatInline>()
    val cardsAfter = mutableMapOf<Int, ChatCardRef>()
    for (inline in inlines) {
        if (inline is ChatInline.Card) {
            runs += current
            cardsAfter[runs.lastIndex] = inline.card
            current = mutableListOf()
        } else {
            current += inline
        }
    }
    runs += current

    runs.forEachIndexed { i, run ->
        val annotated = buildAnnotatedString {
            for (inline in run) {
                when (inline) {
                    is ChatInline.Text -> append(inline.text)
                    is ChatInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inline.text) }
                    is ChatInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(inline.text) }
                    is ChatInline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                        append(inline.text)
                    }
                    is ChatInline.Link -> withLink(
                        LinkAnnotation.Url(
                            inline.url,
                            TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                        ),
                    ) { append(inline.text) }
                    is ChatInline.Card -> Unit
                }
            }
        }
        // Space next to a card is the card row's gap, not text.
        val shown = if (cardsAfter.containsKey(i) || i > 0) annotated.trimmed() else annotated
        if (shown.text.isNotBlank()) {
            Text(
                text = shown,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        cardsAfter[i]?.let { card -> Box(Modifier.padding(vertical = cardPadding)) { cardRow(card) } }
    }
}

/** [this] without leading and trailing whitespace, styles kept. */
private fun androidx.compose.ui.text.AnnotatedString.trimmed(): androidx.compose.ui.text.AnnotatedString {
    val start = text.indexOfFirst { !it.isWhitespace() }
    if (start < 0) return androidx.compose.ui.text.AnnotatedString("")
    val end = text.indexOfLast { !it.isWhitespace() } + 1
    return subSequence(start, end)
}

// ── Chat Card ───────────────────────────────────────────────────────

@Composable
private fun ChatCardRow(
    card: ChatCardRef,
    onNavigateToArtist: (String) -> Unit,
    onNavigateToAlbum: (String, String) -> Unit,
    onPlayTrack: (String, String) -> Unit,
    onOpenPlaylist: (ChatCardRef.Playlist) -> Unit,
    cardEnricher: ChatCardEnricher,
) {
    // Fetch artwork URL asynchronously from metadata providers
    var artworkUrl by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(card) {
        artworkUrl = when (card) {
            is ChatCardRef.Track -> cardEnricher.getTrackArtwork(card.title, card.artist, card.album)
            is ChatCardRef.Album -> cardEnricher.getAlbumArtwork(card.title, card.artist)
            is ChatCardRef.Artist -> cardEnricher.getArtistImage(card.name)
            is ChatCardRef.Playlist -> null
        }
    }

    val cardBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
    val placeholderName = when (card) {
        is ChatCardRef.Track -> card.artist
        is ChatCardRef.Album -> card.artist
        is ChatCardRef.Artist -> card.name
        is ChatCardRef.Playlist -> card.name
    }
    val isArtist = card is ChatCardRef.Artist

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(cardBg)
            .clickable {
                when (card) {
                    is ChatCardRef.Track -> onPlayTrack(card.title, card.artist)
                    is ChatCardRef.Album -> onNavigateToAlbum(card.title, card.artist)
                    is ChatCardRef.Artist -> onNavigateToArtist(card.name)
                    is ChatCardRef.Playlist -> onOpenPlaylist(card)
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
                is ChatCardRef.Track -> {
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
                is ChatCardRef.Album -> {
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
                is ChatCardRef.Artist -> {
                    Text(
                        text = card.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                is ChatCardRef.Playlist -> {
                    Text(
                        text = card.name,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = card.trackCount.toIntOrNull()?.let { "Playlist · ${countOf(it, "track")}" } ?: "Playlist",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Type badge
        val badgeText = when (card) {
            is ChatCardRef.Track -> "TRACK"
            is ChatCardRef.Album -> "ALBUM"
            is ChatCardRef.Artist -> "ARTIST"
            is ChatCardRef.Playlist -> "PLAYLIST"
        }
        Text(
            text = badgeText,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )

        // Play button for tracks and albums
        if (card is ChatCardRef.Track || card is ChatCardRef.Album) {
            Spacer(modifier = Modifier.width(4.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(FillAccent),
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
                        FillAccent
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                    },
                ),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Send,
                contentDescription = "Send",
                tint = if (text.isNotBlank() && !isLoading) {
                    Color.White
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                },
            )
        }
    }
}
