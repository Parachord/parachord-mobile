package com.parachord.shared.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ResolverConfigMergeTest {

    private fun obj(raw: String?): JsonObject? = raw?.let { Json.parseToJsonElement(it).jsonObject }

    @Test
    fun `a plugin's empty own config keeps the username the host supplies`() {
        // listenbrainz.axe declares username / userToken fields; nobody filled them
        // in, so the plugin's own config is {} and must not erase the connected account.
        val merged = mergeResolverConfigs(
            mapOf("listenbrainz" to """{"username":"someone"}"""),
            mapOf("listenbrainz" to "{}"),
        )
        assertEquals(obj("""{"username":"someone"}"""), obj(merged["listenbrainz"]))
    }

    @Test
    fun `a plugin's own field wins and the host's other fields stay`() {
        val merged = mergeResolverConfigs(
            mapOf("lastfm" to """{"username":"someone","apiKey":"built-in"}"""),
            mapOf("lastfm" to """{"username":"other"}"""),
        )
        assertEquals(obj("""{"username":"other","apiKey":"built-in"}"""), obj(merged["lastfm"]))
    }

    @Test
    fun `ids only one layer has pass through`() {
        val merged = mergeResolverConfigs(
            mapOf("soundcloud" to """{"token":"t"}"""),
            mapOf("subsonic" to """{"serverUrl":"https://x"}"""),
        )
        assertEquals(obj("""{"token":"t"}"""), obj(merged["soundcloud"]))
        assertEquals(obj("""{"serverUrl":"https://x"}"""), obj(merged["subsonic"]))
    }

    @Test
    fun `a layer that isn't a JSON object replaces the one before`() {
        val merged = mergeResolverConfigs(mapOf("x" to """{"a":1}"""), mapOf("x" to "not json"))
        assertEquals("not json", merged["x"])
    }
}
