import SwiftUI
import Shared
@preconcurrency import Vision

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

/// Stable identity for a discography cell — keyed off the MBID when present,
/// else title+artist. Used so `ForEach`/`.id` recreate cells per album (not per
/// array slot) when the filter changes, refreshing the artwork (bug 7).
extension AlbumSearchResult {
    // Includes releaseType so a same-titled album AND single/EP (distinct
    // releases the discography shows in different buckets) get DISTINCT ids and
    // don't collide in ForEach.
    var discoId: String { "\(mbid ?? "")|\(releaseType ?? "")|\(title)|\(artist)" }
}

private func pcTrack(from t: TrackSearchResult) -> Track {
    Track(
        id: "\(t.title)|\(t.artist)", title: t.title, artist: t.artist,
        album: t.album, albumId: nil, duration: t.duration, artworkUrl: t.artworkUrl,
        sourceType: nil, sourceUrl: nil, addedAt: 0,
        resolver: nil, spotifyUri: nil, soundcloudId: nil,
        spotifyId: t.spotifyId, appleMusicId: nil, isrc: nil, recordingMbid: t.mbid,
        artistMbid: nil, releaseMbid: nil, crossResolverEnrichedAt: nil
    )
}

/// Best available album art for a track, ENRICHED from the resolver cache.
/// Many track lists arrive art-less (Last.fm history, ListenBrainz, AI recs);
/// once a row resolves for its badges, the resolved sources carry Apple Music /
/// Spotify artwork, so we fall back to that. Filters Last.fm's grey-star
/// placeholder hash. Use anywhere a track row shows a cover so art fills in
/// uniformly across the app.
/// True when a track has been RESOLVED but has no playable source — i.e. no
/// resolver matched it above the confidence floor (same filter as the badge row).
/// Used to gray out unplayable track titles. Returns false while still
/// unresolved (cached == nil) so titles don't flash gray before resolution lands.
@MainActor
func pcTrackNoMatch(artist: String, title: String, album: String?) -> Bool {
    guard let s = IosTrackResolverCache.shared.cached(artist: artist, title: title, album: album) else { return false }
    return !s.contains { !$0.noMatch && ($0.confidence?.doubleValue ?? 0) >= 0.60 }
}

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
// Two-tier decoded-image cache: an in-memory NSCache (sync hits, instant
// revisits) BACKED BY a disk store (caches/imgcache/<stable-hash>.img). NSCache
// purges aggressively under memory pressure and is wiped on relaunch, which is
// why album art "reloaded every page view" and the artist hero "disappeared
// after a while" — once evicted, the only recourse was a network re-fetch that
// flashed a placeholder (or failed). The disk tier means an image fetched once
// is decoded from disk on the next visit/launch — no network, no flash.
//
// IMPORTANT: the disk key is a DETERMINISTIC hash (FNV-1a). Swift's
// `String.hashValue` is seeded per process launch, so it could NOT locate disk
// files across launches — defeating the whole cache.
enum PCImageStore {
    private static let memory: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>(); c.countLimit = 500; return c
    }()
    private static let dir: URL = {
        let d = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("imgcache", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }()
    private static func stableKey(_ s: String) -> String {
        var h: UInt64 = 1469598103934665603
        for b in s.utf8 { h = (h ^ UInt64(b)) &* 1099511628211 }
        return String(h, radix: 16)
    }
    private static func diskURL(_ key: String) -> URL {
        dir.appendingPathComponent(stableKey(key) + ".img")
    }
    /// Synchronous MEMORY hit only (safe to call from `body`).
    static func memoryImage(_ url: URL) -> UIImage? {
        memory.object(forKey: url.absoluteString as NSString)
    }
    /// Memory → disk (populates memory on a disk hit). Call from async `load()`.
    static func load(_ url: URL) -> UIImage? {
        let key = url.absoluteString
        if let m = memory.object(forKey: key as NSString) { return m }
        guard let data = try? Data(contentsOf: diskURL(key)), let img = UIImage(data: data) else { return nil }
        memory.setObject(img, forKey: key as NSString)
        return img
    }
    static func store(_ url: URL, _ data: Data, _ img: UIImage) {
        let key = url.absoluteString
        memory.setObject(img, forKey: key as NSString)
        try? data.write(to: diskURL(key), options: .atomic)
    }
}

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
        // Seed SYNCHRONOUSLY from the cache (memory, then disk) at construction.
        // NSCache purges under memory pressure as you browse, so by the time you
        // reopen an artist page its discography art was usually evicted from
        // memory — and the disk tier was only consulted in the async `.task`,
        // which rendered a skeleton first. That skeleton→image churn read as
        // "reloading every time". A disk hit here means a previously-loaded image
        // shows instantly with NO skeleton. LazyVGrid only builds visible cells,
        // so this is ~a handful of small synchronous decodes, not the whole grid.
        _loaded = State(initialValue: url.flatMap { PCImageStore.load($0) })
    }

    private var cachedImage: UIImage? {
        loaded ?? url.flatMap { PCImageStore.memoryImage($0) }
    }

    var body: some View {
        // ZStack + opacity transitions so the skeleton CROSSFADES into the image
        // (and one image crossfades into the NEXT when the url changes on a reused
        // view — e.g. the mini player / Now Playing art on a song transition). A
        // synchronously-seeded image starts as content with no state change, so it
        // still shows instantly with no fade. Keying content by the image identity
        // makes successive images distinct views so the opacity transition fires.
        ZStack {
            if let img = cachedImage {
                content(Image(uiImage: img))
                    .id(ObjectIdentifier(img))
                    .transition(.opacity)
            } else if url == nil || failed {
                placeholder().transition(.opacity)          // genuine no-art fallback (gradient)
            } else {
                PCSkeletonBox(radius: 8).transition(.opacity) // loading — shimmer, not the gradient
            }
        }
        .animation(.easeInOut(duration: 0.35), value: cachedImage.map { ObjectIdentifier($0) })
        .animation(.easeInOut(duration: 0.35), value: failed)
        .task(id: url) { await load() }
    }

    private func load() async {
        guard let url else { return }
        // Always (re)load for the CURRENT url so a reused view whose url changed
        // (mini player / Now Playing on track change) swaps to the new art instead
        // of keeping the stale image. `.task(id: url)` only fires on a url change.
        if let cached = PCImageStore.load(url) { loaded = cached; return }   // memory or disk — no network
        failed = false
        guard let (data, _) = try? await URLSession.shared.data(from: url),
              let ui = UIImage(data: data) else {
            failed = true
            return
        }
        PCImageStore.store(url, data, ui)
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
    let topInset: CGFloat
    let placeholder: () -> Placeholder
    @State private var loaded: UIImage?
    @State private var detected: UnitPoint?
    @State private var failed = false

    init(url: URL?, topInset: CGFloat = 0, @ViewBuilder placeholder: @escaping () -> Placeholder) {
        self.url = url
        self.topInset = topInset
        self.placeholder = placeholder
        // Seed synchronously from memory/disk so a revisited hero/related image
        // shows instantly (no skeleton/disappear after NSCache eviction). The
        // face center is read separately from pcFaceCache by `center`, so the
        // crop is correct on the first frame too.
        _loaded = State(initialValue: url.flatMap { PCImageStore.load($0) })
    }

    private var uiImage: UIImage? {
        loaded ?? url.flatMap { PCImageStore.memoryImage($0) }
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
                .animation(.easeInOut(duration: 0.35), value: uiImage != nil)
                .animation(.easeInOut(duration: 0.35), value: failed)
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
                .transition(.opacity)
        } else if url == nil || failed {
            placeholder().transition(.opacity)
        } else {
            PCSkeletonBox(radius: 8).transition(.opacity)
        }
    }

    private func load() async {
        guard let url else { return }
        let key = url.absoluteString
        if uiImage == nil {
            if let cached = PCImageStore.load(url) {        // memory or disk — survives eviction/relaunch
                loaded = cached
            } else {
                failed = false
                guard let (data, _) = try? await URLSession.shared.data(from: url),
                      let ui = UIImage(data: data) else { failed = true; return }
                PCImageStore.store(url, data, ui)
                loaded = ui
            }
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

// Session cache of loaded album pages (see ArtistDetailCache). AlbumScreen is
// recreated on every navigation, so without this each reopen re-fetched the
// tracklist and flashed the skeleton.
@MainActor
final class AlbumDetailCache {
    static let shared = AlbumDetailCache()
    struct Entry { var detail: AlbumDetail?; var entities: [Track]; var ts: Date }
    private var cache: [String: Entry] = [:]
    func get(_ key: String) -> Entry? { cache[key] }
    func put(_ key: String, _ e: Entry) { cache[key] = e }
}

@MainActor @Observable
final class AlbumDetailModel {
    private let container = IosContainer.companion.shared
    let title: String
    let artist: String
    var detail: AlbumDetail?
    var entities: [Track] = []
    var isLoading = false
    var loaded = false
    /// Reactive in-collection state (#195 M4) backing the header heart toggle.
    var inCollection = false
    private var collectionSub: Cancellable?
    private let ttl: TimeInterval = 6 * 3600
    private var cacheKey: String { "\(title)|\(artist)".lowercased() }

    init(title: String, artist: String) { self.title = title; self.artist = artist }

    /// Subscribe to the collection flow once (idempotent). Cancel on disappear.
    func watchCollection() {
        guard collectionSub == nil else { return }
        collectionSub = container.watchAlbumInCollection(title: title, artist: artist) { [weak self] yes in
            self?.inCollection = yes.boolValue
        }
    }
    func stopWatching() { collectionSub?.cancel(); collectionSub = nil }

    func toggleCollection() {
        Task {
            try? await container.toggleAlbumCollection(
                title: title, artist: artist, artworkUrl: detail?.artworkUrl,
                year: detail?.year?.int32Value ?? 0, trackCount: Int32(detail?.tracks.count ?? 0))
        }
    }

    func load() async {
        guard !loaded else { return }
        if let c = AlbumDetailCache.shared.get(cacheKey) {
            detail = c.detail; entities = c.entities; loaded = true
            if Date().timeIntervalSince(c.ts) > ttl { Task { await refresh() } }
            return
        }
        isLoading = true
        detail = try? await container.getAlbumDetail(albumTitle: title, artistName: artist)
        entities = detail?.tracks.map { pcTrack(from: $0) } ?? []
        isLoading = false
        loaded = true
        AlbumDetailCache.shared.put(cacheKey, .init(detail: detail, entities: entities, ts: Date()))
    }

    private func refresh() async {
        if let d = try? await container.getAlbumDetail(albumTitle: title, artistName: artist) {
            detail = d; entities = d.tracks.map { pcTrack(from: $0) }
            AlbumDetailCache.shared.put(cacheKey, .init(detail: d, entities: entities, ts: Date()))
        }
    }

    func resolveVisible(_ t: TrackSearchResult, index: Int) {
        IosTrackResolverCache.shared.resolve(
            ResolveRequest(artist: t.artist, title: t.title, album: t.album, spotifyId: t.spotifyId), order: index)
    }
}

struct AlbumScreen: View {
    @State private var model: AlbumDetailModel
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    @Environment(\.dismiss) private var dismiss
    /// Observed so the resolver badges re-render as background resolution lands.
    private var resolverCache = IosTrackResolverCache.shared
    /// LOCAL item-based navigation. AlbumScreen is pushed from many different
    /// stacks (Home's PCRoute path, Search, and item-based navAlbum in
    /// Recommendations/History/Pop/Playlist). A value-based `PCRoute.artist` link
    /// only resolves in the Home stack, so from any other host it mis-routed
    /// (pushed the artist onto the wrong path → "album opened, artist appeared on
    /// top"). A self-contained `navigationDestination(item:)` works in ANY host.
    @State private var navArtist: String?
    @State private var showAlbumMenu = false

    init(title: String, artist: String) {
        _model = State(initialValue: AlbumDetailModel(title: title, artist: artist))
    }

    /// Queue-source context (#209). iOS album routing needs title + artist, so the
    /// artist rides in `id` (there's no album-id concept on the iOS detail route).
    private var albumContext: PlaybackContext {
        PlaybackContext(type: "album", name: model.title, id: model.artist)
    }

    var body: some View {
        VStack(spacing: 0) {
            // "More" (⋯) opens the album options sheet — mirrors Android's
            // AlbumOptionsSheet + the iOS design's ios-detail-more button. The
            // collection toggle lives here (Favorite/HeartBroken), NOT as a header
            // icon (that's the artist screen's pattern — a header Star).
            PCTopBar(title: model.title, leading: .back, onLeading: { dismiss() },
                     trailingIcon: "ellipsis", onTrailing: { showAlbumMenu = true })
            ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                if !model.entities.isEmpty {
                    Button { coordinator.setQueue(model.entities, startIndex: 0, context: albumContext) } label: {
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
        .navigationDestination(item: $navArtist) { ArtistScreen(artistName: $0) }
        .confirmationDialog(model.title, isPresented: $showAlbumMenu, titleVisibility: .visible) {
            Button("Queue Album") { model.entities.forEach { coordinator.addToQueue($0) } }
            Button("Go to Artist") { navArtist = model.artist }
            Button(model.inCollection ? "Remove from Collection" : "Add to Collection") { model.toggleCollection() }
        }
        .task { await model.load() }
        .onAppear { model.watchCollection() }
        .onDisappear { model.stopWatching() }
    }

    private var header: some View {
        HStack(alignment: .top, spacing: 16) {
            pcCover(model.detail?.artworkUrl, seed: model.title + model.artist, size: 132, radius: 12)
                .shadow(color: .black.opacity(0.18), radius: 9, y: 6)
            VStack(alignment: .leading, spacing: 6) {
                Text(model.title).font(.system(size: 21, weight: .semibold)).foregroundStyle(PC.fg1)
                Button { navArtist = model.artist } label: {
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

    private var isCompilation: Bool {
        model.detail?.releaseType?.lowercased() == "compilation"
    }

    private var tracklist: some View {
        LazyVStack(spacing: 0) {
            ForEach(Array((model.detail?.tracks ?? []).enumerated()), id: \.offset) { index, t in
                Button { coordinator.setQueue(model.entities, startIndex: index, context: albumContext) } label: {
                    HStack(spacing: 12) {
                        Text("\(index + 1)").font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(PC.fg3).frame(width: 22)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t.title)
                                .font(.system(size: 15, weight: .medium))
                                .foregroundStyle(pcTrackNoMatch(artist: t.artist, title: t.title, album: t.album) ? PC.fg3
                                    : (coordinator.currentTrack?.id == model.entities[index].id ? PC.accent : PC.fg1))
                                .lineLimit(1)
                            // Always show the per-track artist for a consistent
                            // tracklist look (and correct for compilations).
                            if !t.artist.isEmpty {
                                Text(t.artist).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                            }
                        }
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

// Cross-screen cache of loaded artist pages (info + top tracks + discography),
// keyed by artist name. ArtistScreen — and thus ArtistDetailModel — is recreated
// on every navigation, so WITHOUT this every reopen of the SAME artist re-fetched
// from the network and flashed the skeletons (the reported "still see the shimmer"
// on reopen). Session-scoped; stale entries revalidate quietly in the background.
@MainActor
final class ArtistDetailCache {
    static let shared = ArtistDetailCache()
    struct Entry {
        var info: ArtistInfo?
        var topTracks: [TrackSearchResult]
        var topEntities: [Track]
        var albums: [AlbumSearchResult]
        var albumsError: Bool
        var ts: Date
    }
    private var cache: [String: Entry] = [:]
    func get(_ name: String) -> Entry? { cache[name.lowercased()] }
    func put(_ name: String, _ e: Entry) { cache[name.lowercased()] = e }
}

@MainActor @Observable
final class ArtistDetailModel {
    private let container = IosContainer.companion.shared
    let name: String
    var info: ArtistInfo?
    var topTracks: [TrackSearchResult] = []
    var topEntities: [Track] = []
    var albums: [AlbumSearchResult] = []
    /// Upcoming tour dates (unfiltered — full schedule) for the On Tour tab (#201).
    var tourDates: [ConcertEvent] = []
    /// True when the artist has any upcoming dates → the On Tour tab appears (last).
    var isOnTour = false
    var isLoading = false
    var loaded = false
    /// True when the discography fetch FAILED (a provider — typically MusicBrainz
    /// — errored) vs. the artist genuinely having no releases. Drives the friendly
    /// "couldn't load, try again" state instead of a bare empty grid.
    var albumsError = false
    /// Reactive in-collection state (#195 M4) backing the header heart toggle.
    var inCollection = false
    private var collectionSub: Cancellable?

    init(name: String) { self.name = name }

    /// Subscribe to the collection flow once (idempotent). Cancel on disappear.
    func watchCollection() {
        guard collectionSub == nil else { return }
        collectionSub = container.watchArtistInCollection(name: name) { [weak self] yes in
            self?.inCollection = yes.boolValue
        }
    }
    func stopWatching() { collectionSub?.cancel(); collectionSub = nil }

    func toggleCollection() {
        let image = ArtistImageCache.shared.image(for: name) ?? info?.imageUrl
        Task { try? await container.toggleArtistCollection(name: name, imageUrl: image) }
    }

    private let ttl: TimeInterval = 6 * 3600   // discography rarely changes — revalidate sparingly

    func load() async {
        guard !loaded else { return }
        // Reuse a previously-loaded artist INSTANTLY (no skeleton on reopen).
        if let c = ArtistDetailCache.shared.get(name) {
            info = c.info; topTracks = c.topTracks; topEntities = c.topEntities
            albums = c.albums; albumsError = c.albumsError
            loaded = true
            ArtistImageCache.shared.fetch(name)
            loadTourDates()   // tour dates aren't cached — always (re)fetch (#201)
            // Stale-while-revalidate: refresh quietly in the background past the
            // TTL — updates in place, no skeleton.
            if Date().timeIntervalSince(c.ts) > ttl { Task { await refresh() } }
            return
        }
        isLoading = true
        info = try? await container.getArtistInfo(artistName: name)
        ArtistImageCache.shared.fetch(name)   // header image (#187)
        topTracks = (try? await container.getArtistTopTracks(artistName: name)) ?? []
        topEntities = topTracks.map { pcTrack(from: $0) }
        loadTourDates()   // fire-and-forget so it doesn't delay the page (#201)
        await loadDiscography()
        isLoading = false
        loaded = true
    }

    /// Fetch the unfiltered tour schedule and toggle the On Tour tab on/off.
    /// Non-blocking — runs in its own Task so the rest of the page isn't delayed.
    private func loadTourDates() {
        Task {
            let ev = (try? await container.getArtistEvents(artistName: name)) ?? []
            tourDates = ev
            isOnTour = !ev.isEmpty
        }
    }

    private func saveToCache() {
        ArtistDetailCache.shared.put(name, .init(
            info: info, topTracks: topTracks, topEntities: topEntities,
            albums: albums, albumsError: albumsError, ts: Date()))
    }

    /// Background revalidation (stale-while-revalidate) — updates in place.
    private func refresh() async {
        if let fresh = try? await container.getArtistInfo(artistName: name) { info = fresh }
        let freshTop = (try? await container.getArtistTopTracks(artistName: name)) ?? []
        if !freshTop.isEmpty { topTracks = freshTop; topEntities = freshTop.map { pcTrack(from: $0) } }
        if let fresh = try? await container.getArtistAlbums(artistName: name), !fresh.isEmpty {
            albums = sortedByYearDesc(fresh); albumsError = false
        }
        loadTourDates()   // refresh tour dates too (#201)
        saveToCache()
    }

    /// Fetch the discography, distinguishing a provider FAILURE (→ friendly error)
    /// from a genuinely-empty discography. `getArtistAlbums` throws
    /// DiscographyUnavailableException only when empty BECAUSE a provider errored.
    func loadDiscography() async {
        do {
            albums = sortedByYearDesc(try await container.getArtistAlbums(artistName: name))
            albumsError = false
        } catch {
            albumsError = true
            saveToCache()
            return
        }
        // Android parity (ArtistViewModel.retryDiscography): the first fetch can
        // come back without year/releaseType when MusicBrainz was rate-limited in
        // the parallel burst — so compilations/live get no type and can't bucket.
        // Re-fetch once after a short delay and adopt the typed result.
        if !albums.isEmpty && albums.allSatisfy({ $0.year == nil }) {
            try? await Task.sleep(nanoseconds: 2_000_000_000)
            if let retry = try? await container.getArtistAlbums(artistName: name),
               retry.contains(where: { $0.year != nil }) {
                albums = sortedByYearDesc(retry)
            }
        }
        saveToCache()
    }

    /// User-triggered retry from the discography error state.
    func retryDiscography() async {
        albumsError = false
        await loadDiscography()
    }

    /// Most-recent-first by release year (Android: `.sortedByDescending { it.year ?: 0 }`).
    private func sortedByYearDesc(_ list: [AlbumSearchResult]) -> [AlbumSearchResult] {
        list.sorted { ($0.year?.intValue ?? 0) > ($1.year?.intValue ?? 0) }
    }

    // Resolver pipeline (CLAUDE.md): every track list resolves per visible row.
    func resolveVisible(_ t: TrackSearchResult, index: Int) {
        IosTrackResolverCache.shared.resolve(
            ResolveRequest(artist: t.artist, title: t.title, album: t.album, spotifyId: t.spotifyId), order: index)
    }
}

/// Not `private` — the Now Playing on-tour dot (NowPlayingView) deep-links to
/// `ArtistScreen(artistName:initialTab: .onTour)`, so the enum must be visible
/// across the module (#201).
enum ArtistTab: String, CaseIterable {
    case discography = "Discography", topTracks = "Top Tracks"
    case biography = "Biography", related = "Related Artists"
    case onTour = "On Tour"
}

/// Design-matched Artist screen (screens.jsx ArtistScreen): 360px gradient
/// hero with the name in it + "Play Top Tracks" CTA + 4 tabs (Discography
/// grid / Top Tracks / Biography / Related Artists).
struct ArtistScreen: View {
    @State private var model: ArtistDetailModel
    @State private var tab: ArtistTab
    @State private var discoFilter = "all"
    @Environment(QueuePlaybackCoordinator.self) private var coordinator
    @Environment(\.dismiss) private var dismiss
    /// Observed so top-track art fills in from the resolver as rows resolve.
    private var resolverCache = IosTrackResolverCache.shared
    /// LOCAL item-based navigation (see AlbumScreen.navArtist). ArtistScreen is
    /// reached from many stacks; value-based `PCRoute` links only resolve in the
    /// Home stack, so tapping an album from a non-Home-hosted artist page
    /// mis-routed. Self-contained destinations work from any host.
    @State private var navAlbum: PCAlbumRef?
    @State private var navArtist: String?

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

    /// `initialTab` lets the Now Playing on-tour dot deep-link straight to the
    /// On Tour tab (#201). Defaults to Discography for every existing caller.
    init(artistName: String, initialTab: ArtistTab = .discography) {
        _model = State(initialValue: ArtistDetailModel(name: artistName))
        _tab = State(initialValue: initialTab)
    }

    /// Queue-source context (#209) for top-songs playback → links back here.
    private var artistContext: PlaybackContext {
        PlaybackContext(type: "artist", name: model.name, id: nil)
    }

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
                    // Discography renders its OWN skeleton (filter chips + grid) so
                    // the chip row is reserved and the layout doesn't shift when it
                    // loads (bug 3). Other tabs keep the generic grid skeleton.
                    if tab == .discography { discographyTab } else { PCSkeletonGrid(count: 6) }
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
        // Collection toggle (#195 M4) — mirrors Android's ArtistScreen header
        // action: Star (saved, amber 0xF59E0B) / StarOutline (not saved). Glass
        // treatment matches the back button (the hero is full-bleed, no PCTopBar).
        .overlay(alignment: .topTrailing) {
            Button { model.toggleCollection() } label: {
                Image(systemName: model.inCollection ? "star.fill" : "star")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(model.inCollection ? Color(uiColor: UIColor(hex: 0xF59E0B)) : PC.fg1)
                    .frame(width: 36, height: 36)
                    .background(.ultraThinMaterial, in: Circle())
            }
            .padding(.trailing, 16).padding(.top, 4)
        }
        .navigationDestination(item: $navAlbum) { AlbumScreen(title: $0.title, artist: $0.artist) }
        .navigationDestination(item: $navArtist) { ArtistScreen(artistName: $0) }
        .task { await model.load() }
        .onAppear { model.watchCollection() }
        .onDisappear { model.stopWatching() }
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
            if !model.topEntities.isEmpty { coordinator.setQueue(model.topEntities, startIndex: 0, context: artistContext) }
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

    /// Tab order matches Android: the four base tabs, with On Tour appended LAST
    /// and only when the artist has upcoming dates (ArtistScreen.kt buildList).
    private var visibleTabs: [ArtistTab] {
        var tabs: [ArtistTab] = [.discography, .topTracks, .biography, .related]
        if model.isOnTour { tabs.append(.onTour) }
        return tabs
    }

    private var tabBar: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 24) {
                ForEach(visibleTabs, id: \.self) { t in
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
        case .onTour:      onTourTab
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

    /// Collapse duplicate titles to ONE entry, keeping the most meaningful release
    /// type. Two distinct cases:
    ///   • Cross-provider conflict: MusicBrainz tags a greatest-hits `compilation`
    ///     while Spotify reports the same title as `album` — prefer compilation.
    ///   • MusicBrainz itself lists a studio album AND a promo/early EP or lead
    ///     single under the SAME title (e.g. Fontaines D.C. "Dogrel" = EP + Album,
    ///     "Skinty Fia" = Single + Album) — the STUDIO ALBUM must win, not the EP.
    /// So `album` outranks `ep`/`single`, while `compilation`/`live` (meaningful
    /// re-categorizations of what would otherwise be an album) outrank `album`.
    /// Preserves the model's most-recent-first order (first appearance wins slot).
    private var discoFiltered: [AlbumSearchResult] {
        // Use the shared-deduped list as-is (Android parity). The shared
        // `deduplicateAlbums` already collapses true duplicates but KEEPS distinct
        // release types under the same title — an artist can have both an album AND
        // a same-named single/EP (e.g. Fontaines D.C. "Skinty Fia" = Single + Album),
        // and BOTH should appear, each in its own bucket. Filter by the RAW
        // releaseType — never default null to "album".
        discoFilter == "all" ? model.albums : model.albums.filter { $0.releaseType?.lowercased() == discoFilter }
    }

    // Reserve the chip-row footprint while loading so the grid doesn't jump when
    // the filters appear (bug 3).
    private var discoFilterSkeleton: some View {
        HStack(spacing: 8) {
            ForEach(0..<4, id: \.self) { _ in PCSkeletonBox(width: 78, height: 30, radius: 15) }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20).padding(.vertical, 12)
    }

    // Friendly error shown when a provider (typically MusicBrainz) failed, rather
    // than the misleading empty state. Parity with Android's DiscographyTab error.
    private var discographyError: some View {
        VStack(spacing: 12) {
            Image(systemName: "icloud.slash").font(.system(size: 40)).foregroundStyle(PC.fg3)
            Text("Couldn't load discography").font(.system(size: 16, weight: .semibold)).foregroundStyle(PC.fg1)
            Text("Something went wrong reaching the music database. Please try again.")
                .font(.system(size: 13)).foregroundStyle(PC.fg2)
                .multilineTextAlignment(.center).padding(.horizontal, 40)
            Button { Task { await model.retryDiscography() } } label: {
                Text("Try Again").font(.system(size: 15, weight: .semibold)).foregroundStyle(.white)
                    .padding(.horizontal, 22).frame(height: 42).background(PC.accent, in: Capsule())
            }
            .buttonStyle(.plain).padding(.top, 4)
        }
        .frame(maxWidth: .infinity).padding(.vertical, 50)
    }

    private var discographyTab: some View {
        VStack(alignment: .leading, spacing: 0) {
            if model.isLoading && !model.loaded {
                discoFilterSkeleton
                PCSkeletonGrid(count: 6, columns: 2)
            } else if model.albumsError && model.albums.isEmpty {
                discographyError
            } else {
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
                    // Keyed by album identity (NOT array offset) so switching filters
                    // gives each cell stable identity — the art refreshes with the
                    // title instead of reusing the prior cell's cached image (bug 7).
                    ForEach(discoFiltered, id: \.discoId) { album in
                        Button { navAlbum = PCAlbumRef(title: album.title, artist: album.artist) } label: {
                            VStack(alignment: .leading, spacing: 0) {
                                pcCover(album.artworkUrl, seed: album.title + album.artist, size: nil, radius: 10)
                                    .aspectRatio(1, contentMode: .fit)
                                    .shadow(color: .black.opacity(0.12), radius: 8, y: 4)
                                Text(album.title).font(.system(size: 14, weight: .semibold)).foregroundStyle(PC.fg1)
                                    .lineLimit(1).padding(.top, 10)
                                HStack(spacing: 8) {
                                    // Badge ONLY when typed (Android parity — no "ALBUM" default).
                                    if let typeKey = album.releaseType?.lowercased() {
                                        let typeColor = releaseTypeColor(typeKey)
                                        Text(typeKey.uppercased())
                                            .font(.system(size: 9, weight: .bold)).tracking(1)
                                            .foregroundStyle(typeColor)
                                            .padding(.horizontal, 6).padding(.vertical, 3)
                                            .background(typeColor.opacity(0.15), in: RoundedRectangle(cornerRadius: 3))
                                    }
                                    if let y = album.year { Text(String(y.intValue)).font(.system(size: 12)).foregroundStyle(PC.fg2) }
                                }
                                .padding(.top, 4)
                            }
                        }
                        .buttonStyle(.plain)
                        // NOTE: do NOT put `.id(album.discoId)` here. ArtistScreen
                        // re-renders often (it observes coordinator + resolverCache);
                        // an `.id` on a value-based NavigationLink makes SwiftUI
                        // recreate the link subtree on those renders, which tears
                        // down an in-flight push and pops AlbumScreen back to the
                        // artist. The ForEach `id: \.discoId` above already gives
                        // each cell stable per-album identity for the art refresh.
                    }
                }
                .padding(.horizontal, 20).padding(.vertical, 12)
            }
        }
    }

    // MARK: Top Tracks (resolver pipeline per row)

    private var topTracksTab: some View {
        LazyVStack(spacing: 0) {
            ForEach(Array(model.topTracks.enumerated()), id: \.offset) { index, t in
                Button { coordinator.setQueue(model.topEntities, startIndex: index, context: artistContext) } label: {
                    HStack(spacing: 12) {
                        Text("\(index + 1)").font(.system(size: 15, weight: .bold)).foregroundStyle(PC.accent)
                            .frame(width: 22)
                        pcCover(topTrackArt(t), seed: t.title + t.artist, size: 44, radius: 6)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(t.title).font(.system(size: 15, weight: .medium))
                                .foregroundStyle(pcTrackNoMatch(artist: t.artist, title: t.title, album: t.album) ? PC.fg3 : PC.fg1).lineLimit(1)
                            // Artist under the title — consistent with album tracklists.
                            if !t.artist.isEmpty {
                                Text(t.artist).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                            }
                        }
                        Spacer(minLength: 0)
                        if let d = t.duration, d.int64Value > 0 {
                            Text(pcDur(d.int64Value)).font(.system(size: 13, design: .monospaced)).foregroundStyle(PC.fg3)
                        }
                        // Resolver icons (parity with Pop of the Tops > Songs).
                        if let sources = resolverCache.cached(artist: t.artist, title: t.title, album: t.album),
                           !sources.isEmpty {
                            ResolverBadgeRow(sources: sources)
                        }
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
                        Button { navArtist = a.name } label: {
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

    // MARK: On Tour (#201)

    // Mirrors Android's OnTourTab / TourDateRow (ArtistScreen.kt): a list of
    // upcoming dates — teal MMM/day column + name + venue + city/time + a ticket
    // link. UNFILTERED by location (the full schedule). On Tour appears only when
    // the artist has dates, and is appended last (see `visibleTabs`).
    private var onTourTab: some View {
        Group {
            if model.tourDates.isEmpty {
                Text("No upcoming tour dates").font(.system(size: 14)).foregroundStyle(PC.fg3)
                    .frame(maxWidth: .infinity).padding(40)
            } else {
                LazyVStack(spacing: 0) {
                    ForEach(model.tourDates, id: \.id) { e in
                        tourDateRow(e)
                        Divider().padding(.leading, 84)
                    }
                }
            }
        }
    }

    private func tourDateRow(_ e: ConcertEvent) -> some View {
        HStack(alignment: .top, spacing: 12) {
            // Teal date column (Android: month abbrev + day in #10C9B4 / onSurface).
            VStack(spacing: 1) {
                Text(artistTourFmt(e.date, "MMM").uppercased())
                    .font(.system(size: 11, weight: .semibold)).tracking(0.5)
                    .foregroundStyle(PC.onTour)
                Text(artistTourFmt(e.date, "d"))
                    .font(.system(size: 22, weight: .bold)).foregroundStyle(PC.fg1)
            }
            .frame(width: 56)
            VStack(alignment: .leading, spacing: 2) {
                Text(e.name).font(.system(size: 15, weight: .medium)).foregroundStyle(PC.fg1)
                    .lineLimit(2)
                if let v = e.venueName, !v.isEmpty {
                    Text(v).font(.system(size: 13)).foregroundStyle(PC.fg2).lineLimit(1)
                }
                // Android parity (ConcertsRepository.locationString): city, state,
                // country. displayLocation (when the repo set it) already carries
                // the full string; the fallback now includes country too.
                let loc = e.displayLocation ?? [e.city, e.state, e.country].compactMap { $0 }.joined(separator: ", ")
                if !loc.isEmpty {
                    HStack(spacing: 4) {
                        Image(systemName: "mappin").font(.system(size: 10)).foregroundStyle(PC.fg3)
                        Text(loc).font(.system(size: 11)).foregroundStyle(PC.fg3).lineLimit(1)
                        let t12 = artistTourTime(e.time)
                        if !t12.isEmpty {
                            Text("· \(t12)").font(.system(size: 11)).foregroundStyle(PC.fg3)
                        }
                    }
                }
            }
            Spacer(minLength: 0)
            if let url = e.ticketUrl, let u = URL(string: url) {
                Link(destination: u) {
                    Image(systemName: "arrow.up.right.square").font(.system(size: 18)).foregroundStyle(PC.onTour)
                }
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
    }

    /// 24h "HH:mm" → 12h "h:mm a" (Android parity: ConcertsRepository.displayTime).
    /// Returns "" for nil/blank/unparseable input so the row just omits the time.
    private func artistTourTime(_ raw: String?) -> String {
        guard let raw, !raw.isEmpty else { return "" }
        let inFmt = DateFormatter(); inFmt.locale = Locale(identifier: "en_US_POSIX"); inFmt.dateFormat = "HH:mm"
        guard let d = inFmt.date(from: raw) else { return "" }
        let out = DateFormatter(); out.locale = Locale(identifier: "en_US"); out.dateFormat = "h:mm a"
        return out.string(from: d)
    }

    /// Format an ISO `yyyy-MM-dd` date string with `pattern` (e.g. "MMM", "d").
    private func artistTourFmt(_ raw: String?, _ pattern: String) -> String {
        guard let raw, raw.count >= 10 else { return "" }
        let inFmt = DateFormatter()
        inFmt.dateFormat = "yyyy-MM-dd"; inFmt.locale = Locale(identifier: "en_US_POSIX")
        guard let d = inFmt.date(from: String(raw.prefix(10))) else { return "" }
        let outFmt = DateFormatter()
        outFmt.dateFormat = pattern; outFmt.locale = Locale(identifier: "en_US")
        return outFmt.string(from: d)
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
