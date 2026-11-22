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
//   a plug-in played through getStreamUrl → AVPlayer, with a URL the plug-in
//     builds at play time (Subsonic's html5Stream; Amazon Music's purchases,
//     a Widevine plug-in's plain stream, desktop parachord#1613; #670)

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
    /// A plug-in's `getStreamUrl` gave no stream ([IosPluginStream]'s kind:
    /// `settings` / `startFailed` stop on the track with [message], `refused`
    /// is a refused source named [service]). The walk then moves on.
    var onPluginStreamFailed: ((_ kind: String, _ message: String, _ service: String) -> Void)? = nil
    /// A plug-in refused its stream as not signed in (#775): true stops the
    /// route here so the coordinator can ask to sign in (no other source is
    /// tried); false (or unset) treats it as any refused source.
    var signInFirst: ((_ resolver: String) async -> Bool)? = nil
    /// Resolver ids that resolve but can't stream (stream:false, e.g. Bandcamp)
    /// — opened in the browser. Mirrors Android's PlaybackRouter browser branch.
    var nonStreamingResolvers: Set<String> = []
    /// The play this walk is for, given to an AVPlayer item it loads so the
    /// item's failure is booked against this play only (`IosAVPlayer.load(tag:)`).
    var avLoadTag: Int = 0

    /// Walk the ranked sources; start the first engine-available one.
    /// [startAtSec]: where a resumed track starts (#657). AVPlayer, Apple Music
    /// and Spotify start there; the SoundCloud and Bandcamp embeds can't, so the
    /// caller seeks once they play (`PlaybackEngineKind.startsAtPosition`).
    func play(ranked: [ResolvedSource], title: String, artist: String, startAtSec: Double = 0,
              album: String? = nil, artworkUrl: String? = nil,
              trackId: String = "", durationMs: Int64 = 0) async -> RouteResult {
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

            case let resolver where IosContainer.companion.shared.playsThroughFairPlay(resolverId: resolver):
                // A plug-in's DRM pair as FairPlay HLS (#794, desktop #1627):
                // AVPlayer with an AVContentKeySession relaying each key
                // request through the plug-in. When it doesn't start (no
                // licence, an engine error, the simulator, which has no
                // FairPlay), the plug-in's plain stream plays instead if it
                // has one (shared drmPlainStreamFallbackAllowed), as on Android.
                switch await playFairPlay(source, resolver: resolver, title: title, artist: artist, album: album,
                                          trackId: trackId, durationMs: durationMs, startAtSec: startAtSec) {
                case .played: return .played(.avPlayer, resolver: source.resolver)
                case .superseded, .stop: return .noPlayableSource
                case .next: continue
                case .plainStream: break
                }
                switch await playPluginStream(source, resolver: resolver, title: title, artist: artist, album: album,
                                              trackId: trackId, durationMs: durationMs, startAtSec: startAtSec) {
                case .played: return .played(.avPlayer, resolver: source.resolver)
                case .superseded, .stop: return .noPlayableSource
                case .next, .plainStream: continue
                }

            case let resolver where IosContainer.companion.shared.playsThroughStreamUrl(resolverId: resolver):
                // A plug-in played through `getStreamUrl` (#670).
                switch await playPluginStream(source, resolver: resolver, title: title, artist: artist, album: album,
                                              trackId: trackId, durationMs: durationMs, startAtSec: startAtSec) {
                case .played: return .played(.avPlayer, resolver: source.resolver)
                case .superseded, .stop: return .noPlayableSource
                case .next, .plainStream: continue
                }

            case let resolver where Self.avPlayerResolvers.contains(resolver):
                guard !source.url.isEmpty else { continue }
                avPlayer.load(url: source.url, title: title, artist: artist, resolver: source.resolver, tag: avLoadTag,
                              startAtSec: startAtSec)
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
                switch await musicKit.play(appleMusicId: id, startAtSec: startAtSec) {
                case .started:
                        return .played(.musicKit, resolver: source.resolver)
                case .unavailable:
                    break   // not in the catalog / no subscription → next source
                case .failed(let timedOut):
                    onStartFailed?(timedOut)   // couldn't start → next source
                }

            case "spotify":
                guard spotify.canPlay, let uri = source.spotifyUri, !uri.isEmpty else { continue }
                if await spotify.play(uri: uri, startAtSec: startAtSec) {
                        return .played(.spotify, resolver: source.resolver)
                }
                onSpotifyFailed?()

            default:
                continue
            }
        }
        return .noPlayableSource
    }

    /// What a plug-in path did for one source.
    enum PluginStep {
        /// It loaded on AVPlayer: the walk is done.
        case played
        /// A newer play took over: stop the walk, report nothing.
        case superseded
        /// Stop the walk here (the coordinator asks to sign in, #775).
        case stop
        /// The source didn't play: try the next one.
        case next
        /// The DRM stream didn't start: try the plug-in's plain stream.
        case plainStream
    }

    /// A plug-in played through `getStreamUrl` (#670): the URL is built now,
    /// by capability and method, never by id, and lives for this play only
    /// (AVPlayer's item; never the track, a cache, the saved queue or a log line).
    private func playPluginStream(_ source: ResolvedSource, resolver: String, title: String, artist: String,
                                  album: String?, trackId: String, durationMs: Int64,
                                  startAtSec: Double) async -> PluginStep {
        let container = IosContainer.companion.shared
        let stream = try? await container.pluginStream(source: source, trackId: trackId, title: title,
                                                       artist: artist, album: album, durationMs: durationMs)
        guard let stream else {
            onPluginStreamFailed?(IosPluginStream.companion.START_FAILED, PlayFailureKt.PLAY_START_FAILED_MESSAGE, resolver)
            return .next
        }
        if stream.kind == IosPluginStream.companion.PLAY, let url = stream.url {
            avPlayer.load(url: url, title: title, artist: artist, resolver: source.resolver, tag: avLoadTag,
                          startAtSec: startAtSec)
            return .played
        }
        if stream.kind == IosPluginStream.companion.SUPERSEDED { return .superseded }
        if stream.kind == IosPluginStream.companion.NOT_SIGNED_IN, let signInFirst, await signInFirst(resolver) {
            onPluginStreamFailed?(stream.kind, stream.message ?? PlayFailureKt.PLAY_START_FAILED_MESSAGE, stream.serviceName)
            return .stop
        }
        onPluginStreamFailed?(stream.kind, stream.message ?? PlayFailureKt.PLAY_START_FAILED_MESSAGE, stream.serviceName)
        return .next
    }

    /// How long a FairPlay start may take: the licence and the item ready
    /// (Android's `DRM_START_TIMEOUT_MS`, desktop's `untilAudible`).
    static let fairPlayStartTimeoutSec: Double = 30

    /// A plug-in's FairPlay HLS (#794): open the session, load the stream on
    /// AVPlayer with its key session, and wait until a licence came back and
    /// the item is ready to play. It doesn't play here: the coordinator starts
    /// it, so the engine playing before doesn't run over a start that fails.
    /// Loaded under a tag of its own until it has started, so a failure
    /// during the start is this walk's to handle (the plain stream), not the
    /// coordinator's.
    private func playFairPlay(_ source: ResolvedSource, resolver: String, title: String, artist: String,
                              album: String?, trackId: String, durationMs: Int64,
                              startAtSec: Double) async -> PluginStep {
        let container = IosContainer.companion.shared
        let plainNext: PluginStep = container.hasDrmPlainStream(resolverId: resolver) ? .plainStream : .next
        guard let open = try? await container.openFairPlay(source: source, trackId: trackId, title: title,
                                                           artist: artist, album: album, durationMs: durationMs) else {
            return plainNext
        }
        if Task.isCancelled { return .superseded }
        switch open.kind {
        case IosFairPlayOpen.companion.PLAY:
            break
        case IosFairPlayOpen.companion.SUPERSEDED:
            return .superseded
        case IosPluginStream.companion.NOT_SIGNED_IN, IosPluginStream.companion.REFUSED:
            // A plan or sign-in refusal: never the plain stream (the track
            // isn't the account's to play), as Android and desktop.
            return await refused(kind: open.kind, message: open.message, service: open.serviceName, resolver: resolver)
        default:
            NSLog("FAIRPLAY '%@': %@ didn't open a stream (%@)", title, resolver, open.kind)
            return plainNext
        }
        guard let sessionId = open.sessionId, let url = open.url, let cert = open.certificateB64,
              let keys = IosFairPlayKeys(sessionId: sessionId, certificateB64: cert, resolverId: resolver) else {
            return plainNext
        }
        let cookies = zip(open.cookieNames, open.cookieValues).map { ($0, $1) }
        guard avPlayer.load(fairPlayURL: url, cookies: cookies, keys: keys, title: title, artist: artist,
                            resolver: source.resolver, tag: -1, startAtSec: startAtSec) else {
            Task { try? await container.closeFairPlay(sessionId: sessionId) }
            return plainNext
        }
        let deadline = Date().addingTimeInterval(Self.fairPlayStartTimeoutSec)
        while Date() < deadline {
            if Task.isCancelled {
                avPlayer.haltAudio()
                Task { try? await container.closeFairPlay(sessionId: sessionId) }
                return .superseded
            }
            switch keys.state {
            case .refused(let kind, let message):
                avPlayer.haltAudio()
                Task { try? await container.closeFairPlay(sessionId: sessionId) }
                if kind == IosPluginStream.companion.NOT_SIGNED_IN || kind == IosPluginStream.companion.REFUSED {
                    return await refused(kind: kind, message: message, service: open.serviceName, resolver: resolver)
                }
                NSLog("FAIRPLAY '%@': the licence was refused (%@)", title, kind)
                return plainNext
            case .failed(let why):
                avPlayer.haltAudio()
                Task { try? await container.closeFairPlay(sessionId: sessionId) }
                NSLog("FAIRPLAY '%@': the key request failed: %@", title, why)
                return plainNext
            case .licensed where avPlayer.status == .ready:
                NSLog("FAIRPLAY '%@' licensed and ready (%@)", title, resolver)
                avPlayer.retag(avLoadTag)
                return .played
            default:
                break
            }
            if avPlayer.status == .failed {
                NSLog("FAIRPLAY '%@': the stream failed before it started: %@", title, avPlayer.errorMessage ?? "")
                avPlayer.haltAudio()
                Task { try? await container.closeFairPlay(sessionId: sessionId) }
                return plainNext
            }
            try? await Task.sleep(nanoseconds: 150_000_000)
        }
        NSLog("FAIRPLAY '%@': no licence and ready item within %.0f s", title, Self.fairPlayStartTimeoutSec)
        avPlayer.haltAudio()
        Task { try? await container.closeFairPlay(sessionId: sessionId) }
        return plainNext
    }

    /// A plan or sign-in refusal: the sign-in prompt may stop the walk (#775), else a refused source.
    private func refused(kind: String, message: String?, service: String, resolver: String) async -> PluginStep {
        let words = message ?? PlayFailureKt.PLAY_START_FAILED_MESSAGE
        if kind == IosPluginStream.companion.NOT_SIGNED_IN, let signInFirst, await signInFirst(resolver) {
            onPluginStreamFailed?(kind, words, service)
            return .stop
        }
        onPluginStreamFailed?(kind, words, service)
        return .next
    }
}
