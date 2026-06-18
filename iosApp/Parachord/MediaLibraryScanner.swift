import SwiftUI
import MediaPlayer
import AVFoundation
import Shared

// MARK: - Local file library (#219 / #195)
//
// iOS's analogue of Android's MediaScanner. iOS has no general "files" library —
// local music lives in the device's Music library (MPMediaLibrary). We query the
// songs and keep only genuinely-local, DRM-free items: an item must have a
// non-nil `assetURL` (an `ipod-library://` URL), AND that asset must not be
// FairPlay-protected. Persist the survivors to the shared DB as
// `resolver = "localfiles"` tracks; the native localfiles resolver
// (IosResolverRuntime) matches + routes them to AVPlayer (PlaybackRouter cases
// "localfiles" → avPlayer).
//
// CRITICAL: a non-nil `assetURL` does NOT mean "playable local file". Downloaded
// Apple Music tracks expose a non-nil `assetURL` pointing at a FairPlay-protected
// `.movpkg` container (legacy iTunes-Store DRM is `.m4p`). AVPlayer can't decrypt
// either, so they'd resolve a `localfiles` badge that silently fails to play —
// they already play via the Apple Music resolver (MusicKit holds the key). We
// skip those extensions so only real on-disk files become localfiles tracks.

@MainActor
@Observable
final class MediaLibraryScanner {
    static let shared = MediaLibraryScanner()
    private let container = IosContainer.companion.shared

    enum Phase: Equatable {
        case idle
        case requesting
        case scanning(done: Int, total: Int)
        case done(Int)
        case importing(done: Int, total: Int)
        case imported(Int)
        case denied
        case failed(String)
    }

    var phase: Phase = .idle
    /// Local-library tracks currently persisted (Settings display).
    var libraryCount = 0

    func refreshCount() {
        Task { @MainActor in libraryCount = Int((try? await container.localTrackCount()) ?? 0) }
    }

    func scan() {
        if case .scanning = phase { return }   // already running
        if case .requesting = phase { return }
        Task { @MainActor in await runScan() }
    }

    /// Import audio files the user picked from the Files app (document picker).
    /// iOS sandboxing means the Music-library scan (MPMediaQuery) can't see files
    /// in Files / Downloads / iCloud Drive — this is the bridge for those.
    func importFiles(_ urls: [URL]) {
        if case .scanning = phase { return }
        if case .importing = phase { return }
        Task { @MainActor in await runImport(urls) }
    }

    private func runImport(_ urls: [URL]) async {
        guard !urls.isEmpty else { return }
        phase = .importing(done: 0, total: urls.count)
        var tracks: [Track] = []
        for (i, src) in urls.enumerated() {
            if let t = await Self.importOne(src) { tracks.append(t) }
            phase = .importing(done: i + 1, total: urls.count)
        }
        do {
            try await container.addImportedFiles(tracks: tracks)
            phase = .imported(tracks.count)
            refreshCount()
        } catch {
            phase = .failed(error.localizedDescription)
        }
    }

    private func runScan() async {
        phase = .requesting
        guard await requestAuth() else { phase = .denied; return }

        let items = MPMediaQuery.songs().items ?? []
        phase = .scanning(done: 0, total: items.count)

        var tracks: [Track] = []
        for (i, item) in items.enumerated() {
            // Cloud-only items have no on-disk asset → AVPlayer can't play them.
            guard let assetURL = item.assetURL else { continue }
            // Downloaded Apple Music (.movpkg) / legacy iTunes DRM (.m4p) DO have
            // a non-nil assetURL but are FairPlay-protected — AVPlayer can't
            // decrypt them. They already play via the Apple Music resolver, so
            // skip them here rather than store a dead localfiles source.
            let ext = assetURL.pathExtension.lowercased()
            if ext == "movpkg" || ext == "m4p" { continue }
            let art = Self.saveArtwork(item)
            tracks.append(Self.makeTrack(item, assetURL: assetURL, artworkUrl: art))
            if i % 25 == 0 { phase = .scanning(done: i, total: items.count) }
            await Task.yield()
        }

        do {
            try await container.addLocalTracks(tracks: tracks)
            phase = .done(tracks.count)
            refreshCount()
        } catch {
            phase = .failed(error.localizedDescription)
        }
    }

    private func requestAuth() async -> Bool {
        if MPMediaLibrary.authorizationStatus() == .authorized { return true }
        return await withCheckedContinuation { cont in
            MPMediaLibrary.requestAuthorization { status in cont.resume(returning: status == .authorized) }
        }
    }

