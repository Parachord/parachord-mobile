import Foundation
import UIKit

// MARK: - Now Playing for the embedded players (SoundCloud, Bandcamp)
//
// For a track playing in a web view, WebKit publishes the lock screen /
// Control Center / CarPlay Now Playing from a page's Media Session, not from
// `MPNowPlayingInfoCenter`. Two pages can hold one: the Bandcamp embed (or
// SoundCloud's widget page) that plays the track, and SoundCloud's widget page
// while it holds WebKit's audio session with its 1-second silent loop (see
// iosApp/AGENTS.md, "Bandcamp through its embed"). WebKit shows whichever page
// updated last. Both get the track's metadata; only the playing page gets the
// position. Given the position too, the holding page reported the track as
// paused and took the lock screen back on every update. As a Bandcamp track
// starts the holding page often updates last, so for about 5 s (until WebKit
// updates the playing page by itself) its 0:01 shows.
//
// Artwork: WebKit fetches a Media Session's artwork itself, and that failed for
// covers behind redirects (coverartarchive → archive.org → a storage node):
// CarPlay showed no art. So the app downloads the cover (through
// `PCImageStore`, at most 5 s), shrinks it to 512 px or less and passes it as a
// `data:image/jpeg` URL. Metadata goes in first with the http(s) URL, so
// playback never waits; the data URL replaces it once ready, if the same track
// is still playing. A cover that can't be had keeps the http(s) URL.
//
// The scripts here are fixed. The values reach them as ONE JSON object built
// with `JSONSerialization`, never interpolated as raw strings, and artwork is
// passed only for an http(s) URL. These scripts are ours: the pages' own
// files (`bandcamp-embed-control.js`, `soundcloud-widget*.{html,js}`) are
// byte-identical copies of desktop's and are never edited for this.

enum EmbedNowPlaying {
    /// A cover the app downloaded, as a `data:` URL, and its pixel size.
    struct InlineArtwork {
        let src: String
        let width: Int
        let height: Int
    }

    /// Sets `navigator.mediaSession.metadata` from the track. The action
    /// handlers stay as each page set them. [inline] (from `inlineArtwork`)
    /// wins over [artworkUrl].
    static func metadataScript(title: String, artist: String, album: String?, artworkUrl: String?,
                               inline: InlineArtwork? = nil) -> String? {
        var info: [String: Any] = ["title": title, "artist": artist, "album": album ?? ""]
        if let inline {
            info["artwork"] = [["src": inline.src, "sizes": "\(inline.width)x\(inline.height)", "type": "image/jpeg"]]
        } else if let art = httpArtwork(artworkUrl) {
            info["artwork"] = [["src": art, "sizes": "512x512", "type": imageType(art)]]
        } else {
            info["artwork"] = [[String: String]]()
        }
        guard let json = jsonLiteral(info) else { return nil }
        return """
        (function (info) {
          var ms = navigator.mediaSession;
          if (!ms || typeof MediaMetadata === 'undefined') return false;
          ms.metadata = new MediaMetadata(info);
          return true;
        })(\(json))
        """
    }

    /// Sets the Media Session's play state and, with a known duration, its
    /// position. The rate stays 1 (0 is invalid); a paused session reads
    /// `playbackState`.
    static func positionScript(duration: Double, position: Double, playing: Bool) -> String? {
        guard duration.isFinite, position.isFinite else { return nil }
        guard let json = jsonLiteral(["duration": max(duration, 0), "position": max(position, 0), "playing": playing]) else {
            return nil
        }
        return """
        (function (s) {
          var ms = navigator.mediaSession;
          if (!ms) return false;
          try { ms.playbackState = s.playing ? 'playing' : 'paused'; } catch (e) {}
          if (ms.setPositionState && s.duration > 0) {
            try { ms.setPositionState({ duration: s.duration, position: Math.min(s.position, s.duration), playbackRate: 1 }); } catch (e) {}
          }
          return true;
        })(\(json))
        """
    }

