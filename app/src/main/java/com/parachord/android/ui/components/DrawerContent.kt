package com.parachord.android.ui.components

import com.parachord.android.ui.theme.composeColor
import com.parachord.shared.ui.DesignTokens
import com.parachord.android.ui.theme.FillAccent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import com.parachord.android.ui.theme.ParachordTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.graphics.Brush
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.android.ui.icons.parachordWordmark
import com.parachord.shared.ui.icons.AppIcon
import com.parachord.shared.ui.icons.PageIcon
import com.parachord.android.ui.navigation.Routes
import kotlin.math.cos
import kotlin.math.sin

/**
 * Drawer menu item definition.
 */
data class DrawerMenuItem(
    val route: String,
    val label: String,
    val icon: AppIcon,
    val activeColor: Color,
)

// ── Drawer Item Definitions ─────────────────────────────────────────

private val yourMusicItems = listOf(
    DrawerMenuItem(Routes.HISTORY, "History", PageIcon.HISTORY.icon, DesignTokens.Page.HISTORY.composeColor),
)

private val discoverItems = listOf(
    DrawerMenuItem(Routes.FRESH_DROPS, "Fresh Drops", PageIcon.FRESH_DROPS.icon, DesignTokens.Page.FRESH_DROPS.composeColor),
    DrawerMenuItem(Routes.RECOMMENDATIONS, "Recommendations", PageIcon.RECOMMENDATIONS.icon, DesignTokens.Page.RECOMMENDATIONS.composeColor),
    DrawerMenuItem(Routes.POP_OF_THE_TOPS, "Pop of the Tops", PageIcon.POP_OF_THE_TOPS.icon, DesignTokens.Page.POP_OF_THE_TOPS.composeColor),
    DrawerMenuItem(Routes.CRITICAL_DARLINGS, "Critical Darlings", PageIcon.CRITICAL_DARLINGS.icon, DesignTokens.Page.CRITICAL_DARLINGS.composeColor),
    DrawerMenuItem(Routes.CONCERTS, "Concerts", PageIcon.CONCERTS.icon, DesignTokens.Page.CONCERTS.composeColor),
)

private val FriendPurple = FillAccent
private val OnAirGreen: Color @androidx.compose.runtime.Composable get() = com.parachord.android.ui.theme.Success

// Desktop --surface-dark: dark pill bg for friend mini playbar
private val MiniPlaybarBgLight = Color(0xF21F2937) // rgba(31,41,55,0.95)
private val MiniPlaybarBgDark = Color(0xF2262626)  // rgba(38,38,38,0.95)

/** Hexagonal clip path matching the desktop's polygon(50% 0%, 100% 25%, 100% 75%, 50% 100%, 0% 75%, 0% 25%). */
private val HexagonShape = object : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, 0f)       // top center
            lineTo(w, h * 0.25f)       // top right
            lineTo(w, h * 0.75f)       // bottom right
            lineTo(w * 0.5f, h)        // bottom center
            lineTo(0f, h * 0.75f)      // bottom left
            lineTo(0f, h * 0.25f)      // top left
            close()
        }
        return Outline.Generic(path)
    }
}

private val settingsItem = DrawerMenuItem(
    Routes.SETTINGS, "Settings", PageIcon.SETTINGS.icon, Color(0xFF9CA3AF),
)

