package com.parachord.android.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.parachord.shared.sync.MigrationPlanSummary
import com.parachord.shared.sync.MigrationProviderDiff
import com.parachord.shared.sync.trackLabel

/** UI state for the legacy → N-way migration preview modal (#289 brick #5C). */
sealed interface MigrationPreviewState {
    data object Hidden : MigrationPreviewState
    data object Loading : MigrationPreviewState
    data class Loaded(val summary: MigrationPlanSummary, val recomputed: Boolean = false) : MigrationPreviewState
    data class Error(val message: String) : MigrationPreviewState
}

/**
 * Preview → approve / report / cancel modal for the new-sync cutover. Mirrors
 * desktop's preview flow: shows exactly what flipping to `new` would change
 * (removes prominent), with Accept (recompute-then-flip), "Report a problem"
 * (prefilled GitHub issue), and Cancel. Nothing is armed until Accept.
 */
@Composable
fun MigrationPreviewDialog(
    state: MigrationPreviewState,
    onAccept: () -> Unit,
    onReport: () -> Unit,
    onCancel: () -> Unit,
) {
    if (state is MigrationPreviewState.Hidden) return
    val loaded = state as? MigrationPreviewState.Loaded
    val hasReportable = loaded != null && (loaded.summary.hasChanges || loaded.summary.protectedList.isNotEmpty())
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Use new sync") },
        text = {
            when (state) {
                MigrationPreviewState.Hidden -> Unit
                MigrationPreviewState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Computing what the switch would change…")
                }
                is MigrationPreviewState.Error -> Text("Couldn't compute the preview: ${state.message}")
                is MigrationPreviewState.Loaded -> MigrationPlanBody(state.summary, state.recomputed)
            }
        },
        confirmButton = {
            if (loaded != null) {
                TextButton(onClick = onAccept) {
                    Text(if (loaded.recomputed) "Review & accept" else "Accept changes")
                }
            }
        },
        dismissButton = {
            Row {
                if (hasReportable) TextButton(onClick = onReport) { Text("Report a problem") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun MigrationPlanBody(summary: MigrationPlanSummary, recomputed: Boolean) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        if (recomputed) {
            Text(
                "The plan changed since you last looked — review again before accepting.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
        }
        // Cross-client coexistence nudge (#289): running the old engine on one
        // device and the new engine on another against the SAME shared playlists
        // can churn them until every client matches.
        Text(
            "Using Parachord on another device (desktop, etc.)? Switch it to new sync too — " +
                "running the old and new engines on the same playlists can churn them until all " +
                "your devices match.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        if (!summary.hasChanges && summary.protectedList.isEmpty()) {
            Text("No changes needed — your playlists are already in sync. Switching is a zero-risk cutover.")
            return@Column
        }
        Text(
            "Switching to the new sync engine would make these changes:",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        for (pl in summary.changed) {
            Text(pl.displayName, fontWeight = FontWeight.SemiBold)
            for (provider in pl.providers) ProviderDiffRows(provider)
            Spacer(Modifier.height(8.dp))
        }
        if (summary.protectedList.isNotEmpty()) {
            Text("Protected from a destructive change:", fontWeight = FontWeight.SemiBold)
            for (p in summary.protectedList) {
                val why = if (p.reason == "total-wipe") "would empty the playlist" else "an unexpectedly large drop"
                Text("• ${p.displayName} ($why)", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
        }
        val tail = if (summary.noopCount > 0) " · ${summary.noopCount} unchanged" else ""
        Text(
            "${summary.totalAdds} add, ${summary.totalRemoves} remove across ${summary.changed.size} playlist(s)$tail",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProviderDiffRows(provider: MigrationProviderDiff) {
    Text(
        provider.providerId,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 8.dp),
    )
    // Removes rendered prominently (the thing the user must consciously see).
    for (t in provider.removes) Text(
        "− ${trackLabel(t)}",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 16.dp),
    )
    for (t in provider.adds) Text(
        "+ ${trackLabel(t)}",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(start = 16.dp),
    )
}
