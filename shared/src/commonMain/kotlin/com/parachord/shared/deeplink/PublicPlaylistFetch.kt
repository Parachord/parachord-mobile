package com.parachord.shared.deeplink

import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.takeFrom
import io.ktor.utils.io.readAvailable

/** The most a hosted XSPF may be: desktop's `HOSTED_PLAYLIST_MAX_BYTES`. */
const val HOSTED_PLAYLIST_MAX_BYTES = 5 * 1024 * 1024

/** The most a `parachord://play/playlist?url=` tracklist may be. */
const val PROTOCOL_TRACKLIST_MAX_BYTES = 100 * 1024

/** How many redirects a playlist fetch follows. */
const val PLAYLIST_FETCH_MAX_REDIRECTS = 5

/** A playlist URL the app refused to fetch, or a fetch that failed. */
class PlaylistFetchException(message: String) : IllegalStateException(message)

/**
 * Fetch a user-supplied playlist URL (a hosted XSPF, a protocol tracklist) as
 * text, for both apps (GHSA-gfvj-rh43-vmp9). Two things the shared client
 * doesn't do on its own:
 *
 * - **Every hop is checked.** Redirects aren't followed automatically: each
 *   3xx `Location` is resolved against the current URL and run through
 *   [validatePublicHttpsUrl] before any request goes to it (https only, no
 *   private, loopback, link-local or local-name host), at most
 *   [PLAYLIST_FETCH_MAX_REDIRECTS] hops. Following them automatically let a
 *   public https URL send the app to a device on the user's network or to
 *   plain http.
 * - **The body is bounded while it's read.** A `Content-Length` over
 *   [maxBytes] is refused before reading, and reading stops at [maxBytes] + 1
 *   bytes, so an oversized or endless body is never held whole.
 *
 * The first URL is checked here too, so callers can't forget. Throws
 * [IllegalArgumentException] (from the validator) for a refused URL and
 * [PlaylistFetchException] for a bad status, too many redirects or a body over
 * the cap. DNS rebinding stays out of scope, as the validator documents.
 */
suspend fun fetchPublicPlaylistText(httpClient: HttpClient, url: String, maxBytes: Int): String {
    // The shared client follows redirects; this one doesn't (the engines never
    // do: Ktor's OkHttp engine disables OkHttp's own following, and the Darwin
    // engine declines URLSession's). `config` shares the engine.
    val client = httpClient.config { followRedirects = false }
    try {
        var current = url
        var hops = 0
        while (true) {
            validatePublicHttpsUrl(current)
            when (val step = fetchOnce(client, current, maxBytes)) {
                is FetchStep.Body -> return step.text
                is FetchStep.Redirect -> {
                    if (++hops > PLAYLIST_FETCH_MAX_REDIRECTS) throw PlaylistFetchException("Too many redirects")
                    current = step.location
                }
            }
        }
    } finally {
        client.close() // closes only this view; the engine stays with the shared client
    }
}

private sealed class FetchStep {
    class Body(val text: String) : FetchStep()
    class Redirect(val location: String) : FetchStep()
}

private suspend fun fetchOnce(client: HttpClient, url: String, maxBytes: Int): FetchStep =
    client.prepareGet(url).execute { resp ->
        val status = resp.status.value
        if (status in 300..399) {
            val location = resp.headers[HttpHeaders.Location]
                ?: throw PlaylistFetchException("Redirect without a location")
            return@execute FetchStep.Redirect(URLBuilder(url).takeFrom(location).buildString())
        }
        if (!resp.status.isSuccess()) throw PlaylistFetchException("HTTP $status")
        val tooLarge = "Playlist is too large (over ${maxBytes / 1024} KB)"
        resp.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let {
            if (it > maxBytes) throw PlaylistFetchException(tooLarge)
        }
        val channel = resp.bodyAsChannel()
        val chunks = ArrayList<ByteArray>()
        var total = 0
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = channel.readAvailable(buffer, 0, buffer.size)
            if (n < 0) break
            // Nothing ready yet: wait for data (or the end) rather than spin.
            if (n == 0) { if (!channel.awaitContent(1)) break else continue }
            total += n
            if (total > maxBytes) throw PlaylistFetchException(tooLarge)
            chunks.add(buffer.copyOf(n))
        }
        val all = ByteArray(total)
        var at = 0
        for (c in chunks) { c.copyInto(all, at); at += c.size }
        FetchStep.Body(all.decodeToString())
    }
