import SwiftUI
import Shared
import Vision

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

/// Best available album art for a track, ENRICHED from the resolver cache.
/// Many track lists arrive art-less (Last.fm history, ListenBrainz, AI recs);
/// once a row resolves for its badges, the resolved sources carry Apple Music /
/// Spotify artwork, so we fall back to that. Filters Last.fm's grey-star
/// placeholder hash. Use anywhere a track row shows a cover so art fills in
/// uniformly across the app.
@MainActor
func pcTrackArt(_ artworkUrl: String?, artist: String, title: String, album: String?) -> String? {
    if let a = artworkUrl, !a.isEmpty, !a.contains("2a96cbd8b46e442fc41c2b86b821562f") { return a }
    if let srcs = IosTrackResolverCache.shared.cached(artist: artist, title: title, album: album),
       let a = srcs.compactMap({ $0.artworkUrl }).first(where: { !$0.isEmpty }) {
        return a
    }
    return nil
}

@ViewBuilder
func pcCover(_ url: String?, seed: String, size: CGFloat?, radius: CGFloat) -> some View {
    let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
    // Cover content: real art fill-cropped, gradient placeholder on miss/load.
    let content = ZStack {
        if let url, let u = URL(string: url) {
            PCCachedImage(url: u) { img in
                img.resizable().scaledToFill()
            } placeholder: {
                PCArtwork(name: seed, radius: radius)
            }
        } else {
            PCArtwork(name: seed, radius: radius)
        }
    }
    if let size {
        content.frame(width: size, height: size).clipShape(shape)
    } else {
        // Grid mode (size == nil): LOCK a 1:1 cell. A clear square spacer fixes
        // the footprint so an occasional non-square remote image can't dictate
        // the cell height and break row alignment; the image fill-crops into it.
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay { content }
            .clipShape(shape)
    }
}

// MARK: - Cached async image
//
// SwiftUI's AsyncImage caches nothing: every view rebuild (navigating back,
// LazyVGrid cell recycling) restarts from the placeholder and reloads, flashing
// the gradient. PCCachedImage caches the DECODED image in-memory and returns it
// SYNCHRONOUSLY on a hit — so revisiting a screen shows art instantly, no flash.
// While first-loading it shows a neutral shimmer (NOT the gradient), so artwork
// goes skeleton -> art; the `placeholder` (gradient) appears ONLY when there's no
// URL or the load fails.
private let pcImageCache: NSCache<NSString, UIImage> = {
    let c = NSCache<NSString, UIImage>()
    c.countLimit = 400
    return c
}()

struct PCCachedImage<Content: View, Placeholder: View>: View {
    private let url: URL?
    private let content: (Image) -> Content
    private let placeholder: () -> Placeholder
    @State private var loaded: UIImage?
    @State private var failed = false

    init(url: URL?,
         @ViewBuilder content: @escaping (Image) -> Content,
         @ViewBuilder placeholder: @escaping () -> Placeholder) {
        self.url = url
        self.content = content
        self.placeholder = placeholder
    }

    private var cachedImage: UIImage? {
        loaded ?? url.flatMap { pcImageCache.object(forKey: $0.absoluteString as NSString) }
    }

    var body: some View {
        Group {
            if let img = cachedImage {
                content(Image(uiImage: img))
            } else if url == nil || failed {
                placeholder()          // genuine no-art fallback (gradient)
            } else {
                PCSkeletonBox(radius: 8) // loading — shimmer, not the gradient
            }
        }
        .task(id: url) { await load() }
    }

    private func load() async {
        guard let url, loaded == nil else { return }
        let key = url.absoluteString as NSString
        if pcImageCache.object(forKey: key) != nil { return }   // body already shows it
        failed = false
        guard let (data, _) = try? await URLSession.shared.data(from: url),
              let ui = UIImage(data: data) else {
            failed = true
            return
        }
        pcImageCache.setObject(ui, forKey: key)
        loaded = ui
    }
}

