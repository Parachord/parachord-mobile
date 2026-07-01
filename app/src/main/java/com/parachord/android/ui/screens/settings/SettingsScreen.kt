package com.parachord.android.ui.screens.settings

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import com.parachord.android.ui.components.hapticClickable
import com.parachord.android.ui.components.rememberDragHaptics
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import org.koin.androidx.compose.koinViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.BuildConfig
import com.parachord.android.diagnostics.DiagnosticLogExporter
import kotlinx.coroutines.launch
import com.parachord.android.data.scanner.ScanProgress
import com.parachord.android.data.store.SettingsStore
import com.parachord.android.ui.components.ResolverIconSquare
import com.parachord.android.ui.components.ModalBg
import com.parachord.android.ui.components.ModalBgDarker
import com.parachord.android.ui.components.ModalTextActive
import com.parachord.android.ui.components.ModalTextPrimary
import com.parachord.android.ui.components.ModalScrim
import com.parachord.android.ui.components.SectionHeader
import com.parachord.android.ui.components.SwipeableTabLayout
import com.parachord.android.ui.screens.sync.ProviderSyncConfigSheet
import com.parachord.android.ui.screens.sync.SyncSetupSheet
import com.parachord.android.ui.screens.sync.SyncViewModel
import com.parachord.android.ui.theme.ParachordTheme
import com.parachord.android.ui.theme.Success
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ── Plugin Data Model ──────────────────────────────────────────────

private data class PluginInfo(
    val id: String,
    val name: String,
    val resolverId: String, // maps to ResolverIconPaths/ResolverIconColors
    val bgColor: Color,
    val category: PluginCategory,
    val capabilities: List<String>,
    val description: String,
)

private enum class PluginCategory(val label: String) {
    RESOLVER("Content Resolvers"),
    META_SERVICE("Meta Services"),
    CONCERT_SERVICE("Concerts & Events"),
}

/**
 * Convert .axe plugin data from [PluginManager] into the UI's [PluginInfo].
 * Falls back to a hardcoded plugin list when the plugin system hasn't loaded yet.
 */
private fun buildPluginList(
    axePlugins: List<com.parachord.shared.plugin.PluginManager.PluginInfo>
): List<PluginInfo> {
    if (axePlugins.isEmpty()) return builtInPluginsFallback

    return axePlugins.map { axe ->
        val category = when {
            axe.capabilities["resolve"] == true -> PluginCategory.RESOLVER
            axe.capabilities["concerts"] == true -> PluginCategory.CONCERT_SERVICE
            else -> PluginCategory.META_SERVICE
        }
        val caps = axe.capabilities.filter { it.value }.keys.map { key ->
            key.replaceFirstChar { it.uppercase() }
        }
        PluginInfo(
            id = axe.id,
            name = axe.name,
            resolverId = axe.id,
            bgColor = parseColor(axe.color),
            category = category,
            capabilities = caps,
            description = "v${axe.version}",
        )
    }
}

/** Parse a hex color string from the .axe manifest (e.g., "#1DB954"). */
private fun parseColor(hex: String): Color {
    if (hex.isBlank()) return Color(0xFF7C3AED) // default purple
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        Color(0xFF7C3AED)
    }
}

/** Hardcoded fallback used before the .axe plugin system initializes. */
private val builtInPluginsFallback = listOf(
    PluginInfo("spotify", "Spotify", "spotify", Color(0xFF1DB954), PluginCategory.RESOLVER, listOf("Resolve", "Search", "Stream"), "Stream music from Spotify via Connect"),
    PluginInfo("soundcloud", "SoundCloud", "soundcloud", Color(0xFFFF5500), PluginCategory.RESOLVER, listOf("Resolve", "Search", "Stream"), "Search and stream tracks from SoundCloud"),
    PluginInfo("applemusic", "Apple Music", "applemusic", Color(0xFFFA243C), PluginCategory.RESOLVER, listOf("Resolve", "Search", "Stream"), "Search and stream from Apple Music via MusicKit"),
    PluginInfo("localfiles", "Local Files", "localfiles", Color(0xFFA855F7), PluginCategory.RESOLVER, listOf("Resolve", "Browse", "Stream"), "Play music stored on your device"),
    PluginInfo("lastfm", "Last.fm", "lastfm", Color(0xFFD51007), PluginCategory.META_SERVICE, listOf("Metadata", "Scrobble"), "Scrobbling, artist info, and recommendations"),
    PluginInfo("listenbrainz", "ListenBrainz", "listenbrainz", Color(0xFF353070), PluginCategory.META_SERVICE, listOf("Scrobble", "Stats"), "Open-source listening history and statistics"),
    PluginInfo("librefm", "Libre.fm", "librefm", Color(0xFF4CAF50), PluginCategory.META_SERVICE, listOf("Scrobble"), "Free and open music scrobbling service"),
    PluginInfo("discogs", "Discogs", "discogs", Color(0xFF333333), PluginCategory.META_SERVICE, listOf("Metadata", "Bios"), "Artist bios and images from Discogs"),
    PluginInfo("wikipedia", "Wikipedia", "wikipedia", Color(0xFF000000), PluginCategory.META_SERVICE, listOf("Metadata", "Bios"), "Encyclopedia-style artist bios via Wikidata"),
    PluginInfo("chatgpt", "ChatGPT", "chatgpt", Color(0xFF10A37F), PluginCategory.META_SERVICE, listOf("AI DJ", "Chat"), "Generate playlists and chat with AI DJ using ChatGPT"),
    PluginInfo("claude", "Claude", "claude", Color(0xFFD97757), PluginCategory.META_SERVICE, listOf("AI DJ", "Chat"), "Anthropic's Claude AI assistant"),
    PluginInfo("gemini", "Google Gemini", "gemini", Color(0xFF4285F4), PluginCategory.META_SERVICE, listOf("AI DJ", "Chat"), "Generate playlists and chat with Google Gemini"),
    PluginInfo("ticketmaster", "Ticketmaster", "ticketmaster", Color(0xFF026CDF), PluginCategory.CONCERT_SERVICE, listOf("Concerts", "Events"), "Discover concerts and buy tickets"),
    PluginInfo("seatgeek", "SeatGeek", "seatgeek", Color(0xFFFC4C02), PluginCategory.CONCERT_SERVICE, listOf("Concerts", "Events"), "Find live events and tickets"),
)

// ── Settings Screen ────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = koinViewModel(),
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val scrobbling by viewModel.scrobblingEnabled.collectAsStateWithLifecycle()
    val spotifyConnected by viewModel.spotifyConnected.collectAsStateWithLifecycle()
    val hasPreferredSpotifyDevice by viewModel.hasPreferredSpotifyDevice.collectAsStateWithLifecycle()
    val lastFmConnected by viewModel.lastFmConnected.collectAsStateWithLifecycle()
    val listenBrainzConnected by viewModel.listenBrainzConnected.collectAsStateWithLifecycle()
    val libreFmConnected by viewModel.libreFmConnected.collectAsStateWithLifecycle()
    val soundCloudConnected by viewModel.soundCloudConnected.collectAsStateWithLifecycle()
    val soundCloudCredentialsSaved by viewModel.soundCloudCredentialsSaved.collectAsStateWithLifecycle()
    val appleMusicDeveloperToken by viewModel.appleMusicDeveloperToken.collectAsStateWithLifecycle()
    val appleMusicConfigured by viewModel.appleMusicConfigured.collectAsStateWithLifecycle()
    val appleMusicAuthorized by viewModel.appleMusicAuthorized.collectAsStateWithLifecycle()
    val appleMusicSyncEnabled by viewModel.appleMusicSyncEnabled.collectAsStateWithLifecycle()
    val listenBrainzSyncEnabled by viewModel.listenBrainzSyncEnabled.collectAsStateWithLifecycle()
    val appleMusicConnecting by viewModel.appleMusicConnecting.collectAsStateWithLifecycle()
    val persistQueue by viewModel.persistQueue.collectAsStateWithLifecycle()
    val libreFmAuthError by viewModel.libreFmAuthError.collectAsStateWithLifecycle()
    val listenBrainzAuthError by viewModel.listenBrainzAuthError.collectAsStateWithLifecycle()
    val disabledMetaProviders by viewModel.disabledMetaProviders.collectAsStateWithLifecycle()
    val resolverOrder by viewModel.resolverOrder.collectAsStateWithLifecycle()
    val chatGptConnected by viewModel.chatGptConnected.collectAsStateWithLifecycle()
    val claudeConnected by viewModel.claudeConnected.collectAsStateWithLifecycle()
    val geminiConnected by viewModel.geminiConnected.collectAsStateWithLifecycle()
    val sendListeningHistory by viewModel.sendListeningHistory.collectAsStateWithLifecycle()
    val scanProgress by viewModel.scanProgress.collectAsStateWithLifecycle()
    val ticketmasterConnected by viewModel.ticketmasterConnected.collectAsStateWithLifecycle()
    val seatGeekConnected by viewModel.seatGeekConnected.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "SETTINGS",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 0.2.em,
                    ),
                )
            },
            windowInsets = WindowInsets(0),
        )

        SwipeableTabLayout(
            tabs = listOf("Plug-Ins", "Sync", "General", "About"),
        ) { page ->
            when (page) {
                0 -> PlugInsTab(
                    spotifyConnected = spotifyConnected,
                    hasPreferredSpotifyDevice = hasPreferredSpotifyDevice,
                    onClearPreferredSpotifyDevice = { viewModel.clearPreferredSpotifyDevice() },
                    lastFmConnected = lastFmConnected,
                    listenBrainzConnected = listenBrainzConnected,
                    libreFmConnected = libreFmConnected,
                    soundCloudConnected = soundCloudConnected,
                    resolverOrder = resolverOrder,
                    onResolverOrderChanged = { viewModel.setResolverOrder(it) },
                    onSpotifyToggle = {
                        if (spotifyConnected) viewModel.disconnectSpotify()
                        else viewModel.connectSpotify(BuildConfig.SPOTIFY_CLIENT_ID)
                    },
                    onLastFmToggle = {
                        if (lastFmConnected) viewModel.disconnectLastFm()
                        else viewModel.connectLastFm(BuildConfig.LASTFM_API_KEY)
                    },
                    soundCloudCredentialsSaved = soundCloudCredentialsSaved,
                    onSoundCloudSaveCredentials = { id, secret -> viewModel.saveSoundCloudCredentials(id, secret) },
                    onSoundCloudConnect = { viewModel.connectSoundCloud() },
                    onSoundCloudDisconnect = { viewModel.disconnectSoundCloud() },
                    onSoundCloudClearCredentials = { viewModel.clearSoundCloudCredentials() },
                    appleMusicHasToken = appleMusicDeveloperToken != null,
                    appleMusicConfigured = appleMusicConfigured,
                    appleMusicAuthorized = appleMusicAuthorized,
                    appleMusicConnecting = appleMusicConnecting,
                    onAppleMusicSaveToken = { viewModel.setAppleMusicDeveloperToken(it) },
                    onAppleMusicSaveStorefront = { viewModel.setAppleMusicStorefront(it) },
                    onAppleMusicAuthorize = { viewModel.connectAppleMusic() },
                    onAppleMusicDisconnect = { viewModel.disconnectAppleMusic() },
                    onListenBrainzTokenSubmit = { viewModel.setListenBrainzToken(it) },
                    onListenBrainzDisconnect = { viewModel.disconnectListenBrainz() },
                    listenBrainzAuthError = listenBrainzAuthError,
                    onLibreFmConnect = { user, pass -> viewModel.connectLibreFm(user, pass) },
                    onLibreFmDisconnect = { viewModel.disconnectLibreFm() },
                    libreFmAuthError = libreFmAuthError,
                    scrobbling = scrobbling,
                    onScrobblingChanged = { viewModel.setScrobbling(it) },
                    disabledMetaProviders = disabledMetaProviders,
                    onMetaProviderToggle = { name, enabled ->
                        viewModel.setMetaProviderEnabled(name, enabled)
                    },
                    chatGptConnected = chatGptConnected,
                    claudeConnected = claudeConnected,
                    geminiConnected = geminiConnected,
                    onSaveAiConfig = { providerId, apiKey, model ->
                        viewModel.saveAiProviderConfig(providerId, apiKey, model)
                    },
                    onSaveAiModel = { providerId, model ->
                        viewModel.saveAiModel(providerId, model)
                    },
                    getAiModel = { viewModel.getAiModel(it) },
                    onClearAiProvider = { viewModel.clearAiProvider(it) },
                    scanProgress = scanProgress,
                    onScanLocalFiles = { viewModel.scanLocalFiles() },
                    ticketmasterConnected = ticketmasterConnected,
                    seatGeekConnected = seatGeekConnected,
                    onTicketmasterApiKeySubmit = { viewModel.setTicketmasterApiKey(it) },
                    onTicketmasterDisconnect = { viewModel.clearTicketmasterApiKey() },
                    onSeatGeekClientIdSubmit = { viewModel.setSeatGeekClientId(it) },
                    onSeatGeekDisconnect = { viewModel.clearSeatGeekClientId() },
                )
                1 -> SyncTab(
                    spotifyConnected = spotifyConnected,
                    appleMusicAuthorized = appleMusicAuthorized,
                    appleMusicSyncEnabled = appleMusicSyncEnabled,
                    onSetAppleMusicSyncEnabled = { viewModel.setAppleMusicSyncEnabled(it) },
                    listenBrainzConnected = listenBrainzConnected,
                    listenBrainzSyncEnabled = listenBrainzSyncEnabled,
                    onSetListenBrainzSyncEnabled = { viewModel.setListenBrainzSyncEnabled(it) },
                )
                2 -> GeneralTab(
                    themeMode = themeMode,
                    onThemeModeChanged = { viewModel.setThemeMode(it) },
                    persistQueue = persistQueue,
                    onPersistQueueChanged = { viewModel.setPersistQueue(it) },
                )
                3 -> AboutTab()
            }
        }
    }
}

