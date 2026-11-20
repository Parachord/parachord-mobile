package com.parachord.shared.recommendations

import com.parachord.shared.ai.AiAlbumSuggestion
import com.parachord.shared.ai.AiArtistSuggestion
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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

    // ── one item per provider id: see the page tests below ──────────

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

    // ══ The page ═════════════════════════════════════════════════════

    private fun norm(id: String, name: String, sectionsJson: String, canExpand: Boolean = false) =
        ProviderRecommendations(id, name, sections("""{"sections":$sectionsJson}""", id, name), canExpand)
    private fun song(name: String, artist: String = "Art") = """{"type":"song","name":"$name","artist":"$artist"}"""
    private fun al(name: String, artist: String = "X", extra: String = "") = """{"type":"album","name":"$name","artist":"$artist"$extra}"""
    private fun pl(id: String, extra: String = "") = """{"type":"playlist","id":"$id","name":"${id.uppercase()}","url":"https://x/$id"$extra}"""
    private fun List<RecommendationPageSection>.row(key: String) = flatMap { it.rows }.first { it.key == key }
    private fun List<RecommendationPageSection>.rowsOf(type: RecommendationType?) = firstOrNull { it.type == type }?.rows.orEmpty()
    private fun List<RecommendationPageSection>.itemsOf(type: RecommendationType) = filter { it.type == type }.flatMap { it.rows }.flatMap { it.items }
    private fun RecommendationRow.names() = items.map { it.name }
    private fun RecommendationRow.ids() = items.map { it.id }
    private fun RecommendationRow.sourceIds() = sources.map { it.id }
    private fun expansion(itemsJson: String, nextCursor: String? = null, exhausted: Boolean = false) =
        ExpandedSection((json(itemsJson) as JsonArray).mapNotNull { normalizeRecommendationItem(it, null) }, nextCursor, exhausted)
    private fun ai(title: String, artist: String, reason: String = "", art: String? = null) = AiAlbumSuggestion(title, artist, reason, art)
    private val friendServices = setOf("lastfm", "listenbrainz")

    // ── buildRecommendationPage ──────────────────────────────────────

    @Test
    fun `TYPE_SECTIONS is the five-type order`() {
        assertEquals(
            listOf(
                RecommendationType.SONG to "Tracks", RecommendationType.ALBUM to "Albums",
                RecommendationType.ARTIST to "Artists", RecommendationType.PLAYLIST to "Playlists", null to "Listeners",
            ),
            RECOMMENDATION_PAGE_SECTIONS,
        )
    }

    @Test
    fun `sections come in the five-type order and empty ones are left out`() {
        // A song makes a derived artist, so Artists appears without being sent.
        val page = buildRecommendationPage(listOf(norm("lastfm", "Last.fm", """[{"items":[${al("Alb")},${song("s1")}]}]""")))
        assertEquals(listOf(RecommendationType.SONG, RecommendationType.ALBUM, RecommendationType.ARTIST), page.map { it.type })
        val only = buildRecommendationPage(listOf(norm("lastfm", "Last.fm", """[{"items":[${al("Alb")},{"type":"song","name":"s1"}]}]""")))
        assertEquals(listOf(RecommendationType.SONG, RecommendationType.ALBUM), only.map { it.type })
        assertEquals(emptyList(), buildRecommendationPage())
    }

    @Test
    fun `For you interleaves providers round-robin with Shuffleupagus last`() {
        val providers = listOf(
            norm("lastfm", "Last.fm", """[{"items":[${song("a")},${song("b")},${song("c")}]}]"""),
            // `personal`, like Shuffleupagus's albums, so every album shares one category.
            norm("listenbrainz", "ListenBrainz", """[{"category":"personal","items":[${song("d")},${song("e")},${al("x", "A")},${al("y", "A")}]}]"""),
        )
        val shuffle = shuffleupagusSection(listOf(ai("s1", "A", "r1"), ai("s2", "A", "r2")), emptyList())
        val page = buildRecommendationPage(providers, shuffle)
        assertEquals(listOf("a", "d", "b", "e", "c"), page.row("type:song").names().filter { it.length == 1 })
        val albums = page.row("type:album")
        assertEquals(listOf("x", "s1", "y", "s2"), albums.names())
        assertEquals(listOf("listenbrainz", "shuffleupagus"), albums.sourceIds())
        assertEquals("Albums", albums.title)
        assertTrue(albums.blended)
        assertFalse(albums.canLoadMore)
        assertEquals(emptyList(), albums.pageable)
    }

    @Test
    fun `a duplicate keeps the first provider and records every source`() {
        val providers = listOf(
            norm("lastfm", "Last.fm", """[{"items":[{"type":"song","id":"l1","name":"Doberman","artist":"Deafheaven"}]}]"""),
            norm("listenbrainz", "ListenBrainz", """[{"items":[{"type":"song","id":"b1","name":"doberman","artist":"deafheaven"}]}]"""),
        )
        val row = buildRecommendationPage(providers).row("type:song")
        assertEquals(1, row.items.size)
        assertEquals("l1", row.items[0].id)
        assertEquals("lastfm", row.items[0].resolverId)
        assertEquals(listOf("lastfm", "listenbrainz"), row.items[0].sourceIds)
        assertEquals(listOf("lastfm", "listenbrainz"), row.sourceIds())
    }

    @Test
    fun `a Shuffleupagus duplicate keeps its reason on the merged item`() {
        val providers = listOf(norm("lastfm", "Last.fm", """[{"items":[${al("Sunbather", "Deafheaven")}]}]"""))
        val shuffle = shuffleupagusSection(listOf(ai("Sunbather", "Deafheaven", "peak")), emptyList())
        val row = buildRecommendationPage(providers, shuffle).row("type:album")
        assertEquals(1, row.items.size)
        assertEquals("lastfm", row.items[0].resolverId)
        assertEquals("peak", row.items[0].reason)
        assertEquals(listOf("lastfm", "shuffleupagus"), row.items[0].sourceIds)
    }

    @Test
    fun `derived artists join Artists For you with the song provider as source`() {
        val row = buildRecommendationPage(listOf(norm("lastfm", "Last.fm", """[{"items":[${song("a", "Deafheaven")}]}]"""))).row("type:artist")
        assertEquals(1, row.items.size)
        assertEquals("Deafheaven", row.items[0].name)
        assertTrue(row.items[0].derived)
        assertEquals(listOf("lastfm"), row.items[0].sourceIds)
        assertEquals(listOf(PageSource("lastfm", "Last.fm", 1)), row.sources)
        assertFalse(row.blended)
    }

    @Test
    fun `every type section is one row — titled sections blend in with untitled items`() {
        val providers = listOf(
            norm("lastfm", "Last.fm", """[{"items":[${al("L1", "A")},${al("L2", "A")}]}]"""),
            norm("amazonmusic", "Amazon Music", """[
              {"id":"s1","title":"Albums for You","items":[${al("M1", "B")},{"type":"playlist","name":"P","url":"https://x/p"}]},
              {"id":"s2","title":"Based on Your Recents","items":[${al("M2", "B")},${al("M3", "B")}]}
            ]"""),
        )
        val page = buildRecommendationPage(providers)
        for (section in page) assertEquals(1, section.rows.size)
        val albums = page.rowsOf(RecommendationType.ALBUM)[0]
        assertEquals("type:album", albums.key)
        assertEquals(listOf("L1", "M1", "L2", "M2", "M3"), albums.names())
        assertEquals(listOf("lastfm", "amazonmusic"), albums.sourceIds())
        assertEquals(listOf("P"), page.rowsOf(RecommendationType.PLAYLIST)[0].names())
    }

    @Test
    fun `a section keeps an album and its same-named title track`() {
        val page = buildRecommendationPage(listOf(norm("amazonmusic", "Amazon Music",
            """[{"id":"s1","title":"Hot","items":[${al("Blue")},${song("Blue", "X")}]}]""")))
        assertEquals(listOf("Blue"), page.row("type:album").names())
        assertEquals(listOf("Blue"), page.row("type:song").names())
    }

    @Test
    fun `a row lists the provider sections that can page its type — sharing one pagingKey across types`() {
        val sectionsJson = """[
          {"id":"s1","title":"Hot","nextCursor":"c0","items":[{"type":"playlist","name":"P","url":"https://x/p"},${al("Alb")}]},
          {"title":"No id","items":[${al("Alb2")}]}
        ]"""
        val providers = listOf(norm("amazonmusic", "Amazon Music", sectionsJson, canExpand = true))
        val page = buildRecommendationPage(providers)
        val entry = PageableSection("amazonmusic", "Amazon Music", "s1", "Hot", null, "amazonmusic|s1", "c0")
        assertEquals(listOf(entry), page.row("type:playlist").pageable)
        assertEquals(listOf(entry), page.row("type:album").pageable)
        assertTrue(page.row("type:playlist").canLoadMore)
        // A resolver that can't page, or an exhausted section, lists nothing.
        assertFalse(buildRecommendationPage(listOf(norm("amazonmusic", "Amazon Music", sectionsJson))).row("type:album").canLoadMore)
        val done = buildRecommendationPage(providers, expanded = mapOf("amazonmusic|s1" to expansion("[]", exhausted = true)))
        assertEquals(emptyList(), done.row("type:album").pageable)
    }

    @Test
    fun `paged items append after the blended first pages — in their type — and carry the cursor`() {
        val providers = listOf(
            norm("lastfm", "Last.fm", """[{"items":[${al("L1", "A")},${al("L2", "A")}]}]"""),
            norm("amazonmusic", "Amazon Music", """[{"id":"s1","title":"Hot","nextCursor":"c0","items":[
              {"type":"playlist","name":"P","url":"https://x/p"},${al("M1")}]}]""", canExpand = true),
        )
        val expanded = mapOf("amazonmusic|s1" to expansion(
            """[${al("M2")},${al("M1")},{"type":"playlist","name":"P2","url":"https://x/p2"}]""", nextCursor = "c1"))
        val page = buildRecommendationPage(providers, expanded = expanded)
        assertEquals(listOf("L1", "M1", "L2", "M2"), page.row("type:album").names())
        assertEquals(listOf("P", "P2"), page.row("type:playlist").names())
        assertEquals("c1", page.row("type:album").pageable[0].nextCursor)
        // A paged item takes its section's provider.
        assertEquals("amazonmusic", page.row("type:album").items.last().resolverId)
    }

    @Test
    fun `weekly playlists with the same name stay separate — keyed by their own id`() {
        fun w(id: String, contextType: String) = """{"id":"$id","contextType":"$contextType","label":"$id","weekLabel":"This Week"}"""
        val providers = listOf(norm("listenbrainz", "ListenBrainz", """[
          {"id":"weekly-exploration","title":"Weekly Exploration","items":[{"type":"playlist","name":"This Week","weekly":${w("e1", "weekly-exploration")}}]},
          {"id":"weekly-jams","title":"Weekly Jams","items":[{"type":"playlist","name":"This Week","weekly":${w("j1", "weekly-jam")}}]}
        ]"""))
        assertEquals(listOf("e1", "j1"), buildRecommendationPage(providers).row("type:playlist").items.map { it.weekly?.id })
    }

    @Test
    fun `only the newest week of each weekly playlist is kept — by date else the provider's first`() {
        fun w(id: String, contextType: String, date: String) = """{"id":"$id","contextType":"$contextType","label":"$id","weekLabel":"$id","date":"$date"}"""
        val providers = listOf(norm("listenbrainz", "ListenBrainz", """[
          {"id":"weekly-exploration","title":"Weekly Exploration","items":[
            {"type":"playlist","name":"Last","weekly":${w("e-last", "weekly-exploration", "2026-11-02")}},
            {"type":"playlist","name":"This","weekly":${w("e-this", "weekly-exploration", "2026-11-09")}}
          ]},
          {"id":"weekly-jams","title":"Weekly Jams","items":[
            {"type":"playlist","name":"J this","weekly":{"id":"j-this","contextType":"weekly-jam","label":"x","weekLabel":"This Week"}},
            {"type":"playlist","name":"J last","weekly":{"id":"j-last","contextType":"weekly-jam","label":"x","weekLabel":"Last Week"}}
          ]},
          {"items":[{"type":"playlist","name":"Mix","url":"https://x/mix"}]}
        ]"""))
        // Weekly playlists are Made for you and the uncategorized Mix is More playlists.
        val items = buildRecommendationPage(providers).rowsOf(RecommendationType.PLAYLIST).flatMap { it.items }
        assertEquals(listOf("e-this", "j-this", "Mix"), items.map { it.weekly?.id ?: it.name })
    }

    @Test
    fun `weekly playlists lead Playlists — Exploration before Jams — the rest blended`() {
        fun w(id: String, contextType: String) = """{"id":"lb-$id","contextType":"$contextType","label":"$id","weekLabel":"Week of x"}"""
        val providers = listOf(
            norm("amazonmusic", "Amazon Music", """[{"id":"am","title":"Amazon Picks","items":[{"type":"playlist","name":"AP","url":"https://x/ap"}]}]"""),
            norm("listenbrainz", "ListenBrainz", """[
              {"id":"weekly-jams","title":"Weekly Jams","items":[{"type":"playlist","name":"J1","weekly":${w("j1", "weekly-jam")}}]},
              {"id":"weekly-exploration","title":"Weekly Exploration","items":[{"type":"playlist","name":"E1","weekly":${w("e1", "weekly-exploration")}}]},
              {"items":[{"type":"playlist","name":"Mix","url":"https://x/mix"}]}
            ]"""),
        )
        val rows = buildRecommendationPage(providers).rowsOf(RecommendationType.PLAYLIST)
        assertEquals(listOf("playlists:personal", "playlists:other"), rows.map { it.key })
        assertEquals(listOf("E1", "J1", "AP", "Mix"), rows.flatMap { it.names() })
    }

    // Review focus: the two current weeks share a name and carry no URL.
    @Test
    fun `This Week's Exploration and Jams stay two cards`() {
        val providers = listOf(norm("listenbrainz", "ListenBrainz", """[{"items":[
          {"type":"playlist","name":"This Week","weekly":{"id":"b","contextType":"weekly-jam","label":"Weekly Jams","weekLabel":"This Week"}},
          {"type":"playlist","name":"This Week","weekly":{"id":"a","contextType":"weekly-exploration","label":"Weekly Exploration","weekLabel":"This Week"}}
        ]}]"""))
        val items = buildRecommendationPage(providers).rowsOf(RecommendationType.PLAYLIST).flatMap { it.items }
        assertEquals(listOf("a", "b"), items.map { it.weekly?.id })
    }

    // Review focus: dropping this week's Jams first would let last week's in.
    @Test
    fun `a history mix id is dropped after the newest-week pick`() {
        val providers = listOf(norm("listenbrainz", "ListenBrainz", """[{"items":[
          {"type":"playlist","id":"b","name":"This Week","weekly":{"id":"b","contextType":"weekly-jam","label":"Weekly Jams","weekLabel":"This Week","date":"2026-11-16"}},
          {"type":"playlist","id":"b-old","name":"Last Week","weekly":{"id":"b-old","contextType":"weekly-jam","label":"Weekly Jams","weekLabel":"Last Week","date":"2026-11-09"}},
          {"type":"playlist","id":"a","name":"This Week","weekly":{"id":"a","contextType":"weekly-exploration","label":"Weekly Exploration","weekLabel":"This Week","date":"2026-11-16"}}
        ]}]"""))
        val items = buildRecommendationPage(providers, excludePlaylistIds = setOf("b")).rowsOf(RecommendationType.PLAYLIST).flatMap { it.items }
        assertEquals(listOf("a"), items.map { it.id })
    }

    @Test
    fun `a blocked item is left out of the providers' sections — the paged items and Shuffleupagus`() {
        val providers = listOf(norm("amazonmusic", "Amazon Music", """[{"id":"s1","title":"Hot","items":[
          ${al("Keep", "A")},${al("Gone", "Blocked")},${song("Wonderwall", "Oasis")}]}]""", canExpand = true))
        val expanded = mapOf("amazonmusic|s1" to expansion("""[${al("Paged", "Blocked")},${al("Paged2", "A")}]"""))
        val shuffle = shuffleupagusSection(listOf(ai("Ai", "Blocked"), ai("Ai2", "A")), listOf(AiArtistSuggestion("Blocked", "r")))
        val page = buildRecommendationPage(providers, shuffle, expanded, blocked = setOf("artist:Blocked", "track:Oasis:Wonderwall"))
        // Shuffleupagus's albums are `personal`, so Albums splits into two rows.
        assertEquals(listOf("Ai2"), page.row("albums:personal").names())
        assertEquals(listOf("Keep", "Paged2"), page.row("albums:other").names())
        assertTrue(page.rowsOf(RecommendationType.SONG).isEmpty())
        assertTrue(page.rowsOf(RecommendationType.ARTIST).isEmpty())
    }

    // ── Listeners ────────────────────────────────────────────────────

    private val similarUsers = linkedMapOf(
        "lastfm" to listOf(SimilarUser("Bob", 0.9), SimilarUser("Cy", 0.8, "https://a/cy.png"), SimilarUser("Di", 0.7)),
        "listenbrainz" to listOf(SimilarUser("me", 0.5), SimilarUser("Ed", 0.4)),
    )
    private val serviceNames = mapOf("lastfm" to "Last.fm", "listenbrainz" to "ListenBrainz")

    @Test
    fun `drops followed — removed and self — each service most similar first — the services alternating`() {
        val exclude = FriendExclusions(friendServices, friendKeys = setOf("lastfm:bob"), selfKeys = setOf("listenbrainz:me"))
        val page = buildRecommendationPage(similarUsers = similarUsers, exclude = exclude, providerNames = serviceNames)
        val friends = page.single { it.type == null }
        assertEquals("Listeners", friends.title)
        assertEquals(1, friends.rows.size)
        val row = friends.rows[0]
        assertEquals(listOf("similar:lastfm:Cy", "similar:listenbrainz:Ed", "similar:lastfm:Di"), row.listeners.map { it.id })
        assertEquals(
            ListenerItem("similar:lastfm:Cy", "Cy", "lastfm", 0.8, "https://a/cy.png", emptyList(), "lastfm", listOf("lastfm")),
            row.listeners[0],
        )
        assertTrue(row.blended)
        assertEquals("Listeners", row.title)
        assertEquals(LISTENERS_ROW_KEY, row.key)
        assertEquals("friends:similar", row.key)
        assertEquals(listOf("Last.fm", "ListenBrainz"), row.sources.map { it.name })
        assertEquals(listOf("lastfm", "listenbrainz"), row.sourceIds())
    }

    @Test
    fun `each service sorts by its own score before they alternate — no score sorts last — topArtists carried`() {
        val su = linkedMapOf(
            "lastfm" to listOf(SimilarUser("A", 0.1), SimilarUser("B"), SimilarUser("C", 0.5, topArtists = listOf("X", "Y", "Z", "W"))),
            "listenbrainz" to listOf(SimilarUser("E", 0.5), SimilarUser("D", 0.9)),
        )
        val row = buildRecommendationPage(similarUsers = su, exclude = FriendExclusions(friendServices)).rowsOf(null)[0]
        assertEquals(listOf("C", "D", "A", "E", "B"), row.listeners.map { it.name })
        assertEquals(listOf("X", "Y", "Z"), row.listeners[0].topArtists)
        assertEquals(emptyList(), row.listeners[1].topArtists)
    }

    @Test
    fun `with one provider is a single-source row`() {
        val row = buildRecommendationPage(
            similarUsers = mapOf("lastfm" to similarUsers.getValue("lastfm")),
            exclude = FriendExclusions(friendServices), providerNames = serviceNames,
        ).rowsOf(null)[0]
        assertFalse(row.blended)
        assertEquals(listOf(PageSource("lastfm", "Last.fm", 3)), row.sources)
    }

    @Test
    fun `the same name on both services stays two entries`() {
        val su = linkedMapOf("lastfm" to listOf(SimilarUser("Zed", 1.0)), "listenbrainz" to listOf(SimilarUser("zed", 1.0)))
        assertEquals(2, buildRecommendationPage(similarUsers = su, exclude = FriendExclusions(friendServices)).rowsOf(null)[0].listeners.size)
    }

    @Test
    fun `only a friend service makes Friends entries`() {
        // Without the friend services, nobody is offered.
        assertEquals(emptyList(), buildRecommendationPage(similarUsers = similarUsers))
        val su = linkedMapOf("someservice" to listOf(SimilarUser("Zed", 1.0)), "lastfm" to similarUsers.getValue("lastfm"))
        val row = buildRecommendationPage(similarUsers = su, exclude = FriendExclusions(friendServices)).rowsOf(null)[0]
        assertEquals(listOf("lastfm", "lastfm", "lastfm"), row.listeners.map { it.service })
        assertEquals(listOf("lastfm"), row.sourceIds())
        assertEquals(emptyList(), buildRecommendationPage(similarUsers = mapOf("someservice" to listOf(SimilarUser("Zed", 1.0))), exclude = FriendExclusions(friendServices)))
    }

    @Test
    fun `everyone excluded leaves no Friends section`() {
        val exclude = FriendExclusions(friendServices, setOf("lastfm:bob", "lastfm:cy", "lastfm:di", "listenbrainz:ed"), setOf("listenbrainz:me"))
        assertEquals(emptyList(), buildRecommendationPage(similarUsers = similarUsers, exclude = exclude))
    }

    @Test
    fun `a Listeners row takes a provider's name when no name is given`() {
        val providers = listOf(norm("lastfm", "Last.fm", """[{"items":[${song("a")}]}]"""))
        val row = buildRecommendationPage(providers, similarUsers = mapOf("lastfm" to listOf(SimilarUser("Cy", 1.0))), exclude = FriendExclusions(friendServices)).rowsOf(null)[0]
        assertEquals("Last.fm", row.sources[0].name)
    }

    // ── filters and flatten ──────────────────────────────────────────

    private val filterPage by lazy {
        buildRecommendationPage(listOf(
            norm("lastfm", "Last.fm", """[{"items":[${song("a")},${song("both")}]}]"""),
            norm("listenbrainz", "ListenBrainz", """[{"items":[${song("d")},${song("both")}]}]"""),
        ))
    }

    @Test
    fun `filterRowBySource keeps the items one source sent — shared ones included`() {
        val row = filterPage.row("type:song")
        assertEquals(listOf("a", "d", "both"), row.names())
        assertEquals(listOf("a", "both"), filterRowBySource(row, "lastfm").names())
        assertEquals(listOf("d", "both"), filterRowBySource(row, "listenbrainz").names())
        assertEquals(emptyList(), filterRowBySource(row, "amazonmusic").items)
        assertSame(row, filterRowBySource(row, "all"))
        assertSame(row, filterRowBySource(row, null))
        assertEquals(3, row.items.size)
    }

    @Test
    fun `pageSources lists every source on the page in order — with its item count`() {
        // a, d, both (songs) plus the derived artist "Art", which both sent.
        assertEquals(listOf(PageSource("lastfm", "Last.fm", 3), PageSource("listenbrainz", "ListenBrainz", 3)), pageSources(filterPage))
        assertEquals(emptyList(), pageSources(null))
    }

    @Test
    fun `flattenRecommendationItems returns every item in page order`() {
        val flat = flattenRecommendationItems(filterPage)
        assertEquals(filterPage.flatMap { s -> s.rows.flatMap { it.items } }, flat)
        assertEquals("a", flat[0].name)
        assertEquals(emptyList(), flattenRecommendationItems(null))
    }

    // ── shuffleupagusSection ─────────────────────────────────────────

    @Test
    fun `albums and artists become items with reasons and image passthrough`() {
        val shuffle = shuffleupagusSection(
            listOf(ai("Sunbather", "Deafheaven", "Doom-gaze peak", "https://example.com/sunbather.jpg")),
            listOf(AiArtistSuggestion("Anathema", "因为他们", null)),
        )!!
        val album = shuffle.items[0]
        assertEquals(RecommendationType.ALBUM, album.type)
        assertEquals("Sunbather", album.name)
        assertEquals("Deafheaven", album.artist)
        assertEquals("Doom-gaze peak", album.reason)
        assertEquals("https://example.com/sunbather.jpg", album.imageUrl)
        assertEquals("shuffleupagus:album:${trackSlug("Deafheaven", "Sunbather")}", album.id)
        val artist = shuffle.items[1]
        assertEquals(RecommendationType.ARTIST, artist.type)
        assertEquals("Anathema", artist.name)
        assertEquals("因为他们", artist.reason)
        assertNull(artist.imageUrl)
        assertEquals("Shuffleupagus", shuffle.title)
        assertEquals("personal", shuffle.category)
    }

    @Test
    fun `entries without both artist and title or name are dropped — empty is null`() {
        assertNull(shuffleupagusSection(listOf(ai("No Artist", "")), listOf(AiArtistSuggestion("", ""))))
        assertNull(shuffleupagusSection(emptyList(), emptyList()))
    }

    // ── playlist category rows ───────────────────────────────────────

    @Test
    fun `rows follow category order and blend providers`() {
        val providers = listOf(
            norm("a", "A", """[{"id":"mine","title":"Mine","category":"personal","items":[${pl("p1")},${pl("p2")}]},
                              {"id":"comm","title":"Comm","category":"community","items":[${pl("c1")}]}]"""),
            norm("b", "B", """[{"id":"ed","title":"Ed","category":"editorial","items":[${pl("e1")}]},
                              {"id":"misc","title":"Misc","items":[${pl("o1")}]}]"""),
        )
        val page = buildRecommendationPage(providers)
        val section = page.single { it.type == RecommendationType.PLAYLIST }
        assertEquals("Playlists", section.title)
        assertEquals(listOf("playlists:personal", "playlists:editorial", "playlists:community", "playlists:other"), section.rows.map { it.key })
        assertEquals(listOf("Made for you", "From the editors", "From other listeners", "More playlists"), section.rows.map { it.title })
        assertEquals(listOf("personal", "editorial", "community", "other"), section.rows.map { it.category })
        assertEquals(listOf("p1", "p2"), page.row("playlists:personal").ids())
        assertEquals(listOf(PageSource("a", "A", 2)), page.row("playlists:personal").sources)
        assertFalse(page.row("playlists:personal").blended)
        assertTrue(section.rows.all { it.type == RecommendationType.PLAYLIST })
    }

    @Test
    fun `an item category overrides its section — and a mixed section feeds two rows with one pagingKey each`() {
        val providers = listOf(norm("amazonmusic", "Amazon Music", """[{"id":"g","title":"G","category":"editorial","items":[
          {"type":"playlist","id":"e1","name":"E1"},{"type":"playlist","id":"p1","name":"P1","category":"personal"}]}]""", canExpand = true))
        val page = buildRecommendationPage(providers)
        assertEquals(listOf("p1"), page.row("playlists:personal").ids())
        assertEquals(listOf("e1"), page.row("playlists:editorial").ids())
        for (key in listOf("playlists:personal", "playlists:editorial")) {
            val r = page.row(key)
            assertEquals(1, r.pageable.size)
            assertEquals("amazonmusic|g", r.pageable[0].pagingKey)
            assertEquals("editorial", r.pageable[0].category)
            assertTrue(r.canLoadMore)
        }
    }

    @Test
    fun `one category — today's single Playlists row`() {
        fun w(id: String, contextType: String) = """{"id":"$id","contextType":"$contextType","label":"$id","weekLabel":"This Week"}"""
        val providers = listOf(norm("listenbrainz", "ListenBrainz", """[
          {"id":"weekly-jams","title":"Weekly Jams","items":[{"type":"playlist","name":"Jams","weekly":${w("j1", "weekly-jam")}}]},
          {"id":"weekly-exploration","title":"Weekly Exploration","items":[{"type":"playlist","name":"Exploration","weekly":${w("e1", "weekly-exploration")}}]}
        ]""", canExpand = true))
        val rows = buildRecommendationPage(providers).rowsOf(RecommendationType.PLAYLIST)
        assertEquals(1, rows.size)
        assertEquals("type:playlist", rows[0].key)
        assertEquals("Playlists", rows[0].title)
        assertNull(rows[0].category)
        assertEquals(listOf("e1", "j1"), rows[0].items.map { it.weekly?.id })
        assertEquals(listOf("listenbrainz|weekly-jams", "listenbrainz|weekly-exploration"), rows[0].pageable.map { it.pagingKey })
        assertTrue(rows[0].pageable.all { it.category == null })
    }

    @Test
    fun `weekly items lead Made for you even without a category`() {
        val providers = listOf(
            norm("b", "B", """[{"id":"mine","title":"Mine","category":"personal","items":[${pl("p1")}]},
                              {"id":"ed","title":"Ed","category":"editorial","items":[${pl("e1")}]}]"""),
            norm("a", "A", """[{"id":"weekly-jams","title":"Weekly Jams","items":[{"type":"playlist","name":"Jams",
              "weekly":{"id":"j1","contextType":"weekly-jam","label":"Jams","weekLabel":"This Week"}}]}]"""),
        )
        val page = buildRecommendationPage(providers)
        val personal = page.row("playlists:personal")
        assertEquals("j1", personal.items[0].weekly?.id)
        assertEquals(listOf("Jams", "P1"), personal.names())
        assertEquals(listOf("e1"), page.row("playlists:editorial").ids())
    }

    @Test
    fun `an uncategorized older plugin beside a categorized one`() {
        val providers = listOf(
            norm("a", "A", """[{"id":"old","title":"Old","items":[${pl("o1")},${pl("o2")}]}]"""),
            norm("b", "B", """[{"id":"mine","title":"Mine","category":"personal","items":[${pl("p1")}]}]"""),
        )
        val page = buildRecommendationPage(providers)
        assertEquals(listOf("o1", "o2"), page.row("playlists:other").ids())
        assertEquals(listOf("p1"), page.row("playlists:personal").ids())
        val all = page.rowsOf(RecommendationType.PLAYLIST).flatMap { it.ids() }
        assertEquals(1, all.count { it == "o1" })
        assertEquals(1, all.count { it == "o2" })
    }

    @Test
    fun `two providers disagree — the earliest category wins`() {
        val providers = listOf(
            norm("a", "A", """[{"id":"ed","title":"Ed","category":"editorial","items":[${pl("x")},${pl("e2")}]}]"""),
            norm("b", "B", """[{"id":"mine","title":"Mine","category":"personal","items":[${pl("x")}]}]"""),
        )
        val page = buildRecommendationPage(providers)
        val personal = page.row("playlists:personal")
        assertEquals(listOf("x"), personal.ids())
        assertTrue(personal.items[0].sourceIds.containsAll(listOf("a", "b")))
        assertEquals(listOf("e2"), page.row("playlists:editorial").ids())
        assertEquals(1, page.rowsOf(RecommendationType.PLAYLIST).flatMap { it.ids() }.count { it == "x" })
    }

    @Test
    fun `artist row title from seeds`() {
        val providers = listOf(norm("a", "A", """[
          {"id":"fl1","title":"Fans Like Radiohead","category":"artist","seedArtist":"Radiohead","items":[${pl("r1")}]},
          {"id":"fl2","title":"Fans Like radiohead","category":"artist","seedArtist":"radiohead","items":[${pl("r2")}]},
          {"id":"fl3","title":"Fans Like Blur","category":"artist","seedArtist":"Blur","items":[${pl("b1")}]},
          {"id":"mine","title":"Mine","category":"personal","items":[${pl("p1")}]}
        ]"""))
        val page = buildRecommendationPage(providers)
        val artist = page.row("playlists:artist")
        assertEquals("Because you like Radiohead and Blur", artist.title)
        assertEquals("artist", artist.category)
        assertEquals(listOf("Radiohead", "radiohead", "Blur"), artist.items.map { it.seedArtist })
        assertNull(page.row("playlists:personal").items[0].seedArtist)
    }

    @Test
    fun `paged items take their section category`() {
        val providers = listOf(
            norm("a", "A", """[{"id":"mine","title":"Mine","category":"personal","items":[${pl("p1")}]}]"""),
            norm("amazonmusic", "Amazon Music", """[{"id":"comm","title":"Comm","category":"community","items":[${pl("c1")},${pl("c2")}]}]""", canExpand = true),
        )
        val expanded = mapOf("amazonmusic|comm" to expansion("""[{"type":"playlist","id":"c3","name":"C3","url":"https://x/c3"}]""", nextCursor = "n1"))
        val page = buildRecommendationPage(providers, expanded = expanded)
        val community = page.row("playlists:community")
        assertEquals(listOf("c1", "c2", "c3"), community.ids())
        assertEquals(1, community.pageable.size)
        assertEquals("amazonmusic|comm", community.pageable[0].pagingKey)
        assertEquals("community", community.pageable[0].category)
        assertEquals("n1", community.pageable[0].nextCursor)
        assertEquals(emptyList(), page.row("playlists:personal").pageable)
        assertEquals(listOf("p1"), page.row("playlists:personal").ids())
    }

    @Test
    fun `history mixes still excluded before the split`() {
        val providers = listOf(norm("a", "A", """[
          {"id":"mine","title":"Mine","category":"personal","items":[${pl("mix1")},${pl("p1")}]},
          {"id":"ed","title":"Ed","category":"editorial","items":[${pl("e1")}]}
        ]"""))
        val page = buildRecommendationPage(providers, excludePlaylistIds = setOf("mix1"))
        assertFalse("mix1" in page.rowsOf(RecommendationType.PLAYLIST).flatMap { it.ids() })
        assertEquals(listOf("p1"), page.row("playlists:personal").ids())
    }

    @Test
    fun `every other row carries category null — and stamping leaves the provider items alone`() {
        val providers = listOf(norm("a", "A", """[
          {"id":"mine","title":"Mine","category":"personal","items":[${pl("p1")},${al("Alb")},${song("S", "X")}]},
          {"id":"misc","title":"Misc","items":[${pl("o1")}]}
        ]"""))
        val page = buildRecommendationPage(providers, similarUsers = mapOf("lastfm" to listOf(SimilarUser("Bob", 1.0))))
        for (section in page) {
            if (section.type == RecommendationType.PLAYLIST) continue
            for (row in section.rows) assertNull(row.category)
        }
        assertNull(providers[0].sections[1].items[0].category)
        assertNull(providers[0].sections[0].items[1].category)
        // Albums are stamped too; on the single Albums row the items keep their category while the row's stays null.
        assertEquals("personal", page.rowsOf(RecommendationType.ALBUM)[0].items[0].category)
    }

    // ── album category rows ──────────────────────────────────────────

    @Test
    fun `album rows follow the album order — keys and titles`() {
        val providers = listOf(norm("a", "A", """[
          {"id":"ed","title":"Ed","category":"editorial","items":[${al("E1")}]},
          {"id":"misc","title":"Misc","items":[${al("O1")}]},
          {"id":"mine","title":"Mine","category":"personal","items":[${al("P1")}]},
          {"id":"fresh","title":"Fresh","category":"new","items":[${al("N1")}]}
        ]"""))
        val page = buildRecommendationPage(providers)
        val rows = page.rowsOf(RecommendationType.ALBUM)
        assertEquals("Albums", page.single { it.type == RecommendationType.ALBUM }.title)
        assertEquals(listOf("albums:new", "albums:personal", "albums:editorial", "albums:other"), rows.map { it.key })
        assertEquals(listOf("New for you", "Based on your listening", "From the editors", "More albums"), rows.map { it.title })
        assertEquals(listOf("new", "personal", "editorial", "other"), rows.map { it.category })
        assertTrue(rows.all { it.type == RecommendationType.ALBUM })
        assertEquals(listOf("N1"), page.row("albums:new").names())
        assertEquals(listOf("O1"), page.row("albums:other").names())
        assertEquals(listOf(PageSource("a", "A", 1)), page.row("albums:new").sources)
    }

    @Test
    fun `one album category — today's single Albums row`() {
        val rows = buildRecommendationPage(listOf(norm("a", "A", """[{"items":[${al("O1")},${al("O2")}]}]"""))).rowsOf(RecommendationType.ALBUM)
        assertEquals(1, rows.size)
        assertEquals("type:album", rows[0].key)
        assertEquals("Albums", rows[0].title)
        assertNull(rows[0].category)
        assertEquals(listOf("O1", "O2"), rows[0].names())
    }

    @Test
    fun `Shuffleupagus albums land in Based on your listening`() {
        val shuffle = shuffleupagusSection(listOf(ai("S", "Y", "r")), emptyList())!!
        assertEquals("personal", shuffle.category)
        val page = buildRecommendationPage(listOf(norm("a", "A", """[{"id":"ed","title":"Ed","category":"editorial","items":[${al("E1")}]}]""")), shuffle)
        assertEquals(listOf("S"), page.row("albums:personal").names())
        assertEquals(listOf(PageSource("shuffleupagus", "Shuffleupagus", 1)), page.row("albums:personal").sources)
        assertEquals(listOf("E1"), page.row("albums:editorial").names())
    }

    @Test
    fun `an artist album row titles from its seeds`() {
        val providers = listOf(norm("a", "A", """[
          {"id":"fl","title":"Fans Like","category":"artist","seedArtist":"FIDLAR","items":[${al("F1", "Z")}]},
          {"id":"mine","title":"Mine","category":"personal","items":[${al("P1")}]}
        ]"""))
        val page = buildRecommendationPage(providers)
        assertEquals("Because you like FIDLAR", page.row("albums:artist").title)
        assertEquals("FIDLAR", page.row("albums:artist").items[0].seedArtist)
        assertNull(page.row("albums:personal").items[0].seedArtist)
    }

    @Test
    fun `providers disagree on an album — the earliest album category wins`() {
        // `new` ranks nowhere in the playlist table, so this only passes when the album rank is consulted.
        val providers = listOf(
            norm("a", "A", """[{"id":"ed","title":"Ed","category":"editorial","items":[${al("Same", "Q")},${al("E2")}]}]"""),
            norm("b", "B", """[{"id":"fresh","title":"Fresh","category":"new","items":[${al("same", "q")}]}]"""),
        )
        val page = buildRecommendationPage(providers)
        val fresh = page.row("albums:new")
        assertEquals(listOf("Same"), fresh.names())
        assertEquals(listOf("a", "b"), fresh.items[0].sourceIds)
        assertEquals(listOf("E2"), page.row("albums:editorial").names())
        assertEquals(1, page.rowsOf(RecommendationType.ALBUM).flatMap { it.names() }.count { it.lowercase() == "same" })
    }

    @Test
    fun `paged albums take their section category`() {
        val providers = listOf(
            norm("a", "A", """[{"id":"mine","title":"Mine","category":"personal","items":[${al("P1")}]}]"""),
            norm("amazonmusic", "Amazon Music", """[{"id":"ed","title":"Ed","category":"editorial","items":[${al("E1")},${al("E2")}]}]""", canExpand = true),
        )
        val page = buildRecommendationPage(providers, expanded = mapOf("amazonmusic|ed" to expansion("[${al("E3")}]", nextCursor = "n1")))
        val editorial = page.row("albums:editorial")
        assertEquals(listOf("E1", "E2", "E3"), editorial.names())
        assertEquals("editorial", editorial.items[2].category)
        assertEquals(1, editorial.pageable.size)
        assertEquals("amazonmusic|ed", editorial.pageable[0].pagingKey)
        assertEquals("editorial", editorial.pageable[0].category)
        assertEquals("n1", editorial.pageable[0].nextCursor)
        assertTrue(editorial.canLoadMore)
        assertEquals(emptyList(), page.row("albums:personal").pageable)
        assertEquals(listOf("P1"), page.row("albums:personal").names())
    }

    @Test
    fun `a single Albums row's pageable entries carry the section's category`() {
        val providers = listOf(norm("amazonmusic", "Amazon Music", """[{"id":"fresh","title":"Fresh","category":"new","items":[${al("N1")}]}]""", canExpand = true))
        val rows = buildRecommendationPage(providers).rowsOf(RecommendationType.ALBUM)
        assertEquals(1, rows.size)
        assertEquals("type:album", rows[0].key)
        assertNull(rows[0].category)
        assertEquals(listOf("amazonmusic|fresh" to "new"), rows[0].pageable.map { it.pagingKey to it.category })
    }

    @Test
    fun `album items carry their position in the blend — categorized or not`() {
        val providers = listOf(
            norm("a", "A", """[{"items":[${al("A0")},${al("A1")}]}]"""),
            norm("b", "B", """[{"id":"mine","title":"Mine","category":"personal","items":[${al("B0")}]},
              {"id":"ed","title":"Ed","category":"editorial","items":[${al("B1")},{"type":"playlist","id":"p1","name":"P1","url":"https://x/p1"}]}]"""),
        )
        val page = buildRecommendationPage(providers)
        val byName = page.rowsOf(RecommendationType.ALBUM).flatMap { it.items }.associate { it.name to it.blendIndex }
        assertEquals(mapOf("A0" to 0, "B0" to 1, "A1" to 2, "B1" to 3), byName)
        assertNull(page.rowsOf(RecommendationType.PLAYLIST)[0].items[0].blendIndex)
        assertTrue(providers.flatMap { p -> p.sections.flatMap { it.items } }.all { it.blendIndex == null })

        val single = listOf(norm("a", "A", """[{"items":[${al("O1")},${al("O2")}]}]"""), norm("b", "B", """[{"items":[${al("O3")}]}]"""))
        val rows = buildRecommendationPage(single).rowsOf(RecommendationType.ALBUM)
        assertEquals(listOf("type:album"), rows.map { it.key })
        assertEquals(listOf("O1" to 0, "O3" to 1, "O2" to 2), rows[0].items.map { it.name to it.blendIndex })
    }

    @Test
    fun `playlist rows are unchanged beside album rows`() {
        val sectionsJson = """{"id":"pa","title":"Picks A","category":"personal","items":[${al("P1")}]},
                              {"id":"pp","title":"Picks P","category":"personal","items":[${pl("p1")}]}"""
        val page = buildRecommendationPage(listOf(norm("a", "A", "[$sectionsJson]")))
        assertEquals(listOf("type:playlist"), page.rowsOf(RecommendationType.PLAYLIST).map { it.key })
        assertEquals(listOf("type:album"), page.rowsOf(RecommendationType.ALBUM).map { it.key })
        val more = buildRecommendationPage(listOf(norm("a", "A",
            """[$sectionsJson,{"id":"pc","title":"Comm","category":"community","items":[${pl("c1")}]}]""")))
        assertEquals(listOf("playlists:personal", "playlists:community"), more.rowsOf(RecommendationType.PLAYLIST).map { it.key })
        assertEquals(listOf("Made for you", "From other listeners"), more.rowsOf(RecommendationType.PLAYLIST).map { it.title })
        assertEquals(listOf("type:album"), more.rowsOf(RecommendationType.ALBUM).map { it.key })
    }

    // ── one item per provider id ─────────────────────────────────────

    private fun alId(id: String, name: String, artist: String) = """{"type":"album","id":"$id","name":"$name","artist":"$artist"}"""

    @Test
    fun `one provider — one id under two artist strings — one item and the earliest category`() {
        val providers = listOf(norm("amazonmusic", "Amazon Music", """[
          {"id":"ed","title":"Editorial","category":"editorial","items":[${alId("B0ASIN", "Site Unseen", "Waxahatchee")}]},
          {"id":"new","title":"New","category":"new","items":[${alId("B0ASIN", "Site Unseen", "Waxahatchee, MJ Lenderman")}]}
        ]"""))
        val all = buildRecommendationPage(providers).itemsOf(RecommendationType.ALBUM)
        assertEquals(listOf("B0ASIN"), all.map { it.id })
        assertEquals("Waxahatchee", all[0].artist)
        assertEquals("new", all[0].category)
        assertEquals(listOf("amazonmusic"), all[0].sourceIds)
    }

    @Test
    fun `two providers sending one id merge their sources`() {
        val providers = listOf(
            norm("a", "A", """[{"items":[${alId("mbid-1", "Sunbather", "Deafheaven")}]}]"""),
            norm("b", "B", """[{"items":[${alId("mbid-1", "Sunbather (Remastered)", "Deafheaven")}]}]"""),
        )
        val all = buildRecommendationPage(providers).itemsOf(RecommendationType.ALBUM)
        assertEquals(1, all.size)
        assertEquals("Sunbather", all[0].name)
        assertEquals("a", all[0].resolverId)
        assertEquals(listOf("a", "b"), all[0].sourceIds)
    }

    @Test
    fun `a later copy matching either the id or the name of a merged copy joins it`() {
        val providers = listOf(
            norm("a", "A", """[{"items":[${alId("id-1", "Ride", "Arkells")}]}]"""),
            norm("b", "B", """[{"items":[${alId("id-1", "Ride", "Arkells, Someone")}]}]"""),
            norm("c", "C", """[{"items":[${alId("id-other", "Ride", "Arkells, Someone")}]}]"""),
        )
        val all = buildRecommendationPage(providers).itemsOf(RecommendationType.ALBUM)
        assertEquals(1, all.size)
        assertEquals(listOf("a", "b", "c"), all[0].sourceIds)
    }

    @Test
    fun `different ids and names stay separate — and a song never merges into an album`() {
        val page = buildRecommendationPage(listOf(norm("a", "A", """[{"title":"Mixed","items":[
          ${alId("id-1", "Ride", "Arkells")},${alId("id-2", "Ride", "Someone Else")},
          {"type":"song","id":"id-1","name":"Ride","artist":"Arkells"}]}]""")))
        assertEquals(listOf("id-1", "id-2"), page.itemsOf(RecommendationType.ALBUM).map { it.id })
        assertEquals(listOf("id-1"), page.itemsOf(RecommendationType.SONG).map { it.id })
    }

    // ── homeRecommendationAlbums ─────────────────────────────────────

    private fun homeSections(vararg rows: RecommendationRow) =
        listOf(RecommendationPageSection(RecommendationType.ALBUM, "Albums", rows.toList()))
    private fun homeRow(sources: List<PageSource>, vararg items: RecommendationItem) =
        RecommendationRow(key = "k", type = RecommendationType.ALBUM, sources = sources, items = items.toList())
    private fun homeAl(id: String, name: String, artist: String, blendIndex: Int, resolverId: String = "a") =
        RecommendationItem(type = RecommendationType.ALBUM, id = id, name = name, artist = artist, resolverId = resolverId, blendIndex = blendIndex)

    @Test
    fun `one card per id — even when the artist strings differ`() {
        val out = homeRecommendationAlbums(homeSections(
            homeRow(listOf(PageSource("a", "A")), homeAl("B0ASIN", "Site Unseen", "Waxahatchee", 0)),
            homeRow(listOf(PageSource("a", "A")), homeAl("B0ASIN", "Site Unseen", "Waxahatchee, MJ Lenderman", 1)),
        ), null)
        assertEquals(listOf("B0ASIN"), out.map { it.id })
        assertEquals("Site Unseen", out[0].name)
        assertEquals("Waxahatchee", out[0].artist)
        assertEquals("A", out[0].resolverName)
        assertEquals("A", recommendationWhy(out[0]))
    }

    @Test
    fun `keeps blend order — dedupes by artist and title as before — and appends Shuffleupagus once`() {
        val out = homeRecommendationAlbums(
            homeSections(
                homeRow(listOf(PageSource("a", "A")), homeAl("x2", "Two", "X", 1), homeAl("x1", "One", "X", 0)),
                homeRow(listOf(PageSource("b", "B")), homeAl("y1", "one", "x", 2, resolverId = "b")),
            ),
            listOf(ai("Two", "X"), ai("Three", "X", "peak", "https://i/3.jpg")),
            imageFor = { "img:${it.id}" },
        )
        assertEquals(listOf("One", "Two", "Three"), out.map { it.name })
        assertEquals("img:x1", out[0].imageUrl)
        assertEquals("shuffleupagus", out[2].resolverId)
        assertEquals("Shuffleupagus", out[2].resolverName)
        assertEquals("Shuffleupagus · peak", recommendationWhy(out[2]))
        assertEquals("https://i/3.jpg", out[2].imageUrl)
        assertEquals(out.size, out.map { it.id }.toSet().size)
    }

    @Test
    fun `tolerates nothing at all`() {
        assertEquals(emptyList(), homeRecommendationAlbums(null, null))
    }

    // ── filterBySource / albumsNeedingArt (desktop home-model.test.js) ──

    private fun src(resolverId: String, title: String = resolverId, sourceIds: List<String> = emptyList()) =
        RecommendationItem(type = RecommendationType.ALBUM, id = title, name = title, resolverId = resolverId, sourceIds = sourceIds)

    @Test
    fun `filterBySource — All returns the same list and a source filters by resolverId or sourceIds`() {
        val items = listOf(src("lastfm", "1"), src("x", "2", listOf("x", "lastfm")), src("y", "3"))
        assertSame(items, filterBySource(items, "All"))
        assertEquals(2, filterBySource(items, "lastfm").size)
    }

    @Test
    fun `albumsNeedingArt — the first six overall plus the first six of each source`() {
        val albums = (0 until 10).map { src("lastfm", "lastfm$it") } + (0 until 10).map { src("applemusic", "applemusic$it") }
        assertEquals((0 until 6).map { "lastfm$it" } + (0 until 6).map { "applemusic$it" }, albumsNeedingArt(albums, 6).map { it.name })
    }

    @Test
    fun `albumsNeedingArt — a pick two sources share counts for both`() {
        val shared = src("lastfm", "shared", listOf("lastfm", "listenbrainz"))
        val albums = (0 until 8).map { src("lastfm", "lastfm$it") } + (0 until 3).map { src("listenbrainz", "listenbrainz$it") } + shared
        val titles = albumsNeedingArt(albums, 6).map { it.name }
        assertEquals(1, titles.count { it == "shared" })
    }

    @Test
    fun `albumsNeedingArt — empty and missing input`() {
        assertEquals(emptyList(), albumsNeedingArt(null))
        assertEquals(emptyList(), albumsNeedingArt(emptyList()))
    }
}