// MARK: - Face-aware artist image
//
// iOS parity with Android's FaceAwareImage (ML Kit). Runs Vision face detection
// once per image, biases the fill-crop so the face stays in frame (vs a plain
// center crop that can clip a face in the top third), and caches both the decoded
// image (pcImageCache) and the face center (pcFaceCenters) so revisiting is
// instant with no re-detection. Falls back to a centered crop when no face is
// found, a shimmer while loading, and `placeholder` when there's no URL.
private let pcFaceCache = NSCache<NSString, NSValue>()

struct PCArtistImage<Placeholder: View>: View {
    let url: URL?
    /// Top area covered by the Dynamic Island / status bar in a full-bleed hero.
    /// The face is biased BELOW this; 0 (the default, used by circles) just
    /// centers the face normally.
    var topInset: CGFloat = 0
    @ViewBuilder var placeholder: () -> Placeholder
    @State private var loaded: UIImage?
    @State private var detected: UnitPoint?
    @State private var failed = false

    private var uiImage: UIImage? {
        loaded ?? url.flatMap { pcImageCache.object(forKey: $0.absoluteString as NSString) }
    }
    private var center: UnitPoint {
        if let detected { return detected }
        if let url, let v = pcFaceCache.object(forKey: url.absoluteString as NSString) {
            return UnitPoint(x: v.cgPointValue.x, y: v.cgPointValue.y)
        }
        return .center
    }

    var body: some View {
        GeometryReader { geo in
            cropped(geo.size)
        }
        .task(id: url) { await load() }
    }

    @ViewBuilder private func cropped(_ size: CGSize) -> some View {
        if let img = uiImage, size.width > 0, size.height > 0, img.size.width > 0, img.size.height > 0 {
            // Aspect-fill the container, then offset so the face center maps to the
            // container center (continuous bias, like Android's BiasAlignment).
            let s = max(size.width / img.size.width, size.height / img.size.height)
            let dW = img.size.width * s
            let dH = img.size.height * s
            // Shift so the face center lands at the container center, clamped so
            // the image still covers the container (no gaps). offset ∈ [C - d, 0].
            let ox = min(0, max(size.width - dW, size.width / 2 - center.x * dW))
            // Bias the face below `topInset` (Dynamic Island): target the center
            // of the VISIBLE region and allow shifting DOWN into the inset area
            // (covered by the island; the hero fills that strip with its gradient).
            // topInset == 0 (circles) reduces to plain face-centering.
            let targetY = topInset + (size.height - topInset) / 2
            let oy = min(topInset, max(size.height - dH, targetY - center.y * dH))
            Image(uiImage: img)
                .resizable()
                .frame(width: dW, height: dH)
                .offset(x: ox, y: oy)
                .frame(width: size.width, height: size.height, alignment: .topLeading)
                .clipped()
        } else if url == nil || failed {
            placeholder()
        } else {
            PCSkeletonBox(radius: 8)
        }
    }

    private func load() async {
        guard let url else { return }
        let key = url.absoluteString
        if uiImage == nil {
            failed = false
            guard let (data, _) = try? await URLSession.shared.data(from: url),
                  let ui = UIImage(data: data) else { failed = true; return }
            pcImageCache.setObject(ui, forKey: key as NSString)
            loaded = ui
        }
        if pcFaceCache.object(forKey: key as NSString) == nil, let img = uiImage {
            let pt = await Self.detectFaceCenter(img)
            pcFaceCache.setObject(NSValue(cgPoint: CGPoint(x: pt.x, y: pt.y)), forKey: key as NSString)
            detected = pt
        }
    }

