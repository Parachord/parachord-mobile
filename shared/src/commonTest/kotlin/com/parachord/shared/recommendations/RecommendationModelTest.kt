package com.parachord.shared.recommendations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Desktop's vectors for the Recommendations model
 * (`tests/renderer/recommendation-model.test.js`, parachord#1309): the
 * contract the plugins and both clients rely on. Change a rule on desktop
 * first.
 */
class RecommendationModelTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun item(text: String, resolverId: String? = null) = normalizeRecommendationItem(json(text), resolverId)

    // ── normalizeRecommendationItem ──────────────────────────────────

    @Test
    fun aSongKeepsItsProviderIdAndFields() {
        val item = item(
            """{"type":"song","id":"amz-track-1","name":"Doberman","artist":"Deafheaven","duration":394,
               "imageUrl":"https://m.media-amazon.com/images/I/1.jpg","url":"https://music.amazon.com/tracks/B0DTRL3P23/"}""",
            "amazonmusic",
        )!!
        assertEquals(RecommendationType.SONG, item.type)
        assertEquals("amz-track-1", item.id)
        assertEquals("Doberman", item.name)
        assertEquals("Deafheaven", item.artist)
        assertEquals(394.0, item.durationSeconds)
        assertEquals("amazonmusic", item.resolverId)
    }

    @Test
    fun aSongWithNoProviderIdFallsBackToTheTrackSlug() {
        val item = item("""{"type":"song","name":"Sunbather","artist":"Deafheaven"}""", "lastfm")!!
        assertEquals("deafheaven-sunbather", item.id)
        assertEquals(trackSlug("Deafheaven", "Sunbather"), item.id)
    }

    @Test
    fun aNumericIdIsNotAnId() {
        // Desktop keeps only string ids (`typeof raw.id === 'string'`).
        val item = item("""{"type":"song","id":12345,"name":"Sunbather","artist":"Deafheaven"}""")!!
        assertEquals("deafheaven-sunbather", item.id)
    }

    @Test
    fun trackSlugFoldsAccentsAndDropsPunctuation() {
        assertEquals("bjork-joga", trackSlug("Björk", "Jóga"))
        assertEquals("acdc-backinblack", trackSlug("AC/DC", "Back in Black"))
        assertEquals("jay-z-99problems", trackSlug("JAY-Z", "99 Problems"))
        assertEquals("кино-группакрови", trackSlug("Кино", "Группа крови"))
    }

    @Test
    fun aSongsMbidAndIsrcRideOnTheItem() {
        val item = item(
            """{"type":"song","id":"lb-1","name":"Dream House","artist":"Deafheaven",
               "mbid":"9d59b396-1f9d-4a70-8b17-6b1dd97d1c73","isrc":"GB5EP1400001"}""",
            "listenbrainz",
        )!!
        assertEquals("9d59b396-1f9d-4a70-8b17-6b1dd97d1c73", item.mbid)
        assertEquals("GB5EP1400001", item.isrc)
    }

    @Test
    fun blankMbidAndIsrcAreDropped() {
        val item = item("""{"type":"song","name":"Sunbather","artist":"Deafheaven","mbid":"","isrc":"  "}""", "lastfm")!!
        assertNull(item.mbid)
        assertNull(item.isrc)
    }

    @Test
    fun anArtistItemCarriesNoArtistField() {
        val item = item("""{"type":"artist","id":"B004RUHJBC","name":"Deafheaven"}""", "amazonmusic")!!
        assertEquals(RecommendationType.ARTIST, item.type)
        assertEquals("B004RUHJBC", item.id)
        assertNull(item.artist)
        assertNull(item("""{"type":"artist","id":"a1","name":"Björk","artist":"Björk"}""")!!.artist)
    }

    @Test
    fun unknownTypesNamelessItemsAndNonObjectsAreDropped() {
        assertNull(item("""{"type":"audiobook","name":"Harry Potter"}"""))
        assertNull(item("""{"type":"album","name":"   "}"""))
        assertNull(item("null"))
        assertNull(item("""{"type":"song"}"""))
        assertNull(item("""["song"]"""))
        assertNull(item("""{"type":"song","name":42}"""))
    }

    @Test
    fun optionalFieldsAreKeptOnlyAsNonEmptyStrings() {
        val item = item(
            """{"type":"playlist","id":"pl1","name":"Heavy Classics","url":"https://music.amazon.com/playlists/pl1",
               "imageUrl":"","reason":"  ","description":"x"}""",
        )!!
        assertEquals("https://music.amazon.com/playlists/pl1", item.url)
        assertNull(item.imageUrl)
        assertNull(item.reason)
    }

    @Test
    fun durationIsANonZeroNumberOnSongsOnly() {
        assertNull(item("""{"type":"song","name":"A","artist":"B","duration":null}""")!!.durationSeconds)
        assertNull(item("""{"type":"song","name":"A","artist":"B","duration":0}""")!!.durationSeconds)
        assertNull(item("""{"type":"song","name":"A","artist":"B","duration":"soon"}""")!!.durationSeconds)
        assertEquals(262.0, item("""{"type":"song","name":"A","artist":"B","duration":"262"}""")!!.durationSeconds)
        assertNull(item("""{"type":"album","name":"A","artist":"B","duration":262}""")!!.durationSeconds)
    }

    // ── normalizeRecommendationSections ──────────────────────────────

    @Test
    fun sectionsAreNormalizedAndEmptyOnesDropped() {
        val result = normalizeRecommendationSections(
            json(
                """{"sections":[
                    {"title":"Albums for You","id":"uri:///recommendations/browse-home/albumsStrategy","description":"For you","items":[
                        {"type":"album","id":"B0G2TSTWXD","name":"Overspace & Supertime","artist":"Cryptic Shift"}]},
                    {"items":[]},
                    {"items":[{"type":"song","name":"Doberman","artist":"Deafheaven"}]}
                ]}""",
            ),
            resolverId = "amazonmusic",
            resolverName = "Amazon Music",
        )
        assertEquals("amazonmusic", result.resolverId)
        assertEquals(2, result.sections.size)
        assertEquals("Albums for You", result.sections[0].title)
        assertEquals("uri:///recommendations/browse-home/albumsStrategy", result.sections[0].id)
        assertEquals("For you", result.sections[0].summary)
        assertNull(result.sections[1].title)
    }

    @Test
    fun aBareArrayIsAcceptedAndAnythingElseIsEmpty() {
        assertEquals(1, normalizeRecommendationSections(json("""[{"items":[{"type":"artist","name":"Björk"}]}]""")).sections.size)
        assertEquals(emptyList(), normalizeRecommendationSections(null).sections)
        assertEquals(emptyList(), normalizeRecommendationSections(json("null")).sections)
        assertEquals(emptyList(), normalizeRecommendationSections(json("""{"sections":"nope"}""")).sections)
        assertEquals(emptyList(), normalizeRecommendationSections(json("""{"sections":[{"items":"nope"},7]}""")).sections)
    }

    @Test
    fun aSectionsCursorIsPassedThroughAsText() {
        fun cursor(value: String) = normalizeRecommendationSections(
            json("""{"sections":[{"id":"s","title":"T","nextCursor":$value,"items":[{"type":"artist","name":"A"}]}]}"""),
        ).sections.single().nextCursor
        assertEquals("playlistsStrategy.20", cursor("\"playlistsStrategy.20\""))
        assertEquals("20", cursor("20"))
        assertNull(cursor("null"))
        assertNull(cursor("{\"page\":2}"))
        assertNull(cursor("\"\""))
    }

    // ── deriveArtistsFromSongs / dedupeItems ─────────────────────────

    private fun song(artist: String?, name: String = "x", id: String = "$artist-$name", resolverId: String? = null) =
        RecommendationItem(RecommendationType.SONG, id = id, name = name, artist = artist, resolverId = resolverId)

    @Test
    fun derivedArtistsAreUniqueInFirstAppearanceOrder() {
        val artists = deriveArtistsFromSongs(
            listOf(
                song("Deafheaven"),
                song("deafheaven"),
                song("Anathema"),
                RecommendationItem(RecommendationType.ALBUM, id = "al", name = "x", artist = "Ignored"),
                song(""),
                song(null),
            ),
        )
        assertEquals(listOf("Deafheaven", "Anathema"), artists.map { it.name })
        assertEquals(RecommendationType.ARTIST, artists[0].type)
        assertTrue(artists[0].derived)
        assertEquals("deafheaven", artists[0].id)
    }

    @Test
    fun sameArtistAndTitleIsOneSongAndPlaylistsDedupeByUrl() {
        val out = dedupeItems(
            listOf(
                song("Deafheaven", "Sunbather", id = "1"),
                song("deafheaven", "sunbather", id = "2"),
                RecommendationItem(RecommendationType.PLAYLIST, id = "p1", name = "A", url = "https://music.amazon.com/playlists/p1"),
                RecommendationItem(RecommendationType.PLAYLIST, id = "p2", name = "B", url = "https://music.amazon.com/playlists/p1"),
            ),
        )
        assertEquals(listOf("1", "p1"), out.map { it.id })
    }

    // ── buildRecommendationSections ──────────────────────────────────

    private val providers = listOf(
        normalizeRecommendationSections(
            json(
                """{"sections":[
                    {"title":"Albums for You","id":"uri:///rec/albums","items":[
                        {"type":"album","id":"a1","name":"Tempelschlaf","artist":"The Ruins Of Beverast"}]},
                    {"title":"Your Frequent Plays","id":"uri:///rec/frequent","items":[
                        {"type":"song","id":"s1","name":"Doberman","artist":"Deafheaven","duration":394},
                        {"type":"artist","id":"ar1","name":"Deafheaven"}]}
                ]}""",
            ),
            "amazonmusic", "Amazon Music",
        ),
        normalizeRecommendationSections(
            json(
                """{"sections":[{"items":[
                    {"type":"song","id":"l1","name":"Doberman","artist":"Deafheaven"},
                    {"type":"song","id":"l2","name":"Dream House","artist":"Deafheaven"}]}]}""",
            ),
            "lastfm", "Last.fm",
        ),
    )

    @Test
    fun genericBucketsComeFirstThenTitledSectionsInProviderOrder() {
        val sections = buildRecommendationSections(providers)
        assertEquals(
            listOf("generic:artist", "generic:song", "amazonmusic:uri:///rec/albums", "amazonmusic:uri:///rec/frequent"),
            sections.map { it.key },
        )
        val songs = sections.first { it.key == "generic:song" }
        assertEquals(RecommendationType.SONG, songs.bucketType)
        assertEquals(listOf("l1", "l2"), songs.items.map { it.id })
        // Derived from the generic songs only, not from a titled section's.
        val artists = sections.first { it.key == "generic:artist" }
        assertEquals(listOf("Deafheaven"), artists.items.map { it.name })
        assertTrue(artists.items.single().derived)
    }

    @Test
    fun genericSongsDedupeAcrossProvidersKeepingTheFirst() {
        val lb = normalizeRecommendationSections(
            json("""{"sections":[{"items":[{"type":"song","id":"lb1","name":"doberman","artist":"DEAFHEAVEN","mbid":"m"}]}]}"""),
            "listenbrainz", "ListenBrainz",
        )
        val songs = buildRecommendationSections(listOf(providers[1], lb)).first { it.key == "generic:song" }
        assertEquals(listOf("l1", "l2"), songs.items.map { it.id })
    }

    @Test
    fun aTitledSectionKeepsItsProviderAndLoadsMoreOnlyWhenItCan() {
        val frequent = buildRecommendationSections(providers, expandableResolverIds = setOf("amazonmusic"))
            .first { it.key == "amazonmusic:uri:///rec/frequent" }
        assertEquals("amazonmusic", frequent.resolverId)
        assertEquals("Amazon Music", frequent.resolverName)
        assertEquals("uri:///rec/frequent", frequent.sectionId)
        assertEquals("Your Frequent Plays", frequent.title)
        assertTrue(frequent.canLoadMore)
        assertFalse(buildRecommendationSections(providers).first { it.key == "amazonmusic:uri:///rec/frequent" }.canLoadMore)
    }

    @Test
    fun expandedItemsMergeIntoTheirSectionAndKeepTheCursor() {
        val expanded = mapOf(
            "amazonmusic|uri:///rec/albums" to RecommendationExpansion(
                items = listOf(item("""{"type":"album","id":"a2","name":"Sunbather","artist":"Deafheaven"}""", "amazonmusic")!!),
                nextCursor = "albumsStrategy.20",
            ),
        )
        val albums = buildRecommendationSections(providers, expanded, setOf("amazonmusic"))
            .first { it.key == "amazonmusic:uri:///rec/albums" }
        assertEquals(listOf("a1", "a2"), albums.items.map { it.id })
        assertEquals("albumsStrategy.20", albums.nextCursor)
        assertTrue(albums.canLoadMore)

        val done = buildRecommendationSections(
            providers,
            mapOf("amazonmusic|uri:///rec/albums" to RecommendationExpansion(exhausted = true)),
            setOf("amazonmusic"),
        ).first { it.key == "amazonmusic:uri:///rec/albums" }
        assertFalse(done.canLoadMore)
    }

    @Test
    fun aSectionsOwnFirstPageCursorIsWhereLoadMoreStarts() {
        val provider = normalizeRecommendationSections(
            json(
                """{"sections":[{"id":"uri:///rec/playlists","title":"Playlists for You","nextCursor":"playlistsStrategy.20","items":[
                    {"type":"playlist","id":"pp1","name":"My Discovery Mix","url":"https://music.amazon.com/playlists/PP1"}]}]}""",
            ),
            "amazonmusic", "Amazon Music",
        )
        val playlists = buildRecommendationSections(listOf(provider), expandableResolverIds = setOf("amazonmusic")).single()
        assertEquals("amazonmusic:uri:///rec/playlists", playlists.key)
        assertTrue(playlists.canLoadMore)
        assertEquals("playlistsStrategy.20", playlists.nextCursor)
    }

    @Test
    fun noProvidersIsNoSections() {
        assertEquals(emptyList(), buildRecommendationSections(emptyList()))
        assertEquals(emptyList(), buildRecommendationSections(listOf(ProviderRecommendations(resolverId = "x"))))
    }
}
