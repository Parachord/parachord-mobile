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
func pcCover(_ url: String?, seed: String, size: CGFloat?, radius: CGFloat) -> some View {
    if let url, let u = URL(string: url) {
        AsyncImage(url: u) { img in
            img.resizable().aspectRatio(contentMode: .fill)
        } placeholder: {
            PCArtwork(name: seed, size: size, radius: radius)
        }
        .frame(width: size, height: size) // nil → fills available width (grid)
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
    /// Observed so the resolver badges re-render as background resolution lands.
    private var resolverCache = IosTrackResolverCache.shared

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
                        Text(t.title)
                            .font(.system(size: 15, weight: .medium))
                            .foregroundStyle(coordinator.currentTrack?.id == model.entities[index].id ? PC.accent : PC.fg1)
                            .lineLimit(1)
                        Spacer(minLength: 8)
                        // Android TrackRow order: title → duration → resolver icons (rightmost).
                        if let d = t.duration, d.int64Value > 0 {
                            Text(pcDur(d.int64Value)).font(.system(size: 13, design: .monospaced)).foregroundStyle(PC.fg3)
                        }
                        if let sources = resolverCache.cached(artist: t.artist, title: t.title, album: t.album),
                           !sources.isEmpty {
                            ResolverBadgeRow(sources: sources)
                        }
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

    // Resolver pipeline (CLAUDE.md): every track list resolves per visible row.
    func resolveVisible(_ t: TrackSearchResult, index: Int) {
        IosTrackResolverCache.shared.resolve(
            ResolveRequest(artist: t.artist, title: t.title, album: t.album), order: index)
    }
}

private enum ArtistTab: String, CaseIterable {
    case discography = "Discography", topTracks = "Top Tracks"
    case biography = "Biography", related = "Related Artists"
}

/// Design-matched Artist screen (screens.jsx ArtistScreen): 360px gradient
/// hero with the name in it + "Play Top Tracks" CTA + 4 tabs (Discography
/// grid / Top Tracks / Biography / Related Artists).
struct ArtistScreen: View {
    @State private var model: ArtistDetailModel
    @State private var tab: ArtistTab = .discography
    @State private var discoFilter = "All"
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    @Environment(\.dismiss) private var dismiss

    init(artistName: String) { _model = State(initialValue: ArtistDetailModel(name: artistName)) }

    // ART_COLOR (screens.jsx): hash(name) % 5 dark gradient pairs.
    private static let palettes: [(UInt32, UInt32)] = [
        (0x3d3d3d, 0x1a1a1a), (0x2a3a4a, 0x0e1822), (0x3a2a3a, 0x1a121a),
        (0x4a3a2a, 0x1f1810), (0x2a4a3a, 0x0e221a),
    ]
    private var gradient: (Color, Color) {
        var h: Int32 = 0
        for b in model.name.unicodeScalars { h = (h &<< 5) &- h &+ Int32(b.value) }
        let p = Self.palettes[Int(abs(h)) % Self.palettes.count]
        return (Color(uiColor: UIColor(hex: p.0)), Color(uiColor: UIColor(hex: p.1)))
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                hero
                cta
                tabBar
                tabContent
                if model.isLoading && !model.loaded {
                    ProgressView().frame(maxWidth: .infinity).padding(.vertical, 40)
                }
            }
            .padding(.bottom, 130)
        }
        .ignoresSafeArea(edges: .top)
        .toolbar(.hidden, for: .navigationBar)
        .overlay(alignment: .topLeading) {
            Button { dismiss() } label: {
                Image(systemName: "chevron.left").font(.system(size: 17, weight: .semibold))
                    .foregroundStyle(PC.fg1).frame(width: 36, height: 36)
                    .background(.ultraThinMaterial, in: Circle())
            }
            .padding(.leading, 16).padding(.top, 4)
        }
        .task { await model.load() }
    }

    // MARK: Hero

    private var hero: some View {
        ZStack(alignment: .bottom) {
            LinearGradient(colors: [gradient.0, gradient.1], startPoint: .topLeading, endPoint: .bottomTrailing)
            // bottom fade into the page background
            LinearGradient(
                colors: [.clear, .clear, .black.opacity(0.35), PC.bgPrimary],
                startPoint: .top, endPoint: .bottom)
            Text(model.name.uppercased())
                .font(.system(size: 28, weight: .light)).tracking(5)
                .foregroundStyle(.white).multilineTextAlignment(.center)
                .shadow(color: .black.opacity(0.6), radius: 16)
                .padding(.horizontal, 24).padding(.bottom, 36)
        }
        .frame(height: 360)
        .frame(maxWidth: .infinity)
        .clipped()
    }

    private var cta: some View {
        Button {
            if !model.topEntities.isEmpty { coordinator.setQueue(model.topEntities, startIndex: 0) }
        } label: {
            Label("Play Top Tracks", systemImage: "play.fill")
                .font(.system(size: 15, weight: .semibold)).foregroundStyle(.white)
                .padding(.horizontal, 22).frame(height: 44)
                .background(PC.accent, in: Capsule())
                .shadow(color: PC.accent.opacity(0.4), radius: 12, y: 8)
        }
        .buttonStyle(.plain)
        .offset(y: -20)
    }

    // MARK: Tabs

    private var tabBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 24) {
                ForEach(ArtistTab.allCases, id: \.self) { t in
                    Button { withAnimation(.easeOut(duration: 0.2)) { tab = t } } label: {
                        VStack(spacing: 7) {
                            Text(t.rawValue.uppercased()).font(.system(size: 13, weight: .semibold)).tracking(1)
                                .foregroundStyle(tab == t ? PC.fg1 : PC.fg3)
                            Rectangle().fill(tab == t ? PC.accent : .clear).frame(height: 2)
                        }
                        .fixedSize()
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 20)
        }
        .overlay(Rectangle().fill(PC.border).frame(height: 1), alignment: .bottom)
        .padding(.top, 4)
    }

    @ViewBuilder
    private var tabContent: some View {
        switch tab {
        case .discography: discographyTab
        case .topTracks:   topTracksTab
        case .biography:   biographyTab
        case .related:     relatedTab
        }
    }

    // MARK: Discography (chips + 2-col grid)

    private func releaseKind(_ a: AlbumSearchResult) -> String {
        switch (a.releaseType ?? "album").lowercased() {
        case "single": return "Singles"
        case "ep": return "EPs"
        default: return "Studio Albums"
        }
    }
    private var discoFiltered: [AlbumSearchResult] {
        discoFilter == "All" ? model.albums : model.albums.filter { releaseKind($0) == discoFilter }
    }

    private var discographyTab: some View {
        VStack(alignment: .leading, spacing: 0) {
            let counts: [(String, Int)] = [
                ("All", model.albums.count),
                ("Studio Albums", model.albums.filter { releaseKind($0) == "Studio Albums" }.count),
                ("EPs", model.albums.filter { releaseKind($0) == "EPs" }.count),
                ("Singles", model.albums.filter { releaseKind($0) == "Singles" }.count),
            ]
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(counts.filter { $0.1 > 0 || $0.0 == "All" }, id: \.0) { name, n in
                        let on = discoFilter == name
                        Button { discoFilter = name } label: {
                            Text("\(name) \(n)").font(.system(size: 13, weight: .medium))
                                .foregroundStyle(on ? PC.bgPrimary : PC.fg1)
                                .padding(.horizontal, 14).padding(.vertical, 6)
                                .background(on ? PC.fg1 : PC.bgInset, in: Capsule())
                                .overlay(Capsule().strokeBorder(on ? .clear : PC.border))
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 20)
            }
            .padding(.vertical, 12)

            LazyVGrid(columns: [GridItem(.flexible(), spacing: 16), GridItem(.flexible(), spacing: 16)], spacing: 18) {
                ForEach(Array(discoFiltered.enumerated()), id: \.offset) { _, album in
                    NavigationLink { AlbumScreen(title: album.title, artist: album.artist) } label: {
                        VStack(alignment: .leading, spacing: 0) {
                            pcCover(album.artworkUrl, seed: album.title + album.artist, size: nil, radius: 10)
                                .aspectRatio(1, contentMode: .fit)
                                .shadow(color: .black.opacity(0.12), radius: 8, y: 4)
                            Text(album.title).font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.fg1)
                                .lineLimit(1).padding(.top, 10)
                            HStack(spacing: 8) {
                                let isAlbum = releaseKind(album) == "Studio Albums"
                                Text((album.releaseType ?? "album").uppercased())
                                    .font(.system(size: 9, weight: .bold)).tracking(1)
                                    .foregroundStyle(isAlbum ? PC.accent : PC.fg2)
                                    .padding(.horizontal, 6).padding(.vertical, 3)
                                    .background((isAlbum ? PC.accent.opacity(0.10) : PC.bgInset), in: RoundedRectangle(cornerRadius: 3))
                                if let y = album.year { Text(String(y.intValue)).font(.system(size: 12)).foregroundStyle(PC.fg2) }
                            }
                            .padding(.top, 4)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 20).padding(.vertical, 12)
        }
    }

    // MARK: Top Tracks (resolver pipeline per row)

    private var topTracksTab: some View {
        LazyVStack(spacing: 0) {
            ForEach(Array(model.topTracks.enumerated()), id: \.offset) { index, t in
                Button { coordinator.setQueue(model.topEntities, startIndex: index) } label: {
                    HStack(spacing: 12) {
                        Text("\(index + 1)").font(.system(size: 15, weight: .bold)).foregroundStyle(PC.accent)
                            .frame(width: 22)
                        pcCover(t.artworkUrl, seed: t.title + t.artist, size: 44, radius: 6)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t.title).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1).lineLimit(1)
                            if let d = t.duration, d.int64Value > 0 {
                                Text(pcDur(d.int64Value)).font(.system(size: 13)).foregroundStyle(PC.fg2)
                            }
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 20).padding(.vertical, 8).contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .onAppear { model.resolveVisible(t, index: index) }
                .pcTrackContextMenu(model.topEntities[index], coordinator: coordinator)
            }
            if model.topTracks.isEmpty && model.loaded {
                Text("No top tracks").font(.system(size: 14)).foregroundStyle(PC.fg3).padding(40)
            }
        }
        .padding(.vertical, 8)
    }

    // MARK: Biography

    private var biographyTab: some View {
        VStack(alignment: .leading, spacing: 16) {
            if let bio = model.info?.bio, !bio.isEmpty {
                Text(bio).font(.system(size: 15)).foregroundStyle(PC.fg1).lineSpacing(5)
                if !(model.info?.tags.isEmpty ?? true) {
                    FlowTags(tags: model.info?.tags ?? [])
                }
            } else {
                Text("No biography available.").font(.system(size: 14)).foregroundStyle(PC.fg3)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
    }

    // MARK: Related Artists (2-col grid)

    private var relatedTab: some View {
        let related = model.info?.similarArtists ?? []
        return Group {
            if related.isEmpty {
                Text("No related artists.").font(.system(size: 14)).foregroundStyle(PC.fg3).padding(40)
            } else {
                LazyVGrid(columns: [GridItem(.flexible(), spacing: 16), GridItem(.flexible(), spacing: 16)], spacing: 18) {
                    ForEach(Array(related.enumerated()), id: \.offset) { _, a in
                        NavigationLink { ArtistScreen(artistName: a.name) } label: {
                            VStack(alignment: .leading, spacing: 0) {
                                pcCover(a.imageUrl, seed: a.name, size: nil, radius: 10)
                                    .aspectRatio(1, contentMode: .fit)
                                    .shadow(color: .black.opacity(0.12), radius: 8, y: 4)
                                Text(a.name).font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.fg1)
                                    .lineLimit(1).padding(.top, 10)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 20).padding(.vertical, 12)
            }
        }
    }
}

/// Wrapping tag chips for the artist biography.
private struct FlowTags: View {
    let tags: [String]
    var body: some View {
        // simple wrap via a lazy vgrid of adaptive chips
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 70), spacing: 8)], alignment: .leading, spacing: 8) {
            ForEach(tags.prefix(12), id: \.self) { tag in
                Text(tag).font(.system(size: 12, weight: .medium)).foregroundStyle(PC.fg2)
                    .padding(.horizontal, 10).padding(.vertical, 5)
                    .background(PC.bgInset, in: Capsule())
            }
        }
    }
}

private func pcDur(_ ms: Int64) -> String {
    let s = Int(ms / 1000); return String(format: "%d:%02d", s / 60, s % 60)
}