    /// Average detected face center as a top-left-origin UnitPoint (.center if none).
    private static func detectFaceCenter(_ image: UIImage) async -> UnitPoint {
        guard let cg = image.cgImage else { return .center }
        return await withCheckedContinuation { cont in
            let request = VNDetectFaceRectanglesRequest { req, _ in
                guard let faces = req.results as? [VNFaceObservation], !faces.isEmpty else {
                    cont.resume(returning: .center); return
                }
                let n = CGFloat(faces.count)
                let x = faces.map { $0.boundingBox.midX }.reduce(0, +) / n
                // Vision is bottom-left origin → flip Y for SwiftUI (top-left).
                let yBottom = faces.map { $0.boundingBox.midY }.reduce(0, +) / n
                cont.resume(returning: UnitPoint(x: x, y: 1 - yBottom))
            }
            DispatchQueue.global(qos: .userInitiated).async {
                try? VNImageRequestHandler(cgImage: cg, orientation: .up, options: [:]).perform([request])
            }
        }
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
    @Environment(\.dismiss) private var dismiss
    /// Observed so the resolver badges re-render as background resolution lands.
    private var resolverCache = IosTrackResolverCache.shared

    init(title: String, artist: String) {
        _model = State(initialValue: AlbumDetailModel(title: title, artist: artist))
    }

    var body: some View {
        VStack(spacing: 0) {
            PCTopBar(title: model.title, leading: .back, onLeading: { dismiss() })
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
                    PCSkeletonList(count: 8, art: 44)
                }
            }
            .padding(.bottom, 130)
            }
        }
        .toolbar(.hidden, for: .navigationBar)
        .task { await model.load() }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 16) {
            pcCover(model.detail?.artworkUrl, seed: model.title + model.artist, size: 132, radius: 12)
                .shadow(color: .black.opacity(0.18), radius: 9, y: 6)
            VStack(alignment: .leading, spacing: 6) {
                Text(model.title).font(.system(size: 21, weight: .semibold)).foregroundStyle(PC.fg1)
                NavigationLink(value: PCRoute.artist(model.artist)) {
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
        ArtistImageCache.shared.fetch(name)   // header image (#187)
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
    @State private var discoFilter = "all"
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    @Environment(\.dismiss) private var dismiss
    /// Observed so top-track art fills in from the resolver as rows resolve.
    private var resolverCache = IosTrackResolverCache.shared

    /// Real album art for a top track: prefer the resolver's artwork (Apple
    /// Music / iTunes / Spotify) over Last.fm's, and drop Last.fm's "no image"
    /// star placeholder so we fall back to our gradient until art lands.
    private func topTrackArt(_ t: TrackSearchResult) -> String? {
        if let resolved = resolverCache.cached(artist: t.artist, title: t.title, album: t.album)?
            .compactMap({ $0.artworkUrl }).first(where: { !$0.isEmpty }) {
            return resolved
        }
        if let a = t.artworkUrl, !a.isEmpty, !a.contains("2a96cbd8b46e442fc41c2b86b821562f") { return a }
        return nil
    }

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
                if model.isLoading && !model.loaded {
                    PCSkeletonGrid(count: 6)
                } else {
                    tabContent
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

    /// Header artwork (#187): cached Apple-Music-first artist image, falling back
    /// to whatever getArtistInfo populated, then the gradient.
    private var heroImage: String? {
        ArtistImageCache.shared.image(for: model.name) ?? model.info?.imageUrl
    }

    private var hero: some View {
        ZStack(alignment: .bottom) {
            // Base gradient — fills the strip behind the Dynamic Island when the
            // face-aware crop shifts the photo down to clear it.
            LinearGradient(colors: [gradient.0, gradient.1],
                           startPoint: .topLeading, endPoint: .bottomTrailing)
            // Artist photo, face-aware and biased BELOW the Dynamic Island so the
            // face isn't hidden by it (topInset = the status-bar/island height).
            PCArtistImage(url: heroImage.flatMap { $0.isEmpty ? nil : URL(string: $0) },
                          topInset: heroTopInset) {
                Color.clear   // no-art → the base gradient shows
            }
            // Dark scrim for text legibility + fade into the page background.
            LinearGradient(
                colors: [.black.opacity(0.2), .clear, .black.opacity(0.4), PC.bgPrimary],
                startPoint: .top, endPoint: .bottom)
            Text(model.name.uppercased())
                .font(.system(size: 28, weight: .light)).tracking(5)
                .foregroundStyle(.white).multilineTextAlignment(.center)
                .shadow(color: .black.opacity(0.6), radius: 16)
                .padding(.horizontal, 24).padding(.bottom, 64)
        }
        .frame(height: 360)
        .frame(maxWidth: .infinity)
        .clipped()
    }

    /// Top inset covered by the Dynamic Island / status bar (the hero is
    /// full-bleed under it), so the face crop can be biased below it.
    private var heroTopInset: CGFloat {
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first?.safeAreaInsets.top ?? 47
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

    // Release-type filters — same categories, order, logic, and colors as Android's
    // DiscographyTab (#197). The MB mapper's normalizeReleaseType already folds Live
    // and Compilation (MB secondary types) into `releaseType`, so we filter / count /
    // colour by releaseType directly, exactly like Android.
    private struct DiscoFilter: Hashable { let label: String; let key: String }
    private static let discoFilters: [DiscoFilter] = [
        DiscoFilter(label: "All", key: "all"),
        DiscoFilter(label: "Studio Albums", key: "album"),
        DiscoFilter(label: "Singles", key: "single"),
        DiscoFilter(label: "EPs", key: "ep"),
        DiscoFilter(label: "Live", key: "live"),
        DiscoFilter(label: "Compilations", key: "compilation"),
    ]

    /// Per-type chip/badge colour — verbatim from Android's releaseTypeBadgeColor.
    private func releaseTypeColor(_ key: String?) -> Color {
        switch key?.lowercased() {
        case "album":       return Color(uiColor: UIColor(hex: 0x6366F1)) // indigo
        case "ep":          return Color(uiColor: UIColor(hex: 0xA855F7)) // purple
        case "single":      return Color(uiColor: UIColor(hex: 0xEC4899)) // pink
        case "live":        return Color(uiColor: UIColor(hex: 0xF59E0B)) // amber
        case "compilation": return Color(uiColor: UIColor(hex: 0x14B8A6)) // teal
        default:            return Color(uiColor: UIColor(hex: 0x9CA3AF)) // gray
        }
    }

    private func albumTypeKey(_ a: AlbumSearchResult) -> String { (a.releaseType ?? "album").lowercased() }

    private var discoFiltered: [AlbumSearchResult] {
        discoFilter == "all" ? model.albums : model.albums.filter { albumTypeKey($0) == discoFilter }
    }

    private var discographyTab: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Counts per releaseType (only albums with an explicit type — Android parity).
            let typeCounts = Dictionary(grouping: model.albums.compactMap { $0.releaseType?.lowercased() }, by: { $0 })
                .mapValues { $0.count }
            let available = Self.discoFilters.filter { $0.key == "all" || typeCounts[$0.key] != nil }
            if available.count > 1 {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(available, id: \.key) { f in
                            let on = discoFilter == f.key
                            let color = releaseTypeColor(f.key == "all" ? nil : f.key)
                            let count = f.key == "all" ? nil : typeCounts[f.key]
                            let label = count != nil ? "\(f.label) (\(count!))" : f.label
                            Button { discoFilter = f.key } label: {
                                Text(label).font(.system(size: 13, weight: .medium))
                                    .foregroundStyle(on ? color : PC.fg2)
                                    .padding(.horizontal, 14).padding(.vertical, 6)
                                    .background(on ? color.opacity(0.20) : PC.bgInset, in: Capsule())
                                    .overlay(Capsule().strokeBorder(on ? Color.clear : PC.border))
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 20)
                }
                .padding(.vertical, 12)
            }

            LazyVGrid(columns: [GridItem(.flexible(), spacing: 16), GridItem(.flexible(), spacing: 16)], spacing: 18) {
                ForEach(Array(discoFiltered.enumerated()), id: \.offset) { _, album in
                    NavigationLink(value: PCRoute.album(title: album.title, artist: album.artist)) {
                        VStack(alignment: .leading, spacing: 0) {
                            pcCover(album.artworkUrl, seed: album.title + album.artist, size: nil, radius: 10)
                                .aspectRatio(1, contentMode: .fit)
                                .shadow(color: .black.opacity(0.12), radius: 8, y: 4)
                            Text(album.title).font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.fg1)
                                .lineLimit(1).padding(.top, 10)
                            HStack(spacing: 8) {
                                let typeKey = albumTypeKey(album)
                                let typeColor = releaseTypeColor(typeKey)
                                Text(typeKey.uppercased())
                                    .font(.system(size: 9, weight: .bold)).tracking(1)
                                    .foregroundStyle(typeColor)
                                    .padding(.horizontal, 6).padding(.vertical, 3)
                                    .background(typeColor.opacity(0.15), in: RoundedRectangle(cornerRadius: 3))
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
                        pcCover(topTrackArt(t), seed: t.title + t.artist, size: 44, radius: 6)
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

    // Related Artists — matches Android RelatedArtistsTab (#192): a 3-column grid
    // of CIRCULAR artist images with an initial-letter placeholder and a centered
    // 2-line name. Each circle fetches its Apple-Music-first image via the shared
    // ArtistImageCache (#187).
    private var relatedTab: some View {
        let related = model.info?.similarArtists ?? []
        return Group {
            if related.isEmpty {
                Text("No related artists.").font(.system(size: 14)).foregroundStyle(PC.fg3).padding(40)
            } else {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 12, alignment: .top), count: 3), spacing: 16) {
                    ForEach(Array(related.enumerated()), id: \.offset) { _, a in
                        NavigationLink(value: PCRoute.artist(a.name)) {
                            VStack(spacing: 6) {
                                relatedCircle(a)
                                // Reserve a fixed 2-line height so 1- and 2-line
                                // names occupy the same space — keeps every cell
                                // identical and the circles aligned across rows.
                                Text(a.name).font(.system(size: 12, weight: .medium)).foregroundStyle(PC.fg1)
                                    .multilineTextAlignment(.center).lineLimit(2)
                                    .frame(maxWidth: .infinity, minHeight: 32, alignment: .top)
                            }
                        }
                        .buttonStyle(.plain)
                        .onAppear { ArtistImageCache.shared.fetch(a.name) }
                    }
                }
                .padding(.horizontal, 16).padding(.vertical, 12)
            }
        }
    }

    /// Circular related-artist image: AM-first cached image fill-cropped in a
    /// circle, with an initial-letter placeholder until it lands (Android parity).
    private func relatedCircle(_ a: SimilarArtist) -> some View {
        let url = ArtistImageCache.shared.image(for: a.name) ?? nonPlaceholderArt(a.imageUrl)
        // A size-less Color.clear drives the square (= grid column width); the
        // image is an OVERLAY so it fills + clips but can NEVER change the size.
        // Previously the image's native aspect ratio leaked through
        // .aspectRatio(1) into the container, so a portrait vs landscape source
        // gave different circle sizes even within one row (e.g. Cardi B smaller).
        return Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                PCArtistImage(url: url.flatMap { URL(string: $0) }) { initialCircle(a.name) }
            }
            .clipShape(Circle())
            .shadow(color: .black.opacity(0.1), radius: 6, y: 3)
    }

    /// Initial-letter placeholder circle (Android RelatedArtistsTab parity).
    private func initialCircle(_ name: String) -> some View {
        ZStack {
            Circle().fill(PC.bgInset)
            Text(name.prefix(1).uppercased())
                .font(.system(size: 24, weight: .semibold)).foregroundStyle(PC.fg3)
        }
    }

    /// Drop Last.fm's grey-star "no image" placeholder so we fall back to the
    /// initial-letter circle until a real image lands.
    private func nonPlaceholderArt(_ url: String?) -> String? {
        guard let u = url, !u.isEmpty, !u.contains("2a96cbd8b46e442fc41c2b86b821562f") else { return nil }
        return u
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
