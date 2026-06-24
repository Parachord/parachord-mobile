package com.parachord.android.ui.screens.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.parachord.android.ui.theme.ParachordTheme
import com.parachord.android.ui.theme.PurpleDark
import com.parachord.android.ui.theme.PurpleLight
import com.parachord.shared.sync.PlaylistSyncMode
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/**
 * Per-provider "Configure what syncs" sheet (#266). Reached by tapping a
 * provider's "Configure what syncs" row in Settings → Library Sync. Mirrors
 * iOS's `ProviderSyncConfigSheet` (iosApp `SettingsView.swift`):
 *
 *  - Axis toggles (tracks / albums / artists / playlists), persisted live.
 *    Turning one OFF that still has synced items raises a Keep/Remove prompt
 *    on Done (reuses [SyncViewModel.RemovalConfirmation] + the engine's
 *    `countItemsForProviderAxis` / `removeItemsForProviderAxis`).
 *  - PULL providers (Spotify / Apple Music): a checklist of the user's
 *    IMPORTED playlists. Unchecking one + Done → Keep (detach, row survives)
 *    or Remove (delete the local copy, stays on the service).
 *  - PUSH provider (ListenBrainz): All / Choose / None over the local
 *    push-eligible playlists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSyncConfigSheet(
    providerId: String,
    onDismiss: () -> Unit,
    viewModel: SyncViewModel = koinViewModel(),
) {
    val scope = rememberCoroutineScope()
    LaunchedEffect(providerId) { viewModel.openConfig(providerId) }

    val accent = if (ParachordTheme.isDark) PurpleDark else PurpleLight
    val displayName = providerDisplayNameFor(providerId)
    val loading by viewModel.configLoading.collectAsStateWithLifecycle()
    val axes by viewModel.configAxes.collectAsStateWithLifecycle()
    val pendingAxisRemoval by viewModel.configPendingRemoval.collectAsStateWithLifecycle()
    val pendingPullRemoval by viewModel.pendingPullRemoval.collectAsStateWithLifecycle()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun dismiss() {
        viewModel.closeConfig()
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = { dismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Sync,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            if (loading) {
                Spacer(Modifier.height(32.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accent)
                }
                Spacer(Modifier.height(32.dp))
            } else {
                Spacer(Modifier.height(16.dp))
                ConfigSectionLabel("What syncs")
                for (axis in viewModel.supportedAxes(providerId)) {
                    AxisToggleRow(
                        label = axisLabelFor(providerId, axis),
                        icon = axisIconFor(axis),
                        checked = axis in axes,
                        accent = accent,
                        onCheckedChange = { viewModel.toggleConfigAxis(axis, it) },
                    )
                }

                if ("playlists" in axes) {
                    Spacer(Modifier.height(8.dp))
                    if (viewModel.isPullProvider(providerId)) {
                        PullPlaylistPicker(viewModel, providerId, displayName, accent)
                    } else {
                        PushPlaylistPicker(viewModel, displayName, accent)
                    }
                }

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        scope.launch {
                            // Axis-off keep/remove first, then playlist-deselect
                            // keep/remove (matches iOS ordering). Either raises a
                            // dialog and keeps the sheet open; otherwise persist
                            // + dismiss.
                            if (!viewModel.configNeedsPrompt()) dismiss()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                ) { Text("Done") }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // ── Axis-off keep/remove dialog ──────────────────────────────────
    pendingAxisRemoval?.let { c ->
        val summary = c.droppedAxes.joinToString(", ") {
            "${c.itemCountByAxis[it] ?: 0} ${it.replaceFirstChar { ch -> ch.uppercase() }}"
        }
        AlertDialog(
            onDismissRequest = { viewModel.configCancelAxisRemoval() },
            title = { Text("Stop syncing from $displayName?") },
            text = {
                Text(
                    "You turned off syncing for $summary from $displayName. " +
                        "Remove them from your collection, or keep them? Items also " +
                        "synced from another service are kept either way.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.configConfirmAxisRemove { dismiss() } }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.configConfirmAxisKeep()
                    dismiss()
                }) { Text("Keep") }
            },
        )
    }

    // ── Pull-deselect keep/remove dialog ─────────────────────────────
    pendingPullRemoval?.let { p ->
        val n = p.names.size
        AlertDialog(
            onDismissRequest = { viewModel.configCancelPullRemoval() },
            title = { Text("Stop syncing $n playlist${if (n == 1) "" else "s"}?") },
            text = {
                Text(
                    "These playlists will stop syncing from $displayName. Remove " +
                        "them from Parachord (they stay on $displayName), or keep them " +
                        "in your library? (A playlist also synced from another service " +
                        "keeps syncing there.)",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.configConfirmPullRemove { dismiss() } }) {
                    Text("Remove from Parachord", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.configConfirmPullKeep { dismiss() } }) {
                    Text("Keep local copy")
                }
            },
        )
    }
}

@Composable
private fun PullPlaylistPicker(
    viewModel: SyncViewModel,
    providerId: String,
    displayName: String,
    accent: Color,
) {
    val imported by viewModel.configImported.collectAsStateWithLifecycle()
    val checked by viewModel.configPullChecked.collectAsStateWithLifecycle()

    ConfigSectionLabel("$displayName playlists to sync")
    if (imported.isEmpty()) {
        Text(
            "No $displayName playlists imported yet. Run a sync first.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else {
        for (pl in imported) {
            PlaylistCheckRow(
                name = pl.name,
                trackCount = pl.trackCount,
                checked = pl.id in checked,
                accent = accent,
                onToggle = { viewModel.toggleConfigPullChecked(pl.id) },
            )
        }
    }
}

@Composable
private fun PushPlaylistPicker(
    viewModel: SyncViewModel,
    displayName: String,
    accent: Color,
) {
    val mode by viewModel.configPlaylistMode.collectAsStateWithLifecycle()
    val pushable by viewModel.configPushable.collectAsStateWithLifecycle()
    val selected by viewModel.configSelectedPushIds.collectAsStateWithLifecycle()

    ConfigSectionLabel("Playlists to mirror to $displayName")
    // All / Choose / None segmented selector.
    val modes = listOf(
        PlaylistSyncMode.ALL to "All",
        PlaylistSyncMode.SELECTED to "Choose",
        PlaylistSyncMode.NONE to "None",
    )
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        modes.forEachIndexed { index, (m, label) ->
            SegmentedButton(
                selected = mode == m,
                onClick = { viewModel.setConfigPlaylistMode(m) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = modes.size),
                colors = SegmentedButtonDefaults.colors(activeContainerColor = accent.copy(alpha = 0.25f)),
            ) { Text(label) }
        }
    }

    if (mode == PlaylistSyncMode.SELECTED) {
        Spacer(Modifier.height(8.dp))
        if (pushable.isEmpty()) {
            Text(
                "No playlists eligible to mirror to $displayName yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            for (pl in pushable) {
                PlaylistCheckRow(
                    name = pl.name,
                    trackCount = pl.trackCount,
                    checked = pl.id in selected,
                    accent = accent,
                    onToggle = { viewModel.toggleConfigPushSelected(pl.id) },
                )
            }
        }
    }
}

@Composable
private fun PlaylistCheckRow(
    name: String,
    trackCount: Int,
    checked: Boolean,
    accent: Color,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (checked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (checked) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$trackCount track${if (trackCount == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AxisToggleRow(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    checked: Boolean,
    accent: Color,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = accent,
                checkedThumbColor = Color.White,
            ),
        )
    }
}

@Composable
private fun ConfigSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
    )
}

private fun providerDisplayNameFor(providerId: String): String = when (providerId) {
    "applemusic" -> "Apple Music"
    "spotify" -> "Spotify"
    "listenbrainz" -> "ListenBrainz"
    else -> providerId.replaceFirstChar { it.uppercase() }
}

/** Provider-native axis label (Apple Music names its library differently). */
private fun axisLabelFor(providerId: String, axis: String): String {
    val isApple = providerId == "applemusic"
    return when (axis) {
        "tracks" -> if (isApple) "Library Songs" else "Liked Songs"
        "albums" -> if (isApple) "Library Albums" else "Saved Albums"
        "artists" -> if (isApple) "Library Artists" else "Followed Artists"
        "playlists" -> "Playlists"
        else -> axis.replaceFirstChar { it.uppercase() }
    }
}

@Suppress("DEPRECATION")
private fun axisIconFor(axis: String): androidx.compose.ui.graphics.vector.ImageVector = when (axis) {
    "tracks" -> Icons.Default.Favorite
    "albums" -> Icons.Default.Album
    "artists" -> Icons.Default.Person
    "playlists" -> Icons.Default.QueueMusic
    else -> Icons.Default.Sync
}
