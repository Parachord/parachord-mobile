package com.parachord.shared.home

import com.parachord.shared.api.Announcement
import com.parachord.shared.api.Cta

// The banner list for Home, a port of desktop's `home-banners.js`. The
// announcements arrive already filtered (surface, version, expiry,
// dismissals), so this only normalises them, keeping their order. The CSS
// severity colours stay in the design tokens.

data class HomeBannerItem(
    val id: String,
    val title: String,
    val body: String?,
    val iconUrl: String?,
    val cta: Cta?,
    /** `info`, `success`, `warn` or `error`. */
    val severity: String,
)

fun homeBannerItems(announcements: List<Announcement>): List<HomeBannerItem> =
    announcements.map {
        HomeBannerItem(
            id = it.id,
            title = it.title,
            body = it.body,
            iconUrl = it.iconUrl?.ifEmpty { null },
            cta = it.cta,
            severity = it.severity?.ifEmpty { null } ?: "info",
        )
    }

/** The id that earns a `view` event: the banner Home paints (the first). One queued behind it hasn't been seen. */
fun bannerViewId(items: List<HomeBannerItem>): String? = items.firstOrNull()?.id?.ifEmpty { null }
