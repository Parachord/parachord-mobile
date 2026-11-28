package com.parachord.android.car.cal

import android.content.Intent
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.Row
import androidx.car.app.model.RowSection
import androidx.car.app.model.Section
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.Tab
import androidx.car.app.model.TabContents
import androidx.car.app.model.TabTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.parachord.android.R
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.android.ui.ParachordActivity
import com.parachord.shared.car.CarBrowse
import com.parachord.shared.car.CarIds
import com.parachord.shared.car.CarItem
import com.parachord.shared.recommendations.RecommendationPageBlock
import com.parachord.shared.recommendations.recommendationPageLayout
import com.parachord.shared.repository.HistoryMixesRepository
import com.parachord.shared.repository.RecommendationsError
import com.parachord.shared.repository.RecommendationsRepository
import com.parachord.shared.repository.loadRecommendationsPage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * The car home as a `TabTemplate`:
 * - **Library**: draws the shared [CarBrowse] root (`Playlists`, `Loved Songs`,
 *   `Collection Radio`) — identical titles, order, and drill-down behavior to
 *   Android Auto and CarPlay — plus the parked-only "Open Parachord" row.
 * - **For You**: the shared recommendations page as sections.
 * - **Now Playing**: pushes `MediaPlaybackTemplate`.
 * - **Search**: pushes `SearchTemplate`.
 */
