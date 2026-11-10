package com.parachord.shared.model

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * For You kept losing its cached list to the error screen when a refresh failed.
 * The same rule decides what iOS shows, where errors used to be dropped.
 */
class KeepDataOnErrorTest {
    private val cached: Resource<List<String>> = Resource.Success(listOf("cached"))

    @Test fun a_failed_refresh_keeps_the_cached_list() {
        assertEquals(cached, keepDataOnError(cached, Resource.Error("Failed to load recommendations")))
    }

    @Test fun with_nothing_cached_the_error_shows() {
        val err = Resource.Error("Failed to load recommendations")
        assertEquals(err, keepDataOnError(Resource.Loading, err))
    }

    @Test fun fresh_data_replaces_the_cache() {
        val fresh = Resource.Success(listOf("fresh"))
        assertEquals(fresh, keepDataOnError(cached, fresh))
    }

    @Test fun an_empty_answer_replaces_the_cache_and_its_reason_shows() {
        val empty: Resource<List<String>> = Resource.Success(emptyList())
        assertEquals(empty, keepDataOnError(cached, empty))
        val reason = Resource.Error("ListenBrainz hasn't made recommendations for you yet.")
        assertEquals(reason, keepDataOnError(empty, reason))
    }

    @Test fun settle_keeps_the_cached_list_through_a_failed_refresh() = runTest {
        val flow = flowOf(cached, Resource.Loading, Resource.Error("Failed"))
        assertEquals(cached, flow.settle())
    }

    @Test fun settle_shows_the_error_when_nothing_loaded() = runTest {
        val err = Resource.Error("Connect Last.fm to view your top tracks")
        assertEquals(err, flowOf<Resource<List<String>>>(Resource.Loading, err).settle())
    }

    @Test fun settle_ends_on_the_fresh_list() = runTest {
        val fresh = Resource.Success(listOf("fresh"))
        assertEquals(fresh, flowOf(Resource.Loading, cached, fresh).settle())
    }
}
