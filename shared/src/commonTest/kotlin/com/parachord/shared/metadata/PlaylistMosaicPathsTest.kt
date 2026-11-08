package com.parachord.shared.metadata

import com.parachord.shared.metadata.PlaylistMosaicPaths.Repair
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * iOS moves an app's data container across updates; the mosaic files move
 * with it but the stored absolute URL keeps the old container UUID.
 */
class PlaylistMosaicPathsTest {

    private val oldDir = "/Users/x/Containers/Data/Application/1EAFF74A-0000/Library/Application Support/playlist_mosaics"
    private val newDir = "/Users/x/Containers/Data/Application/2FF16718-0000/Library/Application Support/playlist_mosaics"
    private val stored = "file://$oldDir/listenbrainz-abc.jpg"

    @Test
    fun `a mosaic whose file moved with the container is re-pointed`() {
        val onDisk = setOf("$newDir/listenbrainz-abc.jpg")
        assertEquals(
            Repair.Rewrite("file://$newDir/listenbrainz-abc.jpg"),
            PlaylistMosaicPaths.repair(stored, newDir) { it in onDisk },
        )
    }

    @Test
    fun `the cache-bust query survives the move`() {
        val onDisk = setOf("$newDir/hosted-xspf-7cd1.jpg")
        assertEquals(
            Repair.Rewrite("file://$newDir/hosted-xspf-7cd1.jpg?v=123"),
            PlaylistMosaicPaths.repair("file://$oldDir/hosted-xspf-7cd1.jpg?v=123", newDir) { it in onDisk },
        )
    }

    @Test
    fun `a mosaic whose file is still there is kept`() {
        val current = "file://$newDir/listenbrainz-abc.jpg"
        assertEquals(Repair.Keep, PlaylistMosaicPaths.repair(current, newDir) { it == "$newDir/listenbrainz-abc.jpg" })
    }

    @Test
    fun `a mosaic gone from both places is regenerated`() {
        assertEquals(Repair.Regenerate, PlaylistMosaicPaths.repair(stored, newDir) { false })
        assertEquals(Repair.Regenerate, PlaylistMosaicPaths.repair(stored, null) { false })
    }

    @Test
    fun `a missing local file that isn't a mosaic is regenerated — not re-pointed`() {
        assertEquals(
            Repair.Regenerate,
            PlaylistMosaicPaths.repair("file:///old/Library/Caches/art/abc.jpg", newDir) { it.startsWith(newDir) },
        )
    }

    @Test
    fun `remote art is left alone`() {
        assertEquals(Repair.Keep, PlaylistMosaicPaths.repair("https://mosaic.scdn.co/300/abc", newDir) { false })
    }

    @Test
    fun `read-time resolution maps a moved mosaic and passes everything else through`() {
        val onDisk = setOf("$newDir/listenbrainz-abc.jpg")
        assertEquals("file://$newDir/listenbrainz-abc.jpg", PlaylistMosaicPaths.resolveForRead(stored, newDir) { it in onDisk })
        assertEquals(stored, PlaylistMosaicPaths.resolveForRead(stored, newDir) { false })
        assertEquals("https://x/y.jpg", PlaylistMosaicPaths.resolveForRead("https://x/y.jpg", newDir) { false })
    }

    @Test
    fun `covers are the first four distinct non-blank track covers in order`() {
        assertEquals(
            listOf("a", "b", "c", "d"),
            PlaylistMosaicPaths.covers(listOf(null, "a", "", "b", "a", "c", "d", "e")),
        )
    }

    @Test
    fun `the cover fingerprint is stable and order-sensitive`() {
        val ab = PlaylistMosaicPaths.coverSignature(listOf("https://x/a.jpg", "https://x/b.jpg"))
        assertEquals(16, ab.length)
        assertEquals(ab, PlaylistMosaicPaths.coverSignature(listOf("https://x/a.jpg", "https://x/b.jpg")))
        assertEquals(false, ab == PlaylistMosaicPaths.coverSignature(listOf("https://x/b.jpg", "https://x/a.jpg")))
        // Pinned so both platforms (JVM + Native) provably compute the same value.
        assertEquals("cbf29ce484222325", PlaylistMosaicPaths.coverSignature(emptyList()))
    }

    @Test
    fun `version reads the v query`() {
        assertEquals("abc", PlaylistMosaicPaths.version("file:/x/playlist_mosaics/a.jpg?v=abc"))
        assertEquals("abc", PlaylistMosaicPaths.version("file:///x/a.jpg?t=1&v=abc"))
        assertNull(PlaylistMosaicPaths.version("file:///x/a.jpg"))
    }

    @Test
    fun `Android's single-slash file URI is a local file with the same path`() {
        val android = "file:/data/user/0/p/files/playlist_mosaics/a.jpg"
        assertEquals(true, PlaylistMosaicPaths.isLocalFile(android))
        assertEquals("/data/user/0/p/files/playlist_mosaics/a.jpg", PlaylistMosaicPaths.filePath("$android?v=3"))
        assertEquals("/abs/playlist_mosaics/a.jpg", PlaylistMosaicPaths.filePath("file:///abs/playlist_mosaics/a.jpg"))
        assertEquals(false, PlaylistMosaicPaths.isLocalFile("https://mosaic.scdn.co/300/abc"))
        assertEquals(false, PlaylistMosaicPaths.isLocalFile(null))
        assertEquals(Repair.Keep, PlaylistMosaicPaths.repair(android, "/data/user/0/p/files/playlist_mosaics") { true })
    }

    @Test
    fun `file name handles both file URI forms and strips the query`() {
        assertEquals("a.jpg", PlaylistMosaicPaths.fileName("file:/data/user/0/p/files/playlist_mosaics/a.jpg"))
        assertEquals("a.jpg", PlaylistMosaicPaths.fileName("file:///data/playlist_mosaics/a.jpg?v=9"))
        assertNull(PlaylistMosaicPaths.fileName("https://mosaic.scdn.co/300/abc"))
        assertNull(PlaylistMosaicPaths.fileName(null))
    }
}
