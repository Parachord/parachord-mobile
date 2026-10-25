package com.parachord.android.ui.screens.discover

import com.parachord.shared.model.Resource
import org.junit.Assert.assertEquals
import org.junit.Test

/** For You kept losing its cached list to the error screen when a refresh failed. */
class KeepDataOnErrorTest {
    private val cached: Resource<List<String>> = Resource.Success(listOf("cached"))

    @Test fun `a failed refresh keeps the cached list`() {
        assertEquals(cached, keepDataOnError(cached, Resource.Error("Failed to load recommendations")))
    }

    @Test fun `with nothing cached the error shows`() {
        val err = Resource.Error("Failed to load recommendations")
        assertEquals(err, keepDataOnError(Resource.Loading, err))
    }

    @Test fun `fresh data replaces the cache`() {
        val fresh = Resource.Success(listOf("fresh"))
        assertEquals(fresh, keepDataOnError(cached, fresh))
    }
}
