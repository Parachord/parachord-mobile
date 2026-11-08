import Foundation
import MediaPlayer
import UIKit
import Shared

/// The one place Parachord talks to the system's Now Playing: the lock screen,
/// Control Center, AirPods / Watch, and CarPlay's Now Playing screen all read
/// `MPNowPlayingInfoCenter` and send their buttons through
/// `MPRemoteCommandCenter`. Owned by the primary `QueuePlaybackCoordinator`.
///
/// **Commands** (play, pause, toggle, next, previous, scrub) go to the
/// coordinator, never to one engine, so a button in the car moves Parachord's
/// queue whichever service is playing. The ±15 s skip commands stay off: with
/// them on, the lock screen and CarPlay show skip-interval buttons instead of
/// next / previous.
///
/// **Info is published only for the local player (`.avPlayer`)**, the one engine
/// that has no publisher of its own. The others already have one, and writing
/// over it from here is either ignored or fights it:
/// - Apple Music: `ApplicationMusicPlayer` publishes natively. Left untouched.
/// - Spotify: plays in Spotify's app; our session mixes with others, so iOS
///   shows Spotify's Now Playing (the CarPlay design's decision).
/// - SoundCloud and Bandcamp: WebKit publishes the page's Media Session from its
///   GPU process and takes the buttons itself (see `iosApp/AGENTS.md`). The
///   simulator's `mediaremoted` log shows it as a separate client
///   (`com.apple.WebKit.GPU/com.parachord.ios`) that wins while it plays.
/// While Spotify, SoundCloud or Bandcamp plays (or a browser-only source is
/// open) the app's own info is cleared, so a stale local track can't show if
/// their publisher goes away. With no current track it's cleared too.
@MainActor
final class NowPlayingPublisher {

    private weak var coordinator: QueuePlaybackCoordinator?

    /// What the lock screen shows, minus the elapsed time. A change republishes.
    private struct Signature: Equatable {
        var trackId: String
        var title: String
        var artist: String
        var album: String
        var durationSec: Int
        var isPlaying: Bool
        var artworkUrl: String?
        var hasArtwork: Bool
    }

    private var lastSignature: Signature?
    private var lastElapsed: Double = 0
    private var lastRate: Double = 0
    private var lastPublishedAt = Date.distantPast
    /// True once the info has been taken down, so an idle tick doesn't rewrite nil.
    private var cleared = false

    private var artwork: (url: String, value: MPMediaItemArtwork)?
    private var artworkLoadingUrl: String?
    private var artworkFailedUrl: String?

    init(coordinator: QueuePlaybackCoordinator) {
        self.coordinator = coordinator
        registerCommands()
    }

    // MARK: - Commands

    /// A button press from the lock screen, Control Center, headphones or the car.
    enum Command {
        case play, pause, toggle, next, previous
        case seek(Double)
    }

