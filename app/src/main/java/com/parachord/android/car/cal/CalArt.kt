package com.parachord.android.car.cal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cover art for the CAL rows: loads artwork URLs through the app's Coil
 * `ImageLoader` (the same cache the phone UI rides on) and hands them back as
 * bounded [CarIcon]s (`128px` bitmaps so a template stays well under the 1 MB
 * Binder transaction limit).
 *
 * Rows render from whatever is already cached and screens observe [loadedUrls]
 * inside their own lifecycle-cancelled scope to invalidate when a relevant URL
 * lands (no dropped concurrent callbacks, no callbacks firing on destroyed
 * screens). A failed URL is retried at most once a minute so a flaky network
 * can't loop invalidations.
 */
class CalArt {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val cacheLock = Any()
    private val icons = LruCache<String, CarIcon>(MAX_CACHED_ICONS)
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()

    private val _loadedUrls = MutableSharedFlow<String>(extraBufferCapacity = 64)
    /** Emits each artwork URL as its [CarIcon] enters the cache. */
    val loadedUrls: SharedFlow<String> = _loadedUrls.asSharedFlow()

    /** The cached icon for [url], or null while it loads or if it never loaded. */
    fun icon(url: String?): CarIcon? {
        if (url.isNullOrBlank()) return null
        return synchronized(cacheLock) { icons.get(url) }
    }

    /**
     * Starts a load for [url] if it isn't cached, in-flight, or in its failure
     * backoff window. Callers observe [loadedUrls] within their own lifecycle
     * scope to trigger `invalidate()`.
     */
    fun load(context: Context, url: String?) {
        if (url.isNullOrBlank()) return
        if (synchronized(cacheLock) { icons.get(url) != null }) return
        val now = System.currentTimeMillis()
        failedAt[url]?.let { lastFail ->
            if (now - lastFail < RETRY_MS) return
            failedAt.remove(url)
        }
        if (!loading.add(url)) return
        scope.launch {
            val icon = try {
                fetch(context.applicationContext, url)
            } finally {
                loading.remove(url)
            }
            if (icon != null) {
                synchronized(cacheLock) { icons.put(url, icon) }
                _loadedUrls.tryEmit(url)
            } else {
                recordFailure(url, System.currentTimeMillis())
            }
        }
    }

    internal fun recordFailure(url: String, nowMs: Long) {
        if (failedAt.size >= MAX_FAILED_ENTRIES) {
            failedAt.entries.removeIf { nowMs - it.value >= RETRY_MS }
            if (failedAt.size >= MAX_FAILED_ENTRIES) failedAt.clear()
        }
        failedAt[url] = nowMs
    }

    internal fun putForTest(url: String, icon: CarIcon) {
        synchronized(cacheLock) { icons.put(url, icon) }
    }

    internal fun isInFailureBackoff(url: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val ts = failedAt[url] ?: return false
        return nowMs - ts < RETRY_MS
    }

    private suspend fun fetch(context: Context, url: String): CarIcon? = withContext(Dispatchers.IO) {
        try {
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(SIZE_PX)
                .allowHardware(false)
                .build()
            val result = context.imageLoader.execute(request)
            val drawable = (result as? SuccessResult)?.drawable ?: return@withContext null
            val bitmap = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, SIZE_PX, SIZE_PX)
            drawable.draw(canvas)
            CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    fun shutdown() = scope.cancel()

    internal companion object {
        const val RETRY_MS = 60_000L
        const val SIZE_PX = 128
        const val MAX_CACHED_ICONS = 64
        const val MAX_FAILED_ENTRIES = 256
    }
}

/**
 * CAL's [androidx.car.app.model.Row.Builder.setImage] throws on null, and a
 * row's art isn't cached yet when the row first renders — set the image only
 * when there is one; the screen invalidates when the load lands.
 */
fun androidx.car.app.model.Row.Builder.setImageWhenReady(
    icon: CarIcon?,
): androidx.car.app.model.Row.Builder = if (icon != null) setImage(icon) else this

/**
 * Maximum number of list items the host allows in a single template, capped at
 * [maxCap] so Binder template transactions stay well below the 1 MB IPC limit.
 */
internal fun carListLimit(carContext: CarContext, maxCap: Int = 50, fallback: Int = 25): Int = try {
    carContext.getCarService(ConstraintManager::class.java)
        .getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        .coerceIn(1, maxCap)
} catch (_: Exception) {
    fallback.coerceAtMost(maxCap)
}
