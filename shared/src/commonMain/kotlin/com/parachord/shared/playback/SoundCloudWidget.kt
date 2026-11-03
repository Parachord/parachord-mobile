package com.parachord.shared.playback

/**
 * The links SoundCloud's embeddable widget works from (#495, desktop
 * parachord#1209). Both apps play SoundCloud through the widget: Android in a
 * hidden WebView (`SoundCloudWidgetBridge`), iOS in a hidden WKWebView
 * (`IosSoundCloudWidget`), each hosting the same page
 * (`soundcloud-widget.html`) around desktop's `soundcloud-widget-player.js`.
 */
object SoundCloudWidget {
    const val SERVICE_NAME = "SoundCloud"

    /**
     * What the widget loads: the track's soundcloud.com page when the source
     * carries it, else its API URL built from the id, the form SoundCloud's
     * own embed code uses. Null when there's neither.
     */
    fun widgetLink(url: String?, soundcloudUrl: String?, soundcloudId: String?): String? {
        soundcloudUrl?.takeIf { isSoundCloudUrl(it) }?.let { return it }
        url?.takeIf { isSoundCloudUrl(it) }?.let { return it }
        return soundcloudId?.trim()?.takeIf { it.isNotEmpty() }?.let { "https://api.soundcloud.com/tracks/$it" }
    }

    /**
     * The track's page on soundcloud.com, for "Open in SoundCloud"; never an
     * API URL, which isn't a page. Null when unknown.
     */
    fun pageLink(url: String?, soundcloudUrl: String?): String? =
        listOf(soundcloudUrl, url).firstOrNull { it != null && isSoundCloudUrl(it) && host(it) != "api.soundcloud.com" }

    /**
     * What the app says when the widget refused a track and nothing else can
     * play it (desktop's wording): reason `preview` is a track SoundCloud lets
     * the widget play only a 30-second preview of; anything else, one it won't
     * play at all.
     */
    fun refusedMessage(serviceName: String, title: String, reason: String): String =
        if (reason == "preview") "$serviceName only lets Parachord play a preview of \"$title\"."
        else "$serviceName only plays \"$title\" on its own site."

    private fun isSoundCloudUrl(url: String): Boolean {
        val host = host(url) ?: return false
        return url.startsWith("https://") && (host == "soundcloud.com" || host.endsWith(".soundcloud.com"))
    }

    private fun host(url: String): String? =
        url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
            .substringAfterLast('@').substringBefore(':').lowercase().takeIf { it.isNotEmpty() }
}
