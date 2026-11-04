package com.parachord.shared.deeplink

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** A shared track link looked up through its plugin (#502, desktop parachord#1219 createTrackFromMeta). */
class PluginTrackLinkTest {

    private val link = "https://soundcloud.com/flume/never-be-like-you-feat-kai"

    /** soundcloud.axe 1.3.0's oEmbed result: soundcloudId is a number, no duration. */
    private val soundCloudLookup = """
        {"resolverId":"soundcloud","track":{
          "id":"soundcloud-242136202","title":"Never Be Like You feat. Kai","artist":"Flume",
          "album":"SoundCloud","sources":["soundcloud"],"soundcloudId":242136202,
          "soundcloudUrl":"$link","albumArt":"https://i1.sndcdn.com/artworks-x-t500x500.jpg"}}
    """.trimIndent()

    @Test
    fun aSoundCloudLinkBecomesATrackWithItsSoundCloudSource() {
        val linked = assertNotNull(PluginTrackLink.parse(soundCloudLookup, link))
        val source = assertNotNull(linked.source)
        assertEquals("soundcloud", source.resolver)
        assertEquals("242136202", source.soundcloudId)
        assertEquals(link, source.soundcloudUrl)
        assertEquals(link, source.url)
        assertEquals("Never Be Like You feat. Kai", source.matchedTitle)
        assertEquals("Flume", source.matchedArtist)
        assertEquals(1.0, source.confidence)

        val track = linked.track
        assertEquals("Never Be Like You feat. Kai", track.title)
        assertEquals("Flume", track.artist)
        assertEquals("soundcloud", track.resolver)
        assertEquals("242136202", track.soundcloudId)
        assertEquals(link, track.sourceUrl)
        assertEquals("https://i1.sndcdn.com/artworks-x-t500x500.jpg", track.artworkUrl)
        assertNull(track.duration)
    }

    @Test
    fun anAlbumNamedAfterThePluginsOwnServiceIsNoAlbum() {
        // soundcloud.axe's `album: 'SoundCloud'` placeholder. It would otherwise show
        // as the album in Now Playing and reach scrobbles and album lookups.
        val linked = assertNotNull(PluginTrackLink.parse(soundCloudLookup, link))
        assertNull(linked.track.album)
    }

    @Test
    fun theServiceNameMatchIgnoresCaseSpacesAndPunctuation() {
        // resolverId "applemusic" is the plugin named "Apple Music".
        val linked = assertNotNull(
            PluginTrackLink.parse(
                """{"resolverId":"applemusic","track":{"title":"t","artist":"a","album":"Apple Music"}}""",
                link,
            ),
        )
        assertNull(linked.track.album)
    }

    @Test
    fun aRealAlbumIsKept() {
        val linked = assertNotNull(
            PluginTrackLink.parse(
                """{"resolverId":"soundcloud","track":{"title":"t","artist":"a","album":"Skin"}}""",
                link,
            ),
        )
        assertEquals("Skin", linked.track.album)
    }

    @Test
    fun anAlbumNamedAfterAnotherServiceIsKept() {
        // Only the plugin's own name is a placeholder, so nothing here lists services.
        val linked = assertNotNull(
            PluginTrackLink.parse(
                """{"resolverId":"bandcamp","track":{"title":"t","artist":"a","album":"SoundCloud"}}""",
                link,
            ),
        )
        assertEquals("SoundCloud", linked.track.album)
    }

    @Test
    fun noLookupMeansNoTrack() {
        // No plugin claims the link, or the plugin answered null (a set or a user page).
        assertNull(PluginTrackLink.parse(null, link))
        assertNull(PluginTrackLink.parse("null", link))
        assertNull(PluginTrackLink.parse("not json", link))
    }

    @Test
    fun aTrackNeedsATitleAndArtist() {
        assertNull(PluginTrackLink.parse("""{"resolverId":"soundcloud","track":{"title":"x"}}""", link))
    }

    @Test
    fun metadataWithNoServiceIdsAddsNoSource() {
        val linked = assertNotNull(PluginTrackLink.parse("""{"resolverId":"x","track":{"title":"t","artist":"a"}}""", link))
        assertNull(linked.source)
        assertNull(linked.track.resolver)
    }

    @Test
    fun anotherPluginsServiceUrlIsTheSourceUrl() {
        val linked = assertNotNull(
            PluginTrackLink.parse(
                """{"resolverId":"bandcamp","track":{"title":"t","artist":"a","duration":210,"bandcampUrl":"https://a.bandcamp.com/track/t"}}""",
                "https://a.bandcamp.com/track/t",
            ),
        )
        assertEquals("https://a.bandcamp.com/track/t", linked.source?.url)
        assertEquals(210_000L, linked.track.duration)
    }
}
