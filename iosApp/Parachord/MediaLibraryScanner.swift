import SwiftUI
import MediaPlayer
import Shared

// MARK: - Local file library (#219 / #195)
//
// iOS's analogue of Android's MediaScanner. iOS has no general "files" library —
// local music lives in the device's Music library (MPMediaLibrary). We query the
// songs, keep only items with a non-nil `assetURL` (a file:// to the audio on
// disk — DRM / cloud Apple-Music items have none and can't play via AVPlayer),
// persist them to the shared DB as `resolver = "localfiles"` tracks, and the
// native localfiles resolver (IosResolverRuntime) matches + routes them to
// AVPlayer (PlaybackRouter already cases "localfiles" → avPlayer).

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

    private func runScan() async {
        phase = .requesting
        guard await requestAuth() else { phase = .denied; return }

        let items = MPMediaQuery.songs().items ?? []
        phase = .scanning(done: 0, total: items.count)

        var tracks: [Track] = []
        for (i, item) in items.enumerated() {
            // Skip DRM / cloud items (no on-disk file → AVPlayer can't play them).
            guard let assetURL = item.assetURL else { continue }
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
}
