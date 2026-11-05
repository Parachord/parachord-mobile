package com.parachord.android.data.db.dao

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.parachord.shared.db.ParachordDb
import com.parachord.shared.db.dao.TrackDao
import com.parachord.shared.model.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** #529: both search paths run against a real database. The rules are in `LibraryTrackSearchTest`. */
class TrackDaoSearchTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { ParachordDb.Schema.create(it) }
    private val dao = TrackDao(ParachordDb(driver), driver)

    private suspend fun seed() {
        dao.insert(Track(id = "1", title = "Roads", artist = "Portishead", album = "Dummy", resolver = "localfiles"))
        dao.insert(Track(id = "2", title = "Glory Box", artist = "Portishead", album = "Dummy", resolver = "spotify"))
    }

    @Test
    fun `the search flow finds artist and title together`() = runTest {
        seed()
        assertEquals(listOf("Roads"), dao.search("Portishead Roads").first().map { it.title })
        assertEquals(listOf("Roads"), dao.search("Roads Portishead").first().map { it.title })
    }

    @Test
    fun `the one-shot search finds artist and title together`() = runTest {
        seed()
        assertEquals(listOf("Roads"), dao.searchOnce("Portishead Roads").map { it.title })
    }

    @Test
    fun `one word still finds every match`() = runTest {
        seed()
        assertEquals(setOf("Roads", "Glory Box"), dao.search("Portis").first().map { it.title }.toSet())
    }

    @Test
    fun `local files are only the rows that play from a file`() = runTest {
        seed()
        assertEquals(listOf("Roads"), dao.getLocalFiles().map { it.title })
    }
}