class CalHomeScreen(carContext: CarContext) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val carBrowse: CarBrowse by lazy { GlobalContext.get().get() }
    private val recommendationsRepository: RecommendationsRepository by lazy { GlobalContext.get().get() }
    private val historyMixesRepository: HistoryMixesRepository by lazy { GlobalContext.get().get() }
    private val playback: CalPlaybackBridge by lazy { GlobalContext.get().get() }
    private val art: CalArt by lazy { GlobalContext.get().get() }
    private val stateHolder: PlaybackStateHolder by lazy { GlobalContext.get().get() }

    private var activeTabId = TAB_LIBRARY
    private var rootItems: List<CarItem> = emptyList()
    private var libraryLoaded = false
    private var libraryJob: Job? = null
    private var recommendationsJob: Job? = null
    private var activeTabArtworkUrls: Set<String> = emptySet()
    private val enrichedRecKeys = HashSet<String>()

    init {
        scope.launch {
            recommendationsRepository.page.drop(1).collect {
                if (activeTabId == TAB_FOR_YOU) invalidate()
            }
        }
        scope.launch {
            art.loadedUrls.collect { url ->
                if (url in activeTabArtworkUrls) invalidate()
            }
        }
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        // Initial load and refresh when returning from the full
                        // app (new playlists or newly connected services).
                        loadLibrary()
                        loadRecommendations()
                    }
                    Lifecycle.Event.ON_DESTROY -> scope.cancel()
                    else -> Unit
                }
            },
        )
    }

    override fun onGetTemplate(): Template {
        val callback = object : TabTemplate.TabCallback {
            override fun onTabSelected(contentId: String) {
                when (contentId) {
                    TAB_SEARCH -> {
                        screenManager.push(CalSearchScreen(carContext))
                    }
                    TAB_NOW_PLAYING -> {
                        // Only once something has played: the host renders the
                        // playback view from the session, and with nothing
                        // playing it is an empty dark surface.
                        if (stateHolder.state.value.currentTrack != null) {
                            screenManager.push(CalNowPlayingScreen(carContext))
                        } else {
                            CarToast.makeText(carContext, "Nothing playing yet", CarToast.LENGTH_SHORT).show()
                            invalidate()
                        }
                    }
                    else -> {
                        activeTabId = contentId
                        if (contentId == TAB_FOR_YOU) {
                            loadRecommendations()
                        }
                        invalidate()
                    }
                }
            }
        }
        return TabTemplate.Builder(callback)
            .setHeaderAction(Action.APP_ICON)
            .addTab(
                Tab.Builder().setTitle("Library").setContentId(TAB_LIBRARY)
                    .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car_library)).build())
                    .build(),
            )
            .addTab(
                Tab.Builder().setTitle("For You").setContentId(TAB_FOR_YOU)
                    .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_auto_heart_outline)).build())
                    .build(),
            )
            .addTab(
                Tab.Builder().setTitle("Now Playing").setContentId(TAB_NOW_PLAYING)
                    .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car_play)).build())
                    .build(),
            )
            .addTab(
                Tab.Builder().setTitle("Search").setContentId(TAB_SEARCH)
                    .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_car_search)).build())
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

    // ── Library tab (shared CarBrowse root) ──────────────────────────

    private fun libraryTemplate(): Template {
        // Reserve 1 slot for the "Open Parachord" row in settingsSection().
        val limit = (carListLimit(carContext) - 1).coerceAtLeast(1)
        val shownUrls = HashSet<String>()
        val library = RowSection.Builder()
            .setTitle("Library")
            .apply {
                rootItems.take(limit).forEach { item ->
                    addItem(
                        carBrowseItemRow(
                            carContext = carContext,
                            item = item,
                            art = art,
                            shownUrls = shownUrls,
                            onBrowse = { child ->
                                screenManager.push(
                                    CalBrowseScreen(
                                        carContext = carContext,
                                        parentId = child.id,
                                        title = child.title.ifBlank { "Library" },
                                    ),
                                )
                            },
                            onPlay = { child -> playback.playCarItem(child.id) },
                        ),
                    )
                }
            }
            .setNoItemsMessage(if (!libraryLoaded) "Loading…" else "Nothing here yet")
            .build()
        activeTabArtworkUrls = shownUrls
        return SectionedItemTemplate.Builder()
            .setHeader(Header.Builder().setTitle("Parachord").build())
            .setSections(listOf<Section<*>>(library, settingsSection()))
            .build()
    }

    /** The parked-only way into the full app (settings, accounts, setup). */
    private fun settingsSection(): Section<*> =
        RowSection.Builder()
            .setTitle("Full app")
            .addItem(
                Row.Builder()
                    .setTitle("Open Parachord")
                    .addText("Settings and setup — parked")
                    .setImage(
                        CarIcon.Builder(
                            IconCompat.createWithResource(carContext, R.drawable.ic_car_settings),
                        ).build(),
                    )
                    .setOnClickListener {
                        // From the application context, not the CarContext: the
                        // CarContext targets the host's virtual display (the
                        // SecurityException "with launchDisplayId=13"), while the
                        // app context starts ParachordActivity on the car's own display.
                        carContext.applicationContext.startActivity(
                            Intent(carContext, ParachordActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                    .build(),
            )
            .build()

    // ── For You tab ─────────────────────────────────────────────────

    private fun forYouTemplate(): Template {
        val state = recommendationsRepository.page.value
        val sections = mutableListOf<Section<*>>()
        val shownUrls = HashSet<String>()
        // Reserve 1 slot for settingsSection().
        var remaining = (carListLimit(carContext) - 1).coerceAtLeast(1)
        var enrichBudget = MAX_ENRICH_PER_PASS
        for (block in recommendationPageLayout(state, null, emptySet())) {
            if (remaining <= 0) break
            if (block is RecommendationPageBlock.Row) {
                val items = block.view.items.take(remaining)
                if (items.isNotEmpty()) {
                    sections.add(
                        RowSection.Builder()
                            .setTitle(block.title?.takeIf { it.isNotBlank() } ?: "For You")
                            .apply {
                                items.forEach { item ->
                                    val safeName = item.name.ifBlank { "Untitled" }
                                    val label = item.artist
                                        ?.takeIf { it.isNotBlank() }
                                        ?.let { "$safeName — $it" }
                                        ?: safeName
                                    val img = item.imageUrl
                                    if (!img.isNullOrBlank()) {
                                        shownUrls.add(img)
                                        art.load(carContext, img)
                                    } else if (enrichBudget > 0) {
                                        val recKey = "${item.type}:${item.name}:${item.artist.orEmpty()}"
                                        if (enrichedRecKeys.add(recKey)) {
                                            recommendationsRepository.cardShown(item)
                                            enrichBudget--
                                        }
                                    }
                                    addItem(
                                        Row.Builder()
                                            .setTitle(label)
                                            .setImageWhenReady(art.icon(img))
                                            .setOnClickListener {
                                                playback.playRecommendation(block.row, item, block.view.items)
                                            }
                                            .build(),
                                    )
                                }
                            }
                            .setNoItemsMessage("Nothing here yet")
                            .build(),
                    )
                    remaining -= items.size
                }
            }
        }
        activeTabArtworkUrls = shownUrls
        if (sections.isEmpty()) {
            sections.add(
                RowSection.Builder()
                    .setTitle("For You")
                    .setNoItemsMessage(
                        when {
                            state.loading -> "Loading recommendations…"
                            state.error is RecommendationsError.NotConfigured ->
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
        if (libraryJob?.isActive == true) return
        libraryJob = scope.launch {
            rootItems = try {
                carBrowse.children(CarIds.ROOT)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            libraryLoaded = true
            if (activeTabId == TAB_LIBRARY) invalidate()
        }
    }

    private fun loadRecommendations() {
        if (recommendationsJob?.isActive == true) return
        recommendationsJob = scope.launch {
            try {
                loadRecommendationsPage(
                    force = false,
                    background = scope,
                    mixes = { historyMixesRepository.load(it) },
                    page = { recommendationsRepository.load(it) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Repository surfaces its own error state on page.
            }
        }
    }

    private companion object {
        const val TAB_LIBRARY = "library"
        const val TAB_FOR_YOU = "for_you"
        const val TAB_NOW_PLAYING = "now_playing"
        const val TAB_SEARCH = "search"
        const val MAX_ENRICH_PER_PASS = 12
    }
}
