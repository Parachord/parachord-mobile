package com.parachord.android.playback

import android.app.Application
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class MediaArtworkProviderTest {

    private lateinit var provider: MediaArtworkProvider

    @Before
    fun setUp() {
        MediaArtworkProvider.clearRegistryForTest()
        val info = ProviderInfo().apply {
            authority = "com.parachord.android.artwork"
        }
        provider = Robolectric.buildContentProvider(MediaArtworkProvider::class.java)
            .create(info)
            .get()
    }

    @Test fun `uriFor builds deterministic content URI and is idempotent`() {
        val url = "https://coverartarchive.org/release/abc/front-250"
        val first = MediaArtworkProvider.uriFor("com.parachord.android", url)
        val second = MediaArtworkProvider.uriFor("com.parachord.android", url)
        assertNotNull(first)
        assertEquals(first, second)
        assertEquals("content", first!!.scheme)
        assertEquals("com.parachord.android.artwork", first.authority)
        assertTrue(Regex("^[0-9a-f]{32}$").matches(first.lastPathSegment!!))

        // Wrapping an already-wrapped URI returns the exact same URI (case-insensitive).
        val rewrapped = MediaArtworkProvider.uriFor("com.parachord.android", first.toString())
        assertEquals(first, rewrapped)
        val upperScheme = first.toString().replaceFirst("content://", "CONTENT://")
        assertEquals(upperScheme, MediaArtworkProvider.uriFor("com.parachord.android", upperScheme).toString())

        // Different URLs map to distinct keys.
        val other = MediaArtworkProvider.uriFor("com.parachord.android", "https://example.com/other.jpg")
        assertNotEquals(first, other)
    }

    @Test fun `uriFor returns null for null, blank, or unsupported schemes`() {
        assertNull(MediaArtworkProvider.uriFor("com.parachord.android", null))
        assertNull(MediaArtworkProvider.uriFor("com.parachord.android", ""))
        assertNull(MediaArtworkProvider.uriFor("com.parachord.android", "   "))
        assertNull(MediaArtworkProvider.uriFor("com.parachord.android", "http://example.com/plain.jpg"))
        assertNull(MediaArtworkProvider.uriFor("com.parachord.android", "data:image/png;base64,AAAA"))
        assertNull(MediaArtworkProvider.uriFor("com.parachord.android", "javascript:alert(1)"))
    }

    @Test fun `extractValidKey rejects traversal, extra segments, and non-hex keys`() {
        val validKey = MediaArtworkProvider.keyForUrl("https://example.com/a.jpg")
        assertEquals(
            validKey,
            MediaArtworkProvider.extractValidKey(Uri.parse("content://com.parachord.android.artwork/$validKey")),
        )
        val bad = listOf(
            "content://com.parachord.android.artwork/",
            "content://com.parachord.android.artwork/../../etc/passwd",
            "content://com.parachord.android.artwork/$validKey/extra",
            "content://com.parachord.android.artwork/${validKey.uppercase()}",
            "content://com.parachord.android.artwork/not-a-hex-key",
            "content://com.parachord.android.artwork/abcdef",
            "content://com.parachord.android.artwork/${validKey}00",
        )
        for (raw in bad) {
            assertNull("Expected null for $raw", MediaArtworkProvider.extractValidKey(Uri.parse(raw)))
        }
    }

    @Test fun `getType returns image jpeg for valid key and null otherwise`() {
        val uri = MediaArtworkProvider.uriFor("com.parachord.android", "https://example.com/cover.jpg")!!
        assertEquals("image/jpeg", provider.getType(uri))
        assertNull(provider.getType(Uri.parse("content://com.parachord.android.artwork/bad")))
    }

    @Test fun `openFile rejects non-read modes`() {
        val uri = MediaArtworkProvider.uriFor("com.parachord.android", "https://example.com/cover.jpg")!!
        for (mode in listOf("w", "rw", "wt", "wa")) {
            try {
                provider.openFile(uri, mode)
                fail("Expected SecurityException for mode=$mode")
            } catch (_: SecurityException) {
                // expected
            }
        }
    }

    @Test fun `openFile rejects invalid or unregistered keys`() {
        val unknownKey = "0123456789abcdef0123456789abcdef"
        try {
            provider.openFile(Uri.parse("content://com.parachord.android.artwork/$unknownKey"), "r")
            fail("Expected FileNotFoundException for unregistered key")
        } catch (_: FileNotFoundException) {
            // expected
        }
        try {
            provider.openFile(Uri.parse("content://com.parachord.android.artwork/../secret"), "r")
            fail("Expected FileNotFoundException for traversal key")
        } catch (_: FileNotFoundException) {
            // expected
        }
    }

    @Test fun `openFile serves cached file even after registry is cleared`() {
        val url = "https://example.com/cached.jpg"
        val uri = MediaArtworkProvider.uriFor("com.parachord.android", url)!!
        val key = uri.lastPathSegment!!
        val cacheDir = File(RuntimeEnvironment.getApplication().cacheDir, "car_artwork").apply { mkdirs() }
        val payload = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        File(cacheDir, "$key.jpg").writeBytes(payload)

        // Simulate a process restart where in-memory registry is empty.
        MediaArtworkProvider.clearRegistryForTest()

        provider.openFile(uri, "r").use { pfd ->
            val read = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
            assertArrayEquals(payload, read)
        }
    }

    @Test fun `getOrFetchFile short-circuits own provider URI without re-hashing`() {
        val app = RuntimeEnvironment.getApplication()
        val url = "https://example.com/self-check.jpg"
        val uri = MediaArtworkProvider.uriFor(app.packageName, url)!!
        val key = uri.lastPathSegment!!
        val cacheDir = File(app.cacheDir, "car_artwork").apply { mkdirs() }
        val expected = File(cacheDir, "$key.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }

        val resolved = MediaArtworkProvider.getOrFetchFile(app, uri.toString())
        assertEquals(expected.canonicalPath, resolved?.canonicalPath)
    }

    @Test fun `fetchRawBytes rejects unsupported schemes, self content URI, and unallowlisted content authority`() {
        val app = RuntimeEnvironment.getApplication()
        assertNull(MediaArtworkProvider.fetchRawBytes(app, "http://example.com/plain.jpg"))
        assertNull(MediaArtworkProvider.fetchRawBytes(app, "data:image/png;base64,AAAA"))
        assertNull(
            MediaArtworkProvider.fetchRawBytes(
                app,
                "content://${app.packageName}.artwork/0123456789abcdef0123456789abcdef",
            ),
        )
        assertNull(MediaArtworkProvider.fetchRawBytes(app, "content://com.other.app.secret/data"))
    }

    @Test fun `readLocalAppFile allows app files with query params and blocks traversal`() {
        val app = RuntimeEnvironment.getApplication()
        val mosaics = File(app.filesDir, "playlist_mosaics").apply { mkdirs() }
        val mosaic = File(mosaics, "mix.jpg").apply { writeBytes(byteArrayOf(9, 8, 7)) }

        val withQuery = Uri.parse("${mosaic.toURI()}?v=12345")
        assertArrayEquals(byteArrayOf(9, 8, 7), MediaArtworkProvider.readLocalAppFile(app, withQuery))

        // Arbitrary files inside filesDir outside playlist_mosaics/ must be rejected.
        val otherInternal = File(app.filesDir, "other_secret.json").apply { writeBytes(byteArrayOf(1, 2)) }
        assertNull(MediaArtworkProvider.readLocalAppFile(app, Uri.parse(otherInternal.toURI().toString())))

        assertNull(MediaArtworkProvider.readLocalAppFile(app, Uri.parse("file:///etc/passwd")))
        assertNull(
            MediaArtworkProvider.readLocalAppFile(
                app,
                Uri.parse("file://${app.filesDir.path}/../shared_prefs/secret.xml"),
            ),
        )
    }

    @Test fun `fetchHttps rejects private hosts and private redirect hops`() {
        var networkCalled = false
        val client = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(
                Interceptor { chain ->
                    networkCalled = true
                    val url = chain.request().url.toString()
                    when {
                        url == "https://public.example.com/redirect-to-lan" -> Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(307)
                            .message("Temporary Redirect")
                            .header("Location", "https://192.168.1.1/secret.jpg")
                            .body(ByteArray(0).toResponseBody())
                            .build()
                        url == "https://public.example.com/redirect-ok" -> Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(307)
                            .message("Temporary Redirect")
                            .header("Location", "https://cdn.example.com/art.jpg")
                            .body(ByteArray(0).toResponseBody())
                            .build()
                        url == "https://cdn.example.com/art.jpg" -> Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(byteArrayOf(1, 2, 3).toResponseBody("image/jpeg".toMediaType()))
                            .build()
                        else -> Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body(ByteArray(0).toResponseBody())
                            .build()
                    }
                },
            )
            .build()

        // Direct private/loopback URLs must be rejected before any network call.
        assertNull(MediaArtworkProvider.fetchHttps("https://127.0.0.1/a.jpg", client))
        assertNull(MediaArtworkProvider.fetchHttps("https://192.168.1.10/a.jpg", client))
        assertNull(MediaArtworkProvider.fetchHttps("https://localhost/a.jpg", client))
        assertFalse("Private URL should never hit network", networkCalled)

        // Redirect from public host to private IP must be rejected.
        assertNull(MediaArtworkProvider.fetchHttps("https://public.example.com/redirect-to-lan", client))

        // Public 307 redirect to another public HTTPS host succeeds (Cover Art Archive flow).
        assertArrayEquals(
            byteArrayOf(1, 2, 3),
            MediaArtworkProvider.fetchHttps("https://public.example.com/redirect-ok", client),
        )

        // Non-2xx returns null.
        assertNull(MediaArtworkProvider.fetchHttps("https://public.example.com/missing.jpg", client))
    }

    @Test fun `computeSampleSize guards decompression bombs and extreme aspect ratios`() {
        assertNull(MediaArtworkProvider.computeSampleSize(0, 500, 512))
        assertNull(MediaArtworkProvider.computeSampleSize(500, -1, 512))
        assertNull(MediaArtworkProvider.computeSampleSize(20_000, 200, 512))
        assertNull(MediaArtworkProvider.computeSampleSize(200, 20_000, 512))

        assertEquals(1, MediaArtworkProvider.computeSampleSize(512, 512, 512))
        assertEquals(1, MediaArtworkProvider.computeSampleSize(200, 200, 512))
        assertEquals(2, MediaArtworkProvider.computeSampleSize(1024, 1024, 512))
        // Extreme aspect ratio (4096 x 100) must still downsample by longest axis.
        assertEquals(8, MediaArtworkProvider.computeSampleSize(4096, 100, 512))
        assertEquals(8, MediaArtworkProvider.computeSampleSize(100, 4096, 512))
    }

    @Test fun `transcodeToBoundedJpeg rejects empty bytes and encodes valid bitmap`() {
        assertNull(MediaArtworkProvider.transcodeToBoundedJpeg(ByteArray(0)))

        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()

        val jpeg = MediaArtworkProvider.transcodeToBoundedJpeg(out.toByteArray(), maxDim = 512)
        assertNotNull(jpeg)
        assertTrue(jpeg!!.isNotEmpty())
    }

    @Test fun `pruneCacheIfNeeded evicts oldest files while preserving keepName and removes stale tmp`() {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "car_artwork_prune_test").apply {
            deleteRecursively()
            mkdirs()
        }
        val baseTime = System.currentTimeMillis() - 600_000L
        for (i in 0 until 205) {
            val f = File(dir, "file_${i.toString().padStart(3, '0')}.jpg").apply { writeBytes(byteArrayOf(1)) }
            f.setLastModified(baseTime + i * 1000L)
        }
        // Make file_000 the oldest, but pass it as keepName so it must survive pruning.
        val staleTmp = File(dir, "abandoned.tmp.123").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(baseTime)
        }
        val freshTmp = File(dir, "inflight.tmp.456").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(System.currentTimeMillis())
        }

        MediaArtworkProvider.pruneCacheIfNeeded(dir, keepName = "file_000.jpg")

        assertTrue("keepName must not be pruned", File(dir, "file_000.jpg").exists())
        assertFalse("stale tmp should be removed", staleTmp.exists())
        assertTrue("fresh tmp should be kept", freshTmp.exists())
        val remainingJpgs = dir.listFiles { f -> f.name.endsWith(".jpg") }?.size ?: 0
        assertEquals(150, remainingJpgs)
        dir.deleteRecursively()
    }

    @Test fun `write operations throw UnsupportedOperationException`() {
        val uri = Uri.parse("content://com.parachord.android.artwork/0123456789abcdef0123456789abcdef")
        try {
            provider.insert(uri, ContentValues())
            fail("Expected UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) { /* expected */ }
        try {
            provider.update(uri, ContentValues(), null, null)
            fail("Expected UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) { /* expected */ }
        try {
            provider.delete(uri, null, null)
            fail("Expected UnsupportedOperationException")
        } catch (_: UnsupportedOperationException) { /* expected */ }
    }
}
