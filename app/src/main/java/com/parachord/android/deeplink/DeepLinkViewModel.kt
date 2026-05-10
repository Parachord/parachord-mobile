package com.parachord.android.deeplink

import android.net.Uri
import com.parachord.shared.platform.Log
import com.parachord.shared.deeplink.DeepLinkAction
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.parachord.android.ai.AiChatService
import com.parachord.android.data.db.entity.TrackEntity
import com.parachord.android.playback.PlaybackController
import com.parachord.android.playback.QueueManager
import com.parachord.android.playlist.PlaylistImportManager
import com.parachord.android.resolver.ResolverManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "DeepLinkViewModel"

/**
 * Navigation events emitted by deep link processing.
 * MainActivity observes these and calls navController.navigate().
 */
sealed class DeepLinkNavEvent {
    data class Artist(val name: String) : DeepLinkNavEvent()
    data class Album(val title: String, val artist: String) : DeepLinkNavEvent()
    data class Playlist(val id: String) : DeepLinkNavEvent()
    data object Home : DeepLinkNavEvent()
    data class Library(val tab: Int) : DeepLinkNavEvent()
    data object History : DeepLinkNavEvent()
    data class Friend(val id: String) : DeepLinkNavEvent()
    data object Recommendations : DeepLinkNavEvent()
    data object Charts : DeepLinkNavEvent()
    data object CriticalDarlings : DeepLinkNavEvent()
    data object Playlists : DeepLinkNavEvent()
    data object Settings : DeepLinkNavEvent()
    data class Search(val query: String?) : DeepLinkNavEvent()
    data class Chat(val prompt: String?) : DeepLinkNavEvent()
    data class Toast(val message: String) : DeepLinkNavEvent()
}

/**
 * A pending confirmation for a deep link action that needs user approval.
 * Matches the desktop's "An external link wants to..." dialog pattern.
 */
data class DeepLinkConfirmation(
    val title: String,
    val message: String,
    val action: DeepLinkAction,
)