    private func registerCommands() {
        let center = MPRemoteCommandCenter.shared()
        let bind: (MPRemoteCommand, Command) -> Void = { command, action in
            command.isEnabled = true
            command.addTarget { [weak self] _ in self?.perform(action) ?? .commandFailed }
        }
        bind(center.playCommand, .play)
        bind(center.pauseCommand, .pause)
        bind(center.togglePlayPauseCommand, .toggle)
        bind(center.nextTrackCommand, .next)
        bind(center.previousTrackCommand, .previous)
        center.changePlaybackPositionCommand.isEnabled = true
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let e = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            return self?.perform(.seek(e.positionTime)) ?? .commandFailed
        }
        center.skipForwardCommand.isEnabled = false
        center.skipBackwardCommand.isEnabled = false
    }

    /// Run a command on the coordinator. MediaPlayer calls the handlers on the
    /// main thread. The info follows by itself: the local player reports play,
    /// pause and seek (`IosAVPlayer.onNowPlayingChanged`), and the 1 s tick
    /// catches the track change.
    func perform(_ command: Command) -> MPRemoteCommandHandlerStatus {
        guard let coord = coordinator, coord.currentTrack != nil else { return .noActionableNowPlayingItem }
        // A browser-only source plays outside the app; "play" would reopen the page.
        if coord.activeEngine == .browser { return .commandFailed }
        switch command {
        case .play: if !coord.isPlaying { coord.togglePlayPause() }
        case .pause: if coord.isPlaying { coord.togglePlayPause() }
        case .toggle: coord.togglePlayPause()
        case .next: coord.skipNext()
        case .previous: coord.skipPrevious()
        case .seek(let seconds): coord.seek(to: seconds)
        }
        return .success
    }

    // MARK: - Info

    /// Republish when something the lock screen shows changed, or the elapsed
    /// time drifted from what the system is extrapolating. Cheap enough for the
    /// coordinator's 1 s tick; `force` when the local player played, paused,
    /// finished a seek or learned its duration.
    func refresh(force: Bool = false) {
        guard let coord = coordinator else { return }
        guard let track = coord.currentTrack else {
            clear()
            return
        }
        switch coord.activeEngine {
        case .avPlayer:
            break
        case .musicKit:
            // ApplicationMusicPlayer publishes natively: leave the info alone.
            lastSignature = nil
            return
        case .spotify, .soundCloud, .bandcamp, .browser:
            // Published from another process (Spotify's app, WebKit's GPU
            // process), or not playing in the app at all. Take the local
            // player's last track down so it can't resurface if that publisher
            // goes away; this never touches theirs.
            clear()
            return
        }
        // Nothing before the local player has an item (a queue restored paused
        // at launch may well play on another engine), so the info stays empty
        // until a track really plays here.
        guard coord.player.loadedURL != nil else {
            lastSignature = nil
            return
        }
        // A new track is starting: `currentTrack` is already the new one while
        // the player (or the engine about to take over) still has the old one.
        // Keep what's shown until it has started.
        guard !coord.isStarting else {
            lastSignature = nil
            return
        }

        let artUrl = pcTrackArt(track.artworkUrl, artist: track.artist, title: track.title, album: track.album)
        loadArtworkIfNeeded(artUrl)
        let art = artwork.flatMap { $0.url == artUrl ? $0.value : nil }

        let title = !track.title.isEmpty ? track.title
            : !coord.player.nowPlayingTitle.isEmpty ? coord.player.nowPlayingTitle : "Unknown Title"
        let artist = !track.artist.isEmpty ? track.artist
            : !coord.player.nowPlayingArtist.isEmpty ? coord.player.nowPlayingArtist : "Unknown Artist"
        let duration = coord.duration
        let elapsed = coord.currentTime
        let playing = coord.isPlaying
        let signature = Signature(
            trackId: track.id, title: title, artist: artist, album: track.album ?? "",
            durationSec: Int(duration.rounded()), isPlaying: playing,
            artworkUrl: artUrl, hasArtwork: art != nil)

        let now = Date()
        let expected = lastElapsed + lastRate * now.timeIntervalSince(lastPublishedAt)
        if !force, signature == lastSignature, abs(expected - elapsed) < 1.5 { return }

        var info: [String: Any] = [
            MPMediaItemPropertyTitle: title,
            MPMediaItemPropertyArtist: artist,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: elapsed,
            // 1.0 animates the scrubber and shows the pause glyph; 0.0 the play glyph.
            MPNowPlayingInfoPropertyPlaybackRate: playing ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let album = track.album, !album.isEmpty { info[MPMediaItemPropertyAlbumTitle] = album }
        if duration > 0 { info[MPMediaItemPropertyPlaybackDuration] = duration }
        if let art { info[MPMediaItemPropertyArtwork] = art }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        cleared = false

        if signature != lastSignature {
            NSLog("NOWPLAYING published engine=avPlayer title=%@ album=%@ duration=%.0f playing=%d artwork=%d",
                  title, track.album ?? "-", duration, playing ? 1 : 0, art != nil ? 1 : 0)
        }
        lastSignature = signature
        lastElapsed = elapsed
        lastRate = playing ? 1.0 : 0.0
        lastPublishedAt = now
    }

    /// Take the local player's info off the lock screen.
    private func clear() {
        lastSignature = nil
        guard !cleared else { return }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        cleared = true
    }

    // MARK: - Artwork

    /// Load the cover once per URL, through the same image cache the app's
    /// covers use, then republish with it.
    private func loadArtworkIfNeeded(_ urlString: String?) {
        guard let urlString, artwork?.url != urlString, artworkLoadingUrl != urlString,
              artworkFailedUrl != urlString, let url = URL(string: urlString) else { return }
        if let cached = PCImageStore.load(url) {
            artwork = (urlString, Self.makeArtwork(cached))
            return
        }
        artworkLoadingUrl = urlString
        Task { @MainActor [weak self] in
            let data: Data?
            if url.isFileURL {
                data = try? Data(contentsOf: url)
            } else {
                data = (try? await URLSession.shared.data(from: url))?.0
            }
            guard let self else { return }
            if self.artworkLoadingUrl == urlString { self.artworkLoadingUrl = nil }
            guard let data, let image = UIImage(data: data) else {
                self.artworkFailedUrl = urlString
                return
            }
            PCImageStore.store(url, data, image)
            self.artwork = (urlString, Self.makeArtwork(image))
            self.refresh(force: true)
        }
    }

    private nonisolated static func makeArtwork(_ image: UIImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }
}