@Composable
fun DrawerContent(
    currentRoute: String?,
    friends: List<FriendEntity> = emptyList(),
    onItemClick: (String) -> Unit,
    onFriendClick: (String) -> Unit = {},
    onListenAlong: (FriendEntity) -> Unit = {},
    onUnpinFriend: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Wordmark color adapts to theme: dark text on light bg, light text on dark bg
    val wordmarkFill = MaterialTheme.colorScheme.onSurface
    val wordmarkIcon = parachordWordmark(wordmarkFill)

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp)
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 12.dp),
    ) {
        // ── Parachord wordmark at top ──
        Icon(
            imageVector = wordmarkIcon,
            contentDescription = "Parachord",
            tint = Color.Unspecified, // colors are baked into the vector (fill param + red accent)
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
                .height(35.dp),
        )

        // ── Scrollable content (everything except Settings) ──
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
        ) {
            // ── YOUR MUSIC Section ──
            DrawerSectionHeader("YOUR MUSIC")
            yourMusicItems.forEach { item ->
                DrawerNavItem(
                    item = item,
                    selected = currentRoute == item.route,
                    onClick = { onItemClick(item.route) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── DISCOVER Section ──
            DrawerSectionHeader("DISCOVER")
            discoverItems.forEach { item ->
                DrawerNavItem(
                    item = item,
                    selected = currentRoute == item.route,
                    onClick = { onItemClick(item.route) },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── FRIENDS Section (pinned only, on-air sorted first) ──
            if (friends.isNotEmpty()) {
                val sortedFriends = friends.sortedWith(
                    compareByDescending<FriendEntity> { it.isOnAir }
                        .thenByDescending { it.cachedTrackTimestamp }
                )
                DrawerSectionHeader("FRIENDS")
                sortedFriends.forEach { friend ->
                    DrawerFriendItem(
                        friend = friend,
                        selected = currentRoute == "friend/${friend.id}",
                        onClick = { onFriendClick(friend.id) },
                        onListenAlong = { onListenAlong(friend) },
                        onUnpin = { onUnpinFriend(friend.id) },
                    )
                }
            }
        }

        // ── Settings pinned to bottom ──
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 1.dp,
        )
        Box(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
        ) {
            DrawerNavItem(
                item = settingsItem,
                selected = currentRoute == settingsItem.route,
                onClick = { onItemClick(settingsItem.route) },
            )
        }
    }
}

@Composable
private fun DrawerSectionHeader(title: String) {
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.88.sp, // ~0.08em at 11sp
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun DrawerNavItem(
    item: DrawerMenuItem,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(6.dp)
    val backgroundColor = if (selected) item.activeColor.copy(alpha = 0.15f) else Color.Transparent
    val textColor = if (selected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    }
    val fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(backgroundColor)
            .hapticClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        ParachordIcon(
            item.icon,
            contentDescription = null,
            tint = item.activeColor,
            size = 20.dp,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = item.label,
            fontSize = 14.sp,
            fontWeight = fontWeight,
            color = textColor,
        )
    }
}

// Uses ModalBg, ModalBgDarker, ModalDivider, ModalTextPrimary, ModalIconTint
// from TrackContextMenu.kt (same package)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun DrawerFriendItem(
    friend: FriendEntity,
    selected: Boolean,
    onClick: () -> Unit,
    onListenAlong: () -> Unit = {},
    onUnpin: () -> Unit = {},
) {
    val shape = RoundedCornerShape(6.dp)
    val backgroundColor = if (selected) FriendPurple.copy(alpha = 0.15f) else Color.Transparent
    val textColor = if (selected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    }
    val fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
    val sidebarBgColor = MaterialTheme.colorScheme.surface
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(backgroundColor)
                .hapticCombinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true },
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.Start,
        ) {
            // Hexagonal avatar with on-air indicator (desktop: w-8 h-8, hex clip)
            Box(modifier = Modifier.padding(top = 2.dp)) {
                // Avatar placeholder or image, hex-clipped
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(HexagonShape),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!friend.avatarUrl.isNullOrBlank()) {
                        SubcomposeAsyncImage(
                            model = friend.avatarUrl,
                            contentDescription = friend.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(32.dp),
                            loading = { HexAvatarFallback(friend.displayName) },
                            error = { HexAvatarFallback(friend.displayName) },
                        )
                    } else {
                        HexAvatarFallback(friend.displayName)
                    }
                }

                // On-air green dot with border matching sidebar bg (desktop: w-3 h-3)
                if (friend.isOnAir) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 1.dp, y = 1.dp)
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(sidebarBgColor) // border ring
                            .padding(2.dp)
                            .clip(CircleShape)
                            .background(OnAirGreen),
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Friend name row
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = friend.displayName,
                        fontSize = 13.sp,
                        fontWeight = fontWeight,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }

                // On-air: dark pill mini playbar. Offline: plain muted text.
                if (friend.isOnAir && friend.cachedTrackName != null) {
                    Spacer(modifier = Modifier.height(3.dp))
                    FriendMiniPlaybar(
                        trackName = friend.cachedTrackName!!,
                        artistName = friend.cachedTrackArtist,
                        artworkUrl = friend.cachedTrackArtworkUrl,
                        isOnAir = true,
                    )
                } else if (friend.cachedTrackName != null) {
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = buildString {
                            append(friend.cachedTrackName)
                            friend.cachedTrackArtist?.let { append("  ·  $it") }
                        },
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Always-dark context menu bottom sheet
        if (showMenu) {
            ModalBottomSheet(
                onDismissRequest = { showMenu = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = ModalBg,
                scrimColor = Color.Black.copy(alpha = 0.4f),
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 10.dp)
                            .size(width = 32.dp, height = 4.dp)
                            .background(
                                color = Color.White.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(2.dp),
                            ),
                    )
                },
            ) {
                Column(
                    modifier = Modifier
                        .background(Brush.verticalGradient(listOf(ModalBg, ModalBgDarker)))
                        .padding(bottom = 32.dp),
                ) {
                    if (friend.isOnAir && friend.cachedTrackName != null) {
                        ContextMenuItem(
                            icon = Icons.Filled.Headphones,
                            label = "Listen Along",
                            onClick = {
                                showMenu = false
                                onListenAlong()
                            },
                        )
                        HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))
                    }
                    ContextMenuItem(
                        icon = Icons.Filled.Person,
                        label = "View Profile",
                        onClick = {
                            showMenu = false
                            onClick()
                        },
                    )
                    HorizontalDivider(color = ModalDivider, modifier = Modifier.padding(vertical = 4.dp))
                    ContextMenuItem(
                        icon = Icons.Filled.PushPin,
                        label = "Unpin from Sidebar",
                        onClick = {
                            showMenu = false
                            onUnpin()
                        },
                    )
                }
            }
        }
    }
}

