package com.parachord.shared.repository

import com.parachord.shared.model.HistoryTrack
import com.parachord.shared.model.Resource
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The 24-hour top-lists cache both apps use for History and friend profiles (#595). */
class TopListsCacheTest {
    private val files = mutableMapOf<String, String>()
    private var now = 1_000_000_000L
    private val cache = TopListsCache(read = { files[it] }, write = { f, s -> files[f] = s }, nowMs = { now })
    private val a = listOf(HistoryTrack("Creep", "Radiohead"))
    private val b = listOf(HistoryTrack("Roads", "Portishead"))
    private var loads = 0
    private fun answer(r: Resource<List<HistoryTrack>>) = { loads++; flow { emit(Resource.Loading); emit(r) } }

    @Test fun `a fresh list is shown without a request`() = runTest {
        cache.tracks("7day", answer(Resource.Success(a))).toList()
        now += TopListsCache.TTL_MS - 1
        val out = cache.tracks("7day", answer(Resource.Success(b))).toList()
        assertEquals(listOf<Resource<List<HistoryTrack>>>(Resource.Success(a)), out)
        assertEquals(1, loads)
    }

    @Test fun `after a day it asks again and saves the new list`() = runTest {
        cache.tracks("7day", answer(Resource.Success(a))).toList()
        now += TopListsCache.TTL_MS
        assertEquals(Resource.Success(b), cache.tracks("7day", answer(Resource.Success(b))).toList().last())
        assertEquals(Resource.Success(b), cache.tracks("7day", answer(Resource.Success(a))).toList().single())
    }

    @Test fun `a failure or an empty answer shows the last list`() = runTest {
        cache.tracks("7day", answer(Resource.Success(a))).toList()
        now += TopListsCache.TTL_MS
        assertEquals(Resource.Success(a), cache.tracks("7day", answer(Resource.Error("offline"))).toList().last())
        assertEquals(Resource.Success(a), cache.tracks("7day", answer(Resource.Success(emptyList()))).toList().last())
    }

    @Test fun `with nothing saved a failure shows its error`() = runTest {
        assertEquals(Resource.Error("offline"), cache.tracks("7day", answer(Resource.Error("offline"))).toList().last())
    }

    @Test fun `periods and friends are kept apart`() = runTest {
        cache.tracks(TopListsCache.ownKey("7day"), answer(Resource.Success(a))).toList()
        cache.tracks(TopListsCache.friendKey("jane", "lastfm", "7day"), answer(Resource.Success(b))).toList()
        assertEquals(Resource.Success(a), cache.tracks("7day", { flowOf() }).toList().single())
        assertEquals(Resource.Success(b), cache.tracks("friend:jane:lastfm:7day", { flowOf() }).toList().single())
        assertTrue(files.keys == setOf(TopListsCache.TRACKS_FILE))
    }

    @Test fun `the file iOS already wrote is read`() = runTest {
        files[TopListsCache.TRACKS_FILE] =
            """{"1month":{"fetchedAt":$now,"data":[{"title":"Creep","artist":"Radiohead","playCount":3}]}}"""
        assertEquals(Resource.Success(listOf(HistoryTrack("Creep", "Radiohead", playCount = 3))),
            cache.tracks("1month", { flowOf() }).toList().single())
    }
}
