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
//   localfiles / direct (a playable URL) → AVPlayer
//   soundcloud                           → SoundCloud's widget (#495)
//   bandcamp                             → Bandcamp's embed (#513)
//   applemusic (catalog ID)              → MusicKit
//   spotify (track URI)                  → Spotify Connect

enum PlaybackEngineKind: String {
    case avPlayer, musicKit, spotify, soundCloud, bandcamp, browser
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
    /// Resolvers whose source is a URL AVPlayer plays itself (a local file, a
    /// direct stream), not a service's page.
    static let avPlayerResolvers: Set<String> = ["localfiles", "direct"]

    let avPlayer: IosAVPlayer
    let musicKit: IosMusicKitPlayer
    let spotify: IosSpotifyConnect
    let soundCloud: IosSoundCloudWidget
    let bandcamp: IosBandcampEmbed
    /// Called when an embedded player refuses a source (SoundCloud's widget
    /// can't play, or only previews, the track; Bandcamp's embed doesn't start) with the service's name and
    /// the track's page on it. The walk then moves on to the next source.
    var onRefused: ((_ service: String, _ pageUrl: String?, _ reason: String) -> Void)? = nil
    /// Called when a Spotify source was tried and didn't start (no usable
    /// device, rate-limited, Premium needed). The walk then moves on. In the
    /// car the caller uses it to stop on the track instead of skipping it.
    var onSpotifyFailed: (() -> Void)? = nil
    /// Called when an Apple Music source should have played and couldn't
    /// start (offline, MusicKit unreachable, no answer in time), with
    /// whether it timed out. The walk then moves on (a local file can still
    /// play offline); if nothing plays, the caller stops on the track for
    /// Play rather than skip it (shared `PlayFailure.START_FAILED`).
    var onStartFailed: ((_ timedOut: Bool) -> Void)? = nil
    /// An Apple Music source was skipped because the app isn't allowed to use
    /// Apple Music (access denied or restricted). If nothing plays, the caller
    /// says so (Android's "Sign in to Apple Music to play this track").
    var onAppleMusicUnauthorized: (() -> Void)? = nil
    /// Resolver ids that resolve but can't stream (stream:false, e.g. Bandcamp)
    /// — opened in the browser. Mirrors Android's PlaybackRouter browser branch.
    var nonStreamingResolvers: Set<String> = []
    /// The play this walk is for, given to an AVPlayer item it loads so the
    /// item's failure is booked against this play only (`IosAVPlayer.load(tag:)`).
    var avLoadTag: Int = 0

    /// Walk the ranked sources; start the first engine-available one.
    func play(ranked: [ResolvedSource], title: String, artist: String, album: String? = nil,
              artworkUrl: String? = nil) async -> RouteResult {
        for source in ranked {
            // Non-streaming resolver — hand its URL back to open in the browser,
            // before trying any audio engine for this source. Not Bandcamp, which
            // keeps `stream: false` so older app versions still open the browser,
            // and plays in its embed here (#513).
            if source.resolver != "bandcamp", nonStreamingResolvers.contains(source.resolver), !source.url.isEmpty {
                return .openBrowser(source.url)
            }
            switch source.resolver {
            case "soundcloud":
                // SoundCloud's widget (#495, desktop parachord#1209): the API
                // no longer gives full tracks. A refused track falls through
                // to the next ranked source, like Android's fallback.
                let widget = SoundCloudWidget.shared
                guard let link = widget.widgetLink(url: source.url, soundcloudUrl: source.soundcloudUrl,
                                                   soundcloudId: source.soundcloudId) else { continue }
                let result = await soundCloud.load(link: link,
                                                   volume: IosVolumeOffsets.shared.fraction(for: "soundcloud"),
                                                   title: title, artist: artist, album: album,
                                                   artworkUrl: artworkUrl ?? source.artworkUrl)
                switch result {
                case .started:
                    return .played(.soundCloud, resolver: source.resolver)
                case .refused(let reason):
                    if reason == "superseded" { return .noPlayableSource }
                    onRefused?(widget.SERVICE_NAME, widget.pageLink(url: source.url, soundcloudUrl: source.soundcloudUrl), reason)
                }

            case "bandcamp":
                // Bandcamp's embed (#513, desktop parachord#1231). A track it
                // won't start, or that has no id to be found, falls through to
                // the next ranked source, as SoundCloud's does.
                let embed = BandcampEmbed.shared
                let pageLink = embed.pageLink(url: source.url)
                var result = IosBandcampEmbed.LoadResult.refused("unplayable")
                if let embedUrl = try? await IosContainer.companion.shared.bandcampEmbedUrl(
                    trackId: source.bandcampTrackId, pageUrl: source.url) {
                    result = await bandcamp.load(embedUrl: embedUrl,
                                                 volume: IosVolumeOffsets.shared.fraction(for: "bandcamp"),
                                                 title: title, artist: artist, album: album,
                                                 artworkUrl: artworkUrl ?? source.artworkUrl)
                }
                switch result {
                case .started:
                    return .played(.bandcamp, resolver: source.resolver)
                case .refused(let reason):
                    if reason == "superseded" { return .noPlayableSource }
                    onRefused?(embed.SERVICE_NAME, pageLink, reason)
                }

            case let resolver where Self.avPlayerResolvers.contains(resolver):
                guard !source.url.isEmpty else { continue }
                avPlayer.load(url: source.url, title: title, artist: artist, resolver: source.resolver, tag: avLoadTag)
                return .played(.avPlayer, resolver: source.resolver)

            case "applemusic":
                guard let id = source.appleMusicId, !id.isEmpty else { continue }
                // Request Apple Music access on the first play so a fresh
                // install doesn't depend on the Dev tab: notDetermined →
                // prompt; denied/restricted → skip this source. (The router is
                // @MainActor, so request() runs on the main actor as required.)
                var status = MusicAuthorization.currentStatus
                if status == .notDetermined { status = await MusicAuthorization.request() }
                guard status == .authorized else { onAppleMusicUnauthorized?(); continue }
                switch await musicKit.play(appleMusicId: id) {
                case .started:
                    return .played(.musicKit, resolver: source.resolver)
                case .unavailable:
                    break   // not in the catalog / no subscription → next source
                case .failed(let timedOut):
                    onStartFailed?(timedOut)   // couldn't start → next source
                }

            case "spotify":
                guard spotify.canPlay, let uri = source.spotifyUri, !uri.isEmpty else { continue }
                if await spotify.play(uri: uri) { return .played(.spotify, resolver: source.resolver) }
                onSpotifyFailed?()

            default:
                continue
            }
        }
        return .noPlayableSource
    }
}
