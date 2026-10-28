package com.parachord.android.ui.screens.friends

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import com.parachord.android.ui.components.hapticClickable
import com.parachord.android.ui.theme.ParachordTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.koin.androidx.compose.koinViewModel
import coil.compose.SubcomposeAsyncImage
import com.parachord.android.data.db.entity.FriendEntity
import com.parachord.shared.model.Resource
import com.parachord.android.ui.components.AlbumArtCard
import com.parachord.android.ui.components.ModalBg
import com.parachord.android.ui.components.ModalTextActive
import com.parachord.android.ui.components.ModalTextPrimary
import com.parachord.android.ui.components.ModalTextSecondary

private val OnAirGreen = Color(0xFF22C55E)
private val LastFmRed = Color(0xFFD51007)
private val MiniPlaybarBgLight = Color(0xF21F2937)
private val MiniPlaybarBgDark = Color(0xF2262626)
private val ListenBrainzOrange = Color(0xFFE8702A)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    onBack: () -> Unit,
    onNavigateToFriend: (String) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: FriendsViewModel = koinViewModel(),
) {
    val friends by viewModel.friends.collectAsState()
    val showAddDialog by viewModel.showAddDialog.collectAsState()
    val addFriendInput by viewModel.addFriendInput.collectAsState()
    val addFriendState by viewModel.addFriendState.collectAsState()
    var pendingDeleteFriend by remember { mutableStateOf<FriendEntity?>(null) }

    pendingDeleteFriend?.let { friend ->
        AlertDialog(
            onDismissRequest = { pendingDeleteFriend = null },
            containerColor = ModalBg,
            titleContentColor = ModalTextActive,
            textContentColor = ModalTextPrimary,
            title = { Text("Remove Friend") },
            text = { Text("Are you sure you want to remove ${friend.displayName}?") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeFriend(friend.id)
                    pendingDeleteFriend = null
                }) {
                    Text("Remove", color = Color(0xFFEF4444))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteFriend = null }) {
                    Text("Cancel", color = ModalTextPrimary)
                }
            },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = "FRIENDS",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Light,
                        letterSpacing = 0.2.em,
                    ),
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = { viewModel.openAddDialog() }) {
                    Icon(Icons.Filled.PersonAdd, contentDescription = "Add friend")
                }
            },
            windowInsets = WindowInsets(0),
        )

        if (friends.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.People,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "No friends yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextButton(onClick = { viewModel.openAddDialog() }) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add a friend")
                    }
                }
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(friends, key = { it.id }) { friend ->
                    FriendRow(
                        friend = friend,
                        onClick = { onNavigateToFriend(friend.id) },
                        onDelete = { pendingDeleteFriend = friend },
                        onTogglePin = { viewModel.togglePin(friend) },
                    )
                }
            }
        }
    }

    // Add Friend Dialog
    if (showAddDialog) {
        AddFriendDialog(
            input = addFriendInput,
            state = addFriendState,
            onInputChanged = viewModel::setAddFriendInput,
            onAdd = viewModel::addFriend,
            onDismiss = viewModel::dismissAddDialog,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FriendRow(
    friend: FriendEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit = {},
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
            }
            false // always snap back; dialog handles the delete
        },
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.error)
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onError,
                )
            }
        },
        enableDismissFromStartToEnd = false,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .hapticClickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Avatar with on-air indicator
            Box {
                AlbumArtCard(
                    artworkUrl = friend.avatarUrl,
                    size = 48.dp,
                    cornerRadius = 24.dp,
                    placeholderName = friend.displayName,
                )
                if (friend.isOnAir) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(OnAirGreen),
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = friend.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    ServiceBadge(service = friend.service)
                }
                if (friend.isOnAir && friend.cachedTrackName != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    FriendsMiniPlaybar(
                        trackName = friend.cachedTrackName!!,
                        artistName = friend.cachedTrackArtist,
                        artworkUrl = friend.cachedTrackArtworkUrl,
                    )
                } else if (friend.hidesListening) {
                    // Never on-air; say why instead of a stale track (desktop parachord#1140).
                    Text(
                        text = "Listening hidden",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                } else if (friend.cachedTrackName != null) {
                    Text(
                        text = buildString {
                            append(friend.cachedTrackName)
                            friend.cachedTrackArtist?.let { append("  ·  $it") }
                            if (friend.cachedTrackTimestamp > 0) {
                                append("  ·  ")
                                append(formatTimeAgo(friend.cachedTrackTimestamp))
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Pin to sidebar toggle
            IconButton(
                onClick = onTogglePin,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = if (friend.pinnedToSidebar) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = if (friend.pinnedToSidebar) "Unpin from sidebar" else "Pin to sidebar",
                    tint = if (friend.pinnedToSidebar) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

private fun formatTimeAgo(timestampSeconds: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - timestampSeconds
    return when {
        diff < 60 -> "Just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        diff < 172800 -> "Yesterday"
        diff < 604800 -> "${diff / 86400}d ago"
        else -> "${diff / 604800}w ago"
    }
}

/**
 * Mini playbar pill for on-air friends — matches the sidebar FriendMiniPlaybar.
 * Dark pill background, mini album art, marquee scrolling text, green on-air dot.
 * Only shown for friends currently on-air; offline friends get plain muted text instead.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FriendsMiniPlaybar(
    trackName: String,
    artistName: String?,
    artworkUrl: String?,
) {
    val isDark = ParachordTheme.isDark
    val pillBg = if (isDark) MiniPlaybarBgDark else MiniPlaybarBgLight
    val pillShape = RoundedCornerShape(4.dp)
    val textColor = Color(0xFFD1D5DB) // gray-300

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(pillShape)
            .background(pillBg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Mini album art (24×24, rounded-left only)
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (!artworkUrl.isNullOrBlank()) {
                SubcomposeAsyncImage(
                    model = artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(24.dp),
                    loading = { FriendsMiniArtFallback() },
                    error = { FriendsMiniArtFallback() },
                )
            } else {
                FriendsMiniArtFallback()
            }
        }

        // Track info — marquee scroll matching sidebar
        Text(
            text = buildString {
                append(trackName)
                artistName?.let { append("  ·  $it") }
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 24.sp,
            color = textColor,
            maxLines = 1,
            overflow = TextOverflow.Clip,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .basicMarquee(
                    iterations = Int.MAX_VALUE,
                    initialDelayMillis = 1000,
                    velocity = 30.dp,
                ),
        )

        // On-air green dot
        Box(
            modifier = Modifier
                .padding(end = 6.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(OnAirGreen),
        )
    }
}

@Composable
private fun FriendsMiniArtFallback() {
    Box(
        modifier = Modifier
            .size(24.dp)
            .background(Color(0xFF4B5563)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = Color(0xFF9CA3AF),
            modifier = Modifier.size(12.dp),
        )
    }
}

@Composable
private fun ServiceBadge(service: String) {
    val (text, color) = when (service) {
        "lastfm" -> "Last.fm" to LastFmRed
        "listenbrainz" -> "LB" to ListenBrainzOrange
        else -> service to MaterialTheme.colorScheme.outline
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(
                color = color.copy(alpha = 0.12f),
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun AddFriendDialog(
    input: String,
    state: Resource<Unit>?,
    onInputChanged: (String) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ModalBg,
        titleContentColor = ModalTextActive,
        textContentColor = ModalTextPrimary,
        title = { Text("Add Friend") },
        text = {
            Column {
                Text(
                    text = "Enter a Last.fm or ListenBrainz username, or paste a profile URL",
                    style = MaterialTheme.typography.bodySmall,
                    color = ModalTextSecondary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChanged,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Username or profile URL") },
                    singleLine = true,
                    isError = state is Resource.Error,
                    supportingText = if (state is Resource.Error) {
                        { Text(state.message, color = MaterialTheme.colorScheme.error) }
                    } else {
                        null
                    },
                )
                if (state is Resource.Loading) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Validating user...", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onAdd,
                enabled = input.isNotBlank() && state !is Resource.Loading,
            ) {
                Text("Add Friend", color = Color(0xFF7C3AED))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = ModalTextPrimary)
            }
        },
    )
}
