package com.parachord.shared.repository

import com.parachord.shared.api.LastFmClient
import com.parachord.shared.api.ListenBrainzClient
import com.parachord.shared.metadata.MetadataService
import com.parachord.shared.model.Resource
import com.parachord.shared.settings.SettingsStore
import com.parachord.shared.store.KvStore
import com.parachord.shared.store.SecureTokenStore
import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Taking the first useful value of a History flow, as the DJ chat's context
 * does (`ChatContextProvider`, `.firstOrNull { it is Resource.Success }`),
 * aborts the flow from inside its `emit`. The flows caught that abort (a
 * CancellationException) with their generic `catch (e: Exception)` and
 * emitted an error after it, which crashed with "Flow exception transparency
 * is violated".
 */
class HistoryRepositoryFirstValueTest {

    private class FakeSecureStore : SecureTokenStore {
        override fun get(key: String): String? = null
        override fun set(key: String, value: String) {}
        override fun remove(key: String) {}
        override fun contains(key: String): Boolean = false
        override fun observe(key: String): Flow<String?> = flowOf(null)
    }

    private fun repository(): HistoryRepository {
        val mock = MockEngine { request ->
            val method = request.url.parameters["method"].orEmpty()
            val body = when (method) {
                "user.gettoptracks" ->
                    """{"toptracks":{"track":[{"name":"Song","playcount":"5","artist":{"name":"Band"},"image":[],"@attr":{"rank":"1"}}]}}"""
                "user.gettopartists" ->
                    """{"topartists":{"artist":[{"name":"Band","playcount":"5","image":[],"@attr":{"rank":"1"}}]}}"""
                "user.gettopalbums" ->
                    """{"topalbums":{"album":[{"name":"Record","playcount":"5","artist":{"name":"Band"},"image":[],"@attr":{"rank":"1"}}]}}"""
                else -> "{}"
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val http = HttpClient(mock)
        val settings = SettingsStore(FakeSecureStore(), KvStore(MapSettings("lastfm_username" to "someone")))
        return HistoryRepository(
            lastFmClient = LastFmClient(http),
            listenBrainzClient = ListenBrainzClient(http),
            settingsStore = settings,
            metadataService = MetadataService(emptyList(), getDisabledProviders = { emptySet() }),
            lastFmApiKey = "KEY",
        )
    }

    @Test
    fun `the first top tracks can be taken without a crash`() = runTest {
        val first = repository().getTopTracks("overall", limit = 10).firstOrNull { it is Resource.Success }
        assertIs<Resource.Success<*>>(first)
        assertEquals(1, (first as Resource.Success).data.size)
    }

    @Test
    fun `the first top artists can be taken without a crash`() = runTest {
        val first = repository().getTopArtists("overall", limit = 15).firstOrNull { it is Resource.Success }
        assertIs<Resource.Success<*>>(first)
    }

    @Test
    fun `the first top albums can be taken without a crash`() = runTest {
        val first = repository().getTopAlbums("overall", limit = 10).firstOrNull { it is Resource.Success }
        assertIs<Resource.Success<*>>(first)
    }

    @Test
    fun `the first recent tracks value can be taken without a crash`() = runTest {
        assertIs<Resource.Success<*>>(repository().getRecentTracks().firstOrNull { it is Resource.Success })
    }
}