    /// Extract embedded album art to a cached file:// (so local tracks show their
    /// own art). Returns nil when the item has no artwork → online enrichment fills
    /// it later (MBID/image enrichment), matching Android's "no embedded art" path.
    private static func saveArtwork(_ item: MPMediaItem) -> String? {
        guard let artwork = item.artwork,
              let image = artwork.image(at: CGSize(width: 300, height: 300)),
              let data = image.jpegData(compressionQuality: 0.85) else { return nil }
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("localart", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("\(item.persistentID).jpg")
        try? data.write(to: url)
        return url.absoluteString
    }

    /// Standalone (explicit return type) to dodge Swift's closure type-checker
    /// timeout on the 19-param Track init (iosApp/AGENTS.md).
    private static func makeTrack(_ item: MPMediaItem, assetURL: URL, artworkUrl: String?) -> Track {
        Track(
            id: "local-\(item.persistentID)",
            title: item.title ?? "Unknown",
            artist: item.artist ?? "Unknown Artist",
            album: item.albumTitle, albumId: nil,
            duration: KotlinLong(value: Int64(item.playbackDuration * 1000)),
            artworkUrl: artworkUrl,
            sourceType: "local",
            sourceUrl: assetURL.absoluteString,
            addedAt: 0,
            resolver: "localfiles",
            spotifyUri: nil, soundcloudId: nil, spotifyId: nil, appleMusicId: nil,
            isrc: nil, recordingMbid: nil, artistMbid: nil, releaseMbid: nil,
            crossResolverEnrichedAt: nil
        )
    }

    // MARK: - Files-app import

    /// Copy one picked file into the sandbox, read its tags, build a localfiles
    /// Track. WAV/AIFF usually carry no tags, so title falls back to the original
    /// filename and artist to "Unknown Artist" (Android shows the filename too).
    private static func importOne(_ src: URL) async -> Track? {
        // Heavy copy runs OFF the main actor (nonisolated); URL is Sendable.
        guard let dest = await Task.detached(priority: .userInitiated, operation: {
            copyIntoSandbox(src)
        }).value else { return nil }

        let asset = AVURLAsset(url: dest)
        var title = src.deletingPathExtension().lastPathComponent   // filename fallback
        var artist = "Unknown Artist"
        var album: String? = nil
        var artData: Data? = nil
        if let meta = try? await asset.load(.commonMetadata) {
            for item in meta {
                guard let key = item.commonKey else { continue }
                switch key {
                case .commonKeyTitle:     if let v = await loadString(item) { title = v }
                case .commonKeyArtist:    if let v = await loadString(item) { artist = v }
                case .commonKeyAlbumName: if let v = await loadString(item) { album = v }
                case .commonKeyArtwork:   if let d = (try? await item.load(.dataValue)) ?? nil { artData = d }
                default: break
                }
            }
        }
        let durSec = (try? await asset.load(.duration)).map { CMTimeGetSeconds($0) } ?? 0
        let durationMs = (durSec.isFinite && durSec > 0) ? Int64(durSec * 1000) : 0
        let artUrl = artData.flatMap { saveArtData($0, key: dest.lastPathComponent) }
        return makeImportedTrack(localURL: dest, title: title, artist: artist, album: album,
                                 durationMs: durationMs, artworkUrl: artUrl)
    }

    /// Copy a security-scoped picked URL into Documents/localfiles/<uuid>.<ext> so
    /// the file is permanently ours (the picked URL may be in iCloud/Downloads and
    /// get evicted). `nonisolated` so the (potentially large) copy stays off the
    /// main actor. Returns the sandbox URL, or nil on failure.
    private nonisolated static func copyIntoSandbox(_ src: URL) -> URL? {
        let scoped = src.startAccessingSecurityScopedResource()
        defer { if scoped { src.stopAccessingSecurityScopedResource() } }
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("localfiles", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let ext = src.pathExtension.isEmpty ? "audio" : src.pathExtension
        let dest = dir.appendingPathComponent("\(UUID().uuidString).\(ext)")
        do {
            if FileManager.default.fileExists(atPath: dest.path) {
                try FileManager.default.removeItem(at: dest)
            }
            try FileManager.default.copyItem(at: src, to: dest)
            return dest
        } catch { return nil }
    }

    private static func loadString(_ item: AVMetadataItem) async -> String? {
        guard let s = (try? await item.load(.stringValue)) ?? nil, !s.isEmpty else { return nil }
        return s
    }

    /// Persist embedded artwork Data to the localart cache (same dir as the scan's
    /// embedded art). Returns the file:// string, or nil when there's no art →
    /// online enrichment fills it later (MBID/image enrichment), Android parity.
    private static func saveArtData(_ data: Data, key: String) -> String? {
        guard let image = UIImage(data: data),
              let jpg = image.jpegData(compressionQuality: 0.85) else { return nil }
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("localart", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("\(key).jpg")
        try? jpg.write(to: url)
        return url.absoluteString
    }

    /// Imported-file Track. The "fileimport-" id prefix marks it as a user import
    /// (NOT a Music-library scan), so `addLocalTracks`'s re-scan prune leaves it
    /// alone. sourceUrl is a `file://` URL → AVPlayer plays it directly.
    private static func makeImportedTrack(localURL: URL, title: String, artist: String,
                                          album: String?, durationMs: Int64,
                                          artworkUrl: String?) -> Track {
        Track(
            id: "fileimport-\(UUID().uuidString)",
            title: title,
            artist: artist,
            album: album, albumId: nil,
            duration: KotlinLong(value: durationMs),
            artworkUrl: artworkUrl,
            sourceType: "local",
            // Container-RELATIVE marker, not an absolute file:// — the app data
            // container UUID changes across reinstalls/updates, which would orphan
            // an absolute path. IosAVPlayer.resolveLocalImportPath expands this to
            // the current Documents dir at play time.
            sourceUrl: "pcfile://localfiles/\(localURL.lastPathComponent)",
            addedAt: 0,
            resolver: "localfiles",
            spotifyUri: nil, soundcloudId: nil, spotifyId: nil, appleMusicId: nil,
            isrc: nil, recordingMbid: nil, artistMbid: nil, releaseMbid: nil,
            crossResolverEnrichedAt: nil
        )
    }
}
