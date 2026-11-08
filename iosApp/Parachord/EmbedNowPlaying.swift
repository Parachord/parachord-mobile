import Foundation

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
// The scripts here are fixed. The values reach them as ONE JSON object built
// with `JSONSerialization`, never interpolated as raw strings, and artwork is
// passed only for an http(s) URL. These scripts are ours: the pages' own
// files (`bandcamp-embed-control.js`, `soundcloud-widget*.{html,js}`) are
// byte-identical copies of desktop's and are never edited for this.

enum EmbedNowPlaying {
    /// Sets `navigator.mediaSession.metadata` from the track. The action
    /// handlers stay as each page set them.
    static func metadataScript(title: String, artist: String, album: String?, artworkUrl: String?) -> String? {
        var info: [String: Any] = ["title": title, "artist": artist, "album": album ?? ""]
        if let art = httpArtwork(artworkUrl) {
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
