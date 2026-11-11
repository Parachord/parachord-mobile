package com.parachord.android.playback

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [queryCarConnectionState] reads Android Auto's provider. Only a real 0 may
 * veto the car; a NULL, a missing column or an empty cursor is "unknown".
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class CarConnectionStateQueryTest {

    private fun contextAnswering(cursor: MatrixCursor?): Context {
        val resolver = mockk<ContentResolver>()
        every { resolver.query(any(), any(), any(), any(), any()) } returns cursor
        return mockk<Context>().also { every { it.contentResolver } returns resolver }
    }

    private fun cursorWith(value: Any?) = MatrixCursor(arrayOf(CAR_CONNECTION_STATE_COLUMN)).apply { addRow(arrayOf(value)) }

    @Test fun `a projection connection reads 2`() = runBlocking {
        assertEquals(2, queryCarConnectionState(contextAnswering(cursorWith(2))))
    }

    @Test fun `no car reads 0`() = runBlocking {
        assertEquals(0, queryCarConnectionState(contextAnswering(cursorWith(0))))
    }

    @Test fun `a NULL value is unknown — not a veto`() = runBlocking {
        assertNull(queryCarConnectionState(contextAnswering(cursorWith(null))))
    }

    @Test fun `an empty cursor or no provider is unknown`() = runBlocking {
        assertNull(queryCarConnectionState(contextAnswering(MatrixCursor(arrayOf(CAR_CONNECTION_STATE_COLUMN)))))
        assertNull(queryCarConnectionState(contextAnswering(null)))
    }
}
