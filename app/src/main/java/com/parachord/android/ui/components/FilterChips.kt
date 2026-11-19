package com.parachord.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parachord.android.ui.theme.ParachordTheme
import com.parachord.android.ui.theme.tokenColor
import com.parachord.shared.ui.DesignTokens
import com.parachord.shared.ui.FilterChipSpec

/**
 * One pick-one chip. [color] is its own colour as `0xRRGGBB` (a service's
 * `ServiceColors.color(id)`, a release type's `DesignTokens.releaseTypeColor`),
 * or null for a neutral chip (All, a period, a category).
 */
data class FilterChipItem(val id: String, val label: String, val color: Int? = null)

/**
 * The app's pick-one filter row, as desktop's `filter-chips.js` (#1371; look
 * and sizes in the shared [FilterChipSpec]). Idle chips are bg-secondary with
 * text-primary, and a coloured chip shows its dot; the picked chip fills with
 * its colour (white or near-black text, whichever reads), or, for a neutral
 * chip, with fill-accent or [pageFill] (the page's `DesignTokens.Page.fill`
 * on a page with its own colour). The row scrolls sideways when it doesn't
 * fit, where desktop wraps.
 */
@Composable
fun FilterChips(
    chips: List<FilterChipItem>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    pageFill: Int? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    val dark = ParachordTheme.isDark
    val idleFill = tokenColor(if (dark) DesignTokens.bgSecondary.dark else DesignTokens.bgSecondary.light)
    val idleText = tokenColor(if (dark) DesignTokens.textPrimary.dark else DesignTokens.textPrimary.light)
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(FilterChipSpec.GAP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chips.forEach { chip ->
            val selected = chip.id == selectedId
            val look = FilterChipSpec.look(selected, chip.color, pageFill)
            Row(
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .clip(CircleShape)
                    .background(look.fill?.let { tokenColor(it) } ?: idleFill)
                    .selectable(selected = selected, role = Role.RadioButton) { onSelect(chip.id) }
                    .defaultMinSize(minHeight = FilterChipSpec.MIN_HEIGHT.dp)
                    .padding(horizontal = FilterChipSpec.PAD_HORIZONTAL.dp, vertical = FilterChipSpec.PAD_VERTICAL.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                look.dot?.let { dot ->
                    Box(Modifier.size(FilterChipSpec.DOT.dp).clip(CircleShape).background(tokenColor(dot)))
                    Spacer(Modifier.width(FilterChipSpec.GAP.dp))
                }
                Text(
                    text = chip.label,
                    fontSize = FilterChipSpec.TEXT_SIZE.sp,
                    fontWeight = FontWeight(FilterChipSpec.TEXT_WEIGHT),
                    color = look.text?.let { tokenColor(it) } ?: idleText,
                    maxLines = 1,
                )
            }
        }
    }
}
