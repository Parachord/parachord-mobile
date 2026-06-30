package com.parachord.android.ui.screens.sync

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
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
import com.parachord.android.ui.screens.playlists.PlaylistSort
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
 *  - PUSH provider (ListenBrainz): a plain checklist of push-eligible
 *    playlists, seeded from the live mirrors (sync_playlist_link).
 *    Unchecking one + Done → a single-action "Stop syncing" prompt
 *    (local-only unlink; remote untouched). Checking one admits it to the
 *    next sync's push.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderSyncConfigSheet(
    providerId: String,
    onDismiss: () -> Unit,
    onDone: (() -> Unit)? = null,
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
    val pendingPushRemoval by viewModel.pendingPushRemoval.collectAsStateWithLifecycle()

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
                    UnifiedChannelPicker(viewModel, providerId, displayName, accent)
                }

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        scope.launch {
                            // Axis-off keep/remove first, then playlist-deselect
                            // keep/remove (matches iOS ordering). Either raises a
                            // dialog and keeps the sheet open; otherwise confirm
                            // (enable, if this was a toggle-on gate) + dismiss.
                            if (!viewModel.configNeedsPrompt()) {
                                onDone?.invoke()
                                dismiss()
                            }
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

    // ── Push-deselect "Stop syncing" dialog (single-action) ──────────
    pendingPushRemoval?.let { p ->
        val n = p.names.size
        AlertDialog(
            onDismissRequest = { viewModel.configCancelPushRemoval() },
            title = { Text("Stop syncing $n playlist${if (n == 1) "" else "s"} to $displayName?") },
            text = {
                Text(
                    "These playlists will stop syncing to $displayName. They stay on " +
                        "$displayName (nothing is deleted there) and remain in your " +
                        "Parachord library — they're just removed from Parachord's sync " +
                        "to $displayName.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.configConfirmPushStop { dismiss() } }) {
                    Text("Stop syncing", color = accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.configCancelPushRemoval() }) {
                    Text("Cancel")
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
    val pushable by viewModel.configPushable.collectAsStateWithLifecycle()
    val checked by viewModel.configPushChecked.collectAsStateWithLifecycle()

    // Plain checklist, seeded from the live mirrors (sync_playlist_link). Each
    // row is "mirrored to $displayName: yes/no". Symmetric with the pull picker.
    ConfigSectionLabel("Playlists mirrored to $displayName")
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
                checked = pl.id in checked,
                accent = accent,
                onToggle = { viewModel.toggleConfigPushChecked(pl.id) },
            )
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

// ── Unified per-provider channel picker ──────────────────────────────
// One picker for ALL providers, reading the same authoritative channel state as
// the per-playlist Sync context-menu. Every playlist that CAN sync with the
// provider is shown; checked = currently synced; a "mirror" chip marks playlists
// pushed UP to this provider (their local row is another provider's). Toggling
// goes through SyncViewModel.toggleConfigChannel → setChannel (detach on disable,
// never delete).
@Composable
private fun UnifiedChannelPicker(
    viewModel: SyncViewModel,
    providerId: String,
    displayName: String,
    accent: Color,
) {
    val playlists by viewModel.configChannelPlaylists.collectAsStateWithLifecycle()
    val sort by viewModel.configChannelSort.collectAsStateWithLifecycle()

    Row(
        modifier = Modifier.fillMaxWidth().padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ConfigSectionLabel("$displayName playlists")
        Spacer(Modifier.weight(1f))
        PlaylistSortDropdown(sort, accent) { viewModel.setConfigChannelSort(it) }
    }
    if (playlists.isEmpty()) {
        Text(
            "No playlists available for $displayName yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    } else {
        for (pl in playlists) {
            RemotePlaylistCheckRow(
                name = pl.name,
                ownerName = pl.ownerName,
                trackCount = pl.trackCount,
                checked = pl.enabled,
                originLabel = pl.originLabel,
                notImported = pl.notImported,
                accent = accent,
                onToggle = { viewModel.toggleConfigChannel(pl.localId) },
            )
        }
    }
}

@Composable
private fun PlaylistSortDropdown(
    current: PlaylistSort,
    accent: Color,
    onSelect: (PlaylistSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 8.dp),
        ) {
            Text(current.label, color = accent, style = MaterialTheme.typography.labelMedium)
            Icon(Icons.Default.ArrowDropDown, contentDescription = "Sort", tint = accent)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (opt in PlaylistSort.values()) {
                DropdownMenuItem(
                    text = { Text(opt.label) },
                    onClick = { onSelect(opt); expanded = false },
                    trailingIcon = {
                        if (opt == current) Icon(Icons.Default.Check, contentDescription = null, tint = accent)
                    },
                )
            }
        }
    }
}

@Composable
private fun RemotePlaylistCheckRow(
    name: String,
    ownerName: String?,
    trackCount: Int,
    checked: Boolean,
    originLabel: String?,
    notImported: Boolean,
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (originLabel != null) {
                    Spacer(Modifier.width(6.dp))
                    // Match the Playlists-tab source chips (PlaylistSourceChip):
                    // per-provider brand color at 12% alpha.
                    val chipColor = originChipColor(originLabel)
                    Text(
                        originLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = chipColor,
                        modifier = Modifier
                            .background(chipColor.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            val subtitle = buildString {
                if (notImported) append("Tap to import")
                else append("$trackCount track${if (trackCount == 1) "" else "s"}")
                if (!ownerName.isNullOrBlank()) append(" · $ownerName")
            }
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Origin-chip brand colors — kept in lockstep with the Playlists tab's
 *  PlaylistSourceChip (SpotifyGreen / AppleMusicRed / LB orange). */
private fun originChipColor(label: String): Color = when (label) {
    "Spotify" -> Color(0xFF1DB954)
    "Apple Music" -> Color(0xFFFA243C)
    "ListenBrainz" -> Color(0xFFEB743B)
    "Hosted" -> Color(0xFF3B82F6)
    else -> Color(0xFF6B7280) // Local / unknown
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
