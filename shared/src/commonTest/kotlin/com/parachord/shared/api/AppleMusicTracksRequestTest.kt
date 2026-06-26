package com.parachord.shared.api

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression guard for the Apple Music library track-add body (#272).
 *
 * AM's `POST /v1/me/library/playlists/{id}/tracks` rejects a body whose track
 * references omit `type` with HTTP 400 "Unable to parse request body" (code
 * 40007). The client's `Json` uses `encodeDefaults = false`, so if
 * `AmTrackReference.type` carries a default value it is DROPPED from the body
 * and every AM playlist track-add silently fails. `type` must therefore stay a
 * required (defaultless) property — always serialized regardless of the
 * `encodeDefaults` setting.
 */
class AppleMusicTracksRequestTest {
    // Mirror the EXACT Json config AppleMusicLibraryClient uses.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Test
    fun appendBody_alwaysSerializesTypeField() {
        val body = AmTracksRequest(listOf(AmTrackReference("299250259", "songs")))
        val out = json.encodeToString(AmTracksRequest.serializer(), body)
        assertTrue(
            out.contains("\"type\":\"songs\""),
            "AM track-add body dropped `type` → AM 400 'unable to parse'. Got: $out",
        )
        assertTrue(out.contains("\"id\":\"299250259\""), "id field missing: $out")
    }
}
