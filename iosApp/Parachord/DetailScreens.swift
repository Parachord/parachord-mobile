import SwiftUI
import Shared

// MARK: - Album & Artist detail (Phase 4 — docs/design/parachord-ios)
//
// Backed by the shared MetadataService (cascading MusicBrainz → Last.fm →
// Spotify) wired through IosContainer. Album = header + play-all + numbered
// tracklist. Artist = hero + top songs + discography grid. Both cross-link
// (album artist → artist, artist discography → album) and play through the
// resolver pipeline.

/// Hashable album reference for `navigationDestination(item:)` (context-menu
/// "Go to Album").
struct PCAlbumRef: Hashable { let title: String; let artist: String }

private func pcTrack(from t: TrackSearchResult) -> Track {
    Track(
        id: "\(t.title)|\(t.artist)", title: t.title, artist: t.artist,
        album: t.album, albumId: nil, duration: t.duration, artworkUrl: t.artworkUrl,
        sourceType: nil, sourceUrl: nil, addedAt: 0,
        resolver: nil, spotifyUri: nil, soundcloudId: nil,
        spotifyId: t.spotifyId, appleMusicId: nil, recordingMbid: t.mbid,
        artistMbid: nil, releaseMbid: nil, crossResolverEnrichedAt: nil
    )
}

@ViewBuilder
private func pcCover(_ url: String?, seed: String, size: CGFloat, radius: CGFloat) -> some View {
    if let url, let u = URL(string: url) {
        AsyncImage(url: u) { img in
            img.resizable().aspectRatio(contentMode: .fill)
        } placeholder: {
            PCArtwork(name: seed, size: size, radius: radius)
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    } else {
        PCArtwork(name: seed, size: size, radius: radius)
    }
}

// MARK: - Album

@MainActor @Observable
final class AlbumDetailModel {
    private let container = IosContainer.companion.shared
    let title: String
    let artist: String
    var detail: AlbumDetail?
    var entities: [Track] = []
    var isLoading = false
    var loaded = false

    init(title: String, artist: String) { self.title = title; self.artist = artist }

    func load() async {
        guard !loaded else { return }
        isLoading = true
        detail = try? await container.getAlbumDetail(albumTitle: title, artistName: artist)
        entities = detail?.tracks.map { pcTrack(from: $0) } ?? []
        isLoading = false
        loaded = true
    }

    func resolveVisible(_ t: TrackSearchResult, index: Int) {
        IosTrackResolverCache.shared.resolve(
            ResolveRequest(artist: t.artist, title: t.title, album: t.album), order: index)
    }
}

struct AlbumScreen: View {
    @State private var model: AlbumDetailModel
    @Environment(QueuePlaybackCoordinator.self) private var coordinator

    init(title: String, artist: String) {
        _model = State(initialValue: AlbumDetailModel(title: title, artist: artist))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                if !model.entities.isEmpty {
                    Button { coordinator.setQueue(model.entities, startIndex: 0) } label: {
                        Label("Play All", systemImage: "play.fill")
                            .font(.system(size: 16, weight: .semibold)).foregroundStyle(.white)
                            .padding(.horizontal, 28).frame(height: 48)
                            .background(PC.accent, in: Capsule())
                    }
                    .buttonStyle(.plain)
                    .padding(.horizontal, 20).padding(.vertical, 12)
                }
                tracklist
                if model.isLoading && !model.loaded {
                    ProgressView().frame(maxWidth: .infinity).padding(.vertical, 40)
                }
            }
            .padding(.bottom, 130)
        }
        .navigationTitle(model.title)
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.load() }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 16) {
            pcCover(model.detail?.artworkUrl, seed: model.title + model.artist, size: 132, radius: 12)
                .shadow(color: .black.opacity(0.18), radius: 9, y: 6)
            VStack(alignment: .leading, spacing: 6) {
                Text(model.title).font(.system(size: 21, weight: .semibold)).foregroundStyle(PC.fg1)
                NavigationLink { ArtistScreen(artistName: model.artist) } label: {
                    Text(model.artist).font(.system(size: 16, weight: .medium)).foregroundStyle(PC.accent)
                }
                .buttonStyle(.plain)
                HStack(spacing: 10) {
                    if let rt = model.detail?.releaseType {
                        Text(rt.uppercased()).font(.system(size: 11, weight: .bold))
                            .foregroundStyle(PC.accent).padding(.horizontal, 10).padding(.vertical, 5)
                            .background(PC.accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 6))
                    }
                    if let year = model.detail?.year {
                        Text(String(year.intValue)).font(.system(size: 14)).foregroundStyle(PC.fg3)
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20).padding(.top, 16).padding(.bottom, 8)
    }

    private var tracklist: some View {
        LazyVStack(spacing: 0) {
            ForEach(Array((model.detail?.tracks ?? []).enumerated()), id: \.offset) { index, t in
                Button { coordinator.setQueue(model.entities, startIndex: index) } label: {
                    HStack(spacing: 12) {
                        Text("\(index + 1)").font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(PC.fg3).frame(width: 22)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t.title).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                            Text(t.artist).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 20).padding(.vertical, 9)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .onAppear { model.resolveVisible(t, index: index) }
                .pcTrackContextMenu(model.entities[index], coordinator: coordinator)
            }
        }
    }
}

