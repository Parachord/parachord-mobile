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
    case avPlayer, musicKit, spotify
}

enum RouteResult: Equatable {
    /// Playback started on this engine. For `.avPlayer` the caller still
    /// drives the ready-poll-then-play (the item loads asynchronously).
    case played(PlaybackEngineKind)
    /// Every ranked source needed an engine we lack (no subscription / token).
    case noPlayableSource
}

@MainActor
struct PlaybackRouter {
    let avPlayer: IosAVPlayer
    let musicKit: IosMusicKitPlayer
    let spotify: IosSpotifyConnect

    /// Walk the ranked sources; start the first engine-available one.
    func play(ranked: [ResolvedSource], title: String, artist: String) async -> RouteResult {
        for source in ranked {
            switch source.resolver {
            case "soundcloud", "localfiles", "direct":
                guard !source.url.isEmpty else { continue }
                avPlayer.load(url: source.url, title: title, artist: artist)
                return .played(.avPlayer)

            case "applemusic":
                guard let id = source.appleMusicId, !id.isEmpty else { continue }
                // Request Apple Music access on the first play so a fresh
                // install doesn't depend on the Dev tab: notDetermined →
                // prompt; denied/restricted → skip this source. (The router is
                // @MainActor, so request() runs on the main actor as required.)
                var status = MusicAuthorization.currentStatus
                if status == .notDetermined { status = await MusicAuthorization.request() }
                guard status == .authorized else { continue }
                if await musicKit.play(appleMusicId: id) { return .played(.musicKit) }
                // started false (no subscription / transient) → next source

            case "spotify":
                guard spotify.canPlay, let uri = source.spotifyUri, !uri.isEmpty else { continue }
                if await spotify.play(uri: uri) { return .played(.spotify) }

            default:
                continue
            }
        }
        return .noPlayableSource
    }
}
