package com.parachord.shared.ui

/**
 * A friend's hexagon avatar (desktop `components/friend-avatar.js`) and the
 * listener card around a bigger one (`components/listener-card.js`). Android
 * `FriendAvatar` / `ListenerCard`, iOS `PCFriendAvatar` / `PCListenerCard`
 * draw them: the drawer's friends, Collection's Friends tab, a friend's page
 * and Recommendations' Listeners.
 */
object FriendAvatarSpec {
    /** A point of a hexagon, as fractions of its box. */
    data class Point(val x: Float, val y: Float)

    /** The avatar's hexagon: desktop's `polygon(50% 0%, 100% 25%, 100% 75%, 50% 100%, 0% 75%, 0% 25%)`. */
    val HEXAGON = listOf(Point(0.5f, 0f), Point(1f, 0.25f), Point(1f, 0.75f), Point(0.5f, 1f), Point(0f, 0.75f), Point(0f, 0.25f))

    /** The listener card's hexagon, a regular one: `polygon(50% 0%, 93.3% 25%, 93.3% 75%, 50% 100%, 6.7% 75%, 6.7% 25%)`. */
    val LISTENER_HEXAGON = listOf(
        Point(0.5f, 0f), Point(0.933f, 0.25f), Point(0.933f, 0.75f),
        Point(0.5f, 1f), Point(0.067f, 0.75f), Point(0.067f, 0.25f),
    )

    /** An avatar's size and its initial's size. */
    enum class Size(val box: Int, val font: Int) {
        /** The drawer and a friend row. */
        SMALL(32, 12),
        /** A collapsed profile hero. */
        MEDIUM(40, 16),
        /** A profile hero. */
        LARGE(96, 28),
    }

    /** The initial's background: desktop's `--avatar-gradient`, top left to bottom right. */
    const val GRADIENT_FROM = 0xC084FC
    const val GRADIENT_TO = 0xF472B6
    /** The initial's weight. */
    const val INITIAL_WEIGHT = 500

    /** The letter an avatar with no photo shows: the name's first, upper case, else "?". */
    fun initial(name: String?): String = name?.trim()?.take(1)?.uppercase()?.ifEmpty { null } ?: "?"

    // ── The listener card ──
    /** The card's hexagon. */
    const val LISTENER_AVATAR = 100
    /** The ring around the photo (the inner hexagon is inset by it). */
    const val LISTENER_RING = 3
    const val LISTENER_PADDING_V = 14
    const val LISTENER_PADDING_H = 12
    const val LISTENER_NAME_GAP = 10
    const val LISTENER_INITIAL_SIZE = 28
    /** The on-air dot: its size, its border, and its place from the hexagon's bottom right. */
    const val ON_AIR_DOT = 14
    const val ON_AIR_DOT_BORDER = 2
    const val ON_AIR_DOT_BOTTOM = 4
    const val ON_AIR_DOT_END = 8
    /** The ring while they aren't on air: desktop's hover backgrounds, subtle to default. */
    val RING_FROM = DesignTokens.ThemeColor(0xE5E7EB, 0xFFFFFF, darkAlpha = 0.08f)
    val RING_TO = DesignTokens.ThemeColor(0xF3F4F6, 0xFFFFFF, darkAlpha = 0.05f)

    /** Screen readers' name for opening a listener (desktop's label). */
    fun openLabel(name: String): String = "Open $name’s profile"
}
