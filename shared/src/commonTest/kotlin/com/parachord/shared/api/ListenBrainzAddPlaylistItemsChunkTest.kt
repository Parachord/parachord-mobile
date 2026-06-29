package com.parachord.shared.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #300: LB's playlist `item/add` endpoint caps at 100 recordings per call — a
 * >100-track push 400s "You may only add max 100 recordings per call" and the
 * mirror never fills. [ListenBrainzClient.addPlaylistItems] must chunk at 100.
 */
class ListenBrainzAddPlaylistItemsChunkTest {

    private val jsonHeaders = headersOf("Content-Type", "application/json")

    private fun client(engine: MockEngine) = ListenBrainzClient(
        HttpClient(engine) { install(ContentNegotiation) { json() } },
    )

    private suspend fun countCalls(n: Int): Int {
        var calls = 0
        val engine = MockEngine { calls++; respond("{}", HttpStatusCode.OK, jsonHeaders) }
        client(engine).addPlaylistItems("plmbid", (1..n).map { "mbid-$it" }, "tok")
        return calls
    }

    @Test
    fun addPlaylistItems_under_100_is_one_call() = runTest { assertEquals(1, countCalls(50)) }

    @Test
    fun addPlaylistItems_exactly_100_is_one_call() = runTest { assertEquals(1, countCalls(100)) }

    @Test
    fun addPlaylistItems_101_splits_into_two_calls() = runTest { assertEquals(2, countCalls(101)) }

    @Test
    fun addPlaylistItems_250_splits_into_three_calls() = runTest { assertEquals(3, countCalls(250)) }

    @Test
    fun addPlaylistItems_empty_makes_no_call() = runTest { assertEquals(0, countCalls(0)) }
}
