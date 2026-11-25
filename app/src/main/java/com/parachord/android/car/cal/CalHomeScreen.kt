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

    private var activeTabId = TAB_LIBRARY
    private var playlists: List<Playlist> = emptyList()

    override fun onGetTemplate(): Template {
        if (playlists.isEmpty()) loadLibrary()
        val callback = object : TabTemplate.TabCallback {
            override fun onTabSelected(contentId: String) {
                when (contentId) {
                    TAB_SEARCH -> {
                        screenManager.push(CalSearchScreen(carContext))
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
                    addItem(
                        Row.Builder()
                            .setTitle(playlist.name)
                            .addText("${playlist.trackCount} ${if (playlist.trackCount != 1) "tracks" else "track"}")
                            .setOnClickListener { playback.playPlaylist(playlist.id, playlist.name) }
                            .build(),
                    )
                }
            }
            .setNoItemsMessage("No playlists yet")
            .build()
        return SectionedItemTemplate.Builder()
            .setHeader(Header.Builder().setTitle("Parachord").build())
            .setSections(listOf<androidx.car.app.model.Section<*>>(library))
            .build()
    }

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
                                    addItem(
                                        Row.Builder()
                                            .setTitle(label)
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
            .setSections(sections)
            .build()
    }

    private fun loadLibrary() {
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
                if (event == androidx.lifecycle.Lifecycle.Event.ON_DESTROY) {
                    scope.cancel()
                }
            }
        })
    }

    private companion object {
        const val TAB_LIBRARY = "library"
        const val TAB_FOR_YOU = "for_you"
        const val TAB_SEARCH = "search"
    }
}
