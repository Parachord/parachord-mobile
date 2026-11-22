package com.parachord.android.ui.components

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.parachord.shared.ui.LayoutRules

/**
 * Compose's side of the shared [LayoutRules]: a phone keeps its sizes, a tablet
 * or a phone in landscape fills its width. iOS's `PCLayout` reads the same rules.
 */

/** The window's width in dp. */
@Composable
fun layoutWidthDp(): Double = LocalConfiguration.current.screenWidthDp.toDouble()

/** Each card's width in a row of [cards] across the window, inside [gutter] on both sides, [gap] apart. */
@Composable
fun rowCardWidth(cards: Int, gutter: Dp, gap: Dp, available: Double = layoutWidthDp() - 2 * gutter.value): Dp =
    LayoutRules.rowCardWidth(available = available, cards = cards, gap = gap.value.toDouble()).dp

/**
 * A grid's columns for the window: as many cards of at least [LayoutRules.GRID_CARD_MIN]
 * (or [LayoutRules.GRID_CIRCLE_MIN] for artist [circles]) as fit, never fewer than the
 * phone's [minColumns].
 */
@Composable
fun adaptiveGridCells(
    gutter: Dp = 16.dp,
    gap: Dp = 12.dp,
    circles: Boolean = false,
    minColumns: Int = if (circles) 3 else 2,
): GridCells = GridCells.Fixed(
    LayoutRules.gridColumns(
        available = layoutWidthDp() - 2 * gutter.value,
        gap = gap.value.toDouble(),
        minCard = if (circles) LayoutRules.GRID_CIRCLE_MIN else LayoutRules.GRID_CARD_MIN,
        minColumns = minColumns,
    ),
)
