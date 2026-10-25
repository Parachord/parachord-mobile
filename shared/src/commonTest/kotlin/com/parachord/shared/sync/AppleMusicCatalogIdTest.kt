package com.parachord.shared.sync

import com.parachord.shared.api.AmPlayParams
import com.parachord.shared.api.AppleMusicClient
import com.parachord.shared.api.AppleMusicLibraryClient
import com.parachord.shared.api.auth.AuthCredential
import com.parachord.shared.api.auth.AuthRealm
import com.parachord.shared.api.auth.AuthTokenProvider
import com.parachord.shared.api.catalogIdOr
import com.parachord.shared.resolver.isAppleMusicCatalogId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #265 — for a LIBRARY item, Apple's `playParams.id` is the library id (`i.…`)
 * and the catalog id is `playParams.catalogId`. Sync must use the catalog id as
 * the track's Apple Music id (playback, ISRC lookup, add-to-playlist) while
 * keeping the library id as the sync key. Desktop: `transformTrack` in
 * sync-providers/applemusic.js. Responses below use Apple's documented shape.
 */
class AppleMusicCatalogIdTest {

    // ── pure helpers ─────────────────────────────────────────────────────

    @Test fun catalogIdOr_prefersCatalogId_andFallsBackWhenMissingOrBlank() {
        assertEquals("1440857781", AmPlayParams("i.abc", "song", catalogId = "1440857781").catalogIdOr("i.abc"))
        assertEquals("i.abc", AmPlayParams("i.abc", "song").catalogIdOr("i.abc"))
        assertEquals("i.abc", AmPlayParams("i.abc", "song", catalogId = " ").catalogIdOr("i.abc"))
        assertEquals("i.abc", (null as AmPlayParams?).catalogIdOr("i.abc"))
    }

    @Test fun isAppleMusicCatalogId_acceptsOnlyAllDigitIds() {
        assertTrue(isAppleMusicCatalogId("1440857781"))
        assertFalse(isAppleMusicCatalogId("i.3ZKYfNV54d"))
        assertFalse(isAppleMusicCatalogId("l.abc"))
        assertFalse(isAppleMusicCatalogId("p.goleKsZDVNL"))
        assertFalse(isAppleMusicCatalogId(""))
        assertFalse(isAppleMusicCatalogId(null))
    }

    // ── the provider, against Apple's response shape ─────────────────────

    /** Two library songs: one with a catalogId, one (e.g. an upload) without. */
    private val librarySongs = """
        {"data":[
          {"id":"i.withCat","type":"library-songs","attributes":{"name":"Thinning","artistName":"Speedy Ortiz",
            "playParams":{"id":"i.withCat","kind":"song","isLibrary":true,"reporting":true,"catalogId":"1440857781"}}},
          {"id":"i.noCat","type":"library-songs","attributes":{"name":"Demo","artistName":"Me",
            "playParams":{"id":"i.noCat","kind":"song","isLibrary":true}}}
        ],"meta":{"total":2}}
    """.trimIndent()

    private val requestedCatalogIds = mutableListOf<String>()

    private fun provider(): AppleMusicSyncProvider {
        val json = headersOf("Content-Type", "application/json")
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                path.endsWith("/tracks") -> respond(librarySongs, HttpStatusCode.OK, json)
                path.endsWith("/me/library/songs") -> respond(librarySongs, HttpStatusCode.OK, json)
                path.endsWith("/me/storefront") -> respond("""{"data":[{"id":"us"}]}""", HttpStatusCode.OK, json)
                path.contains("/catalog/us/songs") -> {
                    requestedCatalogIds += req.url.parameters["ids"].orEmpty().split(",")
                    respond(
                        """{"data":[{"id":"1440857781","attributes":{"isrc":"USMRG1500001"}}]}""",
                        HttpStatusCode.OK, json,
                    )
                }
                else -> respond("{}", HttpStatusCode.NotFound, json)
            }
        }
        val http = HttpClient(engine)
        val tokens = object : AuthTokenProvider {
            override suspend fun tokenFor(realm: AuthRealm): AuthCredential? = AuthCredential.BearerWithMUT("dev", "mut")
            override suspend fun invalidate(realm: AuthRealm) {}
        }
        return AppleMusicSyncProvider(AppleMusicLibraryClient(http, tokens), AppleMusicClient(http))
    }

    @Test fun playlistTracks_storeTheCatalogId_fallingBackToTheLibraryId() = runTest {
        val tracks = provider().fetchPlaylistTracks("p.goleKsZDVNL")
        assertEquals(listOf("1440857781", "i.noCat"), tracks.map { it.trackAppleMusicId })
    }

    @Test fun librarySongs_keepTheLibraryIdAsSyncKey_andUseTheCatalogIdAsAppleMusicId() = runTest {
        val synced = provider().fetchTracks(localCount = 0, latestExternalId = null, onProgress = null)!!
        val byTitle = synced.associateBy { it.entity.title }

        val withCat = byTitle.getValue("Thinning")
        assertEquals("i.withCat", withCat.spotifyId, "the sync key (externalId) stays the library id")
        assertEquals("1440857781", withCat.entity.appleMusicId)

        val noCat = byTitle.getValue("Demo")
        assertEquals("i.noCat", noCat.spotifyId)
        assertEquals("i.noCat", noCat.entity.appleMusicId, "no catalogId → falls back to the library id")
        assertEquals("applemusic-i.noCat", noCat.entity.id, "row id unchanged for an un-ISRC'd song")
    }

    @Test fun theIsrcLookupAsksByCatalogId_soTheIsrcActuallyResolves() = runTest {
        val synced = provider().fetchTracks(localCount = 0, latestExternalId = null, onProgress = null)!!

        // The catalog endpoint only knows catalog ids; asking by "i.withCat" found nothing before.
        assertTrue("1440857781" in requestedCatalogIds, "asked for $requestedCatalogIds")
        assertFalse("i.withCat" in requestedCatalogIds)
        assertEquals("USMRG1500001", synced.single { it.entity.title == "Thinning" }.entity.isrc)
    }
}
