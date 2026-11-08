import CarPlay
import Shared
import UIKit

// MARK: - CarPlay list artwork
//
// Loads a row's cover from an http(s) URL or a `file://` path (playlist
// mosaics), through the app's image store (`PCImageStore`, memory + disk), and
// keeps the car-sized copy in memory. A track whose own URL is missing or dead
// falls back to its resolved sources' artwork (`pcTrackArt`, as the phone's
// covers do). Anything that can't load keeps its SF Symbol placeholder.
//
// Playlist mosaics are stored as absolute `file://` paths, which go stale when
// the app's container moves (a reinstall or restore). A missing file is a miss,
// never an error: the next candidate is tried, then the placeholder stays.

@MainActor
enum CarArtwork {
    /// The car screen's scale, set when the CarPlay scene connects.
    static var displayScale: CGFloat = 2

    private static let cache: NSCache<NSString, UIImage> = {
        let c = NSCache<NSString, UIImage>()
        c.countLimit = 600
        return c
    }()
    /// URLs that failed this session, so a dead cover isn't fetched again on every visit.
    private static var failed: Set<String> = []

    private static let requestTimeout: TimeInterval = 15

    /// The URLs to try for [item], best first.
    static func candidates(for item: CarItem) -> [String] {
        var urls: [String] = []
        if item.kind == .track {
            let artist = item.subtitle ?? ""
            if let own = pcTrackArt(item.artworkUrl, artist: artist, title: item.title, album: item.album) {
                urls.append(own)
            }
            // The resolved sources' art, for when the row's own URL doesn't load.
            if let resolved = pcTrackArt(nil, artist: artist, title: item.title, album: item.album),
               !urls.contains(resolved) {
                urls.append(resolved)
            }
        } else if let own = item.artworkUrl, !own.isEmpty {
            urls.append(own)
        }
        return urls
    }

    /// The first of [urls] that loads, or nil.
    static func image(forFirstOf urls: [String]) async -> UIImage? {
        for url in urls {
            if Task.isCancelled { return nil }
            if let image = await image(for: url) { return image }
        }
        return nil
    }

    /// The car-sized image at [url] (http(s) or `file://`), or nil when it
    /// can't be loaded.
    static func image(for url: String?) async -> UIImage? {
        guard let url, !url.isEmpty, !failed.contains(url), let u = URL(string: url) else { return nil }
        guard u.isFileURL || u.scheme == "http" || u.scheme == "https" else { return nil }
        if let hit = cache.object(forKey: url as NSString) { return hit }

        let target = CPListItem.maximumImageSize
        let scale = displayScale
        let timeout = requestTimeout
        let image = await Task.detached(priority: .utility) { () -> UIImage? in
            guard let full = await loadFull(u, timeout: timeout) else { return nil }
            return resized(full, to: target, scale: scale)
        }.value

        if let image {
            cache.setObject(image, forKey: url as NSString)
        } else {
            failed.insert(url)
        }
        return image
    }

    /// An SF Symbol for a row with no cover (yet).
    static func placeholder(for item: CarItem) -> UIImage? {
        let name: String
        switch item.id {
        case CarIds.shared.PLAYLISTS: name = "music.note.list"
        case CarIds.shared.LOVED: name = "heart.fill"
        case CarIds.shared.COLLECTION_RADIO: name = "shuffle"
        default:
            switch item.kind {
            case .playlist: name = "music.note.list"
            case .action: name = "shuffle"
            case .folder: name = "folder"
            default: name = "music.note"
            }
        }
        return UIImage(systemName: name)
    }

    // MARK: Loading (off the main actor)

    /// Memory or disk store first, then the file or the network.
    private nonisolated static func loadFull(_ url: URL, timeout: TimeInterval) async -> UIImage? {
        if let stored = PCImageStore.load(url) { return stored }
        let data: Data?
        if url.isFileURL {
            guard FileManager.default.fileExists(atPath: url.path) else { return nil }
            data = try? Data(contentsOf: url)
        } else {
            var request = URLRequest(url: url)
            request.timeoutInterval = timeout
            guard let (body, response) = try? await URLSession.shared.data(for: request),
                  (response as? HTTPURLResponse).map({ (200..<300).contains($0.statusCode) }) ?? false
            else { return nil }
            data = body
        }
        guard let data, let image = UIImage(data: data) else { return nil }
        PCImageStore.store(url, data, image)
        return image
    }

    /// Scale-to-fill crop into [size] points at [scale].
    private nonisolated static func resized(_ image: UIImage, to size: CGSize, scale: CGFloat) -> UIImage {
        guard size.width > 0, size.height > 0, image.size.width > 0, image.size.height > 0 else { return image }
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        let ratio = max(size.width / image.size.width, size.height / image.size.height)
        let drawn = CGSize(width: image.size.width * ratio, height: image.size.height * ratio)
        let origin = CGPoint(x: (size.width - drawn.width) / 2, y: (size.height - drawn.height) / 2)
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: origin, size: drawn))
        }
    }
}
