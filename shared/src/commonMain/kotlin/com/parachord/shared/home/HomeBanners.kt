package com.parachord.shared.home

import com.parachord.shared.api.Announcement
import com.parachord.shared.api.Cta

// The banner list for Home, a port of desktop's `home-banners.js`. The
// announcements arrive already filtered (surface, version, expiry,
// dismissals), so this only normalises them, keeping their order. A CTA
// whose link isn't http(s) is dropped, so no button shows and no click is
// counted (desktop's banner only opens http(s) links). The CSS severity
// colours stay in the design tokens.

data class HomeBannerItem(
    val id: String,
    val title: String,
    val body: String?,
    val iconUrl: String?,
    val cta: Cta?,
    /** `info`, `success`, `warn` or `error`. */
    val severity: String,
)

/** How a banner is coloured, from its severity. */
enum class BannerRole { ERROR, WARNING, SUCCESS, INFO }

/** `error`, `warn` and `success` have their own role; `info`, no severity or an unknown one is [BannerRole.INFO]. */
fun bannerRole(severity: String?): BannerRole = when (severity) {
    "error" -> BannerRole.ERROR
    "warn" -> BannerRole.WARNING
    "success" -> BannerRole.SUCCESS
    else -> BannerRole.INFO
}

fun homeBannerItems(announcements: List<Announcement>): List<HomeBannerItem> =
    announcements.map {
        HomeBannerItem(
            id = it.id,
            title = it.title,
            body = it.body,
            iconUrl = it.iconUrl?.ifEmpty { null },
            cta = it.cta?.let { cta -> cta.url.trim().takeIf(::isHttpUrl)?.let { url -> cta.copy(url = url) } },
            severity = it.severity?.ifEmpty { null } ?: "info",
        )
    }

/** The id that earns a `view` event: the banner Home paints (the first). One queued behind it hasn't been seen. */
fun bannerViewId(items: List<HomeBannerItem>): String? = items.firstOrNull()?.id?.ifEmpty { null }
