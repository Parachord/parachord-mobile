package com.parachord.android.car.cal

import android.content.Context
import androidx.car.app.model.CarIcon
import androidx.core.graphics.drawable.IconCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Cover art for the CAL rows: loads artwork URLs through the app's Coil
 * `ImageLoader` (the same cache the phone UI rides on) and hands them back as
 * [CarIcon]s. The host renders row images itself; a bitmap read in our process
 * crosses the bridge fine, while a `file:///` URL wouldn't (the host's uid
 * can't read our storage), so everything goes through here as bitmaps.
 *
 * Rows render from whatever is already cached and invalidate the screen when a
 * load lands; a failed URL is retried at most once a minute so a flaky network
 * can't loop a screen's invalidations.
 */
class CalArt {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val icons = ConcurrentHashMap<String, CarIcon>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** The cached icon for [url], or null while it loads or if it never loaded. */
    fun icon(url: String?): CarIcon? {
        if (url.isNullOrBlank()) return null
        return icons[url]
    }

    /**
     * Starts a load for [url] if needed and reports through [onLoaded] (posted
     * to the main thread) whenever a new icon lands — callers invalidate their
     * template so the next `onGetTemplate` picks it up.
     */
    fun load(context: Context, url: String?, onLoaded: () -> Unit) {
        if (url.isNullOrBlank() || icons.containsKey(url)) return
        val now = System.currentTimeMillis()
        failedAt[url]?.let { lastFail ->
            if (now - lastFail < RETRY_MS) return
            failedAt.remove(url)
        }
        if (!loading.add(url)) return
        scope.launch {
            val icon = fetch(context.applicationContext, url)
            loading.remove(url)
            if (icon != null) {
                icons[url] = icon
                mainHandler.post(onLoaded)
            } else {
                failedAt[url] = System.currentTimeMillis()
            }
        }
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
            val bitmap = android.graphics.Bitmap.createBitmap(SIZE_PX, SIZE_PX, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            drawable.setBounds(0, 0, SIZE_PX, SIZE_PX)
            drawable.draw(canvas)
            CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build()
        } catch (_: Exception) {
            null
        }
    }

    fun shutdown() = scope.cancel()

    private companion object {
        const val RETRY_MS = 60_000L
        const val SIZE_PX = 384
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
