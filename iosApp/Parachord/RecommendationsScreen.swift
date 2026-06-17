import SwiftUI
import Shared

// MARK: - For You / Recommendations (Phase 4)
//
// Personalized recs from the shared RecommendationsRepository (ListenBrainz
// history → MetadataService), wired through IosContainer. Recommended tracks
// (play + context menu) and recommended artists (→ ArtistScreen).

@MainActor @Observable
final class RecommendationsModel {
    static let shared = RecommendationsModel()
    private let container = IosContainer.companion.shared
    private let watcher = FlowWatcher(scope: IosContainer.companion.shared.appScope)
    private var subTracks: Cancellable?
    private var subArtists: Cancellable?
    var tracks: [RecommendedTrack] = []
    var entities: [Track] = []
    var artists: [RecommendedArtist] = []
    var isLoading = false
    var loaded = false

    // Watch the shared recommendation flows (cached-first → fresh); the repo owns
    // its disk cache (architecture realignment).
    func load() async {
        guard subTracks == nil else { return }
        isLoading = true
        subTracks = watcher.watch(flow: container.recommendedTracksFlow()) { [weak self] v in
            guard let self else { return }
            if let t = v as? [RecommendedTrack], !t.isEmpty {
                self.tracks = t
                self.entities = t.map { Self.makeTrack($0) }
            }
            self.isLoading = false
            self.loaded = true
        }
        subArtists = watcher.watch(flow: container.recommendedArtistsFlow()) { [weak self] v in
            guard let self else { return }
            if let a = v as? [RecommendedArtist], !a.isEmpty { self.artists = a }
            self.loaded = true
        }
    }

    func resolveVisible(_ t: RecommendedTrack, index: Int) {
        IosTrackResolverCache.shared.resolve(
            ResolveRequest(artist: t.artist, title: t.title, album: t.album), order: index)
    }

    private static func makeTrack(_ t: RecommendedTrack) -> Track {
        Track(
            id: "\(t.title)|\(t.artist)", title: t.title, artist: t.artist,
            album: t.album, albumId: nil, duration: t.duration, artworkUrl: t.artworkUrl,
            sourceType: nil, sourceUrl: nil, addedAt: 0,
            resolver: nil, spotifyUri: nil, soundcloudId: nil,
            spotifyId: nil, appleMusicId: nil, isrc: nil, recordingMbid: nil,
            artistMbid: nil, releaseMbid: nil, crossResolverEnrichedAt: nil
        )
    }
}

private enum RecTab: String, CaseIterable { case artists = "Artists", songs = "Songs" }

struct RecommendationsScreen: View {
    @State private var model = RecommendationsModel.shared
    @State private var tabIndex = 0
    @State private var source = "all"
    @State private var navArtist: String?
    @State private var navAlbum: PCAlbumRef?
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    @Environment(\.dismiss) private var dismiss

    private let sources: [(key: String, label: String)] =
        [("all", "All"), ("listenbrainz", "ListenBrainz"), ("lastfm", "Last.fm")]

    private func matchSource(_ s: String) -> Bool {
        source == "all" || s.lowercased().replacingOccurrences(of: ".", with: "").contains(source)
    }
    private var filteredArtists: [RecommendedArtist] { model.artists.filter { matchSource($0.source) } }
    private var filteredSongs: [(track: RecommendedTrack, entity: Track)] {
        Array(zip(model.tracks, model.entities)).filter { matchSource($0.0.source) }.map { (track: $0.0, entity: $0.1) }
    }

    private var bannerCount: String? {
        guard !model.artists.isEmpty || !model.tracks.isEmpty else { return nil }
        return "\(model.artists.count) artists · \(model.tracks.count) songs"
    }

