package com.parachord.android.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.parachord.android.BuildConfig
import com.parachord.android.net.parachordUserAgent
import com.parachord.shared.api.SecretRedaction
import com.parachord.shared.deeplink.validatePublicHttpsUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.koin.core.context.GlobalContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Read-only [ContentProvider] serving track/playlist artwork as local
 * `content://<applicationId>.artwork/<key>` URIs.
 *
 * **Why this is required on AAOS (and recommended on Android Auto):**
 * AAOS's `car-media-common` (`ImageBinder` / `MediaItemMetadata.ArtworkRef`,
 * shared by both the Car App Library Templates Host's `MediaPlaybackTemplate`
 * and `CarLauncher`'s home-screen media widget) rejects both raw bitmaps in
 * `MediaMetadataCompat` (`METADATA_KEY_ALBUM_ART`, gated off by
 * `flag_improper_image_references = false`) and remote `https://` URIs
 * (`METADATA_KEY_ALBUM_ART_URI`). It only decodes `content://` (and
 * `android.resource://`) URIs via `ContentResolver`.
 *
 * **Security:**
 * - Read-only: any write call or non-`"r"` open mode is rejected.
 * - No SSRF / no path traversal: callers pass an opaque 32-hex-char SHA-256
 *   key. Only URLs explicitly registered in-process by [uriFor] can be
 *   fetched, HTTPS requests validate the initial URL and every redirect hop
 *   through the shared [validatePublicHttpsUrl] guard, `content://` reads
 *   reject self-recursion and allow only MediaStore / our FileProvider, and
 *   cache paths are strictly `[0-9a-f]{32}.jpg` inside `cacheDir/car_artwork/`.
 * - Striped locking deduplicates concurrent fetches when CarLauncher, the CAL
 *   Templates Host, and the service observer request the same cover together.
 */
class MediaArtworkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String? =
        if (extractValidKey(uri) != null) "image/jpeg" else null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") {
            throw SecurityException("MediaArtworkProvider is read-only (mode=$mode)")
        }
        val key = extractValidKey(uri)
            ?: throw FileNotFoundException("Invalid artwork URI")
        val ctx = context ?: throw FileNotFoundException("Provider context unavailable")
        val file = getOrFetchByKey(ctx, key)
            ?: throw FileNotFoundException("Artwork unavailable for key $key")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        throw UnsupportedOperationException("MediaArtworkProvider is read-only")
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        throw UnsupportedOperationException("MediaArtworkProvider is read-only")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int {
        throw UnsupportedOperationException("MediaArtworkProvider is read-only")
    }

    companion object {
        private const val TAG = "MediaArtworkProvider"
        private const val AUTHORITY_SUFFIX = ".artwork"
        private const val CACHE_SUBDIR = "car_artwork"
        private const val MAX_REGISTRY_ENTRIES = 1_000
        private const val MAX_CACHE_FILES = 200
        private const val PRUNE_TARGET_FILES = 150
        private const val STALE_TMP_AGE_MS = 5 * 60 * 1000L
        private const val MAX_DOWNLOAD_BYTES = 10 * 1024 * 1024 // 10 MB
        private const val MAX_REDIRECT_HOPS = 5
        private const val MAX_SOURCE_DIM = 16_384
        private const val MAX_ARTWORK_DIM = 512
        private const val EMBEDDED_ARTWORK_DIM = 256
        private const val JPEG_QUALITY = 88
        private const val EMBEDDED_JPEG_QUALITY = 85

        private val HEX_KEY_REGEX = Regex("^[0-9a-f]{32}$")

        /** Bounded LRU map of key -> original source URL, registered in-process only. */
        private val registryLock = Any()
        private val registry = object : LinkedHashMap<String, String>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
                size > MAX_REGISTRY_ENTRIES
        }

        /** Fixed lock stripes for per-key fetch deduplication without map-removal races. */
        private val keyLocks = Array(32) { Any() }

        /** Bounds concurrent outbound HTTPS fetches so Binder threads cannot starve. */
        private val networkPermits = Semaphore(4)

        /** Serializes disk cache pruning across concurrent fetches. */
        private val pruneLock = Any()

        @Volatile
        private var cachedRedirectlessClient: OkHttpClient? = null
        @Volatile
        private var cachedBaseClient: OkHttpClient? = null

        /** Fallback client if Koin is not initialized (keeps the required Parachord User-Agent). */
        private val fallbackClient: OkHttpClient by lazy {
            val ua = parachordUserAgent(BuildConfig.VERSION_NAME)
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .addInterceptor { chain ->
                    chain.proceed(
                        chain.request().newBuilder()
                            .header("User-Agent", ua)
                            .build()
                    )
                }
                .build()
        }

        fun authority(packageName: String): String = "$packageName$AUTHORITY_SUFFIX"

        internal fun isOwnUri(packageName: String, uri: Uri): Boolean =
            uri.scheme.equals("content", ignoreCase = true) &&
                uri.authority.equals(authority(packageName), ignoreCase = true)

        /**
         * Register [url] and return the `content://<packageName>.artwork/<key>` URI
         * that car hosts and launchers can read via `ContentResolver`.
         *
         * Idempotent: already-wrapped URIs for this provider pass through unchanged.
         */
        fun uriFor(packageName: String, url: String?): Uri? {
            if (url.isNullOrBlank()) return null
            val parsed = Uri.parse(url)
            if (isOwnUri(packageName, parsed)) return parsed
            val scheme = parsed.scheme?.lowercase() ?: return null
            if (scheme != "https" && scheme != "content" && scheme != "file") return null
            val key = keyForUrl(url)
            synchronized(registryLock) {
                registry[key] = url
            }
            return Uri.parse("content://${authority(packageName)}/$key")
        }

        /**
         * Ensure [url] is fetched and cached on disk, returning the cached JPEG
         * file (or null on failure). Shares the exact same cache and striped
         * deduplication as [openFile]. Safe if passed an already-wrapped own URI.
         */
        fun getOrFetchFile(context: Context, url: String): File? {
            if (url.isBlank()) return null
            val parsed = Uri.parse(url)
            if (isOwnUri(context.packageName, parsed)) {
                val key = extractValidKey(parsed) ?: return null
                return getOrFetchByKey(context, key)
            }
            val key = keyForUrl(url)
            synchronized(registryLock) {
                registry[key] = url
            }
            return getOrFetchByKey(context, key)
        }

        /**
         * Fetch [url] through the shared disk cache and return compact 256px
         * JPEG bytes (~15–25 KB) suitable for embedding in `MediaMetadata.artworkData`
         * without inflating `PlayerInfo` Binder transactions.
         */
        fun getOrFetchEmbeddedBytes(context: Context, url: String): ByteArray? {
            val file = getOrFetchFile(context, url) ?: return null
            val raw = try {
                file.readBytes()
            } catch (_: Exception) {
                return null
            }
            return transcodeToBoundedJpeg(raw, EMBEDDED_ARTWORK_DIM, EMBEDDED_JPEG_QUALITY)
        }

        internal fun keyForUrl(url: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            val hex = StringBuilder(32)
            for (i in 0 until 16) {
                val b = digest[i].toInt() and 0xFF
                hex.append(HEX_CHARS[b ushr 4])
                hex.append(HEX_CHARS[b and 0x0F])
            }
            return hex.toString()
        }

        internal fun extractValidKey(uri: Uri): String? {
            val segments = uri.pathSegments
            if (segments.size != 1) return null
            val key = segments[0]
            return key.takeIf { HEX_KEY_REGEX.matches(it) }
        }

        internal fun clearRegistryForTest() {
            synchronized(registryLock) { registry.clear() }
        }

        private val HEX_CHARS = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f',
        )

        private fun lockForKey(key: String): Any =
            keyLocks[(key.hashCode() and Int.MAX_VALUE) % keyLocks.size]

        private fun getOrFetchByKey(context: Context, key: String): File? {
            val dir = File(context.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
            val cacheFile = File(dir, "$key.jpg")
            if (cacheFile.exists() && cacheFile.length() > 0L) {
                cacheFile.setLastModified(System.currentTimeMillis())
                return cacheFile
            }
            val url = synchronized(registryLock) { registry[key] } ?: return null
            synchronized(lockForKey(key)) {
                try {
                    if (cacheFile.exists() && cacheFile.length() > 0L) {
                        cacheFile.setLastModified(System.currentTimeMillis())
                        return cacheFile
                    }
                    val rawBytes = fetchRawBytes(context, url) ?: return null
                    val jpegBytes = transcodeToBoundedJpeg(rawBytes, MAX_ARTWORK_DIM, JPEG_QUALITY)
                        ?: return null
                    val tmp = File(dir, "$key.tmp.${System.nanoTime()}")
                    try {
                        FileOutputStream(tmp).use { it.write(jpegBytes) }
                        if (!tmp.renameTo(cacheFile)) {
                            tmp.delete()
                            return null
                        }
                    } catch (e: Throwable) {
                        tmp.delete()
                        throw e
                    }
                    pruneCacheIfNeeded(dir, keepName = cacheFile.name)
                    return cacheFile
                } catch (e: Exception) {
                    val safeUrl = SecretRedaction.redactSecrets(url)
                    val safeMsg = SecretRedaction.redactSecrets(e.message.orEmpty())
                    Log.w(TAG, "Failed to fetch artwork for $safeUrl: $safeMsg")
                    return null
                }
            }
        }

        internal fun fetchRawBytes(context: Context, url: String): ByteArray? {
            val uri = Uri.parse(url)
            return when (uri.scheme?.lowercase()) {
                "https" -> fetchHttps(url)
                "content" -> readAllowedContentUri(context, uri)
                "file" -> readLocalAppFile(context, uri)
                else -> null
            }
        }

        private fun readAllowedContentUri(context: Context, uri: Uri): ByteArray? {
            val auth = uri.authority ?: return null
            if (auth.equals(authority(context.packageName), ignoreCase = true)) return null
            val allowed = auth.equals("media", ignoreCase = true) ||
                auth.equals("${context.packageName}.fileprovider", ignoreCase = true)
            if (!allowed) return null
            return context.contentResolver.openInputStream(uri)?.use { readBounded(it) }
        }

        internal fun readLocalAppFile(context: Context, uri: Uri): ByteArray? {
            val path = uri.path ?: return null
            val target = File(path).canonicalFile
            val mosaicsRoot = File(context.filesDir, "playlist_mosaics").canonicalFile
            val cacheArtRoot = File(context.cacheDir, CACHE_SUBDIR).canonicalFile
            val allowed = target.path.startsWith(mosaicsRoot.path + File.separator) ||
                target.path.startsWith(cacheArtRoot.path + File.separator)
            if (!allowed || !target.isFile) return null
            return target.inputStream().use { readBounded(it) }
        }

        private fun httpClient(): OkHttpClient {
            val base = GlobalContext.getOrNull()?.getOrNull<OkHttpClient>() ?: return fallbackClient
            val existing = cachedRedirectlessClient
            if (existing != null && cachedBaseClient === base) return existing
            val derived = base.newBuilder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
            cachedBaseClient = base
            cachedRedirectlessClient = derived
            return derived
        }

        internal fun fetchHttps(initialUrl: String, client: OkHttpClient = httpClient()): ByteArray? {
            if (!networkPermits.tryAcquire(10, TimeUnit.SECONDS)) return null
            try {
                var currentUrl = initialUrl
                for (hop in 0..MAX_REDIRECT_HOPS) {
                    try {
                        validatePublicHttpsUrl(currentUrl)
                    } catch (e: IllegalArgumentException) {
                        val safeMsg = SecretRedaction.redactSecrets(e.message.orEmpty())
                        Log.w(TAG, "Refused artwork URL: $safeMsg")
                        return null
                    }
                    val request = Request.Builder().url(currentUrl).get().build()
                    client.newCall(request).execute().use { response ->
                        val code = response.code
                        if (code in 300..399) {
                            val location = response.header("Location") ?: return null
                            if (hop == MAX_REDIRECT_HOPS) return null
                            currentUrl = request.url.resolve(location)?.toString() ?: return null
                            return@use
                        }
                        if (!response.isSuccessful) {
                            val safeUrl = SecretRedaction.redactSecrets(currentUrl)
                            Log.w(TAG, "HTTP $code fetching artwork $safeUrl")
                            return null
                        }
                        val body = response.body ?: return null
                        val contentLength = body.contentLength()
                        if (contentLength > MAX_DOWNLOAD_BYTES) return null
                        return body.byteStream().use { readBounded(it) }
                    }
                }
                return null
            } finally {
                networkPermits.release()
            }
        }

        private fun readBounded(stream: InputStream): ByteArray? {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_DOWNLOAD_BYTES) return null
                out.write(buf, 0, n)
            }
            return if (total > 0) out.toByteArray() else null
        }

        internal fun computeSampleSize(srcW: Int, srcH: Int, maxDim: Int): Int? {
            if (srcW <= 0 || srcH <= 0 || srcW > MAX_SOURCE_DIM || srcH > MAX_SOURCE_DIM) return null
            var sampleSize = 1
            val maxSrc = maxOf(srcW, srcH)
            while (sampleSize <= Int.MAX_VALUE / 4 && maxSrc / (sampleSize * 2) >= maxDim) {
                sampleSize *= 2
            }
            return sampleSize
        }

        internal fun transcodeToBoundedJpeg(
            rawBytes: ByteArray,
            maxDim: Int = MAX_ARTWORK_DIM,
            quality: Int = JPEG_QUALITY,
        ): ByteArray? {
            if (rawBytes.isEmpty()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            val sampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxDim) ?: return null
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, decodeOpts)
                ?: return null
            val scaled = try {
                val maxSide = maxOf(decoded.width, decoded.height)
                if (maxSide > maxDim) {
                    val ratio = maxDim.toFloat() / maxSide.toFloat()
                    val dstW = (decoded.width * ratio).toInt().coerceAtLeast(1)
                    val dstH = (decoded.height * ratio).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(decoded, dstW, dstH, true)
                } else {
                    decoded
                }
            } catch (t: Throwable) {
                decoded.recycle()
                throw t
            }
            return try {
                val out = ByteArrayOutputStream()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                    null
                } else {
                    out.toByteArray()
                }
            } finally {
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
            }
        }

        internal fun pruneCacheIfNeeded(dir: File, keepName: String? = null) {
            synchronized(pruneLock) {
                val now = System.currentTimeMillis()
                dir.listFiles { f -> f.isFile && f.name.contains(".tmp.") }?.forEach { tmp ->
                    if (now - tmp.lastModified() > STALE_TMP_AGE_MS) tmp.delete()
                }
                val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jpg") } ?: return
                if (files.size <= MAX_CACHE_FILES) return
                files.sortBy { it.lastModified() }
                var remaining = files.size
                for (f in files) {
                    if (remaining <= PRUNE_TARGET_FILES) break
                    if (f.name == keepName) continue
                    if (f.delete()) remaining--
                }
            }
        }
    }
}
