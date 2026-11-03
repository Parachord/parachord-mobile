package com.parachord.shared.sync

import kotlin.test.Test
import kotlin.test.assertEquals

class KeptAddedAtTest {
    @Test
    fun anExistingDateSurvivesAResync() = assertEquals(1_700L, keptAddedAt(existing = 1_700L, incoming = 1_800L))

    @Test
    fun aNewRowTakesTheIncomingDate() = assertEquals(1_800L, keptAddedAt(existing = null, incoming = 1_800L))

    @Test
    fun aRowDated1970TakesTheIncomingDate() = assertEquals(1_800L, keptAddedAt(existing = 0L, incoming = 1_800L))
}
