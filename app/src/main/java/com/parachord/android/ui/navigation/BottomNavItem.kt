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
) {
    HOME(Routes.HOME, "Home", PageIcon.HOME.icon),
    SEARCH(Routes.SEARCH, "Search", PageIcon.SEARCH.icon, systemIcon = Icons.Filled.Search),
    COLLECTION(Routes.COLLECTION_BASE, "Collection", PageIcon.COLLECTION.icon),
    PLAYLISTS(Routes.PLAYLISTS, "Playlists", PageIcon.PLAYLISTS.icon),
    ADD(null, "Add", AppIcon.Plus, isAction = true),
}

/**
 * Whether a bottom-bar tab is selected: some destination in the current
 * hierarchy ([destinationRoutes]) is the tab's route, its arguments ignored.
 * Collection's destination is `collection?tab={tab}` while the tab navigates
 * to `collection`, so comparing whole routes never selected it.
 */
fun bottomNavSelected(itemRoute: String?, destinationRoutes: Sequence<String?>): Boolean =
    itemRoute != null && destinationRoutes.any { it?.substringBefore('?') == itemRoute }