class DeepLinkViewModel constructor(
    private val deepLinkHandler: DeepLinkHandler,
    private val externalLinkResolver: ExternalLinkResolver,
    private val playbackController: PlaybackController,
    private val queueManager: QueueManager,
    private val resolverManager: ResolverManager,
    private val playlistImportManager: PlaylistImportManager,
    private val chatService: AiChatService,
) : ViewModel() {

    private val _navEvents = MutableSharedFlow<DeepLinkNavEvent>()
    val navEvents: SharedFlow<DeepLinkNavEvent> = _navEvents.asSharedFlow()

    private val _pendingConfirmation = MutableStateFlow<DeepLinkConfirmation?>(null)
    val pendingConfirmation: StateFlow<DeepLinkConfirmation?> = _pendingConfirmation.asStateFlow()

    fun handleUri(uri: Uri) {
        val action = deepLinkHandler.parse(uri)
        // Actions that need user approval before executing
        val confirmation = buildConfirmation(action)
        if (confirmation != null) {
            _pendingConfirmation.value = confirmation
        } else {
            executeAction(action)
        }
    }

    /** User confirmed the pending action. */
    fun confirmPendingAction() {
        val pending = _pendingConfirmation.value ?: return
        _pendingConfirmation.value = null
        executeAction(pending.action)
    }

    /** User dismissed the confirmation dialog. */
    fun dismissPendingAction() {
        _pendingConfirmation.value = null
    }

    /**
     * Build a confirmation prompt for actions that need user approval.
     * Returns null for actions that can execute immediately (navigation, control, etc.).
     * Matches the desktop's "An external link wants to..." pattern.
     */
    private fun buildConfirmation(action: DeepLinkAction): DeepLinkConfirmation? {
        return when (action) {
            is DeepLinkAction.NavigateChat -> {
                val prompt = action.prompt
                if (prompt != null) {
                    DeepLinkConfirmation(
                        title = "Send to Chat",
                        message = "An external link wants to send this message to chat:\n\n\"${prompt.take(500)}\"",
                        action = action,
                    )
                } else null // No prompt → just navigate, no confirmation needed
            }
            is DeepLinkAction.QueueAdd -> DeepLinkConfirmation(
                title = "Add to Queue",
                message = "An external link wants to add a track to your queue:\n\n${action.artist} – ${action.title}",
                action = action,
            )
            is DeepLinkAction.QueueClear -> DeepLinkConfirmation(
                title = "Clear Queue",
                message = "An external link wants to clear your queue.",
                action = action,
            )
            is DeepLinkAction.ImportPlaylist -> DeepLinkConfirmation(
                title = "Import Playlist",
                message = "An external link wants to import a playlist from:\n\n${action.url}",
                action = action,
            )
            // No confirmation prompt for any `play/*` family action — they're
            // read-equivalent to clicking a Spotify/Apple Music share link
            // (local playback only, no library writes, easily reversed by
            // skip/pause). Dropped from the previously-prompted single-track
            // `Play` for consistency with `PlayAlbum`/`PlayPlaylist`/`PlayRadio`.
            // (Issue #120 item E.)
            else -> null
        }
    }

    private fun executeAction(action: DeepLinkAction) {
        viewModelScope.launch {
            when (action) {
                is DeepLinkAction.OAuthCallback -> { /* handled by OAuthManager in MainActivity */ }

                // ── Playback ──────────────────────────────────────────────

                is DeepLinkAction.Play -> {
                    val sources = resolverManager.resolve(
                        "${action.artist} ${action.title}",
                        targetTitle = action.title,
                        targetArtist = action.artist,
                    )
                    val source = sources.firstOrNull()
                    if (source != null) {
                        val trackEntity = TrackEntity(
                            id = source.url,
                            title = action.title,
                            artist = action.artist,
                            resolver = source.resolver,
                            sourceType = source.sourceType,
                            sourceUrl = source.url,
                            spotifyId = source.spotifyId,
                            spotifyUri = source.spotifyUri,
                            soundcloudId = source.soundcloudId,
                            appleMusicId = source.appleMusicId,
                        )
                        playbackController.playTrack(trackEntity)
                    } else {
                        _navEvents.emit(DeepLinkNavEvent.Toast("Could not find: ${action.artist} - ${action.title}"))
                    }
                }

                is DeepLinkAction.Control -> {
                    when (action.action) {
                        "pause" -> playbackController.togglePlayPause()
                        "resume", "play" -> playbackController.togglePlayPause()
                        "skip", "next" -> playbackController.skipNext()
                        "previous" -> playbackController.skipPrevious()
                    }
                }

                is DeepLinkAction.QueueAdd -> {
                    val sources = resolverManager.resolve(
                        "${action.artist} ${action.title}",
                        targetTitle = action.title,
                        targetArtist = action.artist,
                    )
                    val source = sources.firstOrNull()
                    if (source != null) {
                        val trackEntity = TrackEntity(
                            id = source.url,
                            title = action.title,
                            artist = action.artist,
                            album = action.album,
                            resolver = source.resolver,
                            sourceType = source.sourceType,
                            sourceUrl = source.url,
                            spotifyId = source.spotifyId,
                            spotifyUri = source.spotifyUri,
                            soundcloudId = source.soundcloudId,
                            appleMusicId = source.appleMusicId,
                        )
                        queueManager.addToQueue(listOf(trackEntity))
                        _navEvents.emit(DeepLinkNavEvent.Toast("Added to queue: ${action.title}"))
                    } else {
                        _navEvents.emit(DeepLinkNavEvent.Toast("Could not find: ${action.artist} - ${action.title}"))
                    }
                }

                is DeepLinkAction.QueueClear -> {
                    queueManager.clearQueue()
                    _navEvents.emit(DeepLinkNavEvent.Toast("Queue cleared"))
                }

                is DeepLinkAction.Shuffle -> {
                    if (queueManager.shuffleEnabled != action.enabled) {
                        queueManager.toggleShuffle()
                    }
                    _navEvents.emit(DeepLinkNavEvent.Toast("Shuffle ${if (action.enabled) "on" else "off"}"))
                }

                is DeepLinkAction.Volume -> {
                    // Volume control not available on Android (media volume is system-level)
                    Log.d(TAG, "Volume deep link ignored on Android (level=${action.level})")
                }

                // ── Navigation ────────────────────────────────────────────

                is DeepLinkAction.NavigateHome -> _navEvents.emit(DeepLinkNavEvent.Home)

                is DeepLinkAction.NavigateArtist -> _navEvents.emit(DeepLinkNavEvent.Artist(action.name))

                is DeepLinkAction.NavigateAlbum -> _navEvents.emit(DeepLinkNavEvent.Album(action.title, action.artist))

                is DeepLinkAction.NavigateLibrary -> {
                    val tab = when (action.tab) {
                        "albums" -> 1
                        "artists" -> 2
                        else -> 0
                    }
                    _navEvents.emit(DeepLinkNavEvent.Library(tab))
                }

                is DeepLinkAction.NavigateHistory -> _navEvents.emit(DeepLinkNavEvent.History)

                is DeepLinkAction.NavigateFriend -> _navEvents.emit(DeepLinkNavEvent.Friend(action.id))

                is DeepLinkAction.NavigateRecommendations -> _navEvents.emit(DeepLinkNavEvent.Recommendations)

                is DeepLinkAction.NavigateCharts -> _navEvents.emit(DeepLinkNavEvent.Charts)

                is DeepLinkAction.NavigateCriticalDarlings -> _navEvents.emit(DeepLinkNavEvent.CriticalDarlings)

                is DeepLinkAction.NavigatePlaylists -> _navEvents.emit(DeepLinkNavEvent.Playlists)

                is DeepLinkAction.NavigatePlaylist -> _navEvents.emit(DeepLinkNavEvent.Playlist(action.id))

                is DeepLinkAction.NavigateSettings -> _navEvents.emit(DeepLinkNavEvent.Settings)

                is DeepLinkAction.NavigateSearch -> _navEvents.emit(DeepLinkNavEvent.Search(action.query))

                is DeepLinkAction.NavigateChat -> {
                    if (action.prompt != null) {
                        chatService.setPendingChatPrompt(action.prompt)
                    }
                    _navEvents.emit(DeepLinkNavEvent.Chat(action.prompt))
                }

                // ── Import ────────────────────────────────────────────────

                is DeepLinkAction.ImportPlaylist -> {
                    try {
                        val result = playlistImportManager.importFromUrl(action.url)
                        _navEvents.emit(DeepLinkNavEvent.Playlist(result.playlistId))
                        _navEvents.emit(DeepLinkNavEvent.Toast("Imported '${result.playlistName}' (${result.trackCount} tracks)"))
                    } catch (e: Exception) {
                        _navEvents.emit(DeepLinkNavEvent.Toast("Import failed: ${e.message}"))
                    }
                }

                // ── External URL lookups ──────────────────────────────────

                is DeepLinkAction.SpotifyTrack -> resolveAndPlaySpotifyTrack(action.trackId)
                is DeepLinkAction.SpotifyAlbum -> resolveAndNavigateSpotifyAlbum(action.albumId)
                is DeepLinkAction.SpotifyPlaylist -> resolveAndNavigateSpotifyPlaylist(action.playlistId)
                is DeepLinkAction.SpotifyArtist -> resolveAndNavigateSpotifyArtist(action.artistId)
                is DeepLinkAction.AppleMusicSong -> resolveAndPlayAppleMusicSong(action.songId)
                is DeepLinkAction.AppleMusicAlbum -> resolveAndNavigateAppleMusicAlbum(action.albumId)
                is DeepLinkAction.AppleMusicPlaylist -> {
                    try {
                        val url = "https://music.apple.com/us/playlist/imported/${action.playlistId}"
                        val result = playlistImportManager.importFromUrl(url)
                        _navEvents.emit(DeepLinkNavEvent.Playlist(result.playlistId))
                        _navEvents.emit(DeepLinkNavEvent.Toast("Imported '${result.playlistName}' (${result.trackCount} tracks)"))
                    } catch (e: Exception) {
                        _navEvents.emit(DeepLinkNavEvent.Toast("Could not import Apple Music playlist: ${e.message}"))
                    }
                }

                is DeepLinkAction.Unknown -> Log.w(TAG, "Unknown deep link: ${action.uri}")

                // ── Phase 1 (#119) foundation types — wired in Phase 2 (#120) / Phase 3 (#121) ──
                is DeepLinkAction.PlayAlbum,
                is DeepLinkAction.PlayPlaylist,
                is DeepLinkAction.PlayRadio,
                is DeepLinkAction.ListenAlong -> {
                    Log.d(TAG, "Protocol play handler not yet wired (Phase 2 / 3): $action")
                }
            }
        }
    }

    // ── External URL resolution ──────────────────────────────────────

    private suspend fun resolveAndPlaySpotifyTrack(trackId: String) {
        val result = externalLinkResolver.resolveSpotifyTrack(trackId)
        if (result != null) {
            playbackController.playTrack(result.track)
        } else {
            _navEvents.emit(DeepLinkNavEvent.Toast("Could not load Spotify track"))
        }
    }

    private suspend fun resolveAndNavigateSpotifyAlbum(albumId: String) {
        val result = externalLinkResolver.resolveSpotifyAlbum(albumId)
        if (result != null) {
            _navEvents.emit(DeepLinkNavEvent.Album(result.title, result.artist))
        } else {
            _navEvents.emit(DeepLinkNavEvent.Toast("Could not load Spotify album"))
        }
    }

    private suspend fun resolveAndNavigateSpotifyPlaylist(playlistId: String) {
        try {
            val url = "https://open.spotify.com/playlist/$playlistId"
            val result = playlistImportManager.importFromUrl(url)
            _navEvents.emit(DeepLinkNavEvent.Playlist(result.playlistId))
            _navEvents.emit(DeepLinkNavEvent.Toast("Imported '${result.playlistName}' (${result.trackCount} tracks)"))
        } catch (e: Exception) {
            _navEvents.emit(DeepLinkNavEvent.Toast("Could not import Spotify playlist: ${e.message}"))
        }
    }

    private suspend fun resolveAndNavigateSpotifyArtist(artistId: String) {
        Log.d(TAG, "Resolving Spotify artist: $artistId")
        val result = externalLinkResolver.resolveSpotifyArtist(artistId)
        if (result != null) {
            Log.d(TAG, "Resolved Spotify artist '$artistId' → '${result.name}'")
            _navEvents.emit(DeepLinkNavEvent.Artist(result.name))
        } else {
            Log.w(TAG, "Failed to resolve Spotify artist '$artistId'")
            _navEvents.emit(DeepLinkNavEvent.Toast("Could not load Spotify artist. Make sure Spotify is connected in Settings."))
        }
    }

    private suspend fun resolveAndPlayAppleMusicSong(songId: String) {
        val result = externalLinkResolver.resolveAppleMusicSong(songId)
        if (result != null) {
            playbackController.playTrack(result.track)
        } else {
            _navEvents.emit(DeepLinkNavEvent.Toast("Could not load Apple Music song"))
        }
    }

    private suspend fun resolveAndNavigateAppleMusicAlbum(albumId: String) {
        val result = externalLinkResolver.resolveAppleMusicAlbum(albumId)
        if (result != null) {
            _navEvents.emit(DeepLinkNavEvent.Album(result.title, result.artist))
        } else {
            _navEvents.emit(DeepLinkNavEvent.Toast("Could not load Apple Music album"))
        }
    }
}
