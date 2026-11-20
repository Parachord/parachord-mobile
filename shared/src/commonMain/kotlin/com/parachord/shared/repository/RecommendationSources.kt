package com.parachord.shared.repository

import com.parachord.shared.ai.AiRecommendationService
import com.parachord.shared.ai.AiRecommendations
import com.parachord.shared.plugin.PluginManager
import com.parachord.shared.plugin.needsAccount
import com.parachord.shared.settings.SettingsStore

/**
 * A plugin that can recommend music. [isMetaService] puts it after the
 * content resolvers; [needsAccount] feeds `pluginIsOn`; [canExpand] lets its
 * titled sections with an id page on (an `expand` that answers null ends them).
 */
data class RecommendationProvider(
    val id: String,
    val name: String,
    val isMetaService: Boolean = false,
    val needsAccount: Boolean = false,
    val canExpand: Boolean = true,
)

/**
 * Where recommendations come from. The real one is
 * [PluginRecommendationSource]; tests supply their own.
 */
interface RecommendationSource {
    /** Every plugin that declares `capabilities.recommendations`, loaded or not on. */
    suspend fun providers(): List<RecommendationProvider>

    /**
     * One provider's raw `getRecommendations` answer (`{ sections }` JSON). An
     * answer with no sections is a miss. Throws `PluginNotReadyException` when
     * the provider isn't ready (signed out), any other exception when it
     * couldn't answer.
     */
    suspend fun fetch(providerId: String): String

    /** The next page of a section, as `{ items, nextCursor }` JSON; null when the plugin doesn't paginate. Throws on failure. */
    suspend fun expand(providerId: String, sectionId: String, cursor: String?): String?
}

/**
 * The plugins that declare `capabilities.recommendations` (desktop
 * parachord#1309). A new provider needs only a plugin.
 */
class PluginRecommendationSource(private val pluginManager: PluginManager) : RecommendationSource {
    override suspend fun providers(): List<RecommendationProvider> {
        val ids = pluginManager.recommendationResolvers()
        val infos = pluginManager.plugins.value.associateBy { it.id }
        return ids.map { (id, name) ->
            val info = infos[id]
            RecommendationProvider(
                id = id,
                name = name,
                isMetaService = info?.type == "meta-service",
                needsAccount = info?.let(::needsAccount) ?: false,
                // The loader doesn't say whether a plugin implements
                // expandRecommendationSection; one that doesn't answers null.
                canExpand = true,
            )
        }
    }

    override suspend fun fetch(providerId: String): String = pluginManager.getRecommendations(providerId)

    override suspend fun expand(providerId: String, sectionId: String, cursor: String?): String? =
        pluginManager.expandRecommendationSection(providerId, sectionId, cursor)
}

/**
 * Where Home's history mixes come from: the plugins with
 * `capabilities.historyMixes`. The real one is [PluginHistoryMixSource];
 * tests supply their own.
 */
interface HistoryMixSource {
    /** Every plugin that declares `capabilities.historyMixes`, loaded or not on. */
    suspend fun providers(): List<RecommendationProvider>

    /**
     * One provider's raw `getHistoryMixes` answer (a JSON array of mixes).
     * Throws `PluginNotReadyException` when the provider isn't ready (signed
     * out), any other exception when it couldn't answer.
     */
    suspend fun fetch(providerId: String): String
}

/** The plugins that declare `capabilities.historyMixes` (desktop's `getHistoryMixResolvers`). */
class PluginHistoryMixSource(private val pluginManager: PluginManager) : HistoryMixSource {
    override suspend fun providers(): List<RecommendationProvider> {
        val ids = pluginManager.historyMixResolvers()
        val infos = pluginManager.plugins.value.associateBy { it.id }
        return ids.map { (id, name) ->
            val info = infos[id]
            RecommendationProvider(
                id = id,
                name = name,
                isMetaService = info?.type == "meta-service",
                needsAccount = info?.let(::needsAccount) ?: false,
                canExpand = false,
            )
        }
    }

    override suspend fun fetch(providerId: String): String = pluginManager.getHistoryMixes(providerId)
}

/** Shuffleupagus (the AI plugin's) album and artist picks, as the page reads them. */
interface ShuffleupagusPicks {
    /** The last picks, or null. */
    suspend fun cached(): AiRecommendations?
    /** An AI plugin has a key. */
    suspend fun hasProvider(): Boolean
    /** The user shares listening history with it (desktop's `aiIncludeHistory`). */
    suspend fun sharesHistory(): Boolean
    /** Fresh picks (an LLM call: tens of seconds). Throws on failure. */
    suspend fun load(): AiRecommendations
}

/** [ShuffleupagusPicks] over the shared [AiRecommendationService]. */
class AiShuffleupagusPicks(
    private val service: AiRecommendationService,
    private val settingsStore: SettingsStore,
) : ShuffleupagusPicks {
    override suspend fun cached() = service.getCachedRecommendations()
    override suspend fun hasProvider() = service.hasEnabledProvider()
    override suspend fun sharesHistory() = settingsStore.getSendListeningHistory()
    override suspend fun load() = service.loadRecommendations()
}
