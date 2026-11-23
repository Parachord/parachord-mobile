package com.parachord.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.parachord.android.ui.theme.ParachordTheme
import com.parachord.android.ui.theme.current
import com.parachord.android.ui.theme.tokenColor
import com.parachord.shared.ui.DesignTokens
import com.parachord.shared.ui.FriendAvatarSpec
import com.parachord.shared.ui.icons.AppIcon

/** A hexagon from the shared [FriendAvatarSpec] points, scaled to the box. */
private class PolygonShape(private val points: List<FriendAvatarSpec.Point>) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = Path()
        points.forEachIndexed { i, p ->
            if (i == 0) path.moveTo(p.x * size.width, p.y * size.height) else path.lineTo(p.x * size.width, p.y * size.height)
        }
        path.close()
        return Outline.Generic(path)
    }
}

/** The friend avatar's hexagon (desktop's `FRIEND_HEXAGON`). */
val HexagonShape: Shape = PolygonShape(FriendAvatarSpec.HEXAGON)

/** The listener card's regular hexagon (desktop's `LISTENER_HEXAGON`). */
val ListenerHexagonShape: Shape = PolygonShape(FriendAvatarSpec.LISTENER_HEXAGON)

/** The initial's background: desktop's `--avatar-gradient`. */
private val AvatarGradient = Brush.linearGradient(
    listOf(tokenColor(FriendAvatarSpec.GRADIENT_FROM), tokenColor(FriendAvatarSpec.GRADIENT_TO)),
)

/**
 * A friend's hexagon avatar (desktop `friend-avatar.js`): their photo, or
 * their initial in white on the avatar gradient. A photo that won't load
 * falls back to the initial.
 */
@Composable
fun FriendAvatar(
    name: String?,
    avatarUrl: String?,
    modifier: Modifier = Modifier,
    size: FriendAvatarSpec.Size = FriendAvatarSpec.Size.SMALL,
) {
    var failed by remember(avatarUrl) { mutableStateOf(false) }
    Box(
        modifier = modifier.size(size.box.dp).clip(HexagonShape),
        contentAlignment = Alignment.Center,
    ) {
        if (!avatarUrl.isNullOrBlank() && !failed) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(AvatarGradient), contentAlignment = Alignment.Center) {
                Text(
                    text = FriendAvatarSpec.initial(name),
                    fontSize = size.font.sp,
                    fontWeight = FontWeight.Medium,
                    color = tokenColor(DesignTokens.ON_ACCENT),
                )
            }
        }
    }
}

/**
 * The card for a person whose listening you follow (desktop
 * `listener-card.js`): Recommendations' Listeners and Collection's friends.
 * The card box (10 radius, the card colour and shadow), a 100 dp regular
 * hexagon in a ring that's green while they're [onAir] (with the on-air dot),
 * their photo or initial inside, [badge] over the hexagon's foot, the play
 * disc for their recent top 10, the name with a pin mark when [pinned], then
 * [details].
 */
@Composable
fun ListenerCard(
    name: String,
    avatarUrl: String?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    onPlay: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    onAir: Boolean = false,
    pinned: Boolean = false,
    badge: (@Composable BoxScope.() -> Unit)? = null,
    details: @Composable ColumnScope.() -> Unit = {},
) {
    val shape = RoundedCornerShape(com.parachord.shared.ui.CardSpec.RADIUS.dp)
    val avatar = FriendAvatarSpec.LISTENER_AVATAR.dp
    val label = FriendAvatarSpec.openLabel(name)
    Column(
        modifier = modifier
            .then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth())
            .shadow(2.dp, shape)
            .clip(shape)
            .background(ParachordTheme.colors.cardBackground)
            .hapticCombinedClickable(onClick = onOpen, onLongClick = onLongPress)
            .semantics { contentDescription = label }
            .padding(horizontal = FriendAvatarSpec.LISTENER_PADDING_H.dp, vertical = FriendAvatarSpec.LISTENER_PADDING_V.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(avatar)) {
            val ring = if (onAir) {
                Brush.linearGradient(listOf(DesignTokens.success.current(), DesignTokens.success.current()))
            } else {
                Brush.linearGradient(listOf(FriendAvatarSpec.RING_FROM.current(), FriendAvatarSpec.RING_TO.current()))
            }
            Box(Modifier.fillMaxSize().clip(ListenerHexagonShape).background(ring))
            var failed by remember(avatarUrl) { mutableStateOf(false) }
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(FriendAvatarSpec.LISTENER_RING.dp)
                    .clip(ListenerHexagonShape)
                    .background(DesignTokens.placeholderBg.current()),
                contentAlignment = Alignment.Center,
            ) {
                if (!avatarUrl.isNullOrBlank() && !failed) {
                    AsyncImage(
                        model = avatarUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onError = { failed = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Text(
                        text = FriendAvatarSpec.initial(name),
                        fontSize = FriendAvatarSpec.LISTENER_INITIAL_SIZE.sp,
                        fontWeight = FontWeight.Medium,
                        color = DesignTokens.textTertiary.current(),
                    )
                }
            }
            if (onAir) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = FriendAvatarSpec.ON_AIR_DOT_END.dp, bottom = FriendAvatarSpec.ON_AIR_DOT_BOTTOM.dp)
                        .size(FriendAvatarSpec.ON_AIR_DOT.dp)
                        .clip(CircleShape)
                        .background(DesignTokens.success.current())
                        .border(FriendAvatarSpec.ON_AIR_DOT_BORDER.dp, ParachordTheme.colors.cardBackground, CircleShape),
                )
            }
            badge?.invoke(this)
            onPlay?.let { ArtPlayButton(name, it, Modifier.align(Alignment.TopEnd).padding(ArtPlayInset)) }
        }
        Spacer(Modifier.height(FriendAvatarSpec.LISTENER_NAME_GAP.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(20.dp)) {
            Text(
                text = name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = DesignTokens.textPrimary.current(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (pinned) {
                Spacer(Modifier.width(4.dp))
                ParachordIcon(AppIcon.Bookmark, contentDescription = "Pinned", size = 12.dp, tint = DesignTokens.accentSoft.current())
            }
        }
        details()
    }
}
