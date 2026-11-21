package com.parachord.shared.repository

import com.parachord.shared.recommendations.ExpandedSection
import com.parachord.shared.recommendations.PageSource
import com.parachord.shared.recommendations.RecommendationItem
import com.parachord.shared.recommendations.RecommendationPageSection
import com.parachord.shared.recommendations.RecommendationSection
import com.parachord.shared.recommendations.RecommendationType
import kotlinx.serialization.Serializable

/* What [RecommendationsRepository] hands the screens and keeps on disk. */

/** Why the page has nothing to show. */
sealed class RecommendationsError {
    /** No Last.fm or ListenBrainz username, no content resolver provider on, no AI plugin sharing history. */
    data object NotConfigured : RecommendationsError()
    data class Message(val text: String) : RecommendationsError()
}

/** What the Recommendations page shows. */
data class RecommendationPageState(
    val sections: List<RecommendationPageSection> = emptyList(),
    /**
     * The page built before the history-mix exclusion (desktop's
     * `weeklySections`), for the weekly playlist headers
     * ([com.parachord.shared.recommendations.weeklyPlaylistHeader]): Weekly Jams
     * are also a history mix, so [sections] leaves them out. The same list as
     * [sections] when nothing is excluded.
     */
    val weeklySections: List<RecommendationPageSection> = emptyList(),
    /** The filter bar's pills. */
    val sources: List<PageSource> = emptyList(),
    /** Nothing on screen yet and providers still answering: the page skeleton. */
    val loading: Boolean = false,
    /** Providers still answering that have nothing on screen (each one's skeleton). */
    val pendingProviders: Set<String> = emptySet(),
    /** The similar-users lookup is out and no Listeners row is on screen yet: the Listeners skeleton. */
    val listenersPending: Boolean = false,
    /** Shuffleupagus is picking: the album and artist rows show trailing skeleton cards. */
    val aiLoading: Boolean = false,
    val error: RecommendationsError? = null,
    val hero: String = RecommendationsRepository.HERO_FALLBACK,
    /** The provider sections fetching a page now, by `pagingKey`: their rows show a loading tail. */
    val loadingMore: Set<String> = emptySet(),
    /** Each provider section's pages so far, by `pagingKey` (`lastPageTypes` / `lastPageCategories` end a row's tail). */
    val paging: Map<String, ExpandedSection> = emptyMap(),
)

/** One provider's last answer, as memory and the disk cache keep it. */
@Serializable
internal data class ProviderAnswer(
    val resolverId: String,
    val resolverName: String = "",
    val sections: List<RecommendationSection> = emptyList(),
    /** When it answered (epoch ms). */
    val fetchedAt: Long = 0,
    /** The account it answered for: Last.fm's or ListenBrainz's username, else "". */
    val account: String = "",
    /** The provider's kind, so a saved answer can be gated by `providerIsOn` before the plugins list. */
    val isMetaService: Boolean = false,
    val needsAccount: Boolean = false,
)

/** `recommendations_cache.json`. A file from before this shape decodes empty. */
@Serializable
internal data class RecommendationsDiskCache(
    val answers: Map<String, ProviderAnswer> = emptyMap(),
    val savedAt: Long = 0,
)

/**
 * What the page says when no connected service has anything to recommend.
 * Port of desktop's `recommendations-empty.js` (parachord#1121). ListenBrainz
 * builds its recommendation playlist from a few weeks of listens, so a new
 * account has none. With neither service connected (only a content provider
 * such as Apple Music), the message names none.
 */
fun emptyRecommendationsMessage(hasListenBrainz: Boolean, hasLastFm: Boolean): String = when {
    hasListenBrainz && !hasLastFm ->
        "ListenBrainz hasn't made recommendations for you yet. It builds them from a few weeks of listens, so check back later, or connect Last.fm in Settings for recommendations now."
    hasListenBrainz && hasLastFm ->
        "Neither ListenBrainz nor Last.fm has recommendations for you yet. ListenBrainz builds them from a few weeks of listens, so check back later."
    hasLastFm ->
        "Last.fm doesn't have recommendations for you yet. Check back once it has more of your listening history."
    // Neither scrobbler (an Apple-Music-only user): name no service they never connected.
    else ->
        "No recommendations right now. Check back once your services have more of your listening history."
}
