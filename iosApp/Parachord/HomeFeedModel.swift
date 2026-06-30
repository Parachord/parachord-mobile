import SwiftUI
import Shared

/// Backs the DB/AI home sections (#196 home parity): Recently Added, Your
/// Playlists, Your Collection stats, Recent Loves, AI Suggestions, and the
/// announcement banner. The library sections subscribe to the same collection
/// flows the Collection tab uses (`watchCollection*` / `watchSavedPlaylists`), so
/// they stay live as sync imports land. The Weekly/Discover/Friend sections stay
/// on `DiscoverViewModel`.
@MainActor @Observable
final class HomeFeedModel {
    private let container = IosContainer.companion.shared

    // Library (Phase A)
    var recentAlbums: [Album] = []      // 8 most-recently-added
    var recentPlaylists: [Playlist] = [] // 6 most-recently-modified
    var recentLoves: [Track] = []       // 10 most-recently-added tracks
    var songCount = 0
    var albumCount = 0
    var artistCount = 0
    var playlistCount = 0
    var friendCount = 0
    var libraryLoaded = false
    var libraryEmpty = false

    // AI Suggestions (Phase B)
    var hasAiPlugins: Bool? = nil       // nil = still checking
    var aiAlbums: [IosAiAlbumSuggestion] = []
    var aiArtists: [IosAiArtistSuggestion] = []
    var aiLoading = false
    var aiError: String?

    // Announcement (Phase C)
    var announcement: IosAnnouncement?

    private var subs: [Cancellable] = []
    private var announceSub: Cancellable?

    func start() {
        guard subs.isEmpty else { return }
        subs.append(container.watchCollectionAlbums { [weak self] albums in
            let count = albums.count
            let recent = Array(albums.sorted { $0.addedAt > $1.addedAt }.prefix(8))
            Task { @MainActor in self?.albumCount = count; self?.recentAlbums = recent; self?.recomputeEmpty() }
        })
        subs.append(container.watchCollectionTracks { [weak self] tracks in
            let count = tracks.count
            let recent = Array(tracks.sorted { $0.addedAt > $1.addedAt }.prefix(10))
            Task { @MainActor in self?.songCount = count; self?.recentLoves = recent; self?.recomputeEmpty() }
        })
        subs.append(container.watchCollectionArtists { [weak self] artists in
            let count = artists.count
            Task { @MainActor in self?.artistCount = count }
        })
        subs.append(container.watchCollectionFriends { [weak self] friends in
            let count = friends.count
            Task { @MainActor in self?.friendCount = count }
        })
        subs.append(container.watchSavedPlaylists { [weak self] playlists in
            let count = playlists.count
            let recent = Array(playlists.sorted { lhs, rhs in
                let l = lhs.lastModified > 0 ? lhs.lastModified : lhs.updatedAt
                let r = rhs.lastModified > 0 ? rhs.lastModified : rhs.updatedAt
                return l > r
            }.prefix(6))
            Task { @MainActor in self?.playlistCount = count; self?.recentPlaylists = recent; self?.recomputeEmpty() }
        })

        startAnnouncements()
        Task { await loadAi() }
    }

    private func recomputeEmpty() {
        libraryLoaded = true
        libraryEmpty = songCount == 0 && albumCount == 0 && playlistCount == 0
    }

    // MARK: AI Suggestions (Phase B)
    /// Stale-while-revalidate (Android parity): show disk-cached suggestions
    /// immediately, then fetch fresh in the background.
    func loadAi() async {
        let enabled = (try? await container.hasAiSuggestionsPlugin())?.boolValue ?? false
        hasAiPlugins = enabled
        guard enabled else { return }
        if let cached = (try? await container.aiSuggestionsCached()) ?? nil {
            aiAlbums = cached.albums
            aiArtists = cached.artists
        }
        aiLoading = aiAlbums.isEmpty && aiArtists.isEmpty
        aiError = nil
        do {
            let r = try await container.loadAiSuggestions()
            aiAlbums = r.albums
            aiArtists = r.artists
        } catch {
            if aiAlbums.isEmpty && aiArtists.isEmpty { aiError = error.localizedDescription }
        }
        aiLoading = false
    }

    // MARK: Announcements (Phase C)
    private func startAnnouncements() {
        guard announceSub == nil else { return }
        announceSub = container.watchTopAnnouncement { [weak self] ann in
            Task { @MainActor in
                self?.announcement = ann
                if let id = ann?.id { self?.trackAnnouncement(id, event: "view") }
            }
        }
        Task { try? await container.refreshAnnouncements() }   // cold-start fetch
    }

    func trackAnnouncement(_ id: String, event: String) {
        Task { try? await container.trackAnnouncementEvent(id: id, event: event) }
    }

    func dismissAnnouncement() {
        guard let id = announcement?.id else { return }
        trackAnnouncement(id, event: "dismiss")
        announcement = nil
        Task { try? await container.dismissAnnouncement(id: id) }
    }

    func stop() {
        subs.forEach { $0.cancel() }; subs.removeAll()
        announceSub?.cancel(); announceSub = nil
    }
}
