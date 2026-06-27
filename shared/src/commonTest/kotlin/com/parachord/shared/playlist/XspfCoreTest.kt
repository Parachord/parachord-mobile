package com.parachord.shared.playlist

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XspfCoreTest {

    // ── SHA-256 (must be byte-identical to Android's MessageDigest) ──
    @Test fun `sha256 empty vector`() {
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha256Hex(""))
    }

    @Test fun `sha256 abc vector`() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256Hex("abc"))
    }

    @Test fun `sha256 long vector`() {
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
        )
    }

    @Test fun `sha256 utf8 multibyte is 64 hex chars and deterministic`() {
        assertEquals(64, sha256Hex("é — playlist ✓").length)
        assertEquals(sha256Hex("é — playlist ✓"), sha256Hex("é — playlist ✓"))
    }

    // ── XSPF parsing ──
    private val sample = """
        <?xml version="1.0" encoding="UTF-8"?>
        <playlist version="1" xmlns="http://xspf.org/ns/0/">
          <title>My &amp; Mix</title>
          <creator>Jason</creator>
          <trackList>
            <track>
              <title>Song One</title>
              <creator>Artist A</creator>
              <album>Album X</album>
              <duration>210000</duration>
              <location>https://example.com/a.mp3</location>
            </track>
            <track>
              <title>Song &lt;Two&gt;</title>
              <creator>Artist B</creator>
            </track>
          </trackList>
        </playlist>
    """.trimIndent()

    @Test fun `parses title creator and tracks`() {
        val pl = XspfParser.parse(sample)
        assertEquals("My & Mix", pl.title)
        assertEquals("Jason", pl.creator)
        assertEquals(2, pl.tracks.size)
    }

    @Test fun `parses track fields and decodes entities`() {
        val pl = XspfParser.parse(sample)
        val t0 = pl.tracks[0]
        assertEquals("Song One", t0.title)
        assertEquals("Artist A", t0.artist)
        assertEquals("Album X", t0.album)
        assertEquals(210000L, t0.duration)
        assertEquals("https://example.com/a.mp3", t0.sourceUrl)
        assertEquals("stream", t0.sourceType)
        assertEquals("Song <Two>", pl.tracks[1].title)
        assertNull(pl.tracks[1].sourceUrl)
    }

    @Test fun `does not mistake trackList for track`() {
        // Header region (before first <track>) contains <trackList> — title must
        // still resolve to the playlist title, not be tripped up by the substring.
        assertEquals("My & Mix", XspfParser.parse(sample).title)
    }

    @Test fun `rejects DOCTYPE (XXE defense)`() {
        assertFailsWith<IllegalArgumentException> {
            XspfParser.parse("<!DOCTYPE x [<!ENTITY a \"b\">]><playlist><title>t</title></playlist>")
        }
    }

    @Test fun `empty playlist yields default title and no tracks`() {
        val pl = XspfParser.parse("<playlist></playlist>")
        assertEquals("Imported Playlist", pl.title)
        assertTrue(pl.tracks.isEmpty())
    }
}