// ── Plug-Ins Tab ───────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PlugInsTab(
    spotifyConnected: Boolean,
    hasPreferredSpotifyDevice: Boolean = false,
    onClearPreferredSpotifyDevice: () -> Unit = {},
    lastFmConnected: Boolean,
    listenBrainzConnected: Boolean,
    libreFmConnected: Boolean,
    soundCloudConnected: Boolean,
    onSpotifyToggle: () -> Unit,
    onLastFmToggle: () -> Unit,
    resolverOrder: List<String> = emptyList(),
    onResolverOrderChanged: (List<String>) -> Unit = {},
    soundCloudCredentialsSaved: Boolean = false,
    onSoundCloudSaveCredentials: (String, String) -> Unit = { _, _ -> },
    onSoundCloudConnect: () -> Unit = {},
    onSoundCloudDisconnect: () -> Unit = {},
    onSoundCloudClearCredentials: () -> Unit = {},
    appleMusicHasToken: Boolean = false,
    appleMusicConfigured: Boolean = false,
    appleMusicAuthorized: Boolean = false,
    appleMusicConnecting: Boolean = false,
    onAppleMusicSaveToken: (String) -> Unit = {},
    onAppleMusicSaveStorefront: (String) -> Unit = {},
    onAppleMusicAuthorize: () -> Unit = {},
    onAppleMusicDisconnect: () -> Unit = {},
    onListenBrainzTokenSubmit: (String) -> Unit,
    onListenBrainzDisconnect: () -> Unit,
    listenBrainzAuthError: String?,
    onLibreFmConnect: (String, String) -> Unit,
    onLibreFmDisconnect: () -> Unit,
    libreFmAuthError: String?,
    scrobbling: Boolean,
    onScrobblingChanged: (Boolean) -> Unit,
    disabledMetaProviders: Set<String> = emptySet(),
    onMetaProviderToggle: (String, Boolean) -> Unit = { _, _ -> },
    chatGptConnected: Boolean = false,
    claudeConnected: Boolean = false,
    geminiConnected: Boolean = false,
    onSaveAiConfig: (String, String, String) -> Unit = { _, _, _ -> },
    onSaveAiModel: (String, String) -> Unit = { _, _ -> },
    getAiModel: (String) -> StateFlow<String> = { MutableStateFlow("") },
    onClearAiProvider: (String) -> Unit = {},
    scanProgress: ScanProgress = ScanProgress(),
    onScanLocalFiles: () -> Unit = {},
    ticketmasterConnected: Boolean = false,
    seatGeekConnected: Boolean = false,
    onTicketmasterApiKeySubmit: (String) -> Unit = {},
    onTicketmasterDisconnect: () -> Unit = {},
    onSeatGeekClientIdSubmit: (String) -> Unit = {},
    onSeatGeekDisconnect: () -> Unit = {},
    onBandsintownApiKeySubmit: (String) -> Unit = {},
    onBandsintownDisconnect: () -> Unit = {},
    onSongkickApiKeySubmit: (String) -> Unit = {},
    onSongkickDisconnect: () -> Unit = {},
) {
    var selectedPlugin by remember { mutableStateOf<PluginInfo?>(null) }

    // Build plugin list from .axe plugins (dynamic) with hardcoded fallback
    val settingsViewModel: SettingsViewModel = koinViewModel()
    val axePlugins by settingsViewModel.loadedPlugins.collectAsStateWithLifecycle()
    val disabledPlugins by settingsViewModel.disabledPlugins.collectAsStateWithLifecycle()
    // #213: per-resolver enable/disable (independent of connection). Empty = all on.
    val activeResolvers by settingsViewModel.activeResolvers.collectAsStateWithLifecycle()
    fun isResolverEnabled(id: String): Boolean = activeResolvers.isEmpty() || id in activeResolvers
    val allPlugins = remember(axePlugins) { buildPluginList(axePlugins) }

    val resolverPlugins = allPlugins.filter { it.category == PluginCategory.RESOLVER }
    val metaServices = allPlugins.filter { it.category == PluginCategory.META_SERVICE }
    val concertServices = allPlugins.filter { it.category == PluginCategory.CONCERT_SERVICE }

    // Lookup can be by id or resolverId
    fun findPlugin(key: String): PluginInfo? =
        allPlugins.find { it.id == key || it.resolverId == key }

    fun isConnected(key: String): Boolean {
        val plugin = findPlugin(key)
        val id = plugin?.id ?: key

        // Check user-disabled plugins first — overrides everything
        if (id in disabledPlugins) return false

        return when (id) {
            "spotify" -> spotifyConnected
            "soundcloud" -> soundCloudConnected
            "applemusic" -> appleMusicConfigured && appleMusicAuthorized
            "lastfm" -> lastFmConnected
            "listenbrainz" -> listenBrainzConnected
            "librefm" -> libreFmConnected
            "local-files", "localfiles" -> true
            "discogs" -> id !in disabledMetaProviders
            "wikipedia" -> id !in disabledMetaProviders
            "chatgpt" -> chatGptConnected
            "claude" -> claudeConnected
            "gemini" -> geminiConnected
            "ticketmaster" -> ticketmasterConnected
            "seatgeek" -> seatGeekConnected
            // .axe plugins without auth — enabled by default
            "bandcamp" -> true
            // .axe plugins that need config — disabled until configured
            // Bandsintown/Songkick — connected when API key is stored
            "bandsintown", "songkick" -> {
                val hasKey = settingsViewModel.hasPluginApiKey(id)
                hasKey
            }
            // YouTube and Ollama are filtered out by PluginManager (mobile: false)
            else -> {
                // Unknown .axe plugin — default to enabled if we know about it
                axePlugins.any { it.id == id }
            }
        }
    }

    // Split resolvers three ways (#213):
    //  • enabled     — connected AND user-enabled → draggable priority list
    //  • off         — connected but user-DISABLED → "Disabled" section (toggle back on)
    //  • unconnected — no credentials yet → "Not connected" section
    // resolverOrder uses resolverId values (e.g. "localfiles", "spotify")
    val enabledResolverIds = resolverOrder.filter { id ->
        val plugin = findPlugin(id)
        plugin != null && plugin.category == PluginCategory.RESOLVER &&
            isConnected(id) && isResolverEnabled(id)
    }
    // Connected but turned off by the user — distinct from "not connected".
    val offResolverPlugins = resolverPlugins.filter {
        isConnected(it.resolverId) && !isResolverEnabled(it.resolverId)
    }
    val disabledResolverPlugins = resolverPlugins.filter { !isConnected(it.resolverId) }

    // Mutable state for drag-to-reorder (synced from persisted order)
    val orderedEnabled = remember(enabledResolverIds) {
        mutableStateListOf(*enabledResolverIds.toTypedArray())
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // Content Resolvers section — enabled (draggable)
        item { SectionHeader("Content Resolvers") }
        if (orderedEnabled.isNotEmpty()) {
            item {
                Text(
                    text = "Drag to reorder playback priority",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            item {
                DraggableResolverRow(
                    resolverOrder = orderedEnabled,
                    findPlugin = ::findPlugin,
                    isConnected = ::isConnected,
                    onReorder = { from, to ->
                        val item = orderedEnabled.removeAt(from)
                        orderedEnabled.add(to, item)
                        // Persist: enabled resolvers in new order + disabled resolvers appended
                        val newOrder = orderedEnabled.toList() +
                            resolverOrder.filter { it !in orderedEnabled }
                        onResolverOrderChanged(newOrder)
                    },
                    onPluginClick = { id -> findPlugin(id)?.let { selectedPlugin = it } },
                )
            }
        }

        // Disabled resolvers — connected but turned off by the user (#213).
        // Tap to open the config sheet and flip "Enabled" back on.
        if (offResolverPlugins.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            item {
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    offResolverPlugins.forEach { plugin ->
                        PluginTile(
                            plugin = plugin,
                            isConnected = false,
                            priorityNumber = null,
                            onClick = { selectedPlugin = plugin },
                            modifier = Modifier.width(100.dp),
                            grayed = true,
                        )
                    }
                    repeat(3 - offResolverPlugins.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // Disabled resolvers — grayed out, not draggable
        if (disabledResolverPlugins.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Not connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
            item {
                androidx.compose.foundation.layout.FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    disabledResolverPlugins.forEach { plugin ->
                        PluginTile(
                            plugin = plugin,
                            isConnected = false,
                            priorityNumber = null,
                            onClick = { selectedPlugin = plugin },
                            modifier = Modifier.width(100.dp),
                            grayed = true,
                        )
                    }
                    // Fill remaining space
                    repeat(3 - disabledResolverPlugins.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(24.dp)) }

        // Meta Services section
        item { SectionHeader("Meta Services") }
        item {
            Text(
                text = "Services for recommendations, metadata, and AI features",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        // Meta Services tiles: wrap to multiple rows
        item {
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                metaServices.forEach { plugin ->
                    val connected = isConnected(plugin.id)
                    PluginTile(
                        plugin = plugin,
                        isConnected = connected,
                        priorityNumber = null,
                        onClick = { selectedPlugin = plugin },
                        modifier = Modifier.width(100.dp),
                        grayed = !connected,
                    )
                }
            }
        }

        // Concerts & Events section
        item { SectionHeader("Concerts & Events") }
        item {
            Text(
                text = "Concert discovery and ticket providers",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
        val concertRows = concertServices.chunked(3)
        concertRows.forEach { rowPlugins ->
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = if (rowPlugins !== concertRows.last()) 12.dp else 0.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    rowPlugins.forEach { plugin ->
                        val connected = isConnected(plugin.id)
                        PluginTile(
                            plugin = plugin,
                            isConnected = connected,
                            priorityNumber = null,
                            onClick = { selectedPlugin = plugin },
                            modifier = Modifier.weight(1f),
                            grayed = !connected,
                        )
                    }
                    repeat(3 - rowPlugins.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // Plugin updates section
        item {
            Spacer(modifier = Modifier.height(24.dp))
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Plugin Updates",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "${allPlugins.size} plugins loaded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val syncStatus by settingsViewModel.pluginSyncStatus.collectAsState()
                TextButton(
                    onClick = { settingsViewModel.syncPlugins() },
                    enabled = syncStatus !is SettingsViewModel.PluginSyncStatus.Syncing,
                ) {
                    when (val status = syncStatus) {
                        is SettingsViewModel.PluginSyncStatus.Syncing -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Checking…")
                        }
                        is SettingsViewModel.PluginSyncStatus.Done -> {
                            val label = when {
                                status.added > 0 || status.updated > 0 ->
                                    "✓ ${status.added + status.updated} updated"
                                status.failed > 0 -> "⚠ ${status.failed} failed"
                                else -> "✓ Up to date"
                            }
                            Text(label)
                        }
                        is SettingsViewModel.PluginSyncStatus.Error -> Text("⚠ Failed")
                        else -> Text("Check for updates")
                    }
                }
            }
        }

        item { Spacer(modifier = Modifier.height(32.dp)) }
    }

    // ── Loved-tracks push state (issue #125) ─────────────────────────
    // Sourced from the local SettingsViewModel reference (line above) —
    // no need to plumb props all the way down from SettingsScreen.
    val lovePushEnabled by settingsViewModel.lovePushEnabled.collectAsStateWithLifecycle()
    val loveBackfillState by settingsViewModel.loveBackfillState.collectAsStateWithLifecycle()

    // Config bottom sheet
    selectedPlugin?.let { plugin ->
        PluginConfigSheet(
            plugin = plugin,
            isConnected = isConnected(plugin.id),
            onDismiss = { selectedPlugin = null },
            isResolver = plugin.category == PluginCategory.RESOLVER,
            resolverEnabled = isResolverEnabled(plugin.resolverId),
            onResolverEnabledChange = { enabled ->
                settingsViewModel.setResolverEnabled(plugin.resolverId, enabled)
            },
            onToggleConnection = {
                when (plugin.id) {
                    "spotify" -> onSpotifyToggle()
                    "soundcloud" -> if (soundCloudConnected) onSoundCloudDisconnect() else onSoundCloudConnect()
                    "applemusic" -> if (appleMusicConfigured && appleMusicAuthorized) onAppleMusicDisconnect() else onAppleMusicAuthorize()
                    "lastfm" -> onLastFmToggle()
                    "discogs" -> onMetaProviderToggle("discogs", !isConnected("discogs"))
                    "wikipedia" -> onMetaProviderToggle("wikipedia", !isConnected("wikipedia"))
                    // .axe plugins without native auth — toggle via disabled plugins set
                    else -> settingsViewModel.setPluginEnabled(plugin.id, !isConnected(plugin.id))
                }
            },
            scrobbling = scrobbling,
            onScrobblingChanged = onScrobblingChanged,
            soundCloudCredentialsSaved = soundCloudCredentialsSaved,
            onSoundCloudSaveCredentials = onSoundCloudSaveCredentials,
            onSoundCloudConnect = onSoundCloudConnect,
            onSoundCloudDisconnect = onSoundCloudDisconnect,
            onSoundCloudClearCredentials = onSoundCloudClearCredentials,
            appleMusicHasToken = appleMusicHasToken,
            appleMusicConfigured = appleMusicConfigured,
            appleMusicAuthorized = appleMusicAuthorized,
            appleMusicConnecting = appleMusicConnecting,
            onAppleMusicSaveToken = onAppleMusicSaveToken,
            onAppleMusicSaveStorefront = onAppleMusicSaveStorefront,
            onAppleMusicAuthorize = onAppleMusicAuthorize,
            onAppleMusicDisconnect = onAppleMusicDisconnect,
            onListenBrainzTokenSubmit = onListenBrainzTokenSubmit,
            onListenBrainzDisconnect = onListenBrainzDisconnect,
            listenBrainzAuthError = listenBrainzAuthError,
            onLibreFmConnect = onLibreFmConnect,
            onLibreFmDisconnect = onLibreFmDisconnect,
            libreFmAuthError = libreFmAuthError,
            onSaveAiConfig = onSaveAiConfig,
            onSaveAiModel = onSaveAiModel,
            getAiModel = getAiModel,
            onClearAiProvider = onClearAiProvider,
            hasPreferredSpotifyDevice = hasPreferredSpotifyDevice,
            onClearPreferredSpotifyDevice = onClearPreferredSpotifyDevice,
            scanProgress = scanProgress,
            onScanLocalFiles = onScanLocalFiles,
            onTicketmasterApiKeySubmit = onTicketmasterApiKeySubmit,
            onTicketmasterDisconnect = onTicketmasterDisconnect,
            onSeatGeekClientIdSubmit = onSeatGeekClientIdSubmit,
            onSeatGeekDisconnect = onSeatGeekDisconnect,
            onBandsintownApiKeySubmit = { settingsViewModel.savePluginApiKey("bandsintown", it) },
            onBandsintownDisconnect = { settingsViewModel.clearPluginApiKey("bandsintown") },
            onSongkickApiKeySubmit = { settingsViewModel.savePluginApiKey("songkick", it) },
            onSongkickDisconnect = { settingsViewModel.clearPluginApiKey("songkick") },
            lovePushEnabled = lovePushEnabled,
            loveBackfillState = loveBackfillState,
            onLovePushToggle = { service, enabled -> settingsViewModel.setLovePushEnabled(service, enabled) },
            onLoveBackfillClick = { service -> settingsViewModel.runLoveBackfill(service) },
            onLoveBackfillDismiss = { settingsViewModel.dismissLoveBackfillState() },
        )
    }
}

// ── Draggable Resolver Row ────────────────────────────────────────

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DraggableResolverRow(
    resolverOrder: List<String>,
    findPlugin: (String) -> PluginInfo?,
    isConnected: (String) -> Boolean,
    onReorder: (from: Int, to: Int) -> Unit,
    onPluginClick: (String) -> Unit,
) {
    val density = LocalDensity.current
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetX by remember { mutableStateOf(0f) }
    val dragHaptics = rememberDragHaptics()

    // Calculate tile width based on available space
    // Each tile is ~1/3 of the row width with spacing
    val tileWidthDp = 100.dp // approximate, will use weight in practice

    // Compute drop target index during drag
    val dropTargetIndex = if (draggingIndex >= 0) {
        val tileWidthApprox = with(density) { 100.dp.toPx() + 12.dp.toPx() }
        val shift = (dragOffsetX / tileWidthApprox).roundToInt()
        (draggingIndex + shift).coerceIn(0, resolverOrder.size - 1)
    } else -1

    // Horizontal scrollable row for drag-to-reorder. FlowRow breaks
    // drag because you can't drag across rows. Fixed 100dp tile width
    // prevents tiles from being too small with many resolvers.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        resolverOrder.forEachIndexed { index, pluginId ->
            val plugin = findPlugin(pluginId) ?: return@forEachIndexed
            val isDragging = draggingIndex == index
            val isDropTarget = dropTargetIndex == index && !isDragging
            val elevation by animateDpAsState(
                targetValue = if (isDragging) 8.dp else 0.dp,
                label = "dragElevation",
            )

            Box(
                modifier = Modifier
                    .width(100.dp)
                    .zIndex(if (isDragging) 1f else 0f)
                    .then(
                        if (isDragging) {
                            Modifier.offset { IntOffset(dragOffsetX.roundToInt(), 0) }
                        } else {
                            Modifier
                        },
                    )
                    .then(
                        if (isDragging) {
                            Modifier.shadow(elevation, RoundedCornerShape(16.dp))
                        } else if (isDropTarget) {
                            Modifier.border(
                                width = 2.dp,
                                color = Color(0xFF7C3AED),
                                shape = RoundedCornerShape(16.dp),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .pointerInput(index, resolverOrder.size) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingIndex = index
                                dragOffsetX = 0f
                                dragHaptics.onDragStart()
                            },
                            onDragEnd = {
                                // Calculate target index based on drag offset
                                val tileWidth = size.width.toFloat() + with(density) { 12.dp.toPx() }
                                val indexShift = (dragOffsetX / tileWidth).roundToInt()
                                val targetIndex = (index + indexShift).coerceIn(0, resolverOrder.size - 1)
                                if (targetIndex != index) {
                                    onReorder(index, targetIndex)
                                    dragHaptics.onDragMove()
                                }
                                draggingIndex = -1
                                dragOffsetX = 0f
                            },
                            onDragCancel = {
                                draggingIndex = -1
                                dragOffsetX = 0f
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                dragOffsetX += dragAmount.x
                            },
                        )
                    },
            ) {
                PluginTile(
                    plugin = plugin,
                    isConnected = isConnected(pluginId),
                    priorityNumber = index + 1,
                    onClick = { onPluginClick(pluginId) },
                )
            }
        }
        // Fill remaining space if fewer than 3
        repeat(3 - resolverOrder.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

// ── Plugin Tile ────────────────────────────────────────────────────

@Composable
private fun PluginTile(
    plugin: PluginInfo,
    isConnected: Boolean,
    priorityNumber: Int? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    grayed: Boolean = false,
) {
    val tileAlpha = if (grayed) 0.35f else 1f

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(plugin.bgColor.copy(alpha = tileAlpha))
                .hapticClickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            // Service logo SVG icon (white, centered)
            ResolverIconSquare(
                resolver = plugin.resolverId,
                size = 56.dp,
                showBackground = false,
            )

            // Connected status badge (top-right)
            if (isConnected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Success),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Enabled",
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }

            // Priority number badge (top-left, resolvers only)
            if (priorityNumber != null && isConnected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .size(22.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$priorityNumber",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF111827),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = plugin.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (grayed) {
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

// ── Plugin Config Bottom Sheet ─────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PluginConfigSheet(
    plugin: PluginInfo,
    isConnected: Boolean,
    onDismiss: () -> Unit,
    onToggleConnection: () -> Unit,
    // #213: per-resolver enable/disable (independent of connection). Only
    // meaningful for resolver-category plugins; shown when connected/usable.
    isResolver: Boolean = false,
    resolverEnabled: Boolean = true,
    onResolverEnabledChange: (Boolean) -> Unit = {},
    scrobbling: Boolean,
    onScrobblingChanged: (Boolean) -> Unit,
    soundCloudCredentialsSaved: Boolean = false,
    onSoundCloudSaveCredentials: (String, String) -> Unit = { _, _ -> },
    onSoundCloudConnect: () -> Unit = {},
    onSoundCloudDisconnect: () -> Unit = {},
    onSoundCloudClearCredentials: () -> Unit = {},
    appleMusicHasToken: Boolean = false,
    appleMusicConfigured: Boolean = false,
    appleMusicAuthorized: Boolean = false,
    appleMusicConnecting: Boolean = false,
    onAppleMusicSaveToken: (String) -> Unit = {},
    onAppleMusicSaveStorefront: (String) -> Unit = {},
    onAppleMusicAuthorize: () -> Unit = {},
    onAppleMusicDisconnect: () -> Unit = {},
    onListenBrainzTokenSubmit: (String) -> Unit = {},
    onListenBrainzDisconnect: () -> Unit = {},
    listenBrainzAuthError: String? = null,
    onLibreFmConnect: (String, String) -> Unit = { _, _ -> },
    onLibreFmDisconnect: () -> Unit = {},
    libreFmAuthError: String? = null,
    onSaveAiConfig: (String, String, String) -> Unit = { _, _, _ -> },
    onSaveAiModel: (String, String) -> Unit = { _, _ -> },
    getAiModel: (String) -> StateFlow<String> = { MutableStateFlow("") },
    onClearAiProvider: (String) -> Unit = {},
    hasPreferredSpotifyDevice: Boolean = false,
    onClearPreferredSpotifyDevice: () -> Unit = {},
    scanProgress: ScanProgress = ScanProgress(),
    onScanLocalFiles: () -> Unit = {},
    onTicketmasterApiKeySubmit: (String) -> Unit = {},
    onTicketmasterDisconnect: () -> Unit = {},
    onSeatGeekClientIdSubmit: (String) -> Unit = {},
    onSeatGeekDisconnect: () -> Unit = {},
    onBandsintownApiKeySubmit: (String) -> Unit = {},
    onBandsintownDisconnect: () -> Unit = {},
    onSongkickApiKeySubmit: (String) -> Unit = {},
    onSongkickDisconnect: () -> Unit = {},
    lovePushEnabled: Map<String, Boolean> = emptyMap(),
    loveBackfillState: com.parachord.android.playback.LovesPushService.BackfillState =
        com.parachord.android.playback.LovesPushService.BackfillState.Idle,
    onLovePushToggle: (service: String, enabled: Boolean) -> Unit = { _, _ -> },
    onLoveBackfillClick: (service: String) -> Unit = {},
    onLoveBackfillDismiss: () -> Unit = {},
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            // Header with colored background and service logo
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(plugin.bgColor)
                    .padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    ResolverIconSquare(
                        resolver = plugin.resolverId,
                        size = 56.dp,
                        showBackground = false,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = plugin.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                    )
                    Text(
                        text = plugin.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Capabilities
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                plugin.capabilities.forEach { cap ->
                    Text(
                        text = cap,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Status",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.weight(1f))
                // The chip must reflect whether the resolver is actually WORKING,
                // not just connected. A resolver that's connected but toggled OFF
                // (active_resolvers, #213) doesn't resolve or show badges — showing
                // "ENABLED" for it is what made a Spotify-only user think Spotify
                // worked when the toggle was off (#338). "OFF" distinguishes the
                // toggled-off case from a genuinely not-connected one.
                val statusEnabled = isConnected && (!isResolver || resolverEnabled)
                val statusText = when {
                    statusEnabled -> "ENABLED"
                    isConnected && isResolver -> "OFF"
                    else -> "DISABLED"
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            if (statusEnabled) Success.copy(alpha = 0.15f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (statusEnabled) Success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // #213: per-resolver Enabled toggle — turn a resolver off WITHOUT
            // disconnecting it. Shown for connected/usable resolvers only;
            // gates the resolver via active_resolvers (playback + badges),
            // leaving credentials + metadata untouched.
            if (isResolver && isConnected) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enabled",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Use this resolver for playback",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = resolverEnabled, onCheckedChange = onResolverEnabledChange)
                }
            }

            // Plugin-specific config
            when (plugin.id) {
                "spotify" -> SpotifyConfig(
                    isConnected = isConnected,
                    onToggle = onToggleConnection,
                    hasPreferredDevice = hasPreferredSpotifyDevice,
                    onClearPreferredDevice = onClearPreferredSpotifyDevice,
                )

                "soundcloud" -> SoundCloudConfig(
                    isConnected = isConnected,
                    credentialsSaved = soundCloudCredentialsSaved,
                    onSaveCredentials = onSoundCloudSaveCredentials,
                    onConnect = onSoundCloudConnect,
                    onDisconnect = onSoundCloudDisconnect,
                    onClearCredentials = onSoundCloudClearCredentials,
                )
                "applemusic" -> AppleMusicConfig(
                    hasToken = appleMusicHasToken,
                    configured = appleMusicConfigured,
                    authorized = appleMusicAuthorized,
                    connecting = appleMusicConnecting,
                    onSaveToken = onAppleMusicSaveToken,
                    onSaveStorefront = onAppleMusicSaveStorefront,
                    onConnect = onAppleMusicAuthorize,
                    onDisconnect = onAppleMusicDisconnect,
                )
                "local-files", "localfiles" -> LocalFilesConfig(
                    scanProgress = scanProgress,
                    onScanLocalFiles = onScanLocalFiles,
                )
                "lastfm" -> LastFmConfig(
                    isConnected = isConnected,
                    onToggle = onToggleConnection,
                    scrobbling = scrobbling,
                    onScrobblingChanged = onScrobblingChanged,
                    lovePushEnabled = lovePushEnabled,
                    loveBackfillState = loveBackfillState,
                    onLovePushToggle = onLovePushToggle,
                    onLoveBackfillClick = onLoveBackfillClick,
                    onLoveBackfillDismiss = onLoveBackfillDismiss,
                )
                "listenbrainz" -> ListenBrainzConfig(
                    isConnected = isConnected,
                    onTokenSubmit = onListenBrainzTokenSubmit,
                    onDisconnect = onListenBrainzDisconnect,
                    authError = listenBrainzAuthError,
                    scrobbling = scrobbling,
                    onScrobblingChanged = onScrobblingChanged,
                    lovePushEnabled = lovePushEnabled,
                    loveBackfillState = loveBackfillState,
                    onLovePushToggle = onLovePushToggle,
                    onLoveBackfillClick = onLoveBackfillClick,
                    onLoveBackfillDismiss = onLoveBackfillDismiss,
                )
                "librefm" -> LibreFmConfig(
                    isConnected = isConnected,
                    onConnect = onLibreFmConnect,
                    onDisconnect = onLibreFmDisconnect,
                    authError = libreFmAuthError,
                    scrobbling = scrobbling,
                    onScrobblingChanged = onScrobblingChanged,
                )
                "discogs" -> ToggleableMetaConfig(
                    isEnabled = isConnected,
                    onToggle = onToggleConnection,
                    enabledText = "Discogs metadata provider is active",
                    disabledText = "Enable Discogs to get artist bios and images from the Discogs community database.",
                )
                "wikipedia" -> ToggleableMetaConfig(
                    isEnabled = isConnected,
                    onToggle = onToggleConnection,
                    enabledText = "Wikipedia metadata provider is active",
                    disabledText = "Enable Wikipedia to get encyclopedia-style artist bios and images via Wikidata.",
                )
                "chatgpt", "claude", "gemini" -> {
                    val currentModel by getAiModel(plugin.id).collectAsStateWithLifecycle()
                    AiProviderConfig(
                        providerId = plugin.id,
                        isConnected = isConnected,
                        onSaveConfig = onSaveAiConfig,
                        onSaveModel = onSaveAiModel,
                        onClear = onClearAiProvider,
                        currentModel = currentModel,
                    )
                }
                "ticketmaster" -> ConcertProviderConfig(
                    isConnected = isConnected,
                    keyLabel = "API Key",
                    keyHint = "Enter your Ticketmaster API key",
                    devPortalLabel = "developer-acct.ticketmaster.com",
                    devPortalUrl = "https://developer-acct.ticketmaster.com/",
                    onSubmitKey = onTicketmasterApiKeySubmit,
                    onDisconnect = onTicketmasterDisconnect,
                )
                "seatgeek" -> ConcertProviderConfig(
                    isConnected = isConnected,
                    keyLabel = "Client ID",
                    keyHint = "Enter your SeatGeek Client ID",
                    devPortalLabel = "seatgeek.com/account/develop",
                    devPortalUrl = "https://seatgeek.com/account/develop",
                    onSubmitKey = onSeatGeekClientIdSubmit,
                    onDisconnect = onSeatGeekDisconnect,
                )
                "bandsintown" -> ConcertProviderConfig(
                    isConnected = isConnected,
                    keyLabel = "App ID",
                    keyHint = "Enter your Bandsintown App ID",
                    devPortalLabel = "artists.bandsintown.com",
                    devPortalUrl = "https://artists.bandsintown.com/support/api-installation",
                    onSubmitKey = onBandsintownApiKeySubmit,
                    onDisconnect = onBandsintownDisconnect,
                )
                "songkick" -> ConcertProviderConfig(
                    isConnected = isConnected,
                    keyLabel = "API Key",
                    keyHint = "Enter your Songkick API key",
                    devPortalLabel = "songkick.com/developer",
                    devPortalUrl = "https://www.songkick.com/developer",
                    onSubmitKey = onSongkickApiKeySubmit,
                    onDisconnect = onSongkickDisconnect,
                )
                // Generic .axe plugin toggle — no special config UI
                else -> ToggleableMetaConfig(
                    isEnabled = isConnected,
                    onToggle = onToggleConnection,
                    enabledText = "${plugin.name} is enabled",
                    disabledText = "Enable ${plugin.name}",
                )
            }
        }
    }
}

// ── Spotify Config ─────────────────────────────────────────────────

@Composable
private fun SpotifyConfig(
    isConnected: Boolean,
    onToggle: () -> Unit,
    hasPreferredDevice: Boolean = false,
    onClearPreferredDevice: () -> Unit = {},
) {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Connection",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (isConnected) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Success,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Spotify Premium connected",
                style = MaterialTheme.typography.bodySmall,
                color = Success,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onToggle) {
            Text("Disconnect", color = MaterialTheme.colorScheme.error)
        }

        // Preferred Device setting (matches desktop's "Preferred Device" row)
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Preferred Device",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (hasPreferredDevice) {
            Text(
                text = "A preferred device is saved. It will be used automatically for playback.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onClearPreferredDevice) {
                Text("Clear", color = MaterialTheme.colorScheme.error)
            }
        } else {
            Text(
                text = "No preferred device set. You\u2019ll be prompted to choose when multiple devices are available.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Text(
            text = "Connect your Spotify Premium account to stream music.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onToggle) {
            Text("Connect Spotify", color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ── SoundCloud Config ──────────────────────────────────────────────

@Composable
private fun SoundCloudConfig(
    isConnected: Boolean,
    credentialsSaved: Boolean,
    onSaveCredentials: (String, String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onClearCredentials: () -> Unit,
) {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    // Connection status
    Text(
        text = "Connection",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (isConnected) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Success,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "SoundCloud connected",
                style = MaterialTheme.typography.bodySmall,
                color = Success,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onDisconnect) {
            Text("Disconnect", color = MaterialTheme.colorScheme.error)
        }
    } else {
        Text(
            text = if (credentialsSaved) "Credentials saved. Tap Connect to authorize with SoundCloud."
            else "Add your SoundCloud app credentials below, then connect.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(
            onClick = onConnect,
            enabled = credentialsSaved,
        ) {
            Text(
                "Connect SoundCloud",
                color = if (credentialsSaved) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(12.dp))

    // Advanced — Client ID & Secret
    var advancedOpen by remember { mutableStateOf(!credentialsSaved && !isConnected) }
    TextButton(onClick = { advancedOpen = !advancedOpen }) {
        Text(
            text = if (advancedOpen) "▾ Advanced" else "▸ Advanced",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    if (advancedOpen) {
        val uriHandler = LocalUriHandler.current
        Text(
            text = "Create a SoundCloud app to get your Client ID and Secret:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "soundcloud.com/you/apps →",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.hapticClickable {
                uriHandler.openUri("https://soundcloud.com/you/apps")
            },
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Set the Redirect URI to:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        SelectionContainer {
            Text(
                text = "parachord://auth/callback/soundcloud",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "SoundCloud only allows one redirect URI per app, so you'll need a separate app for Android.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        var clientId by remember { mutableStateOf("") }
        var clientSecret by remember { mutableStateOf("") }

        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it },
            label = { Text("Client ID") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = clientSecret,
            onValueChange = { clientSecret = it },
            label = { Text("Client Secret") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(modifier = Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    if (clientId.isNotBlank() && clientSecret.isNotBlank()) {
                        onSaveCredentials(clientId.trim(), clientSecret.trim())
                        clientId = ""
                        clientSecret = ""
                    }
                },
                enabled = clientId.isNotBlank() && clientSecret.isNotBlank(),
            ) {
                Text("Save Credentials", color = MaterialTheme.colorScheme.primary)
            }
            if (credentialsSaved) {
                TextButton(onClick = onClearCredentials) {
                    Text("Clear", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        if (credentialsSaved) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Success,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Client credentials saved",
                    style = MaterialTheme.typography.bodySmall,
                    color = Success,
                )
            }
        }
    }
}

// ── Apple Music Config ─────────────────────────────────────────────

@Composable
private fun AppleMusicConfig(
    hasToken: Boolean,
    configured: Boolean,
    authorized: Boolean,
    connecting: Boolean = false,
    onSaveToken: (String) -> Unit,
    onSaveStorefront: (String) -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val hasBuiltInToken = BuildConfig.APPLE_MUSIC_DEVELOPER_TOKEN.isNotBlank()

    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    // Connection status
    Text(
        text = "Connection",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (configured && authorized) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Success,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Apple Music connected",
                style = MaterialTheme.typography.bodySmall,
                color = Success,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onDisconnect) {
            Text("Disconnect", color = MaterialTheme.colorScheme.error)
        }
    } else if (hasToken) {
        Text(
            text = "Sign in with your Apple ID to stream from Apple Music.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (connecting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Connecting…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            TextButton(onClick = onConnect) {
                Text("Connect Apple Music", color = MaterialTheme.colorScheme.primary)
            }
        }
    } else {
        Text(
            text = "Add your MusicKit developer token below, then connect.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(12.dp))

    // Advanced — Developer Token & Storefront
    var advancedOpen by remember { mutableStateOf(!hasToken && !hasBuiltInToken) }
    TextButton(onClick = { advancedOpen = !advancedOpen }) {
        Text(
            text = if (advancedOpen) "▾ Advanced" else "▸ Advanced",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    if (advancedOpen) {
        if (hasBuiltInToken && !hasToken) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Success,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Using built-in developer token",
                    style = MaterialTheme.typography.bodySmall,
                    color = Success,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "You can override by entering a custom token below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "A MusicKit developer token is required to access the Apple Music catalog.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        var tokenInput by remember { mutableStateOf("") }

        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it },
            label = { Text("Developer Token") },
            placeholder = { Text("eyJhbGciOiJFUzI1NiIs…") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))

        TextButton(
            onClick = {
                if (tokenInput.isNotBlank()) {
                    onSaveToken(tokenInput.trim())
                    tokenInput = ""
                }
            },
            enabled = tokenInput.isNotBlank(),
        ) {
            Text(
                "Save Token",
                color = if (tokenInput.isNotBlank()) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (hasToken) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Success,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Custom developer token saved",
                    style = MaterialTheme.typography.bodySmall,
                    color = Success,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Storefront
        var storefrontInput by remember { mutableStateOf("") }

        OutlinedTextField(
            value = storefrontInput,
            onValueChange = { storefrontInput = it },
            label = { Text("Storefront") },
            placeholder = { Text("us") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Country code for the Apple Music catalog (default: us)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Spacer(modifier = Modifier.height(12.dp))

        TextButton(
            onClick = {
                val value = storefrontInput.trim().ifBlank { "us" }
                onSaveStorefront(value)
                storefrontInput = ""
            },
        ) {
            Text("Save Storefront", color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ── Local Files Config ─────────────────────────────────────────────

@Composable
private fun LocalFilesConfig(
    scanProgress: ScanProgress = ScanProgress(),
    onScanLocalFiles: () -> Unit = {},
) {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Library",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Spacer(modifier = Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = Icons.Filled.FolderOpen,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "Device storage",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "Local files are automatically scanned from your device's music directories.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    )

    Spacer(modifier = Modifier.height(16.dp))

    if (scanProgress.isScanning) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Scanning\u2026 ${scanProgress.tracksFound} tracks, ${scanProgress.albumsFound} albums",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        OutlinedButton(
            onClick = onScanLocalFiles,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (scanProgress.tracksFound > 0)
                    "Rescan (${scanProgress.tracksFound} tracks found)"
                else
                    "Scan local files",
            )
        }
    }
}

// ── Concert Provider Config ────────────────────────────────────────


@Composable
private fun ConcertProviderConfig(
    isConnected: Boolean,
    keyLabel: String,
    keyHint: String,
    devPortalLabel: String,
    devPortalUrl: String,
    onSubmitKey: (String) -> Unit,
    onDisconnect: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var apiKeyInput by remember { mutableStateOf("") }

    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    // API Key section
    Text(
        text = keyLabel,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (isConnected) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = Color(0xFF10B981),
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "$keyLabel saved",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDisconnect) {
                Text("Remove", color = MaterialTheme.colorScheme.error)
            }
        }
    } else {
        Text(
            text = "Get your $keyLabel at:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "$devPortalLabel \u2192",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.hapticClickable {
                uriHandler.openUri(devPortalUrl)
            },
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = apiKeyInput,
            onValueChange = { apiKeyInput = it },
            label = { Text(keyHint) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = {
                if (apiKeyInput.isNotBlank()) {
                    onSubmitKey(apiKeyInput.trim())
                    apiKeyInput = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = apiKeyInput.isNotBlank(),
            shape = RoundedCornerShape(8.dp),
        ) {
            Text("Save")
        }
    }

}

// ── Last.fm Config ─────────────────────────────────────────────────

@Composable
private fun LastFmConfig(
    isConnected: Boolean,
    onToggle: () -> Unit,
    scrobbling: Boolean,
    onScrobblingChanged: (Boolean) -> Unit,
    lovePushEnabled: Map<String, Boolean>,
    loveBackfillState: com.parachord.android.playback.LovesPushService.BackfillState,
    onLovePushToggle: (service: String, enabled: Boolean) -> Unit,
    onLoveBackfillClick: (service: String) -> Unit,
    onLoveBackfillDismiss: () -> Unit,
) {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Connection",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (isConnected) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Success,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Last.fm connected",
                style = MaterialTheme.typography.bodySmall,
                color = Success,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onToggle) {
            Text("Disconnect", color = MaterialTheme.colorScheme.error)
        }
    } else {
        Text(
            text = "Connect your Last.fm account for scrobbling and recommendations.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onToggle) {
            Text("Connect Last.fm", color = MaterialTheme.colorScheme.primary)
        }
    }

    // Scrobbling toggle
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Scrobbling",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Send listening history to Last.fm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = scrobbling,
            onCheckedChange = onScrobblingChanged,
            enabled = isConnected,
        )
    }

    LovePushControls(
        serviceId = "lastfm",
        serviceLabel = "Last.fm",
        isConnected = isConnected,
        lovePushEnabled = lovePushEnabled,
        backfillState = loveBackfillState,
        onLovePushToggle = onLovePushToggle,
        onBackfillClick = onLoveBackfillClick,
        onBackfillDismiss = onLoveBackfillDismiss,
    )
}

// ── ListenBrainz Config ────────────────────────────────────────────

@Composable
private fun ListenBrainzConfig(
    isConnected: Boolean,
    onTokenSubmit: (String) -> Unit,
    onDisconnect: () -> Unit,
    authError: String? = null,
    scrobbling: Boolean,
    onScrobblingChanged: (Boolean) -> Unit,
    lovePushEnabled: Map<String, Boolean>,
    loveBackfillState: com.parachord.android.playback.LovesPushService.BackfillState,
    onLovePushToggle: (service: String, enabled: Boolean) -> Unit,
    onLoveBackfillClick: (service: String) -> Unit,
    onLoveBackfillDismiss: () -> Unit,
) {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Connection",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (isConnected) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Success,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "ListenBrainz connected",
                style = MaterialTheme.typography.bodySmall,
                color = Success,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onDisconnect) {
            Text("Disconnect", color = MaterialTheme.colorScheme.error)
        }
    } else {
        val uriHandler = LocalUriHandler.current
        val linkColor = MaterialTheme.colorScheme.primary
        val textColor = MaterialTheme.colorScheme.onSurfaceVariant
        val annotatedText = remember(linkColor, textColor) {
            buildAnnotatedString {
                withStyle(SpanStyle(color = textColor)) {
                    append("Enter your ListenBrainz user token to enable scrobbling. Find it at ")
                }
                pushLink(LinkAnnotation.Clickable("lb-settings") {
                    uriHandler.openUri("https://listenbrainz.org/settings/")
                })
                withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    append("listenbrainz.org/settings/")
                }
                pop()
                withStyle(SpanStyle(color = textColor)) {
                    append(".")
                }
            }
        }
        Text(
            text = annotatedText,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(modifier = Modifier.height(12.dp))

        var token by remember { mutableStateOf("") }
        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("User Token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = {
                if (token.isNotBlank()) {
                    onTokenSubmit(token.trim())
                    token = ""
                }
            },
            enabled = token.isNotBlank(),
        ) {
            Text("Save Token", color = MaterialTheme.colorScheme.primary)
        }

        if (authError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = authError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    // Scrobbling toggle
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Scrobbling",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Send listening history to ListenBrainz",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = scrobbling,
            onCheckedChange = onScrobblingChanged,
            enabled = isConnected,
        )
    }

    LovePushControls(
        serviceId = "listenbrainz",
        serviceLabel = "ListenBrainz",
        isConnected = isConnected,
        lovePushEnabled = lovePushEnabled,
        backfillState = loveBackfillState,
        onLovePushToggle = onLovePushToggle,
        onBackfillClick = onLoveBackfillClick,
        onBackfillDismiss = onLoveBackfillDismiss,
    )
}

// ── Love-Push Controls (issue #125) ────────────────────────────────

/**
 * Per-service "love-push" toggle row + manual backfill button. Used by
 * both [LastFmConfig] and [ListenBrainzConfig] (Libre.fm has no
 * equivalent endpoint per desktop's design doc and is excluded).
 *
 * Both rows are disabled until the service is connected (`isConnected =
 * true`) — flipping them earlier would write a Settings entry that goes
 * nowhere, the toggle goes back to false on the first push attempt
 * because the scrobbler returns `isEnabled() = false`.
 *
 * Backfill state is shared across services (a single
 * [LovesPushService.BackfillState] StateFlow) — running one service's
 * backfill grays out the OTHER service's button mid-run, since the
 * service serializes with a 1-req/sec pacing loop. Done state shows a
 * compact summary line "Pushed N, skipped M, failed K" + a Dismiss
 * button that resets to Idle.
 */
@Composable
private fun LovePushControls(
    serviceId: String,
    serviceLabel: String,
    isConnected: Boolean,
    lovePushEnabled: Map<String, Boolean>,
    backfillState: com.parachord.android.playback.LovesPushService.BackfillState,
    onLovePushToggle: (service: String, enabled: Boolean) -> Unit,
    onBackfillClick: (service: String) -> Unit,
    onBackfillDismiss: () -> Unit,
) {
    val pushOn = lovePushEnabled[serviceId] == true
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Push loved tracks to $serviceLabel",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "When you favorite a track, send it as a love to $serviceLabel.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = pushOn,
            onCheckedChange = { onLovePushToggle(serviceId, it) },
            enabled = isConnected,
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    val running = backfillState as?
        com.parachord.android.playback.LovesPushService.BackfillState.Running
    val done = backfillState as?
        com.parachord.android.playback.LovesPushService.BackfillState.Done
    val isThisServiceRunning = running?.service == serviceId
    val isThisServiceDone = done?.service == serviceId
    val isOtherServiceRunning = running != null && running.service != serviceId

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Backfill loved tracks → $serviceLabel",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = when {
                    isThisServiceRunning ->
                        // running != null is implied by isThisServiceRunning (which is
                        // running?.service == serviceId) but the compiler can't infer
                        // it through the cast; the safe-call here keeps it null-safe.
                        running?.let { "Pushing… ${it.pushed + it.skipped + it.failed} / ${it.total}" } ?: ""
                    isThisServiceDone ->
                        done?.let { "Pushed ${it.pushed}, skipped ${it.skipped}, failed ${it.failed}." } ?: ""
                    else ->
                        "One-time push of every track in your collection. Existing pushes are skipped via the per-track cache."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isThisServiceDone) {
            TextButton(onClick = onBackfillDismiss) {
                Text("Dismiss")
            }
        } else {
            TextButton(
                onClick = { onBackfillClick(serviceId) },
                enabled = isConnected && !isThisServiceRunning && !isOtherServiceRunning,
            ) {
                Text(if (isThisServiceRunning) "Running…" else "Backfill")
            }
        }
    }
}

// ── Libre.fm Config ───────────────────────────────────────────────

@Composable
private fun LibreFmConfig(
    isConnected: Boolean,
    onConnect: (String, String) -> Unit,
    onDisconnect: () -> Unit,
    authError: String?,
    scrobbling: Boolean,
    onScrobblingChanged: (Boolean) -> Unit,
) {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Connection",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (isConnected) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Success,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Libre.fm connected",
                style = MaterialTheme.typography.bodySmall,
                color = Success,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextButton(onClick = onDisconnect) {
            Text("Disconnect", color = MaterialTheme.colorScheme.error)
        }
    } else {
        Text(
            text = "Sign in with your Libre.fm account to enable scrobbling.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        var username by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }

        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
        )

        if (authError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = authError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        TextButton(
            onClick = {
                if (username.isNotBlank() && password.isNotBlank()) {
                    onConnect(username.trim(), password)
                }
            },
            enabled = username.isNotBlank() && password.isNotBlank(),
        ) {
            Text("Sign In", color = MaterialTheme.colorScheme.primary)
        }
    }

    // Scrobbling toggle
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Scrobbling",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Send listening history to Libre.fm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = scrobbling,
            onCheckedChange = onScrobblingChanged,
            enabled = isConnected,
        )
    }
}

// ── Toggleable Meta Provider Config (Discogs, Wikipedia) ──────────

@Composable
private fun ToggleableMetaConfig(
    isEnabled: Boolean,
    onToggle: () -> Unit,
    enabledText: String,
    disabledText: String,
) {
    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Enabled",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (isEnabled) enabledText else disabledText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = isEnabled,
            onCheckedChange = { onToggle() },
        )
    }
}

// ── AI Provider Config ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AiProviderConfig(
    providerId: String,
    isConnected: Boolean,
    onSaveConfig: (String, String, String) -> Unit,
    onSaveModel: (String, String) -> Unit = { _, _ -> },
    onClear: (String) -> Unit,
    currentModel: String = "",
) {
    val uriHandler = LocalUriHandler.current

    val (linkText, linkUrl) = when (providerId) {
        "chatgpt" -> "platform.openai.com \u2192" to "https://platform.openai.com/api-keys"
        "claude" -> "console.anthropic.com \u2192" to "https://console.anthropic.com/settings/keys"
        "gemini" -> "ai.google.dev \u2192" to "https://aistudio.google.com/apikey"
        else -> "" to ""
    }

    // Hardcoded model lists as fallback. These match the defaults in each .axe plugin.
    // TODO: Replace with dynamic listModels() from PluginManager once the .axe plugins
    // are wired into the settings flow (requires API key to fetch live model lists).
    val models = when (providerId) {
        "chatgpt" -> listOf("gpt-4o-mini", "gpt-4o", "gpt-4-turbo", "gpt-3.5-turbo")
        "claude" -> listOf("claude-sonnet-4-20250514", "claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022")
        "gemini" -> listOf("gemini-2.5-flash", "gemini-2.0-flash", "gemini-2.0-flash-lite", "gemini-1.5-pro", "gemini-1.5-flash")
        "ollama" -> listOf("llama3", "mistral", "codellama", "phi3")
        else -> emptyList()
    }

    Spacer(modifier = Modifier.height(16.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(16.dp))

    Text(
        text = "Connection",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
    )
    Spacer(modifier = Modifier.height(8.dp))

    if (isConnected) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = Success,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Connected",
                style = MaterialTheme.typography.bodySmall,
                color = Success,
            )
        }
    } else {
        Text(
            text = "Add your API key below",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = linkText,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.hapticClickable { uriHandler.openUri(linkUrl) },
    )

    Spacer(modifier = Modifier.height(12.dp))

    var apiKey by remember { mutableStateOf("") }
    var showSavedConfirmation by remember { mutableStateOf(false) }

    // Dynamic model lists from .axe plugins — fetch when connected
    val settingsViewModel: SettingsViewModel = koinViewModel()
    val dynamicModels by settingsViewModel.dynamicModels.collectAsStateWithLifecycle()
    val modelsLoading by settingsViewModel.modelsLoading.collectAsStateWithLifecycle()

    // Use dynamic models if available, fall back to hardcoded
    val effectiveModels = if (dynamicModels.isNotEmpty()) {
        dynamicModels.map { it.value }
    } else {
        models
    }

    // Fetch dynamic models when this provider is connected
    LaunchedEffect(providerId, isConnected) {
        if (isConnected) settingsViewModel.loadModelsForProvider(providerId)
    }

    // Initialize to the currently saved model, falling back to the first available
    var selectedModel by remember(effectiveModels) {
        mutableStateOf(
            if (currentModel.isNotBlank() && currentModel in effectiveModels) currentModel
            else effectiveModels.firstOrNull() ?: currentModel
        )
    }
    var modelDropdownExpanded by remember { mutableStateOf(false) }

    val sendListeningHistory by settingsViewModel.sendListeningHistory.collectAsStateWithLifecycle()

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(12.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Include listening history",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "Send your listening data to improve recommendations",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = sendListeningHistory,
            onCheckedChange = { settingsViewModel.setSendListeningHistory(it) },
        )
    }

    Spacer(modifier = Modifier.height(12.dp))
    HorizontalDivider()
    Spacer(modifier = Modifier.height(12.dp))

    OutlinedTextField(
        value = apiKey,
        onValueChange = {
            apiKey = it
            showSavedConfirmation = false
        },
        label = { Text("API Key") },
        placeholder = {
            if (isConnected && apiKey.isEmpty()) {
                Text("••••••••••••••••", color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            }
        },
        supportingText = if (showSavedConfirmation) {
            { Text("API key saved", color = MaterialTheme.colorScheme.primary) }
        } else if (isConnected && apiKey.isEmpty()) {
            { Text("Key saved — enter a new key to replace it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else null,
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Model dropdown
    ExposedDropdownMenuBox(
        expanded = modelDropdownExpanded,
        onExpandedChange = { modelDropdownExpanded = it },
    ) {
        OutlinedTextField(
            value = selectedModel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Model") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelDropdownExpanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
            singleLine = true,
        )
        ExposedDropdownMenu(
            expanded = modelDropdownExpanded,
            onDismissRequest = { modelDropdownExpanded = false },
        ) {
            effectiveModels.forEach { model ->
                DropdownMenuItem(
                    text = { Text(model, style = MaterialTheme.typography.bodyMedium) },
                    onClick = {
                        selectedModel = model
                        modelDropdownExpanded = false
                        // Auto-save model change (no need to re-enter API key)
                        if (isConnected) onSaveModel(providerId, model)
                    },
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(12.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(
            onClick = {
                if (apiKey.isNotBlank()) {
                    onSaveConfig(providerId, apiKey.trim(), selectedModel)
                    apiKey = ""
                    showSavedConfirmation = true
                }
            },
            enabled = apiKey.isNotBlank(),
        ) {
            Text("Save", color = MaterialTheme.colorScheme.primary)
        }
        if (isConnected) {
            TextButton(onClick = { onClear(providerId) }) {
                Text("Clear", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ── General Tab ────────────────────────────────────────────────────

@Composable
private fun GeneralTab(
    themeMode: String,
    onThemeModeChanged: (String) -> Unit,
    persistQueue: Boolean,
    onPersistQueueChanged: (Boolean) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item { SectionHeader("Appearance") }
        item {
            ListItem(
                headlineContent = { Text("Dark Mode") },
                supportingContent = {
                    Text(
                        when (themeMode) {
                            "light" -> "Always light"
                            "dark" -> "Always dark"
                            else -> "Follow system"
                        },
                    )
                },
                trailingContent = {
                    ThemeModeSelector(
                        currentMode = themeMode,
                        onModeChanged = onThemeModeChanged,
                    )
                },
            )
        }
        item { SectionHeader("Playback") }
        item {
            ListItem(
                headlineContent = { Text("Remember queue") },
                supportingContent = { Text("Restore your queue when the app restarts") },
                trailingContent = {
                    Switch(
                        checked = persistQueue,
                        onCheckedChange = onPersistQueueChanged,
                    )
                },
            )
        }
    }
}

// ── Sync Tab ───────────────────────────────────────────────────────

@Composable
private fun SyncTab(
    spotifyConnected: Boolean,
    appleMusicAuthorized: Boolean,
    appleMusicSyncEnabled: Boolean,
    onSetAppleMusicSyncEnabled: (Boolean) -> Unit,
    listenBrainzConnected: Boolean,
    listenBrainzSyncEnabled: Boolean,
    onSetListenBrainzSyncEnabled: (Boolean) -> Unit,
) {
    val syncViewModel: SyncViewModel = koinViewModel()
    val syncEnabled by syncViewModel.syncEnabled.collectAsStateWithLifecycle()
    val lastSyncAt by syncViewModel.lastSyncAt.collectAsStateWithLifecycle()
    val isSyncing by syncViewModel.isSyncing.collectAsStateWithLifecycle()
    val syncProgress by syncViewModel.syncProgress.collectAsStateWithLifecycle()
    val syncedSummaries by syncViewModel.syncedSummaries.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { syncViewModel.loadSyncedSummaries() }
    // N-way multimaster (dev) — toggle + shadow report. Same VM instance as the
    // screen (koinViewModel is nav-entry-scoped).
    val settingsViewModel: SettingsViewModel = koinViewModel()
    val nwayEnabled by settingsViewModel.nwayEnabled.collectAsStateWithLifecycle()
    val nwayShadowLog by settingsViewModel.nwayShadowLog.collectAsStateWithLifecycle()
    val shadowScanning by settingsViewModel.shadowScanning.collectAsStateWithLifecycle()
    val nwayPropagateEnabled by settingsViewModel.nwayPropagateEnabled.collectAsStateWithLifecycle()
    val nwayPropagationLog by settingsViewModel.nwayPropagationLog.collectAsStateWithLifecycle()
    val propagating by settingsViewModel.propagating.collectAsStateWithLifecycle()
    val migrationPreview by settingsViewModel.migrationPreview.collectAsStateWithLifecycle()
    val migrationReport by settingsViewModel.migrationReport.collectAsStateWithLifecycle()
    var showSyncSetupSheet by remember { mutableStateOf(false) }
    /** Which provider the wizard is currently configuring. The same sheet
     *  is reused — Spotify rows write `"spotify"`, the AM "Configure Sync…"
     *  row below writes `"applemusic"`. */
    var syncSetupProviderId by remember { mutableStateOf("spotify") }
    var showStopSyncDialog by remember { mutableStateOf(false) }
    /** When non-null, the per-provider "Configure what syncs" sheet (#266) is
     *  open for this provider (pull picker for Spotify/AM, push picker for LB). */
    var configSyncProviderId by remember { mutableStateOf<String?>(null) }
    /** When non-null, the config sheet is open as a first-time ENABLE gate for
     *  this provider — sync isn't actually turned on until the user taps Done
     *  (cleared without enabling if they cancel). Drives the optimistic toggle. */
    var pendingEnableProviderId by remember { mutableStateOf<String?>(null) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // Sync status + global "Sync now" (#303) — one action that syncs every
        // enabled service; disabled while a sync runs (no more "already syncing").
        if (spotifyConnected || appleMusicAuthorized || listenBrainzConnected) {
            item { Spacer(modifier = Modifier.height(8.dp)) }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (isSyncing) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    run {
                                        // Show WHAT is syncing (the phase/message), not
                                        // just bare counters. Prefer the engine's
                                        // descriptive message; fall back to the phase.
                                        val title = syncProgress.message.ifBlank {
                                            when (syncProgress.phase.name) {
                                                "TRACKS" -> "Syncing tracks"
                                                "ALBUMS" -> "Syncing albums"
                                                "ARTISTS" -> "Syncing artists"
                                                "PLAYLISTS" -> "Syncing playlists"
                                                else -> "Syncing…"
                                            }
                                        }
                                        if (syncProgress.total > 0) "$title (${syncProgress.current}/${syncProgress.total})"
                                        else title
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            Text(
                                "Last synced: ${formatRelativeTime(lastSyncAt)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { syncViewModel.syncNow() },
                            // Disabled when no service is actually enabled to sync.
                            enabled = !isSyncing &&
                                (syncEnabled || appleMusicSyncEnabled || listenBrainzSyncEnabled),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(if (isSyncing) "Syncing…" else "Sync now")
                        }
                    }
                }
            }
        }

        // Spotify Sync section — only shown when Spotify is connected
        if (spotifyConnected) {
            item { Spacer(modifier = Modifier.height(8.dp)) }
            item { SectionHeader("Spotify Sync") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Sync enable/disable toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (syncEnabled) "Syncing enabled" else "Syncing disabled",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    if (syncEnabled && syncedSummaries["spotify"].orEmpty().isNotEmpty())
                                        "Syncing: ${syncedSummaries["spotify"]}"
                                    else "Sync your saved tracks, albums, artists, and playlists.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                // Optimistically ON while confirming in the config
                                // sheet; reverts if the user cancels.
                                checked = syncEnabled || pendingEnableProviderId == "spotify",
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        // Gate: open the config sheet first; sync
                                        // only enables when they tap Done.
                                        pendingEnableProviderId = "spotify"
                                        configSyncProviderId = "spotify"
                                    } else {
                                        showStopSyncDialog = true
                                    }
                                },
                            )
                        }

                        // Last synced timestamp
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Last synced: ${formatRelativeTime(lastSyncAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        if (syncEnabled) {
                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                            Spacer(modifier = Modifier.height(16.dp))

                            // Configure what syncs — per-provider picker (#266).
                            // Choose which of your Spotify playlists to import.
                            OutlinedButton(
                                onClick = { configSyncProviderId = "spotify" },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text("Configure what syncs")
                            }
                        }
                    }
                }
            }
        }

        // ── Apple Music Sync section (Phase 6) ───────────────────────
        // Mirrors the Spotify Sync section above. Only shown when
        // Apple Music is authorized (MUT present); the toggle writes
        // to enabled_sync_providers, which the multi-provider sync
        // engine from Phases 4.5 + 5 reads on every cycle.
        //
        // Per Decision D1, Phase 6 ships a single global enable/disable
        // toggle for AM — per-playlist selection is deferred to a
        // follow-up. Enabling AM here means "sync every AM library
        // playlist + push every locally-eligible playlist with AM
        // track IDs to AM."
        if (appleMusicAuthorized) {
            item { Spacer(modifier = Modifier.height(8.dp)) }
            item { SectionHeader("Apple Music Sync") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (appleMusicSyncEnabled) "Syncing enabled" else "Syncing disabled",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    if (appleMusicSyncEnabled && syncedSummaries["applemusic"].orEmpty().isNotEmpty())
                                        "Syncing: ${syncedSummaries["applemusic"]}"
                                    else "Sync your Apple Music library and playlists.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // Toggle ON opens the setup wizard (first-time axis
                            // selection); OFF disables AM sync directly.
                            Switch(
                                checked = appleMusicSyncEnabled || pendingEnableProviderId == "applemusic",
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        // Gate: confirm what syncs before enabling.
                                        pendingEnableProviderId = "applemusic"
                                        configSyncProviderId = "applemusic"
                                    } else {
                                        onSetAppleMusicSyncEnabled(false)
                                    }
                                },
                            )
                        }
                        if (appleMusicSyncEnabled) {
                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(
                                onClick = { configSyncProviderId = "applemusic" },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text("Configure what syncs")
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                "Note: Apple Music's API doesn't allow Parachord to delete or " +
                                    "rename playlists, or remove tracks from a playlist. Those " +
                                    "actions silently no-op on Apple Music — make those changes in " +
                                    "the Music app instead.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // ── ListenBrainz Sync section (Task 19) ──────────────────────
        // Only shown when LB is authorized (token present). Toggle
        // writes to enabled_sync_providers, which the multi-provider
        // sync engine reads on every cycle. Loved tracks already sync
        // separately via the scrobbler love-push pipeline — this
        // controls playlist sync (pushing Parachord-curated playlists
        // up to the user's LB profile).
        if (listenBrainzConnected) {
            item { Spacer(modifier = Modifier.height(8.dp)) }
            item { SectionHeader("ListenBrainz Sync") }
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (listenBrainzSyncEnabled) "Syncing enabled" else "Syncing disabled",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    if (listenBrainzSyncEnabled && syncedSummaries["listenbrainz"].orEmpty().isNotEmpty())
                                        "Syncing: ${syncedSummaries["listenbrainz"]}"
                                    else "Pushes Parachord-curated playlists to your ListenBrainz " +
                                        "profile. Loved tracks sync separately via scrobblers.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = listenBrainzSyncEnabled || pendingEnableProviderId == "listenbrainz",
                                onCheckedChange = { enabled ->
                                    if (enabled) {
                                        // Gate: confirm what syncs before enabling.
                                        pendingEnableProviderId = "listenbrainz"
                                        configSyncProviderId = "listenbrainz"
                                    } else {
                                        onSetListenBrainzSyncEnabled(false)
                                    }
                                },
                            )
                        }
                        // Configure what syncs — push picker (#266): All / Choose /
                        // None over the local playlists eligible to mirror to LB.
                        if (listenBrainzSyncEnabled) {
                            Spacer(modifier = Modifier.height(16.dp))
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedButton(
                                onClick = { configSyncProviderId = "listenbrainz" },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text("Configure what syncs")
                            }
                        }
                    }
                }
            }
        }

        // ── New sync engine — opt-in migration (ALL builds) ─────────
        // User-facing preview → accept flow (desktop parity, parachord#911 /
        // v0.9.5). Nothing is armed until the user accepts; accept flips
        // sync_engine_mode='new'. The MigrationPreviewDialog (rendered later in
        // this composable, outside any build gate) drives the diff/accept/report
        // UI. The shadow-mode + propagation toggles further down stay DEV-only —
        // validation tooling users never need.
        item { Spacer(modifier = Modifier.height(8.dp)) }
        item { SectionHeader("New sync engine") }
        item {
            ListItem(
                headlineContent = { Text("Use new sync (preview)") },
                supportingContent = {
                    Text(
                        "Make playlist edits round-trip across every service instead of one " +
                            "always winning. Preview exactly what switching would change — then " +
                            "accept, report a problem, or cancel. Nothing changes until you accept, " +
                            "and you can switch back anytime.",
                    )
                },
                trailingContent = {
                    Button(onClick = { settingsViewModel.openMigrationPreview() }) { Text("Preview") }
                },
            )
        }

        // ── N-way shadow + propagation (DEV ONLY) ────────────────────
        // Dev validation only: continuous dry-run logging + scoped real-write
        // testing. The preview → accept entry above is the real user path.
        if (BuildConfig.DEBUG) {
            item { Spacer(modifier = Modifier.height(8.dp)) }
            item { SectionHeader("Developer · sync engine") }
            item {
                ListItem(
                    headlineContent = { Text("N-way shadow mode") },
                    supportingContent = {
                        Text(
                            "Runs the multimaster merge in shadow on every sync — logs what " +
                                "it WOULD push, but pushes nothing. Dev validation only.",
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = nwayEnabled,
                            onCheckedChange = { settingsViewModel.setNwayEnabled(it) },
                        )
                    },
                )
            }
            if (nwayEnabled) {
                item {
                    Button(
                        onClick = { settingsViewModel.runShadowScan() },
                        enabled = !shadowScanning,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        Text(if (shadowScanning) "Scanning…" else "Run shadow scan")
                    }
                }
                if (nwayShadowLog.isEmpty()) {
                    item {
                        Text(
                            "No pending changes. Run a shadow scan — it lists only playlists " +
                                "where a copy diverged from the baseline (cheap: one list call " +
                                "per provider, no pushes).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    items(nwayShadowLog) { entry ->
                        ListItem(
                            headlineContent = {
                                Text(if (entry.massChange) "⚠️ ${entry.playlistName}" else entry.playlistName)
                            },
                            supportingContent = {
                                Text(
                                    "changed: ${entry.changedCopies.joinToString().ifEmpty { "—" }}\n" +
                                        "→ merged ${entry.mergedCount} track(s), " +
                                        "would push to: ${entry.wouldPushTo.joinToString().ifEmpty { "none" }}" +
                                        (if (entry.dropPercent > 0) " · drop ${entry.dropPercent}%" else "") +
                                        (if (entry.massChange) " · MASS-CHANGE, would abort" else ""),
                                )
                            },
                        )
                    }
                }

                // ── Propagation (REAL writes, stricter opt-in) ──────────
                item { Spacer(modifier = Modifier.height(8.dp)) }
                item { SectionHeader("Developer · N-way propagation (writes)") }
                item {
                    ListItem(
                        headlineContent = { Text("Enable real writes") },
                        supportingContent = {
                            Text(
                                "Lets propagation PUSH the merged tracklist to your real " +
                                    "Spotify / Apple Music / ListenBrainz playlists. Leave OFF to " +
                                    "preview only. Dry-run works without this.",
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = nwayPropagateEnabled,
                                onCheckedChange = { settingsViewModel.setNwayPropagateEnabled(it) },
                            )
                        },
                    )
                }
                item {
                    Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Button(
                            onClick = { settingsViewModel.runPropagation(dryRun = true) },
                            enabled = !propagating,
                        ) {
                            Text(if (propagating) "Running…" else "Dry-run")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = { settingsViewModel.runPropagation(dryRun = false) },
                            enabled = !propagating && nwayPropagateEnabled,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Text("Run writes")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { settingsViewModel.resetNwayState() },
                            enabled = !propagating,
                        ) {
                            Text("Reset state")
                        }
                    }
                }
                if (nwayPropagationLog.isEmpty()) {
                    item {
                        Text(
                            "No propagation outcome yet. Dry-run previews what WOULD be pushed " +
                                "(no writes); review it on your real library before enabling writes.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                } else {
                    items(nwayPropagationLog) { entry ->
                        val badge = when (entry.status) {
                            "pushed" -> "✅"
                            "would-push" -> "👁"
                            "partial" -> "◑"
                            "total-wipe-abort", "partial-abort" -> "⚠️"
                            else -> "•"
                        }
                        ListItem(
                            headlineContent = { Text("$badge ${entry.playlistName}") },
                            supportingContent = {
                                Text(
                                    "${entry.status} · merged ${entry.mergedCount} track(s) → " +
                                        "targets: ${entry.pushTargets.joinToString().ifEmpty { "none" }}" +
                                        (if (entry.pendingAdds > 0) " · ${entry.pendingAdds} pending" else "") +
                                        (if (entry.unsupportedRemoves > 0) " · ${entry.unsupportedRemoves} not-removable" else ""),
                                )
                            },
                            trailingContent = {
                                // Scoped real-write: push THIS playlist only (needs
                                // the writes toggle on) — validate one before the
                                // full run.
                                TextButton(
                                    onClick = {
                                        settingsViewModel.runPropagationForPlaylist(
                                            entry.localPlaylistId,
                                            dryRun = false,
                                        )
                                    },
                                    enabled = !propagating && nwayPropagateEnabled,
                                ) {
                                    Text("Push this")
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // Sync setup sheet
    if (showSyncSetupSheet) {
        SyncSetupSheet(
            onDismiss = { showSyncSetupSheet = false },
            viewModel = syncViewModel,
            providerId = syncSetupProviderId,
        )
    }

    // Per-provider "Configure what syncs" sheet (#266) — pull picker for
    // Spotify / Apple Music, push picker for ListenBrainz.
    configSyncProviderId?.let { pid ->
        ProviderSyncConfigSheet(
            providerId = pid,
            // Done in a first-time ENABLE gate actually turns sync on; a plain
            // "Configure what syncs" open (no pending) just persists + closes.
            onDone = { pendingEnableProviderId?.let { syncViewModel.enableProviderFromConfig(it) } },
            onDismiss = {
                configSyncProviderId = null
                pendingEnableProviderId = null
            },
            viewModel = syncViewModel,
        )
    }

    // Stop syncing confirmation dialog
    // Migration preview (#289 brick #5) — the dialog + the "Report a problem"
    // effect (copy the body to the clipboard, then open the prefilled issue).
    MigrationPreviewDialog(
        state = migrationPreview,
        onAccept = { settingsViewModel.acceptMigration() },
        onReport = { settingsViewModel.reportMigrationProblem() },
        onCancel = { settingsViewModel.cancelMigration() },
    )
    val migrationClipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val migrationUriHandler = LocalUriHandler.current
    LaunchedEffect(migrationReport) {
        val report = migrationReport ?: return@LaunchedEffect
        migrationClipboard.setText(androidx.compose.ui.text.AnnotatedString(report.body))
        runCatching { migrationUriHandler.openUri(report.githubUrl) }
        settingsViewModel.clearMigrationReport()
    }

    if (showStopSyncDialog) {
        AlertDialog(
            onDismissRequest = { showStopSyncDialog = false },
            containerColor = ModalBg,
            titleContentColor = ModalTextActive,
            textContentColor = ModalTextPrimary,
            title = { Text("Stop Syncing") },
            text = { Text("Do you want to keep or remove the synced items from your Collection?") },
            confirmButton = {
                TextButton(onClick = {
                    syncViewModel.stopSyncing(removeItems = true)
                    showStopSyncDialog = false
                }) { Text("Remove Items", color = Color(0xFFEF4444)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    syncViewModel.stopSyncing(removeItems = false)
                    showStopSyncDialog = false
                }) { Text("Keep Items", color = ModalTextPrimary) }
            },
        )
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    if (timestamp == 0L) return "Never"
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "Just now"
        diff < 3_600_000 -> "${diff / 60_000} minutes ago"
        diff < 86_400_000 -> "${diff / 3_600_000} hours ago"
        else -> "${diff / 86_400_000} days ago"
    }
}

// ── About Tab ──────────────────────────────────────────────────────

@Composable
private fun AboutTab() {
    val uriHandler = LocalUriHandler.current
    val accent = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsStore = org.koin.compose.koinInject<com.parachord.shared.settings.SettingsStore>()
    var exportingLogs by remember { mutableStateOf(false) }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Parachord",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Version ${com.parachord.android.BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(24.dp))

                // Tagline + Tomahawk link — mirrors desktop exactly. Built
                // with AnnotatedString so the inline link gets the accent
                // color and click target without dropping out of a single
                // centered paragraph.
                val tomahawkUrl = "https://github.com/tomahawk-player/tomahawk"
                val tagline = buildAnnotatedString {
                    append("A modern multi-source music player inspired by ")
                    pushStringAnnotation(tag = "URL", annotation = tomahawkUrl)
                    withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Medium)) {
                        append("Tomahawk")
                    }
                    pop()
                    append(".")
                }
                ClickableText(
                    text = tagline,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    ),
                    modifier = Modifier.padding(horizontal = 32.dp),
                    onClick = { offset ->
                        tagline.getStringAnnotations("URL", offset, offset)
                            .firstOrNull()?.let { uriHandler.openUri(it.item) }
                    },
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Report a bug with logs — copies the app's own logs to the
                // clipboard and opens a prefilled GitHub issue for the user to
                // paste them into, so reporters don't need adb (#327 follow-up).
                // Logs are too big for a new-issue URL, hence clipboard. No
                // tokens are included.
                OutlinedButton(
                    onClick = {
                        if (exportingLogs) return@OutlinedButton
                        exportingLogs = true
                        scope.launch {
                            val report = DiagnosticLogExporter(context, settingsStore).prepareBugReport()
                            exportingLogs = false
                            if (report != null) {
                                android.widget.Toast.makeText(
                                    context,
                                    "Logs copied — paste them into the issue",
                                    android.widget.Toast.LENGTH_LONG,
                                ).show()
                                try {
                                    uriHandler.openUri(report.issueUrl)
                                } catch (e: Exception) {
                                    android.widget.Toast.makeText(
                                        context, "Couldn't open GitHub", android.widget.Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            } else {
                                android.widget.Toast.makeText(
                                    context, "Couldn't collect logs", android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                    shape = RoundedCornerShape(8.dp),
                ) {
                    if (exportingLogs) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Collecting…")
                    } else {
                        Icon(Icons.Outlined.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Report a bug with logs")
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Copies your logs and opens a prefilled GitHub issue — just paste and describe the problem. No account tokens are included.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tertiary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )

                Spacer(modifier = Modifier.height(32.dp))
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                Spacer(modifier = Modifier.height(32.dp))

                // Open Source section — same copy as desktop, retargeted
                // for the Android stack and repo.
                Text(
                    text = "OPEN SOURCE SOFTWARE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = tertiary,
                    letterSpacing = 1.2.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Built with Kotlin, Jetpack Compose, and Material 3",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "View on GitHub →",
                    style = MaterialTheme.typography.bodySmall,
                    color = accent,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable {
                        uriHandler.openUri("https://github.com/Parachord/parachord-mobile")
                    },
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Copyright + license — auto-updates the year so we don't
                // ship a stale `© 2026` past New Year's.
                val year = remember { java.util.Calendar.getInstance().get(java.util.Calendar.YEAR) }
                Text(
                    text = "© $year Jason Herskowitz. All rights reserved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tertiary,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Licensed under the MIT License",
                    style = MaterialTheme.typography.bodySmall,
                    color = tertiary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

// ── Theme Mode Selector ────────────────────────────────────────────

@Composable
private fun ThemeModeSelector(
    currentMode: String,
    onModeChanged: (String) -> Unit,
) {
    val modes = listOf("system" to "Auto", "light" to "Light", "dark" to "Dark")
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        modes.forEach { (value, label) ->
            FilterChip(
                selected = currentMode == value,
                onClick = { onModeChanged(value) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}
