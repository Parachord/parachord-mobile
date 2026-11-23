package com.parachord.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.parachord.android.ui.theme.current
import com.parachord.android.ui.theme.tokenColor
import com.parachord.shared.home.HomeCopy
import com.parachord.shared.recommendations.SHUFFLEUPAGUS_ID
import com.parachord.shared.ui.CardSpec
import com.parachord.shared.ui.DesignTokens
import com.parachord.shared.ui.icons.AppIcon
import kotlinx.coroutines.launch

private val BadgeChip = tokenColor(DesignTokens.ON_COLOR_CHIP, DesignTokens.ON_COLOR_CHIP_ALPHA)
private val BadgeInk = tokenColor(DesignTokens.ON_ART_INK)

/**
 * The reason badge (desktop `reason-badge.js`) on a Shuffleupagus pick, or
 * on a Critical Darlings pick with the review as [text] and "What the critics
 * said" as [label] (desktop gives both Shuffleupagus's mark): a white
 * chip disc over the art with Shuffleupagus's logo in dark ink, the same in
 * both themes; a tap shows why in a tooltip, and TalkBack reads the reason as
 * the badge's state. Bottom-left, since the play disc has the cover's
 * bottom-right on a phone (desktop puts the badge bottom-right and shows Play on hover).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReasonBadge(text: String, modifier: Modifier, label: String = HomeCopy.WHY_SHUFFLEUPAGUS) {
    val tooltip = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text) } },
        state = tooltip,
        modifier = modifier,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(CardSpec.REASON_TOUCH.dp)
                .clip(CircleShape)
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    stateDescription = text
                }
                .hapticClickable { scope.launch { tooltip.show() } },
        ) {
            ReasonMark()
        }
    }
}

/**
 * Shuffleupagus's disc on its own (desktop `ReasonMark`): the white chip with
 * its logo in dark ink. The badges draw it, and the Ask box's heading, so the
 * box and the badges read as one thing. [outlined] adds a border so the white
 * disc shows on a light surface (desktop's mark, off the art).
 */
@Composable
fun ReasonMark(outlined: Boolean = false) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(CardSpec.REASON_DISC.dp)
            .clip(CircleShape)
            .background(BadgeChip)
            .then(
                if (outlined) Modifier.border(1.dp, DesignTokens.borderDefault.current(), CircleShape) else Modifier,
            ),
    ) {
        val logo = remember { serviceLogoVector(SHUFFLEUPAGUS_ID, BadgeInk) }
        if (logo != null) {
            Icon(logo, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(CardSpec.REASON_LOGO.dp))
        } else {
            ParachordIcon(AppIcon.Sparkles, contentDescription = null, size = CardSpec.REASON_LOGO.dp, tint = BadgeInk)
        }
    }
}
