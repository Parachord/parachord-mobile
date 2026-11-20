package com.parachord.android.ui.screens.home

import com.parachord.android.playback.PlaybackState
import com.parachord.android.playback.PlaybackStateHolder
import com.parachord.shared.ai.AiRecommendationService
import com.parachord.shared.home.serviceConnected
import com.parachord.shared.home.stepOnePlugins
import com.parachord.shared.plugin.PluginManager
import com.parachord.shared.plugin.PluginManager.PluginInfo
import com.parachord.shared.plugin.pluginIsOn
import com.parachord.shared.repository.LibraryRepository
import com.parachord.shared.settings.SettingsStore
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Android's mapping of its data onto Home's sources. */
class AndroidHomeSourcesTest {

    /** The bundled plug-ins step 1 offers, shaped as their manifests load. */
    private val bundled = listOf(
        PluginInfo("spotify", "Spotify", "1", type = "resolver", capabilities = mapOf("resolve" to true, "stream" to true), requiresAuth = true, authType = "oauth"),
        PluginInfo("applemusic", "Apple Music", "1", type = "resolver", capabilities = mapOf("resolve" to true, "stream" to true), requiresAuth = true),
        PluginInfo("soundcloud", "SoundCloud", "1", type = "resolver", capabilities = mapOf("resolve" to true, "stream" to true), requiresAuth = true, authType = "oauth"),
        PluginInfo("subsonic", "Subsonic", "1", type = "resolver", capabilities = mapOf("resolve" to true, "stream" to true, "html5Stream" to true), requiresAuth = true),
        PluginInfo("bandcamp", "Bandcamp", "1", type = "resolver", capabilities = mapOf("resolve" to true, "embedPlayer" to true)),
        PluginInfo("localfiles", "Local Files", "1", type = "resolver", capabilities = mapOf("resolve" to true, "stream" to true)),
        PluginInfo("lastfm", "Last.fm", "1", type = "meta-service"),
    )

    private fun TestScope.sources(
        configured: Boolean,
        ai: AiRecommendationService = mockk(relaxed = true),
    ): AndroidHomeSources {
        val settings = mockk<SettingsStore>(relaxed = true)
        every { settings.getSpotifyAccessTokenFlow() } returns flowOf(null)
        every { settings.getSoundCloudTokenFlow() } returns flowOf(null)
        coEvery { settings.getAppleMusicUserToken() } returns null
        every { settings.getDisabledPluginsFlow() } returns flowOf(emptySet())
        every { settings.getDisabledMetaProvidersFlow() } returns flowOf(emptySet())
        every { settings.getActiveResolversFlow() } returns flowOf(emptyList())
        val plugins = mockk<PluginManager>(relaxed = true)
        every { plugins.plugins } returns MutableStateFlow(bundled)
        coEvery { plugins.isPluginConfigured(any()) } returns true
        coEvery { plugins.isPluginConfigured("subsonic") } returns configured
        val holder = mockk<PlaybackStateHolder>(relaxed = true)
        every { holder.state } returns MutableStateFlow(PlaybackState())
        val library = mockk<LibraryRepository>(relaxed = true)
        every { library.getAllTracks() } returns flowOf(emptyList())
        every { library.getAllAlbums() } returns flowOf(emptyList())
        every { library.getAllArtists() } returns flowOf(emptyList())
        every { library.getAllPlaylists() } returns flowOf(emptyList())
        return AndroidHomeSources(
            library = library,
            playbackStateHolder = holder,
            settings = settings,
            pluginManager = plugins,
            announcementsRepository = mockk(relaxed = true),
            historyMixesRepository = mockk(relaxed = true),
            recommendationsRepository = mockk(relaxed = true),
            aiRecommendationService = ai,
            freshDropsRepository = mockk(relaxed = true),
            criticalDarlingsRepository = mockk(relaxed = true),
            chartsRepository = mockk(relaxed = true),
            concertsRepository = mockk(relaxed = true),
            concertArtistSeed = mockk(relaxed = true),
            concertArtistImages = com.parachord.shared.home.ConcertArtistImages { null },
            // androidPluginIsOn with no token anywhere: a plug-in needing an account
            // the host signs in is off; every other id reads as connected.
            pluginIsOn = { id, meta, needs ->
                val connected = id !in setOf("spotify", "applemusic", "soundcloud")
                pluginIsOn(id, meta, needs, connected, emptyList(), emptySet(), emptySet())
            },
            scope = backgroundScope,
        )
    }

    @Test
    fun `a new user with Subsonic not set up has nothing connected`() = runTest {
        val s = sources(configured = false)
        val accounts = s.accounts.first()
        val resolvers = s.playbackResolvers.first()
        assertEquals(false, accounts["subsonic"])
        assertFalse("subsonic" in resolvers)
        assertFalse(serviceConnected(stepOnePlugins(s.plugins.first()).services, accounts, playbackResolvers = resolvers))
    }

    @Test
    fun `a set-up Subsonic counts as connected`() = runTest {
        val s = sources(configured = true)
        val accounts = s.accounts.first()
        val resolvers = s.playbackResolvers.first()
        assertEquals(true, accounts["subsonic"])
        assertEquals(listOf("subsonic", "bandcamp", "localfiles"), resolvers)
    }

    @Test
    fun `chatAvailable is null until known`() = runTest {
        val ai = mockk<AiRecommendationService>()
        coEvery { ai.hasEnabledProvider() } returns true
        val values = sources(configured = false, ai = ai).chatAvailable.take(2).toList()
        assertNull(values[0])
        assertEquals(true, values[1])
    }
}
