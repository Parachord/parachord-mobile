package com.parachord.shared.home

import com.parachord.shared.model.Playlist
import com.parachord.shared.model.Track
import com.parachord.shared.playback.PlaybackContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HomeModelTest {
    private val now = 1_700_000_000_000L
    private fun ctx(type: String, name: String = "", id: String? = null) = PlaybackContext(type, name, id)
    private fun playing(c: PlaybackContext) = ActiveContexts(playing = c)
    private fun queued(c: PlaybackContext) = ActiveContexts(queued = c)
    private fun pl(id: String, name: String, changed: Long? = null) = Playlist(id = id, name = name, tracksChangedAt = changed)
    private fun love(title: String, artist: String, album: String? = null, mbid: String? = null, art: String? = null) =
        Track(id = "$artist-$title", title = title, artist = artist, album = album, releaseMbid = mbid, artworkUrl = art)
    private fun mix(id: String) = HistoryMix(id, id, id, url = "u", resolverId = "r", resolverName = "R")

    // ── isActiveContext ──
    @Test
    fun playlistAndMixById() {
        assertTrue(isActiveContext(JumpCandidate.Playlist("a", null), playing(ctx("playlist", id = "a"))))
        assertFalse(isActiveContext(JumpCandidate.Playlist("a", null), queued(ctx("playlist", id = "b"))))
        assertTrue(isActiveContext(JumpCandidate.Mix("m"), queued(ctx("mix", id = "m"))))
    }

    @Test
    fun albumByNormalisedNames() {
        // mobile's album context: name = album title, id = artist
        assertTrue(isActiveContext(JumpCandidate.Album("Hartsdale Hotbox (Deluxe)", "The Frog"),
            queued(ctx("album", "hartsdale hotbox", "Frog"))))
        assertFalse(isActiveContext(JumpCandidate.Album("Other", "Frog"),
            queued(ctx("album", "hartsdale hotbox", "Frog"))))
        // a candidate with an mbid still matches by name when the context has none
        assertTrue(isActiveContext(JumpCandidate.Album("Hotbox", "Frog", "x"), playing(ctx("album", "Hotbox", "Frog"))))
        assertFalse(isActiveContext(JumpCandidate.Album("Hotbox", "Frog"), playing(ctx("playlist", "Hotbox", "Frog"))))
    }

    @Test
    fun aPlaylistContextWithNoIdMatchesByTitle() {
        val c = JumpCandidate.Playlist("a", "Daily Brew")
        assertTrue(isActiveContext(c, playing(ctx("playlist", "Daily Brew"))))
        assertFalse(isActiveContext(c, queued(ctx("playlist", "Other"))))
        assertFalse(isActiveContext(c, playing(ctx("playlist", "Daily Brew", "b"))))
        assertFalse(isActiveContext(JumpCandidate.Playlist("a", null), playing(ctx("playlist", "Daily Brew"))))
    }

    @Test
    fun jumpBackInTilesSkipsAPlaylistPlayingByTitleOnly() {
        val playlists = listOf(pl("p1", "Daily Brew", 10), pl("p2", "Older", 5))
        val tiles = jumpBackInTiles(playlists, emptyList(), emptyList(), playing(ctx("playlist", "Daily Brew")), now)
        assertEquals("p2", (tiles[0] as JumpTile.Playlist).playlist.id)
    }

    @Test
    fun lovesByContextType() {
        assertTrue(isActiveContext(JumpCandidate.Loves, playing(ctx("loves"))))
        assertFalse(isActiveContext(JumpCandidate.Loves, playing(ctx("album"))))
        assertFalse(isActiveContext(JumpCandidate.Loves, ActiveContexts(null, null)))
    }

    // ── latestLoveAlbum ──
    @Test
    fun latestLoveAlbumSkipsSinglesAndAQueuedAlbum() {
        val loves = listOf(
            love("S", "A"),
            love("Q", "Frog", "Queued"),
            love("Frog", "Hovvdy", "Hotbox", "r1"),
        )
        val active = queued(ctx("album", "Queued", "Frog"))
        assertEquals(LoveAlbum("Hotbox", "Hovvdy", "Frog", "r1"), latestLoveAlbum(loves, active))
        assertNull(latestLoveAlbum(emptyList(), ActiveContexts(null, null)))
    }

    // ── jumpBackInTiles ──
    private val playlists = listOf(pl("p1", "One", 300), pl("p2", "Two", 200))
    private val loves = listOf(love("T", "A", "Al"))

    @Test
    fun orderAndOneOfEachKind() {
        val t = jumpBackInTiles(playlists, loves, listOf(mix("m1")), ActiveContexts(null, null), now)
        assertEquals(listOf("Playlist", "Loves", "Album", "Mix"), t.map { it::class.simpleName })
        assertEquals("p1", (t[0] as JumpTile.Playlist).playlist.id)
        assertEquals("Playlist", t[0].subtitle)
        assertEquals("Your recent loves", t[1].subtitle)
        assertEquals("T · the album behind your latest love", t[2].subtitle)
        assertEquals("Made from your listening", t[3].subtitle)
    }

    @Test
    fun thePlayingFreshestPlaylistGivesWayToTheSecond() {
        val t = jumpBackInTiles(playlists, loves, emptyList(), playing(ctx("playlist", id = "p1")), now)
        assertEquals("p2", (t[0] as JumpTile.Playlist).playlist.id)
    }

    @Test
    fun emptySourcesAreLeftOut() {
        assertEquals(emptyList(), jumpBackInTiles(emptyList(), emptyList(), emptyList(), ActiveContexts(null, null), now))
    }

    @Test
    fun aChangedPlaylistTileCarriesTheBadgeSubtitle() {
        val p = Playlist(id = "p", name = "P", tracksChangedAt = now - 3_600_000L, lastChangeAdded = 4)
        val t = jumpBackInTiles(listOf(p), emptyList(), emptyList(), ActiveContexts(null, null), now)
        assertTrue(t[0].subtitle.startsWith("Playlist · 4 new "))
    }

    // ── homeSections ──
    @Test
    fun everythingEmpty() {
        val s = homeSections(bannerItems = emptyList<Any>(), tiles = emptyList<Any>(), mixes = emptyList<Any>(),
            playlists = emptyList<Any>(), albums = emptyList<Any>(), fresh = emptyList<Any>(),
            critical = emptyList<Any>(), concerts = emptyList<Any>(), concertsLocation = "")
        assertEquals(HomeSections(false, false, true, false, false, false, false, false, false, true), s)
    }

    @Test
    fun theImportCardIsForANewUserOnly() {
        assertFalse(homeSections(tiles = emptyList<Any>(), playlists = emptyList<Any>(), hasCollection = true).importCard)
        assertFalse(homeSections(tiles = emptyList<Any>(), playlists = listOf(1), hasCollection = false).importCard)
        assertTrue(homeSections(tiles = emptyList<Any>(), playlists = emptyList<Any>(), hasCollection = false).importCard)
        val s = homeSections(tiles = listOf(1), playlists = emptyList<Any>(), hasCollection = false)
        assertTrue(s.jump && s.importCard)
    }

    @Test
    fun albumsShowAndConcertsNeedALocation() {
        val s = homeSections(tiles = listOf(1), albums = listOf(1), concerts = listOf(1), concertsLocation = "", hasCollection = true)
        assertTrue(s.jump && s.albums && s.ask)
        assertFalse(s.importCard || s.concerts)
        assertTrue(homeSections(concerts = listOf(1), concertsLocation = "Austin").concerts)
    }

    @Test
    fun theMixesRowShowsWithLovesEvenWhenNoServiceHasMixes() {
        assertTrue(homeSections(mixes = emptyList<Any>(), loves = listOf(1)).mixes)
        assertFalse(homeSections(mixes = emptyList<Any>(), loves = emptyList<Any>()).mixes)
    }

    // ── exampleShufflePrompt ──
    @Test
    fun genericWithNoArtists() {
        assertEquals("Make me a playlist for right now", exampleShufflePrompt(emptyList(), 0))
    }

    @Test
    fun firstThreeArtistsPhrasingBySeed() {
        assertEquals("A rainy Sunday playlist built around A, B and C", exampleShufflePrompt(listOf("A", "B", "C", "D"), 0))
        val all = (0..3).map { exampleShufflePrompt(listOf("A"), it) }
        assertEquals(4, all.toSet().size)
        assertEquals(all[0], exampleShufflePrompt(listOf("A"), 4))
        assertEquals(all[3], exampleShufflePrompt(listOf("A"), -1))
        assertEquals("A late night mix with A", all[1])
        assertEquals("Songs that sound like A", all[2])
        assertEquals("A road trip playlist starting with A", all[3])
        assertEquals("A rainy Sunday playlist built around A and B", exampleShufflePrompt(listOf("A", "B"), 0))
    }

    // ── homeTopArtists ──
    @Test
    fun mostFrequentArtistsTiesByRecencyCapped() {
        val ls = listOf("Hovvdy", "Nap Eyes", "Hovvdy", "Frog", "Nap Eyes", "Frog", "Frog").map { love("t", it) }
        assertEquals(listOf("Frog", "Hovvdy", "Nap Eyes"), homeTopArtists(ls))
        assertEquals(listOf("Frog"), homeTopArtists(ls, 1))
    }

    @Test
    fun dropsBlankAndOverLongNames() {
        assertEquals(listOf("Frog"), homeTopArtists(listOf(love("t", " "), love("t", "x".repeat(81)), love("t", "Frog"))))
    }

    // ── promptName hardening ──
    @Test
    fun bidiControlsAndOtherControlCharactersAreStripped() {
        val evil = "Frog‮evil⁦x⁩\u0007‏"
        assertEquals("A rainy Sunday playlist built around Frogevilx", exampleShufflePrompt(listOf(evil), 0))
    }

    @Test
    fun theArabicLetterMarkAndZeroWidthCharactersAreStripped() {
        assertEquals("A rainy Sunday playlist built around Frog", exampleShufflePrompt(listOf("Fr؜og​‌‍"), 0))
    }

    @Test
    fun whitespaceControlsBecomeOneSpace() {
        assertEquals("A rainy Sunday playlist built around Nap Eyes", exampleShufflePrompt(listOf("Nap\t\nEyes"), 0))
    }

    @Test
    fun eachNameIsCappedAtFortyCharacters() {
        assertEquals("A rainy Sunday playlist built around ${"y".repeat(40)} and Frog",
            exampleShufflePrompt(listOf("y".repeat(60), "Frog"), 0))
    }

    @Test
    fun aNameThatIsOnlyControlsIsDropped() {
        assertEquals("A rainy Sunday playlist built around Frog", exampleShufflePrompt(listOf("‮⁦", "Frog"), 0))
        assertEquals("Make me a playlist for right now", exampleShufflePrompt(listOf("‮"), 0))
    }

    @Test
    fun theCutNeverSplitsASurrogatePair() {
        val name = "a".repeat(39) + "🎸" // 39 letters + guitar emoji = 40 code points
        assertEquals(name, promptName(name))
        assertEquals(name, promptName(name + "zzz"))
        val cut = promptName("a".repeat(40) + "🎸")
        assertEquals("a".repeat(40), cut)
        assertEquals("", promptName(null))
    }

    // ── filterBySource / albumsNeedingArt ──
    private data class Item(val title: String, val resolverId: String?, val sourceIds: List<String> = emptyList())
    private val rid = { i: Item -> i.resolverId }
    private val sids = { i: Item -> i.sourceIds }

    @Test
    fun filterAllReturnsTheSameList() {
        val items = listOf(Item("a", "lastfm"))
        assertTrue(items === filterBySource(items, "All", rid, sids))
    }

    @Test
    fun filtersByResolverIdOrSourceIds() {
        val items = listOf(Item("a", "lastfm"), Item("b", "x", listOf("x", "lastfm")), Item("c", "y"))
        assertEquals(2, filterBySource(items, "lastfm", rid, sids).size)
    }

    private fun mk(src: String, n: Int) = (0 until n).map { Item("$src$it", src) }

    @Test
    fun theFirstSixOverallPlusTheFirstSixOfEachSource() {
        val albums = mk("lastfm", 10) + mk("applemusic", 10)
        val want = albumsNeedingArt(albums, 6, rid, sids).map { it.title }
        assertEquals((0..5).map { "lastfm$it" } + (0..5).map { "applemusic$it" }, want)
    }

    @Test
    fun aPickTwoSourcesShareCountsForBoth() {
        val shared = Item("shared", "lastfm", listOf("lastfm", "listenbrainz"))
        val titles = albumsNeedingArt(mk("lastfm", 8) + mk("listenbrainz", 3) + shared, 6, rid, sids).map { it.title }
        assertEquals(1, titles.count { it == "shared" })
    }

    @Test
    fun albumsNeedingArtOnEmptyInput() {
        assertEquals(emptyList(), albumsNeedingArt(emptyList(), 6, rid, sids))
    }

    // ── recommendations throttle ──
    private val t = 1_000_000_000L

    @Test
    fun loadsWhenIdleAndNoFullLoadHasFinished() {
        assertTrue(shouldLoadHomeRecommendations(false, true, 0L, t))
        assertTrue(shouldLoadHomeRecommendations(false, true, null, t))
        assertTrue(shouldLoadHomeRecommendations(true, false, 0L, t))
    }

    @Test
    fun skipsWhileALoadRuns() {
        assertFalse(shouldLoadHomeRecommendations(true, true, 0L, t))
    }

    @Test
    fun skipsWithinTenMinutesOfAFinishedFullLoad() {
        assertEquals(600_000L, HOME_RECOMMENDATIONS_THROTTLE_MS)
        assertFalse(shouldLoadHomeRecommendations(false, true, t - 9 * 60_000L, t))
        assertTrue(shouldLoadHomeRecommendations(false, true, t - 10 * 60_000L, t))
    }

    @Test
    fun everyAskedProviderFailedMeansNoStamp() {
        assertFalse(shouldStampHomeRecommendationsLoad(3, 3, 0))
        assertFalse(shouldStampHomeRecommendationsLoad(2, 2, 4))
    }

    @Test
    fun someAnsweredOrAllCachedMeansAStamp() {
        assertTrue(shouldStampHomeRecommendationsLoad(3, 1, 2))
        assertTrue(shouldStampHomeRecommendationsLoad(0, 0, 5))
    }

    @Test
    fun noSectionOnThePageMeansNoStamp() {
        assertFalse(shouldStampHomeRecommendationsLoad(2, 0, 0))
    }

    // ── lovesMosaicAlbums ──
    @Test
    fun theMosaicTakesTheNewestDistinctAlbumsAtMostFour() {
        val ls = listOf(
            love("t", "A", "X", art = "ax"), love("t", "A", "X"), love("t", "B", ""),
            love("t", "B", "Y"), love("t", "C", "Z"), love("t", "D", "W"), love("t", "E", "V"),
        )
        assertEquals(
            listOf(LovesMosaicAlbum("A", "X", "ax"), LovesMosaicAlbum("B", "Y", null),
                LovesMosaicAlbum("C", "Z", null), LovesMosaicAlbum("D", "W", null)),
            lovesMosaicAlbums(ls),
        )
        assertEquals(emptyList(), lovesMosaicAlbums(emptyList()))
    }
}
