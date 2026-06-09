import SwiftUI
import Shared

// MARK: - For You / Recommendations (Phase 4)
//
// Personalized recs from the shared RecommendationsRepository (ListenBrainz
// history → MetadataService), wired through IosContainer. Recommended tracks
// (play + context menu) and recommended artists (→ ArtistScreen).

@MainActor @Observable
final class RecommendationsModel {
    private let container = IosContainer.companion.shared
    var tracks: [RecommendedTrack] = []
    var entities: [Track] = []
    var artists: [RecommendedArtist] = []
    var isLoading = false
    var loaded = false

    func load() async {
        guard !loaded else { return }
        isLoading = true
        let t = (try? await container.loadRecommendedTracks()) ?? []
        tracks = t
        entities = t.map { Self.makeTrack($0) }
        artists = (try? await container.loadRecommendedArtists()) ?? []
        isLoading = false
        loaded = true
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
            spotifyId: nil, appleMusicId: nil, recordingMbid: nil,
            artistMbid: nil, releaseMbid: nil, crossResolverEnrichedAt: nil
        )
    }
}

private enum RecTab: String, CaseIterable { case artists = "Artists", songs = "Songs" }

struct RecommendationsScreen: View {
    @State private var model = RecommendationsModel()
    @State private var tab: RecTab = .artists
    @State private var source = "all"
    @State private var navArtist: String?
    @State private var navAlbum: PCAlbumRef?
    @Environment(QueuePlaybackCoordinator.self) private var coordinator

    private let sources: [(key: String, label: String)] =
        [("all", "All"), ("listenbrainz", "ListenBrainz"), ("lastfm", "Last.fm")]

    private func matchSource(_ s: String) -> Bool {
        source == "all" || s.lowercased().replacingOccurrences(of: ".", with: "").contains(source)
    }
    private var filteredArtists: [RecommendedArtist] { model.artists.filter { matchSource($0.source) } }
    private var filteredSongs: [(track: RecommendedTrack, entity: Track)] {
        Array(zip(model.tracks, model.entities)).filter { matchSource($0.0.source) }.map { (track: $0.0, entity: $0.1) }
    }

    var body: some View {
        VStack(spacing: 0) {
            Picker("", selection: $tab) {
                ForEach(RecTab.allCases, id: \.self) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented).padding(.horizontal, 16).padding(.top, 8)

            sourceChips

            if model.isLoading && !model.loaded {
                Spacer(); ProgressView(); Spacer()
            } else if model.loaded && model.tracks.isEmpty && model.artists.isEmpty {
                Spacer()
                Text("No recommendations yet — connect ListenBrainz and listen to a few tracks.")
                    .font(.system(size: 14)).foregroundStyle(PC.fg2)
                    .multilineTextAlignment(.center).padding(40)
                Spacer()
            } else {
                ScrollView {
                    switch tab {
                    case .artists: artistsGrid
                    case .songs:   songsList
                    }
                }
            }
        }
        .navigationTitle("For You")
        .navigationBarTitleDisplayMode(.inline)
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
        LazyVGrid(columns: [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)], spacing: 18) {
            ForEach(Array(filteredArtists.enumerated()), id: \.offset) { _, a in
                NavigationLink { ArtistScreen(artistName: a.name) } label: {
                    VStack(spacing: 8) {
                        artistCircle(a)
                        Text(a.name).font(.system(size: 12, weight: .medium))
                            .foregroundStyle(PC.fg1).lineLimit(1).multilineTextAlignment(.center)
                    }
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 16)
        .padding(.bottom, 120)
    }

    @ViewBuilder
    private func artistCircle(_ a: RecommendedArtist) -> some View {
        if let url = a.imageUrl, let u = URL(string: url) {
            AsyncImage(url: u) { img in img.resizable().aspectRatio(contentMode: .fill) }
                placeholder: { PCArtwork(name: a.name, size: nil, radius: 999) }
                .aspectRatio(1, contentMode: .fit).clipShape(Circle())
        } else {
            PCArtwork(name: a.name, size: nil, radius: 999).aspectRatio(1, contentMode: .fit).clipShape(Circle())
        }
    }

    // ── Songs tab: track list (resolver pipeline per row) ──────────────
    private var songsList: some View {
        LazyVStack(spacing: 0) {
            ForEach(Array(filteredSongs.enumerated()), id: \.offset) { index, pair in
                Button {
                    coordinator.setQueue(filteredSongs.map { $0.entity }, startIndex: index)
                } label: {
                    HStack(spacing: 12) {
                        pcCover(pair.track.artworkUrl, seed: pair.track.title + pair.track.artist, size: 44, radius: 6)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(pair.track.title).font(.system(size: 15, weight: .medium))
                                .foregroundStyle(coordinator.currentTrack?.id == pair.entity.id ? PC.accent : PC.fg1).lineLimit(1)
                            Text(pair.track.artist).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                        }
                        Spacer(minLength: 0)
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
