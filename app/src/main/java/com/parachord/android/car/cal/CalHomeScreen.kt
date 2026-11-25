package com.parachord.android.car.cal

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Header
import androidx.car.app.model.Row
import androidx.car.app.model.RowSection
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import com.parachord.android.data.repository.LibraryRepository
import com.parachord.shared.recommendations.RecommendationPageBlock
import com.parachord.shared.recommendations.recommendationPageLayout
import com.parachord.shared.repository.RecommendationsError
import com.parachord.shared.repository.RecommendationsRepository
import com.parachord.shared.car.CarBrowse
import com.parachord.shared.model.Playlist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * The car home as a `TabTemplate`: Library (Collection Radio and the saved
 * playlists), For You (the shared recommendations page as sections), and a
 * Search tab that opens the host's search screen. The host renders the tabs and
 * enforces distraction compliance; every tap plays through the shared queues —
 * the same ones the phone Home and the Android Auto tree play.
 */
class CalHomeScreen(carContext: CarContext) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val libraryRepository: LibraryRepository by lazy { GlobalContext.get().get() }
    private val recommendationsRepository: RecommendationsRepository by lazy { GlobalContext.get().get() }
    private val playback: CalPlaybackBridge by lazy { GlobalContext.get().get() }
    private val art: CalArt by lazy { GlobalContext.get().get() }
    private val stateHolder: com.parachord.android.playback.PlaybackStateHolder by lazy { GlobalContext.get().get() }

    private var activeTabId = TAB_LIBRARY
    private var playlists: List<Playlist> = emptyList()
    private var libraryLoaded = false

    override fun onGetTemplate(): Template {
        if (!libraryLoaded) loadLibrary()
        val callback = object : TabTemplate.TabCallback {
            override fun onTabSelected(contentId: String) {
                when (contentId) {
                    TAB_SEARCH -> {
                        screenManager.push(CalSearchScreen(carContext))
                        invalidate()
                    }
                    TAB_NOW_PLAYING -> {
                        // Only once something has played: the host renders the
                        // playback view from the session, and with nothing
                        // playing it is an empty dark surface.
                        if (stateHolder.state.value.currentTrack != null) {
                            screenManager.push(CalNowPlayingScreen(carContext))
                        }
                        invalidate()
                    }
                    else -> {
                        activeTabId = contentId
                        invalidate()
                    }
                }
            }
        }
        return TabTemplate.Builder(callback)
            .setHeaderAction(Action.APP_ICON)
            .addTab(
                Tab.Builder().setTitle("Library").setContentId(TAB_LIBRARY)
                    .setIcon(CarIcon.Builder(androidx.core.graphics.drawable.IconCompat.createWithResource(carContext, com.parachord.android.R.drawable.ic_launcher_foreground)).build())
                    .build(),
            )
            .addTab(
                Tab.Builder().setTitle("For You").setContentId(TAB_FOR_YOU)
                    .setIcon(CarIcon.Builder(androidx.core.graphics.drawable.IconCompat.createWithResource(carContext, com.parachord.android.R.drawable.ic_auto_heart_outline)).build())
                    .build(),
            )
            .addTab(
                Tab.Builder().setTitle("Now Playing").setContentId(TAB_NOW_PLAYING)
                    .setIcon(CarIcon.Builder(androidx.core.graphics.drawable.IconCompat.createWithResource(carContext, android.R.drawable.ic_media_play)).build())
                    .build(),
            )
            .addTab(
                Tab.Builder().setTitle("Search").setContentId(TAB_SEARCH)
                    .setIcon(CarIcon.Builder(androidx.core.graphics.drawable.IconCompat.createWithResource(carContext, android.R.drawable.ic_menu_search)).build())
                    .build(),
            )
            .setTabContents(
                TabContents.Builder(
                    when (activeTabId) {
                        TAB_FOR_YOU -> forYouTemplate()
                        else -> libraryTemplate()
                    },
                ).build(),
            )
            .setActiveTabContentId(activeTabId)
            .build()
    }

    // ── Library tab ──────────────────────────────────────────────────

    private fun libraryTemplate(): Template {
        val library = RowSection.Builder()
            .setTitle("Library")
            .addItem(
                Row.Builder()
                    .setTitle("Collection Radio")
                    .addText("Everything you have, shuffled")
                    .setOnClickListener { playback.playCollectionRadio() }
                    .build(),
            )
            .apply {
                playlists.forEach { playlist ->
                    val artworkUrl = playlist.artworkUrl
                    artworkUrl?.let { art.load(carContext, it) { invalidate() } }
                    addItem(
                        Row.Builder()
                            .setTitle(playlist.name)
                            .addText("${playlist.trackCount} ${if (playlist.trackCount != 1) "tracks" else "track"}")
                            .setImageWhenReady(art.icon(artworkUrl))
                            .setOnClickListener { playback.playPlaylist(playlist.id, playlist.name) }
                            .build(),
                    )
                }
            }
            .setNoItemsMessage("No playlists yet")
            .build()
        return SectionedItemTemplate.Builder()
            .setHeader(Header.Builder().setTitle("Parachord").build())
            .setSections(listOf<androidx.car.app.model.Section<*>>(library, settingsSection()))
            .build()
    }

    /** The parked-only way into the full app (settings, accounts, setup). */
    private fun settingsSection(): androidx.car.app.model.Section<*> =
        RowSection.Builder()
            .setTitle("Full app")
            .addItem(
                Row.Builder()
                    .setTitle("Open Parachord")
                    .addText("Settings and setup — parked")
                    .setImage(
                        CarIcon.Builder(
                            androidx.core.graphics.drawable.IconCompat.createWithResource(
                                carContext,
                                android.R.drawable.ic_menu_preferences,
                            ),
                        ).build(),
                    )
                    .setOnClickListener {
                        // From the application context, not the CarContext: the
                        // CarContext targets the host's virtual display (the
                        // SecurityException "with launchDisplayId=13"), while the
                        // app context starts MainActivity on the car's own display.
                        carContext.applicationContext.startActivity(
                            android.content.Intent(
                                carContext,
                                com.parachord.android.ui.MainActivity::class.java,
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    .build(),
            )
            .build()

    // ── For You tab ─────────────────────────────────────────────────

    private fun forYouTemplate(): Template {
        val state = recommendationsRepository.page.value
        val sections = mutableListOf<androidx.car.app.model.Section<*>>()
        recommendationPageLayout(state, null, emptySet()).forEach { block ->
            if (block is RecommendationPageBlock.Row) {
                val items = block.view.items
                if (items.isNotEmpty()) {
                    sections.add(
                        RowSection.Builder()
                            .setTitle(block.title ?: "For You")
                            .apply {
                                items.forEach { item ->
                                    val label = item.artist
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { "${item.name} — $it" }
                                        ?: item.name
                                    item.imageUrl?.let { art.load(carContext, it) { invalidate() } }
                                    addItem(
                                        Row.Builder()
                                            .setTitle(label)
                                            .setImageWhenReady(art.icon(item.imageUrl))
                                            .setOnClickListener {
                                                playback.playRecommendation(block.row, item, items)
                                            }
                                            .build(),
                                    )
                                }
                            }
                            .setNoItemsMessage("Nothing here yet")
                            .build(),
                    )
                }
            }
        }
        if (sections.isEmpty()) {
            sections.add(
                RowSection.Builder()
                    .setTitle("For You")
                    .setNoItemsMessage(
                        when (state.error) {
                            is RecommendationsError.NotConfigured ->
                                "Connect a music service in Settings to get recommendations"
                            else -> "Nothing here yet — try again in a moment"
                        },
                    )
                    .build(),
            )
        }
        return SectionedItemTemplate.Builder()
            .setHeader(Header.Builder().setTitle("Parachord").build())
            .setSections(sections + settingsSection())
            .build()
    }

    private fun loadLibrary() {
        if (libraryLoaded) return
        libraryLoaded = true
        scope.launch {
            try {
                playlists = libraryRepository.getAllPlaylists().first()
            } catch (_: Exception) {
                playlists = emptyList()
            }
            invalidate()
        }
    }

    // The Screen has no onDestroy override — cancel the scope when the lifecycle does.
    init {
        lifecycle.addObserver(object : androidx.lifecycle.LifecycleEventObserver {
            override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: androidx.lifecycle.Lifecycle.Event) {
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                        // Refresh: a playlist made in the full app (the Settings
                        // row) shows when the drive app comes back.
                        libraryLoaded = false
                        loadLibrary()
                    }
                    androidx.lifecycle.Lifecycle.Event.ON_DESTROY -> scope.cancel()
                    else -> Unit
                }
            }
        })
    }

    private companion object {
        const val TAB_LIBRARY = "library"
        const val TAB_FOR_YOU = "for_you"
        const val TAB_NOW_PLAYING = "now_playing"
        const val TAB_SEARCH = "search"
    }
}
