package com.parachord.android.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parachord.android.ui.theme.composeColor
import com.parachord.shared.ui.DesignTokens
import com.parachord.shared.ui.SectionHeaderSpec

/** A section's in-place link ("See all" / "Show less" on the section itself). */
data class SectionAction(val label: String, val onClick: () -> Unit)

/**
 * The section title over a row or grid of content, as desktop's
 * `section-header.js` (#1370; spec in the shared [SectionHeaderSpec]):
 * 20 / 700 text-primary in sentence case, an optional grey subtitle, and on
 * the right an optional link. With [page] the link is "See all in <page>" in
 * that page's colour and [onSeeAll] opens it; with [action] it acts in place,
 * in the accent. [sub] makes it a subsection title (16 / 600).
 * [horizontalPadding] is the screen gutter; 0 inside a container that has its own.
 *
 * Page heroes, dialog titles, Settings' group labels ([SettingsGroupLabel])
 * and drawer labels are other elements.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    page: DesignTokens.Page? = null,
    onSeeAll: (() -> Unit)? = null,
    action: SectionAction? = null,
    sub: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    horizontalPadding: Dp = 16.dp,
) {
    Row(
        modifier = modifier.padding(start = horizontalPadding, end = horizontalPadding, top = 16.dp, bottom = SectionHeaderSpec.GAP_BELOW.dp),
        horizontalArrangement = Arrangement.spacedBy(SectionHeaderSpec.ROW_GAP.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = (if (sub) SectionHeaderSpec.SUB_TITLE_SIZE else SectionHeaderSpec.TITLE_SIZE).sp,
                fontWeight = FontWeight(if (sub) SectionHeaderSpec.SUB_TITLE_WEIGHT else SectionHeaderSpec.TITLE_WEIGHT),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Spacer(Modifier.height(SectionHeaderSpec.SUBTITLE_GAP.dp))
                Text(
                    text = subtitle,
                    fontSize = SectionHeaderSpec.SUBTITLE_SIZE.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
        val linkLabel = when {
            page != null && onSeeAll != null -> SectionHeaderSpec.seeAllLabel(page)
            action != null -> action.label
            else -> null
        }
        if (linkLabel != null) {
            val linkColor = if (page != null && page != DesignTokens.Page.HOME) page.composeColor
            else MaterialTheme.colorScheme.primary
            Text(
                text = linkLabel,
                fontSize = SectionHeaderSpec.LINK_SIZE.sp,
                fontWeight = FontWeight(SectionHeaderSpec.LINK_WEIGHT),
                color = linkColor,
                maxLines = 1,
                modifier = Modifier.hapticClickable { (if (page != null) onSeeAll else action?.onClick)?.invoke() },
            )
        }
    }
}

/**
 * Settings' small uppercase group label (a settings heading, not a section
 * title): accent, semibold, wide tracking.
 */
@Composable
fun SettingsGroupLabel(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title.uppercase(), // applied at render; the source stays sentence case
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { heading() },
    )
}
