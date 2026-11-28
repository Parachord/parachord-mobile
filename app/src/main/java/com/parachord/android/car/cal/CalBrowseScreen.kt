package com.parachord.android.car.cal

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.Row
import androidx.car.app.model.RowSection
import androidx.car.app.model.SectionedItemTemplate
import androidx.car.app.model.Template
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.parachord.android.R
import com.parachord.shared.car.CarBrowse
import com.parachord.shared.car.CarIds
import com.parachord.shared.car.CarItem
import com.parachord.shared.car.CarItemKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * Draws any non-root node of the shared [CarBrowse] tree (Playlists, Loved
 * Songs, or an opened playlist with its Shuffle row + tracks). Identical titles,
 * caps, ordering, and tap semantics to Android Auto and CarPlay.
 */
class CalBrowseScreen(
    carContext: CarContext,
    private val parentId: String,
    private val title: String,
) : Screen(carContext) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val carBrowse: CarBrowse by lazy { GlobalContext.get().get() }
    private val playback: CalPlaybackBridge by lazy { GlobalContext.get().get() }
    private val art: CalArt by lazy { GlobalContext.get().get() }

    private var items: List<CarItem> = emptyList()
    private var loaded = false
    private var loadJob: Job? = null
    private var shownArtworkUrls: Set<String> = emptySet()

    init {
        scope.launch {
            art.loadedUrls.collect { url ->
                if (url in shownArtworkUrls) invalidate()
            }
        }
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> loadChildren()
                    Lifecycle.Event.ON_DESTROY -> scope.cancel()
                    else -> Unit
                }
            },
        )
    }

    private fun loadChildren() {
        if (loadJob?.isActive == true) return
        loadJob = scope.launch {
            items = try {
                carBrowse.children(parentId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            loaded = true
            invalidate()
        }
    }

    override fun onGetTemplate(): Template {
        val limit = carListLimit(carContext)
        val shownUrls = HashSet<String>()
        val section = RowSection.Builder()
            .setTitle(title.ifBlank { "Library" })
            .apply {
                items.take(limit).forEach { item ->
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
            .setNoItemsMessage(if (!loaded) "Loading…" else "Nothing here yet")
            .build()
        shownArtworkUrls = shownUrls
        return SectionedItemTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle(title.ifBlank { "Library" })
                    .setStartHeaderAction(Action.BACK)
                    .build(),
            )
            .setSections(listOf(section))
            .build()
    }
}

/**
 * Renders one shared [CarItem] into a CAL [Row], handling desktop fallback icons
 * for folders/actions, [CalArt] artwork for playlists/tracks, blank-title guards,
 * and browsable vs playable tap dispatch.
 */
internal fun carBrowseItemRow(
    carContext: CarContext,
    item: CarItem,
    art: CalArt,
    shownUrls: MutableSet<String>,
    onBrowse: (CarItem) -> Unit,
    onPlay: (CarItem) -> Unit,
): Row {
    val artUrl = item.artworkUrl?.takeIf { it.isNotBlank() }
    val icon: CarIcon? = if (artUrl != null) {
        shownUrls.add(artUrl)
        art.load(carContext, artUrl)
        art.icon(artUrl)
    } else {
        fallbackIconFor(carContext, item)
    }
    return Row.Builder()
        .setTitle(item.title.ifBlank { "Untitled" })
        .apply {
            item.subtitle?.takeIf { it.isNotBlank() }?.let { addText(it) }
        }
        .setImageWhenReady(icon)
        .setOnClickListener {
            if (item.isBrowsable) onBrowse(item)
            else if (item.isPlayable) onPlay(item)
        }
        .build()
}

private fun fallbackIconFor(carContext: CarContext, item: CarItem): CarIcon? {
    val resId = when {
        item.id == CarIds.PLAYLISTS -> R.drawable.ic_car_playlist
        item.id == CarIds.LOVED -> R.drawable.ic_auto_heart_filled
        item.id == CarIds.COLLECTION_RADIO -> R.drawable.ic_car_radio
        item.kind == CarItemKind.ACTION -> R.drawable.ic_auto_shuffle
        else -> null
    } ?: return null
    return CarIcon.Builder(IconCompat.createWithResource(carContext, resId)).build()
}
