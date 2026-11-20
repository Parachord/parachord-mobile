package com.parachord.android.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.parachord.android.ui.components.ParachordIcon
import com.parachord.android.ui.theme.FillAccent
import com.parachord.android.ui.theme.current
import com.parachord.android.ui.theme.tokenColor
import com.parachord.shared.home.HomeCopy
import com.parachord.shared.home.HomeSetup
import com.parachord.shared.home.HomeSetupItem
import com.parachord.shared.home.HomeState
import com.parachord.shared.ui.DesignTokens
import com.parachord.shared.ui.icons.AppIcon

private val OnFill = tokenColor(DesignTokens.ON_ACCENT)

/**
 * Home's header (desktop `home-page.js`): the greeting (28 / 700) over the
 * freshest playlist's change line, then the "Finish setting up" pill and
 * "Play Collection Radio". Desktop puts the two buttons on the right; a phone
 * wraps them under the greeting.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeHeader(
    state: HomeState,
    onCollectionRadio: () -> Unit,
    onSetupAction: (action: String) -> Unit,
    onDismissSetupItem: (id: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val setup = state.setup?.takeIf { it.mode == "setup" }
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = DesignTokens.SCREEN_GUTTER.dp)) {
        Text(
            text = state.greeting,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = DesignTokens.textPrimary.current(),
            modifier = Modifier.semantics { heading() },
        )
        state.changeLine?.let { line ->
            Spacer(Modifier.size(4.dp))
            Text(text = line, fontSize = 14.sp, color = DesignTokens.textSecondary.current())
        }
        if (setup != null || state.hasCollection) {
            Spacer(Modifier.size(16.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (setup != null) SetupPill(setup, onSetupAction, onDismissSetupItem)
                if (state.hasCollection) CollectionRadioButton(onCollectionRadio)
            }
        }
    }
}

/** Desktop's `CollectionRadioButton`: the accent fill, a round Play mark and the label. */
@Composable
fun CollectionRadioButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = FillAccent,
        contentColor = OnFill,
        modifier = modifier.heightIn(min = 40.dp),
    ) {
        // Desktop's padding (6 16 6 6) and 10 gap (collection-radio-button.js), kept
        // as desktop has them: 6 is on the shared spacing scale; the mark sits 6
        // from the pill's edge so it stays concentric with it.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(28.dp).background(OnFill, CircleShape),
            ) {
                ParachordIcon(AppIcon.Play, contentDescription = null, size = 12.dp, tint = FillAccent)
            }
            Spacer(Modifier.width(10.dp))
            Text(text = HomeCopy.PLAY_COLLECTION_RADIO, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** "Finish setting up · N of M", which opens the checklist as a bottom sheet (desktop's popover). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupPill(
    setup: HomeSetup,
    onAction: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    Surface(
        onClick = { open = true },
        shape = CircleShape,
        color = DesignTokens.cardBg.current(),
        contentColor = DesignTokens.textPrimary.current(),
        border = BorderStroke(1.dp, DesignTokens.borderDefault.current()),
        modifier = Modifier.heightIn(min = 40.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(text = HomeCopy.setupPill(setup.done, setup.total), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
    if (open) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { open = false },
            sheetState = sheetState,
            containerColor = DesignTokens.bgElevated.current(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = DesignTokens.SCREEN_GUTTER.dp)
                    .padding(bottom = 24.dp)
                    .navigationBarsPadding(),
            ) {
                Text(
                    text = HomeCopy.SETUP_DIALOG,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = DesignTokens.textPrimary.current(),
                    modifier = Modifier.semantics { heading() },
                )
                Spacer(Modifier.size(12.dp))
                setup.items.forEach { item ->
                    SetupItemRow(
                        item = item,
                        onAction = { open = false; onAction(item.action) },
                        onDismiss = { onDismiss(item.id) },
                    )
                }
            }
        }
        // When the last item goes, the session drops the pill (mode "none"),
        // and the sheet leaves with it.
    }
}

@Composable
private fun SetupItemRow(item: HomeSetupItem, onAction: () -> Unit, onDismiss: () -> Unit) {
    val primary = DesignTokens.textPrimary.current()
    val secondary = DesignTokens.textSecondary.current()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) {
            if (item.done) {
                ParachordIcon(AppIcon.Check, contentDescription = null, size = 16.dp, tint = DesignTokens.success.current())
            } else {
                // Desktop's "○": an open ring.
                Box(Modifier.size(12.dp).border(1.5.dp, secondary, CircleShape))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = item.label,
            fontSize = 14.sp,
            color = if (item.done) secondary else primary,
            textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None,
            modifier = Modifier.weight(1f),
        )
        if (!item.done) {
            OutlinedButton(
                onClick = onAction,
                shape = RoundedCornerShape(DesignTokens.RADIUS_BUTTON.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.semantics { contentDescription = HomeCopy.setUpLabel(item.label) },
            ) {
                Text(text = HomeCopy.SET_UP, fontSize = 13.sp, color = primary)
            }
            IconButton(onClick = onDismiss) {
                ParachordIcon(AppIcon.X, contentDescription = HomeCopy.dismissLabel(item.label), size = 16.dp, tint = secondary)
            }
        }
    }
}
