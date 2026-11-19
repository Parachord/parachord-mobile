package com.parachord.shared.recommendations

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Desktop's vectors for the Recommendations model
 * (`tests/renderer/recommendation-model.test.js`): the contract the plugins
 * and every client rely on. One test per desktop `test(...)`, same inputs and
 * expectations. Change a rule on desktop first.
 *
 * Not ported: the song's `track` object desktop builds on the item (mobile
 * makes the track when it plays), so those tests check the item's fields.
 */
class RecommendationModelTest {

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)
    private fun item(text: String, resolverId: String? = null) = normalizeRecommendationItem(json(text), resolverId)
    private fun sections(text: String, resolverId: String? = null, resolverName: String? = null) =
        normalizeRecommendationSections(json(text), resolverId, resolverName)

    /** A hand-built item, like desktop's object literals. */
    private fun rec(
        type: RecommendationType,
        name: String = "x",
        artist: String? = null,
        url: String? = null,
        category: String? = null,
        weekly: WeeklyInfo? = null,
        id: String = "id",
        resolverId: String? = null,
        reason: String? = null,
    ) = RecommendationItem(
        type = type, id = id, name = name, artist = artist, url = url,
        category = category, weekly = weekly, resolverId = resolverId, reason = reason,
    )

    private val aWeek = WeeklyInfo(id = "w", contextType = "weekly-jam", label = "Jams", weekLabel = "This Week", date = null)

    // ── normalizeRecommendationItem ──────────────────────────────────

    @Test
    fun `normalizes a song — whose id is the provider's`() {
        val item = item(
            """{"type":"song","id":"amz-track-1","name":"Doberman","artist":"Deafheaven","duration":394,
               "imageUrl":"https://m.media-amazon.com/images/I/1.jpg","url":"https://music.amazon.com/tracks/B0DTRL3P23/"}""",
            "amazonmusic",
        )!!
        assertEquals(RecommendationType.SONG, item.type)
        assertEquals("amz-track-1", item.id)
        assertEquals("Doberman", item.name)
        assertEquals("Deafheaven", item.artist)
        assertEquals(394.0, item.duration)
        assertEquals("amazonmusic", item.resolverId)
        assertEquals("https://m.media-amazon.com/images/I/1.jpg", item.imageUrl)
        assertEquals("https://music.amazon.com/tracks/B0DTRL3P23/", item.url)
    }

    @Test
    fun `a song with no provider id falls back to the trackSlug of artist+title`() {
        val item = item("""{"type":"song","name":"Sunbather","artist":"Deafheaven"}""", "lastfm")!!
        assertEquals(trackSlug("Deafheaven", "Sunbather"), item.id)
    }

    @Test
    fun `trackSlug folds accents and drops punctuation`() {
        assertEquals("bjork-joga", trackSlug("Björk", "Jóga"))
        assertEquals("acdc-backinblack", trackSlug("AC/DC", "Back in Black"))
        assertEquals("jay-z-99problems", trackSlug("JAY-Z", "99 Problems"))
        assertEquals("кино-группакрови", trackSlug("Кино", "Группа крови"))
    }

    @Test
    fun `a song's mbid and isrc ride onto the item`() {
        val item = item(
            """{"type":"song","id":"lb-1","name":"Dream House","artist":"Deafheaven",
               "mbid":"9d59b396-1f9d-4a70-8b17-6b1dd97d1c73","isrc":"GB5EP1400001"}""",
            "listenbrainz",
        )!!
        assertEquals("9d59b396-1f9d-4a70-8b17-6b1dd97d1c73", item.mbid)
        assertEquals("GB5EP1400001", item.isrc)
    }

    @Test
    fun `a song without mbid or isrc keeps no such fields`() {
        val item = item("""{"type":"song","name":"Sunbather","artist":"Deafheaven","mbid":"","isrc":"  "}""", "lastfm")!!
        assertNull(item.mbid)
        assertNull(item.isrc)
    }

    @Test
    fun `an artist item carries no artist field`() {
        val item = item("""{"type":"artist","id":"B004RUHJBC","name":"Deafheaven"}""", "amazonmusic")!!
        assertEquals(RecommendationType.ARTIST, item.type)
        assertEquals("B004RUHJBC", item.id)
        assertEquals("Deafheaven", item.name)
        assertNull(item.artist)
        assertNull(item.duration)
    }

    @Test
    fun `unknown types — nameless items and non-objects are dropped`() {
        assertNull(item("""{"type":"audiobook","name":"Harry Potter"}"""))
        assertNull(item("""{"type":"album","name":"   "}"""))
        assertNull(normalizeRecommendationItem(null, null))
        assertNull(item("null"))
        assertNull(item("""{"type":"song"}"""))
    }

    @Test
    fun `optional fields are kept only when non-empty strings`() {
        val item = item(
            """{"type":"playlist","id":"pl1","name":"Heavy Classics","url":"https://music.amazon.com/playlists/pl1",
               "imageUrl":"","reason":"  ","description":"x"}""",
        )!!
        assertEquals("https://music.amazon.com/playlists/pl1", item.url)
        assertNull(item.imageUrl)
        assertNull(item.reason)
    }

    @Test
    fun `an artist field on a non-song or album item is dropped`() {
        val item = item("""{"type":"artist","id":"a1","name":"Björk","artist":"Björk"}""")!!
        assertNull(item.artist)
    }

    // ── normalizeRecommendationItem releaseDate ──────────────────────

    @Test
    fun `an album keeps a provider release date — date part only — anything else drops it`() {
        fun album(releaseDate: String) =
            item("""{"type":"album","name":"A","artist":"B","releaseDate":$releaseDate}""", "applemusic")!!.releaseDate
        assertEquals("2026-12-05", album("\"2026-12-05\""))
        assertEquals("2026-12-05", album("\"2026-12-05T00:00:00Z\""))
        assertEquals("2027", album("\"2027\""))
        assertNull(album("\"soon\""))
        assertNull(album("20261205"))
        assertNull(item("""{"type":"playlist","name":"P","releaseDate":"2026-12-05"}""", "x")!!.releaseDate)
    }

    // ── normalizeRecommendationSections ──────────────────────────────

    @Test
    fun `normalizes sections — drops empty ones and untitled sections keep no title`() {
        val result = sections(
            """{"sections":[
                {"title":"Albums for You","id":"uri:///recommendations/browse-home/albumsStrategy","description":"For you","items":[
                  {"type":"album","id":"B0G2TSTWXD","name":"Overspace & Supertime","artist":"Cryptic Shift","imageUrl":"https://m.media-amazon.com/images/I/81swThQtLTL.jpg"}
                ]},
                {"items":[]},
                {"items":[{"type":"song","name":"Doberman","artist":"Deafheaven"}]}
            ]}""",
            "amazonmusic", "Amazon Music",
        )
        assertEquals(2, result.size)
        assertEquals("Albums for You", result[0].title)
        assertEquals("uri:///recommendations/browse-home/albumsStrategy", result[0].id)
        assertEquals("For you", result[0].summary)
        assertEquals("amazonmusic", result[0].items[0].resolverId)
        assertNull(result[1].title)
    }

    @Test
    fun `a bare array is tolerated and a non-array answer is empty`() {
        assertEquals(1, sections("""[{"items":[{"type":"artist","name":"Björk"}]}]""").size)
        assertEquals(emptyList(), normalizeRecommendationSections(null))
        assertEquals(emptyList(), sections("""{"sections":"nope"}"""))
    }

    @Test
    fun `a section's cursor is passed through as text — an object is no cursor`() {
        fun cursor(value: String) = sections("""[{"nextCursor":$value,"items":[{"type":"artist","name":"A"}]}]""")[0].nextCursor
        assertEquals("abc", cursor("\"abc\""))
        assertEquals("5", cursor("5"))
        assertNull(cursor("null"))
        assertNull(cursor("""{"a":1}"""))
    }

    // ── deriveArtistsFromSongs ───────────────────────────────────────

    @Test
    fun `unique artist names in first-appearance order — case-insensitive`() {
        val artists = deriveArtistsFromSongs(
            listOf(
                rec(RecommendationType.SONG, artist = "Deafheaven"),
                rec(RecommendationType.SONG, artist = "deafheaven"),
                rec(RecommendationType.SONG, artist = "Anathema"),
                rec(RecommendationType.ALBUM, artist = "Ignored"),
                rec(RecommendationType.SONG, artist = ""),
            ),
        )
        assertEquals(listOf("Deafheaven", "Anathema"), artists.map { it.name })
        assertEquals(RecommendationType.ARTIST, artists[0].type)
        assertTrue(artists[0].derived)
        assertEquals(slug("Deafheaven"), artists[0].id)
    }

    // ── blockedItemPick ──────────────────────────────────────────────

    @Test
    fun `an artist item is its artist — songs and albums are artist+title — playlists are not blocklisted`() {
        // Mobile's stored entries: "artist:<name>", "album:<artist>:<title>", "track:<artist>:<title>".
        assertTrue(blockedItemPick(rec(RecommendationType.ARTIST, name = "Burzum"), setOf("artist:burzum")))
        assertTrue(blockedItemPick(rec(RecommendationType.SONG, name = "Filosofem", artist = "Burzum"), setOf("artist:Burzum")))
        assertTrue(blockedItemPick(rec(RecommendationType.SONG, name = "Filosofem", artist = "Burzum"), setOf("track:Burzum:Filosofem")))
        assertTrue(blockedItemPick(rec(RecommendationType.ALBUM, name = "Filosofem", artist = "Burzum"), setOf("album:Burzum:Filosofem")))
        assertFalse(blockedItemPick(rec(RecommendationType.PLAYLIST, name = "x"), setOf("artist:x", "album:x:x", "track:x:x")))
    }

    @Test
    fun `the blocklist matches case-insensitively on trimmed names and never fuzzily`() {
        val blocked = setOf("artist:Nickelback", "album:Some Band:Greatest Hits", "track:Oasis:Wonderwall")
        fun song(artist: String, title: String) = blockedItemPick(rec(RecommendationType.SONG, name = title, artist = artist), blocked)
        fun album(artist: String, title: String) = blockedItemPick(rec(RecommendationType.ALBUM, name = title, artist = artist), blocked)
        fun artist(name: String) = blockedItemPick(rec(RecommendationType.ARTIST, name = name), blocked)
        assertTrue(artist(" NICKELBACK "))
        assertTrue(song("nickelback", "Photograph"))
        assertTrue(album("Some Band", "Greatest Hits"))
        // With no album given, a title is matched against blocked albums and tracks alike.
        assertTrue(song("Some Band", "greatest hits"))
        assertFalse(artist("Some Band"))
        assertTrue(song("Oasis", "Wonderwall"))
        assertFalse(song("Oasis", "Live Forever"))
        assertFalse(artist("Oasis"))
        assertFalse(artist("Nickelback Tribute Band"))
        assertFalse(album("Another Band", "Greatest Hits"))
        assertFalse(song("Ryan Adams", "Wonderwall"))
        assertFalse(blockedItemPick(rec(RecommendationType.ARTIST, name = "Nickelback"), emptySet()))
    }

    @Test
    fun `a colon inside an artist or title still matches its entry`() {
        assertTrue(blockedItemPick(rec(RecommendationType.SONG, name = "Part 1: Intro", artist = "Band: Live"), setOf("track:Band: Live:Part 1: Intro")))
        assertFalse(blockedItemPick(rec(RecommendationType.SONG, name = "Intro", artist = "Band: Live"), setOf("track:Band: Live:Part 1: Intro")))
    }

    // ── dedupeItems / recommendationItemKey ──────────────────────────

    @Test
    fun `same artist+title is one song — playlists dedupe by url`() {
        val out = dedupeItems(
            listOf(
                rec(RecommendationType.SONG, artist = "Deafheaven", name = "Sunbather"),
                rec(RecommendationType.SONG, artist = "deafheaven", name = "sunbather"),
                rec(RecommendationType.PLAYLIST, name = "A", url = "https://music.amazon.com/playlists/p1"),
                rec(RecommendationType.PLAYLIST, name = "B", url = "https://music.amazon.com/playlists/p1"),
            ),
        )
        assertEquals(2, out.size)
    }

    @Test
    fun `a weekly playlist with no url is keyed by its own id`() {
        val jams = rec(RecommendationType.PLAYLIST, name = "This Week", weekly = aWeek)
        val exploration = rec(RecommendationType.PLAYLIST, name = "This Week", weekly = aWeek.copy(id = "w2", contextType = "weekly-exploration"))
        assertEquals("weekly:w", recommendationItemKey(jams))
        assertEquals(2, dedupeItems(listOf(jams, exploration)).size)
        assertEquals("name:this week", recommendationItemKey(rec(RecommendationType.PLAYLIST, name = " This Week ")))
    }

    // ── normalizeRecommendationItem weekly field ─────────────────────

    @Test
    fun `keeps a valid weekly field and drops a malformed one`() {
        val weekly = """{"id":"pl1","contextType":"weekly-jam","label":"Jams","weekLabel":"Week of Nov 3","date":"2026-11-03"}"""
        val ok = item("""{"type":"playlist","name":"P","weekly":$weekly}""", "listenbrainz")!!
        assertEquals(WeeklyInfo("pl1", "weekly-jam", "Jams", "Week of Nov 3", "2026-11-03"), ok.weekly)
        val noDate = item("""{"type":"playlist","name":"P","weekly":{"id":"a","contextType":"weekly-exploration","label":"L","weekLabel":"W"}}""", "x")!!
        assertEquals(WeeklyInfo("a", "weekly-exploration", "L", "W", null), noDate.weekly)
        val bad = listOf(
            """{"id":"pl1","contextType":"daily","label":"Jams","weekLabel":"Week of Nov 3"}""",
            """{"id":"","contextType":"weekly-jam","label":"Jams","weekLabel":"Week of Nov 3"}""",
            """{"id":"pl1","contextType":"weekly-jam","label":5,"weekLabel":"Week of Nov 3"}""",
            """{"id":"pl1","contextType":"weekly-jam","label":"Jams"}""",
            "\"nope\"",
        )
        for (b in bad) {
            assertNull(item("""{"type":"playlist","name":"P","weekly":$b}""", "x")!!.weekly, b)
        }
    }

    // ── playlist categories: contract and helpers ────────────────────

    @Test
    fun `playlistCategory — weekly then item then section then other`() {
        val p = RecommendationType.PLAYLIST
        assertEquals("personal", RecommendationCategories.categoryFor(p, rec(p, weekly = aWeek, category = "editorial"), "community"))
        assertEquals("editorial", RecommendationCategories.categoryFor(p, rec(p, category = "editorial"), "community"))
        assertEquals("community", RecommendationCategories.categoryFor(p, rec(p), "community"))
        assertEquals("other", RecommendationCategories.categoryFor(p, rec(p, category = "bogus"), "nope"))
        assertEquals("other", RecommendationCategories.categoryFor(p, rec(p), null))
    }

    @Test
    fun `artistRowTitle — one two three four or more — blanks and case-repeats dropped`() {
        val t = RecommendationCategories::artistRowTitle
        assertEquals("Because you like Radiohead", t(listOf("Radiohead")))
        assertEquals("Because you like Radiohead and Arctic Monkeys", t(listOf("Radiohead", "Arctic Monkeys")))
        assertEquals("Because you like Radiohead, Arctic Monkeys and The Strokes", t(listOf("Radiohead", "Arctic Monkeys", "The Strokes")))
        assertEquals("Because you like A, B, C and more", t(listOf("A", "B", "C", "D")))
        assertEquals("Because you like Radiohead and Blur", t(listOf("Radiohead", " radiohead ", "", "  ", "Blur")))
        assertEquals("Because of artists you like", t(emptyList()))
    }

    @Test
    fun `normalizers keep valid category and seedArtist`() {
        val out = sections(
            """{"sections":[
              {"id":"s1","title":"Fans Like","category":"artist","seedArtist":" Radiohead ","items":[
                {"type":"playlist","id":"p1","name":"P1","category":"editorial"},
                {"type":"album","id":"a1","name":"A1","artist":"X","category":"editorial"}
              ]},
              {"id":"s2","title":"Odd","category":"bogus","seedArtist":"Ignored","items":[{"type":"playlist","id":"p2","name":"P2","category":"nope"}]},
              {"id":"s3","title":"Mixes","category":"personal","seedArtist":"Ignored","items":[{"type":"playlist","id":"p3","name":"P3"}]}
            ]}""",
            "x",
        )
        val (s1, s2, s3) = out
        assertEquals("artist", s1.category)
        assertEquals("Radiohead", s1.seedArtist)
        assertEquals("editorial", s1.items[0].category)
        // An album keeps a category valid for albums; `editorial` is one.
        assertEquals("editorial", s1.items[1].category)
        assertNull(s2.category)
        assertNull(s2.seedArtist)
        assertNull(s2.items[0].category)
        assertNull(s3.seedArtist)
        assertEquals(listOf("personal", "artist", "editorial", "community", "other"), RecommendationCategories.PLAYLIST.map { it.id })
    }

    // ── album categories: tables and helpers ─────────────────────────

    @Test
    fun `categoryFor — album values — playlist values — type-specific validity`() {
        val c = RecommendationCategories
        val a = RecommendationType.ALBUM
        val p = RecommendationType.PLAYLIST
        assertEquals("new", c.categoryFor(a, rec(a, category = "new"), "personal"))
        assertEquals("personal", c.categoryFor(a, rec(a), "personal"))
        assertEquals("other", c.categoryFor(a, rec(a, category = "community"), null))
        assertEquals("other", c.categoryFor(p, rec(p, category = "new"), null))
        assertEquals("personal", c.categoryFor(p, rec(p, weekly = aWeek), "editorial"))
        assertEquals("editorial", c.categoryFor(a, rec(a, weekly = aWeek), "editorial"))
        assertNull(c.categoryFor(RecommendationType.SONG, rec(RecommendationType.SONG, category = "personal"), "personal"))
        assertEquals("community", c.categoryFor(p, rec(p, category = "community"), null))
        assertEquals(listOf("new", "personal", "artist", "editorial", "other"), c.ALBUM.map { it.id })
    }

    @Test
    fun `normalizers keep album categories valid for albums only`() {
        val out = sections(
            """{"sections":[{"id":"n","title":"New","category":"new","items":[
                {"type":"album","id":"a1","name":"A1","artist":"X"},
                {"type":"album","id":"a2","name":"A2","artist":"X","category":"community"},
                {"type":"playlist","id":"p1","name":"P1","category":"new"}
            ]}]}""",
            "x",
        )
        val sec = out.single()
        assertEquals("new", sec.category)
        assertNull(sec.items[1].category)
        assertNull(sec.items[2].category)
    }

    // ── one item per provider id ─────────────────────────────────────

    /**
     * Desktop runs these through `buildRecommendationPage`; here the same
     * inputs go through what it does for one type: each provider's sections
     * flattened, each item stamped with its category, then [mergeOwned] and
     * [interleave].
     */
    private fun merged(vararg providers: Pair<String, String>): List<RecommendationItem> {
        val lists = providers.map { (resolverId, text) ->
            sections("""{"sections":$text}""", resolverId, resolverId.uppercase()).flatMap { section ->
                section.items.map { item ->
                    item.copy(category = RecommendationCategories.categoryFor(item.type, item, section.category))
                }
            }
        }
        return interleave(mergeOwned(lists))
    }

    @Test
    fun `one provider — one id under two artist strings — one item and the earliest category`() {
        val all = merged(
            "amazonmusic" to """[
              {"id":"ed","title":"Editorial","category":"editorial","items":[{"type":"album","id":"B0ASIN","name":"Site Unseen","artist":"Waxahatchee"}]},
              {"id":"new","title":"New","category":"new","items":[{"type":"album","id":"B0ASIN","name":"Site Unseen","artist":"Waxahatchee, MJ Lenderman"}]}
            ]""",
        )
        assertEquals(listOf("B0ASIN"), all.map { it.id })
        assertEquals("Waxahatchee", all[0].artist)
        assertEquals("new", all[0].category)
        assertEquals(listOf("amazonmusic"), all[0].sourceIds)
    }

    @Test
    fun `two providers sending one id merge their sources`() {
        val all = merged(
            "a" to """[{"items":[{"type":"album","id":"mbid-1","name":"Sunbather","artist":"Deafheaven"}]}]""",
            "b" to """[{"items":[{"type":"album","id":"mbid-1","name":"Sunbather (Remastered)","artist":"Deafheaven"}]}]""",
        )
        assertEquals(1, all.size)
        assertEquals("Sunbather", all[0].name)
        assertEquals("a", all[0].resolverId)
        assertEquals(listOf("a", "b"), all[0].sourceIds)
    }

    @Test
    fun `a later copy matching either the id or the name of a merged copy joins it`() {
        val all = merged(
            "a" to """[{"items":[{"type":"album","id":"id-1","name":"Ride","artist":"Arkells"}]}]""",
            "b" to """[{"items":[{"type":"album","id":"id-1","name":"Ride","artist":"Arkells, Someone"}]}]""",
            "c" to """[{"items":[{"type":"album","id":"id-other","name":"Ride","artist":"Arkells, Someone"}]}]""",
        )
        assertEquals(1, all.size)
        assertEquals(listOf("a", "b", "c"), all[0].sourceIds)
    }

    @Test
    fun `different ids and names stay separate — and a song never merges into an album`() {
        val all = merged(
            "a" to """[{"title":"Mixed","items":[
              {"type":"album","id":"id-1","name":"Ride","artist":"Arkells"},
              {"type":"album","id":"id-2","name":"Ride","artist":"Someone Else"},
              {"type":"song","id":"id-1","name":"Ride","artist":"Arkells"}
            ]}]""",
        )
        assertEquals(listOf("id-1", "id-2"), all.filter { it.type == RecommendationType.ALBUM }.map { it.id })
        assertEquals(listOf("id-1"), all.filter { it.type == RecommendationType.SONG }.map { it.id })
    }

    // ── mergeOwned / providerIdKey / interleave details ──────────────

    @Test
    fun `a name-made fallback id is not a provider id`() {
        val song = item("""{"type":"song","name":"Sunbather","artist":"Deafheaven"}""")!!
        assertNull(providerIdKey(song))
        val playlist = item("""{"type":"playlist","name":"Mix","url":"https://x/1"}""")!!
        assertNull(providerIdKey(playlist))
        assertEquals("album:B0", providerIdKey(item("""{"type":"album","id":"B0","name":"A","artist":"B"}""")!!))
    }

    @Test
    fun `a later Shuffleupagus copy gives the kept item its reason`() {
        val kept = rec(RecommendationType.ALBUM, name = "Sunbather", artist = "Deafheaven", resolverId = "lastfm")
        val ai = kept.copy(resolverId = "shuffleupagus", reason = "Doom-gaze peak")
        val other = kept.copy(resolverId = "listenbrainz", reason = "Not Shuffleupagus")
        val out = mergeOwned(listOf(listOf(kept), listOf(other), listOf(ai)))
        assertEquals(listOf(1, 0, 0), out.map { it.size })
        assertEquals("Doom-gaze peak", out[0][0].reason)
        assertEquals(listOf("lastfm", "listenbrainz", "shuffleupagus"), out[0][0].sourceIds)
    }

    @Test
    fun `interleave takes one item per list per round in list order`() {
        assertEquals(listOf(1, 10, 100, 2, 20, 3), interleave(listOf(listOf(1, 2, 3), listOf(10, 20), listOf(100))))
        assertEquals(emptyList(), interleave(emptyList<List<Int>>()))
    }

    @Test
    fun `a song's duration is a number or a numeric string`() {
        assertEquals(394.0, item("""{"type":"song","name":"a","artist":"b","duration":"394"}""")!!.duration)
        assertNull(item("""{"type":"song","name":"a","artist":"b","duration":0}""")!!.duration)
        assertNull(item("""{"type":"song","name":"a","artist":"b","duration":"soon"}""")!!.duration)
        assertNull(item("""{"type":"album","name":"a","artist":"b","duration":394}""")!!.duration)
        assertNotNull(item("""{"type":"song","name":"a","artist":"b","duration":1.5}""")!!.duration)
    }
}
