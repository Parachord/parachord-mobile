import Foundation
import MusicKit
import Shared

// MARK: - Playback router (playback-loop phase)
//
// iOS's equivalent of Android's PlaybackRouter. Given an ALREADY-RANKED list
// of resolved sources (ResolverScoring.selectRanked: floor-filtered,
// priority-then-confidence sorted), it walks the list and plays the FIRST
// source whose engine is actually available on this device. It never
// re-decides which match is best — that ranking is authoritative. A 0.95
// Apple Music source that can't play (no subscription) falls to the NEXT
// ranked source, not below a lower-confidence one.
//
// Engine routing mirrors CLAUDE.md "Playback Routing":
//   soundcloud / localfiles / direct (a playable URL) → AVPlayer
//   applemusic (catalog ID)                           → MusicKit
//   spotify (track URI)                               → Spotify Connect

enum PlaybackEngineKind: String {
    case avPlayer, musicKit, spotify, browser
}

enum RouteResult: Equatable {
    /// Playback started on this engine. The second value is the resolver id of
    /// the source that ACTUALLY played — which is NOT necessarily the top-ranked
    /// source (a higher-ranked Spotify match can fall through to Apple Music when
    /// Spotify isn't connected). The caller stamps this onto the now-playing
    /// track so the scrobble origin reflects what truly streamed, not the
    /// cross-match (#260 / #276). For `.avPlayer` the caller still drives the
    /// ready-poll-then-play (the item loads asynchronously).
    case played(PlaybackEngineKind, resolver: String)
    /// A non-streaming resolver (stream:false, e.g. Bandcamp) — the caller opens
    /// this URL in the browser and shows the track without managing audio.
    case openBrowser(String)
    /// Every ranked source needed an engine we lack (no subscription / token).
    case noPlayableSource
}

@MainActor
struct PlaybackRouter {
    let avPlayer: IosAVPlayer
    let musicKit: IosMusicKitPlayer
    let spotify: IosSpotifyConnect
    /// Resolver ids that resolve but can't stream (stream:false, e.g. Bandcamp)
    /// — opened in the browser. Mirrors Android's PlaybackRouter browser branch.
    var nonStreamingResolvers: Set<String> = []

    /// Walk the ranked sources; start the first engine-available one.
    func play(ranked: [ResolvedSource], title: String, artist: String) async -> RouteResult {
        for source in ranked {
            // Non-streaming resolver (Bandcamp) — hand its URL back to open in
            // the browser, before trying any audio engine for this source.
            if nonStreamingResolvers.contains(source.resolver), !source.url.isEmpty {
                return .openBrowser(source.url)
            }
            switch source.resolver {
            case "soundcloud", "localfiles", "direct":
                guard !source.url.isEmpty else { continue }
                avPlayer.load(url: source.url, title: title, artist: artist)
                return .played(.avPlayer, resolver: source.resolver)

            case "applemusic":
                guard let id = source.appleMusicId, !id.isEmpty else { continue }
                // Request Apple Music access on the first play so a fresh
                // install doesn't depend on the Dev tab: notDetermined →
                // prompt; denied/restricted → skip this source. (The router is
                // @MainActor, so request() runs on the main actor as required.)
                var status = MusicAuthorization.currentStatus
                if status == .notDetermined { status = await MusicAuthorization.request() }
                guard status == .authorized else { continue }
                if await musicKit.play(appleMusicId: id) { return .played(.musicKit, resolver: source.resolver) }
                // started false (no subscription / transient) → next source

            case "spotify":
                guard spotify.canPlay, let uri = source.spotifyUri, !uri.isEmpty else { continue }
                if await spotify.play(uri: uri) { return .played(.spotify, resolver: source.resolver) }

            default:
                continue
            }
        }
        return .noPlayableSource
    }
}
