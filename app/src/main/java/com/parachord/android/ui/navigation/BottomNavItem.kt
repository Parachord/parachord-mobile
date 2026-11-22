package com.parachord.android.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.graphics.vector.ImageVector
import com.parachord.shared.ui.icons.AppIcon
import com.parachord.shared.ui.icons.PageIcon

/**
 * The bottom tab bar. Each tab draws its page's icon (shared [PageIcon],
 * desktop's), except Search, which keeps the platform's magnifier
 * ([systemIcon]; docs/DESIGN_SYSTEM.md → "System actions keep the platform's icon").
 */
enum class BottomNavItem(
    val route: String?,
    val label: String,
    val icon: AppIcon,
    val isAction: Boolean = false,
    val systemIcon: ImageVector? = null,
    /** The page whose colour the tab takes when selected (shared `PageHero.tabColor`). */
    val page: com.parachord.shared.ui.DesignTokens.Page? = null,
) {
    HOME(Routes.HOME, "Home", PageIcon.HOME.icon, page = com.parachord.shared.ui.DesignTokens.Page.HOME),
    SEARCH(Routes.SEARCH, "Search", PageIcon.SEARCH.icon, systemIcon = Icons.Filled.Search, page = com.parachord.shared.ui.DesignTokens.Page.SEARCH),
    COLLECTION(Routes.COLLECTION_BASE, "Collection", PageIcon.COLLECTION.icon, page = com.parachord.shared.ui.DesignTokens.Page.COLLECTION),
    PLAYLISTS(Routes.PLAYLISTS, "Playlists", PageIcon.PLAYLISTS.icon, page = com.parachord.shared.ui.DesignTokens.Page.PLAYLISTS),
    ADD(null, "Add", AppIcon.Plus, isAction = true),
}

/**
 * Whether a bottom-bar tab is selected: some destination in the current
 * hierarchy ([destinationRoutes]) is the tab's route, its arguments ignored.
 * Collection's destination is `collection?tab={tab}&sort={sort}` while the tab navigates
 * to `collection`, so comparing whole routes never selected it.
 */
fun bottomNavSelected(itemRoute: String?, destinationRoutes: Sequence<String?>): Boolean =
    itemRoute != null && destinationRoutes.any { it?.substringBefore('?') == itemRoute }