// ── Friend Mini Playbar ─────────────────────────────────────────────
// Desktop: FriendMiniPlaybar — dark pill, 20px height, mini album art + scrolling text

@Composable
private fun FriendMiniPlaybar(
    trackName: String,
    artistName: String?,
    artworkUrl: String?,
    isOnAir: Boolean,
) {
    val isDark = ParachordTheme.isDark
    val pillBg = if (isDark) MiniPlaybarBgDark else MiniPlaybarBgLight
    val pillShape = RoundedCornerShape(3.dp)
    val trackTextColor = Color(0xFFFFFFFF) // white for track name
    val artistTextColor = Color(0xFFD1D5DB) // gray-300 for artist

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .clip(pillShape)
            .background(pillBg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Mini album art (20×20, rounded-left only)
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(topStart = 3.dp, bottomStart = 3.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (!artworkUrl.isNullOrBlank()) {
                SubcomposeAsyncImage(
                    model = artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(20.dp),
                    loading = { MiniArtFallback() },
                    error = { MiniArtFallback() },
                )
            } else {
                MiniArtFallback()
            }
        }

        // Track info text — marquee scroll matching desktop's animate-marquee
        Text(
            text = buildString {
                append(trackName)
                artistName?.let { append("  ·  $it") }
            },
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 20.sp,
            color = artistTextColor,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 6.dp)
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = 1000, // 1s pause before scrolling (desktop: animation-delay: 1s)
                    velocity = 30.dp,           // slow scroll speed
                ),
        )

        // On-air live indicator
        if (isOnAir) {
            Box(
                modifier = Modifier
                    .padding(end = 5.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(OnAirGreen),
            )
        }
    }
}

@Composable
private fun MiniArtFallback() {
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(Color(0xFF4B5563)), // gray-600
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = Color(0xFF9CA3AF),
            modifier = Modifier.size(10.dp),
        )
    }
}

@Composable
private fun HexAvatarFallback(name: String) {
    // Desktop: gradient from-purple-400 to-pink-400
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(
                brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = listOf(Color(0xFFA78BFA), Color(0xFFF472B6)),
                    start = Offset.Zero,
                    end = Offset(32f, 32f),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1).uppercase(),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}
