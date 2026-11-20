package com.parachord.shared.home

import com.parachord.shared.api.Announcement
import com.parachord.shared.api.Cta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeBannersTest {
    @Test
    fun `empty input gives an empty list`() {
        assertEquals(emptyList(), homeBannerItems(emptyList()))
    }

    @Test
    fun `announcements keep their given order`() {
        val items = homeBannerItems(listOf(
            Announcement(id = "a1", title = "One", body = "b1", severity = "warn"),
            Announcement(id = "a2", title = "Two", body = "b2"),
        ))
        assertEquals(listOf("a1", "a2"), items.map { it.id })
        assertEquals("warn", items[0].severity)
    }

    @Test
    fun `an announcement without severity gets info`() {
        assertEquals("info", homeBannerItems(listOf(Announcement(id = "x", title = "T"))).single().severity)
    }

    @Test
    fun `fields carry over`() {
        val item = homeBannerItems(listOf(
            Announcement(id = "x", title = "T", body = "B", iconUrl = "https://i/x.png", cta = Cta("Go", "https://x")),
        )).single()
        assertEquals("B", item.body)
        assertEquals("https://i/x.png", item.iconUrl)
        assertEquals(Cta("Go", "https://x"), item.cta)
        assertNull(homeBannerItems(listOf(Announcement(id = "y", title = "T"))).single().iconUrl)
    }

    @Test
    fun `only the top banner earns a view`() {
        val items = homeBannerItems(listOf(Announcement(id = "a1", title = "One"), Announcement(id = "a2", title = "Two")))
        assertEquals("a1", bannerViewId(items))
        assertNull(bannerViewId(emptyList()))
    }

    @Test
    fun `a CTA whose link isn't http or https is dropped`() {
        fun cta(url: String) = homeBannerItems(listOf(Announcement(id = "x", title = "T", cta = Cta("Go", url)))).single().cta
        assertNull(cta("javascript:alert(1)"))
        assertNull(cta("parachord://settings"))
        assertNull(cta(""))
        assertEquals(Cta("Go", "http://x"), cta("http://x"))
        assertEquals(Cta("Go", " HTTPS://x "), cta(" HTTPS://x "))
    }
}