    /// The cover at [url] (http(s) only) as a JPEG `data:` URL no larger than
    /// 512 px, from the image store or a download capped at 5 s; nil when it
    /// can't be had, and the caller keeps the http(s) URL.
    static func inlineArtwork(_ url: String?) async -> InlineArtwork? {
        guard let http = httpArtwork(url), let u = URL(string: http) else { return nil }
        return await Task.detached(priority: .utility) { () -> InlineArtwork? in
            var image = PCImageStore.load(u)
            if image == nil {
                guard let (data, response) = try? await downloadSession.data(from: u),
                      let status = (response as? HTTPURLResponse)?.statusCode, (200..<300).contains(status),
                      let downloaded = UIImage(data: data) else { return nil }
                PCImageStore.store(u, data, downloaded)
                image = downloaded
            }
            guard let image else { return nil }
            return encode(image)
        }.value
    }

    /// Covers follow redirects; the whole download, redirects included, gets 5 s.
    private static let downloadSession: URLSession = {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 5
        config.timeoutIntervalForResource = 5
        return URLSession(configuration: config)
    }()

    private static let maxArtworkPixels: CGFloat = 512

    private static func encode(_ image: UIImage) -> InlineArtwork? {
        let width = image.size.width * image.scale, height = image.size.height * image.scale
        guard width > 0, height > 0 else { return nil }
        let ratio = min(1, maxArtworkPixels / max(width, height))
        let size = CGSize(width: max(1, (width * ratio).rounded()), height: max(1, (height * ratio).rounded()))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let resized = UIGraphicsImageRenderer(size: size, format: format).image { _ in
            image.draw(in: CGRect(origin: .zero, size: size))
        }
        guard let jpeg = resized.jpegData(compressionQuality: 0.85) else { return nil }
        return InlineArtwork(src: "data:image/jpeg;base64," + jpeg.base64EncodedString(),
                             width: Int(size.width), height: Int(size.height))
    }

    /// The URL, if it's http(s).
    static func httpArtwork(_ url: String?) -> String? {
        guard let url, let u = URL(string: url), let scheme = u.scheme?.lowercased(),
              scheme == "http" || scheme == "https", u.host != nil else { return nil }
        return url
    }

    private static func imageType(_ url: String) -> String {
        let path = (URL(string: url)?.path ?? "").lowercased()
        if path.hasSuffix(".png") { return "image/png" }
        if path.hasSuffix(".webp") { return "image/webp" }
        return "image/jpeg"
    }

    private static func jsonLiteral(_ object: Any) -> String? {
        guard JSONSerialization.isValidJSONObject(object),
              let data = try? JSONSerialization.data(withJSONObject: object),
              let text = String(data: data, encoding: .utf8) else { return nil }
        // U+2028 / U+2029 are valid in JSON but end a line in older JS parsers.
        return text.replacingOccurrences(of: "\u{2028}", with: "\\u2028")
            .replacingOccurrences(of: "\u{2029}", with: "\\u2029")
    }

    /// When to tell WebKit the position again: on a play / pause, a new
    /// duration, a jump (a seek, or drift past 2 s), and every 5 s while
    /// playing. Not every 500 ms poll.
    struct Throttle {
        private var lastPlaying: Bool?
        private var lastDuration: Double = 0
        private var lastPosition: Double = 0
        private var lastAt: Date = .distantPast

        static let interval: TimeInterval = 5
        static let drift: Double = 2

        mutating func reset() { self = Throttle() }

        mutating func shouldPublish(duration: Double, position: Double, playing: Bool, now: Date = Date()) -> Bool {
            let elapsed = now.timeIntervalSince(lastAt)
            let expected = lastPosition + ((lastPlaying ?? false) ? elapsed : 0)
            let due = playing != lastPlaying
                || abs(duration - lastDuration) > 0.5
                || abs(position - expected) > Self.drift
                || (playing && elapsed >= Self.interval)
            guard due else { return false }
            lastPlaying = playing
            lastDuration = duration
            lastPosition = position
            lastAt = now
            return true
        }
    }
}