// MARK: - Artist

@MainActor @Observable
final class ArtistDetailModel {
    private let container = IosContainer.companion.shared
    let name: String
    var info: ArtistInfo?
    var topTracks: [TrackSearchResult] = []
    var topEntities: [Track] = []
    var albums: [AlbumSearchResult] = []
    var isLoading = false
    var loaded = false

    init(name: String) { self.name = name }

    func load() async {
        guard !loaded else { return }
        isLoading = true
        info = try? await container.getArtistInfo(artistName: name)
        topTracks = (try? await container.getArtistTopTracks(artistName: name)) ?? []
        topEntities = topTracks.map { pcTrack(from: $0) }
        albums = (try? await container.getArtistAlbums(artistName: name)) ?? []
        isLoading = false
        loaded = true
    }
}

struct ArtistScreen: View {
    @State private var model: ArtistDetailModel
    @Environment(QueuePlaybackCoordinator.self) private var coordinator

    init(artistName: String) { _model = State(initialValue: ArtistDetailModel(name: artistName)) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                hero
                if !model.topEntities.isEmpty {
                    Text("Top Songs").pcSectionHeader().padding(.horizontal, 20).padding(.top, 18).padding(.bottom, 8)
                    topSongs
                }
                if !model.albums.isEmpty {
                    Text("Discography").pcSectionHeader().padding(.horizontal, 20).padding(.top, 18).padding(.bottom, 8)
                    discography
                }
                if model.isLoading && !model.loaded {
                    ProgressView().frame(maxWidth: .infinity).padding(.vertical, 40)
                }
            }
            .padding(.bottom, 130)
        }
        .navigationTitle(model.name)
        .navigationBarTitleDisplayMode(.inline)
        .task { await model.load() }
    }

    private var hero: some View {
        VStack(spacing: 12) {
            pcCover(model.info?.imageUrl, seed: model.name, size: 140, radius: 70)
                .shadow(color: .black.opacity(0.2), radius: 12, y: 6)
            Text(model.name).font(.system(size: 24, weight: .bold)).foregroundStyle(PC.fg1)
                .multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 16).padding(.horizontal, 20)
    }

    private var topSongs: some View {
        LazyVStack(spacing: 0) {
            ForEach(Array(model.topTracks.enumerated()), id: \.offset) { index, t in
                Button { coordinator.setQueue(model.topEntities, startIndex: index) } label: {
                    HStack(spacing: 12) {
                        Text("\(index + 1)").font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(PC.fg3).frame(width: 22)
                        pcCover(t.artworkUrl, seed: t.title + t.artist, size: 40, radius: 6)
                        Text(t.title).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 20).padding(.vertical, 8)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .pcTrackContextMenu(model.topEntities[index], coordinator: coordinator)
            }
        }
    }

    private var discography: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 14) {
                ForEach(Array(model.albums.enumerated()), id: \.offset) { _, album in
                    NavigationLink { AlbumScreen(title: album.title, artist: album.artist) } label: {
                        VStack(alignment: .leading, spacing: 8) {
                            pcCover(album.artworkUrl, seed: album.title + album.artist, size: 148, radius: 12)
                            Text(album.title).font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.fg1).lineLimit(1)
                            if let year = album.year {
                                Text(String(year.intValue)).font(.system(size: 13)).foregroundStyle(PC.fg2)
                            }
                        }
                        .frame(width: 148)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 20)
        }
    }
}