    var body: some View {
        VStack(spacing: 0) {
            PCTopBar(title: "Recommendations", leading: .back, onLeading: { dismiss() })
            PCCuratedBanner(
                icon: "star.fill",
                subtitle: "A daily mix tuned to your listening",
                count: bannerCount,
                gradient: [0x7C3AED, 0xA855F7, 0xEC4899])
            PCTabs(tabs: ["Artists", "Songs"], selection: $tabIndex)

            sourceChips

            if model.isLoading && !model.loaded {
                ScrollView { PCSkeletonGrid(count: 9, columns: 3) }
            } else if model.loaded && model.tracks.isEmpty && model.artists.isEmpty {
                Spacer()
                Text("No recommendations yet — connect ListenBrainz and listen to a few tracks.")
                    .font(.system(size: 14)).foregroundStyle(PC.fg2)
                    .multilineTextAlignment(.center).padding(40)
                Spacer()
            } else {
                ScrollView {
                    if tabIndex == 0 { artistsGrid } else { songsList }
                }
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .navigationDestination(item: $navArtist) { ArtistScreen(artistName: $0) }
        .navigationDestination(item: $navAlbum) { AlbumScreen(title: $0.title, artist: $0.artist) }
        .task { await model.load() }
    }

    private var sourceChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(sources, id: \.key) { s in
                    let on = source == s.key
                    Button { source = s.key } label: {
                        Text(s.label).font(.system(size: 13, weight: .medium))
                            .foregroundStyle(on ? .white : PC.fg1)
                            .padding(.horizontal, 14).padding(.vertical, 6)
                            .background(on ? PC.accent : PC.bgInset, in: Capsule())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 10)
        }
        .overlay(Rectangle().fill(PC.border).frame(height: 1), alignment: .bottom)
    }

    // ── Artists tab: grid of circular artist cards ─────────────────────
    private var artistsGrid: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Based on your listening").font(.system(size: 12, weight: .medium))
                .foregroundStyle(PC.fg3).padding(.horizontal, 20).padding(.top, 14).padding(.bottom, 2)
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 18) {
                ForEach(Array(filteredArtists.enumerated()), id: \.offset) { _, a in
                    NavigationLink(value: PCRoute.artist(a.name)) {
                        VStack(spacing: 6) {
                            artistSquare(a)
                            Text(a.name).font(.system(size: 12, weight: .medium))
                                .foregroundStyle(PC.fg1).lineLimit(1).multilineTextAlignment(.center)
                            if let r = a.reason, !r.isEmpty {
                                Text(r).font(.system(size: 10)).foregroundStyle(PC.fg3)
                                    .lineLimit(1).multilineTextAlignment(.center)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 12).padding(.bottom, 120)
        }
    }

    // Square 96dp-style cards (radius 12) — matches Android's AlbumArtCard.
    @ViewBuilder
    private func artistSquare(_ a: RecommendedArtist) -> some View {
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                PCArtistImage(url: a.imageUrl.flatMap { URL(string: $0) }) {
                    PCArtwork(name: a.name, size: nil, radius: 12)
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            .shadow(color: .black.opacity(0.10), radius: 6, y: 3)
    }

    // ── Songs tab: track list (resolver pipeline per row) ──────────────
    private var songsList: some View {
        LazyVStack(spacing: 0) {
            ForEach(Array(filteredSongs.enumerated()), id: \.offset) { index, pair in
                Button {
                    coordinator.setQueue(filteredSongs.map { $0.entity }, startIndex: index)
                } label: {
                    HStack(spacing: 12) {
                        pcCover(pcTrackArt(pair.track.artworkUrl, artist: pair.track.artist, title: pair.track.title, album: pair.track.album), seed: pair.track.title + pair.track.artist, size: 44, radius: 6)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(pair.track.title).font(.system(size: 15, weight: .medium))
                                .foregroundStyle(pcTrackNoMatch(artist: pair.track.artist, title: pair.track.title, album: pair.track.album) ? PC.fg3
                                    : (coordinator.currentTrack?.id == pair.entity.id ? PC.accent : PC.fg1)).lineLimit(1)
                            Text(pair.track.artist).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                        // Resolver icons (parity with Pop of the Tops > Songs).
                        if let sources = IosTrackResolverCache.shared.cached(artist: pair.track.artist, title: pair.track.title, album: pair.track.album),
                           !sources.isEmpty {
                            ResolverBadgeRow(sources: sources)
                        }
                    }
                    .padding(.horizontal, 20).padding(.vertical, 8).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .onAppear { model.resolveVisible(pair.track, index: index) }
                .pcTrackContextMenu(
                    pair.entity, coordinator: coordinator,
                    onGoToArtist: { navArtist = pair.track.artist },
                    onGoToAlbum: pair.track.album.map { album in { navAlbum = PCAlbumRef(title: album, artist: pair.track.artist) } }
                )
            }
        }
        .padding(.vertical, 8).padding(.bottom, 120)
    }
}
