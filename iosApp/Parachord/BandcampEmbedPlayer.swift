import Foundation
import WebKit
import UIKit
import Shared

// MARK: - Bandcamp embed engine (#513, desktop parachord#1231)
//
// Bandcamp tracks play in Bandcamp's own embedded player, as the top-level page
// of a hidden WKWebView. It has no protocol, so desktop's
// `bandcamp-embed-control.js`, copied unchanged, runs in the page and drives its
// `<audio>`. Android does the same in a WebView (`BandcampEmbedBridge`).
//
// Bandcamp's scripts run at that top level, so this view has NO message handler:
// the poll asks `bcState()` from outside, and `BandcampEmbedTracker` (shared)
// trusts none of the answer. Only fixed scripts run, and only while the page is
// exactly the URL asked for.
//
// Each track is a new page, so nothing in it outlives the track. That's why the
// audio-session hold lives in SoundCloud's page, which stays loaded, and why that
// page also carries this track's lock-screen info: see `iosApp/AGENTS.md`,
// "Bandcamp through its embed".

@Observable
final class IosBandcampEmbed {
    enum LoadResult: Equatable {
        case started
        case refused(String)
    }

    private(set) var isPlaying = false
    private(set) var currentTime: Double = 0
    private(set) var duration: Double = 0

    /// Fired when the current track plays to its end.
    @ObservationIgnored var onTrackEnded: (() -> Void)?
    /// Lock-screen / Control Center buttons: "play", "pause", "next", "previous".
    @ObservationIgnored var onRemoteCommand: ((String) -> Void)?
    /// Lock-screen scrubber, in seconds.
    @ObservationIgnored var onRemoteSeek: ((Double) -> Void)?
    /// Keeps WebKit's audio session in use between tracks (SoundCloud's page).
    @ObservationIgnored weak var audioHold: IosSoundCloudWidget?

    @ObservationIgnored private var webView: WKWebView?
    @ObservationIgnored private var delegate: NavigationDelegate?
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var tracker: BandcampEmbedTracker?
    /// The load asked for, and whether it has finished. Until it has, the old page
    /// can still be up: replaying a track is the same URL, and its moved position
    /// would read as "started" while the new page never gets its click.
    @ObservationIgnored private var currentNavigation: WKNavigation?
    @ObservationIgnored private var pageLoaded = false
    @ObservationIgnored private var pollTask: Task<Void, Never>?
    /// When the lock screen / CarPlay is told the position again.
    @ObservationIgnored private var positionThrottle = EmbedNowPlaying.Throttle()
    @ObservationIgnored private lazy var controlScript: String? = {
        guard let url = Bundle.main.url(forResource: "bandcamp-embed-control", withExtension: "js") else { return nil }
        return try? String(contentsOf: url, encoding: .utf8)
    }()

    /// Tracker's limit is 15 s; this backstop covers a poll that stopped.
    private static let loadTimeoutSec = 20.0
    private static var nowMs: Int64 { Int64(Date().timeIntervalSince1970 * 1000) }

    /// Load `embedUrl` (from `BandcampEmbed.embedUrl`) and start it at `volume`
    /// (0...1). Returns once the position has moved, or `.refused`.
    @MainActor
    func load(embedUrl: String, volume: Float, title: String, artist: String, album: String?,
              artworkUrl: String?) async -> LoadResult {
        guard let url = URL(string: embedUrl), let view = ensureWebView() else { return .refused("page") }
        // Before anything else: released and re-requested with the screen locked,
        // iOS refuses WebKit's session and the track plays silent.
        await audioHold?.holdAudio(true)
        audioHold?.mirrorNowPlaying(title: title, artist: artist, album: album, artworkUrl: artworkUrl)

        pollTask?.cancel()
        generation += 1
        let gen = generation
        let load = BandcampEmbedTracker(startedAtMs: Self.nowMs)
        tracker = load
        isPlaying = false
        currentTime = 0
        duration = 0
        pageLoaded = false
        positionThrottle.reset()
        currentNavigation = view.load(URLRequest(url: url))
        // The cover as a data: URL, fetched while the page loads (WebKit's own
        // fetch of the http(s) URL failed behind redirects).
        let artwork = Task { await EmbedNowPlaying.inlineArtwork(artworkUrl) }
        pollTask = Task { @MainActor [weak self] in
            await self?.pollLoop(gen: gen, load: load, embedUrl: embedUrl, volume: volume,
                                 title: title, artist: artist, album: album, artworkUrl: artworkUrl,
                                 artwork: artwork)
        }

        let started = Date()
        let deadline = started.addingTimeInterval(Self.loadTimeoutSec)
        while Date() < deadline {
            if gen != generation { return .refused("superseded") }
            if load.started {
                NSLog("BandcampEmbed: '\(title)' started after \(String(format: "%.1f", Date().timeIntervalSince(started)))s")
                return .started
            }
            if load.refused { break }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        NSLog("BandcampEmbed: '\(title)' didn't start")
        // Keep the audio session: the queue may move on to another embedded track.
        if gen == generation { await stop(keepAudioSession: true) }
        return .refused("unplayable")
    }

    @MainActor
    private func pollLoop(gen: Int, load: BandcampEmbedTracker, embedUrl: String, volume: Float,
                          title: String, artist: String, album: String?, artworkUrl: String?,
                          artwork: Task<EmbedNowPlaying.InlineArtwork?, Never>) async {
        let embed = BandcampEmbed.shared
        var installed = false
        var clicked = false
        var announced = false
        var reportedFinish = false
        var wasPlaying = true
        while !Task.isCancelled && gen == generation {
            var raw: String?
            if pageLoaded && webView?.url?.absoluteString == embedUrl {
                if !installed, let controlScript {
                    _ = await evaluate(controlScript)
                    installed = true
                }
                if !clicked {
                    _ = await evaluate(embed.volumeScript(volume: Double(volume)))
                    let answer = await evaluate(embed.START_SCRIPT)
                    clicked = (answer as? Bool) == true
                    // Neither true nor false: the script landed on the page before this one.
                    if !(answer is Bool) { installed = false }
                }
                if clicked {
                    raw = await evaluate(embed.STATE_SCRIPT) as? String
                    // A reloaded page needs the control script again.
                    if raw == nil { installed = false; announced = false }
                }
            }
            load.update(raw: raw, nowMs: Self.nowMs)
            guard gen == generation else { return }
            isPlaying = load.isPlaying
            currentTime = load.positionSeconds
            if load.durationSeconds > 0 { duration = load.durationSeconds }

            if clicked && raw != nil {
                if !announced {
                    announced = true
                    _ = await evaluate(Self.nowPlayingScript)
                    if let script = EmbedNowPlaying.metadataScript(title: title, artist: artist, album: album,
                                                                   artworkUrl: artworkUrl) {
                        _ = await evaluate(script)
                    }
                    positionThrottle.reset()
                    wasPlaying = true
                    // Then the downloaded cover, once it's ready and if this is
                    // still the track: the holding page first, this page last,
                    // since WebKit shows the page that updated last.
                    Task { @MainActor [weak self] in
                        guard let inline = await artwork.value, let self, gen == self.generation else { return }
                        NSLog("BandcampEmbed: cover for '\(title)' as a data URL, \(inline.width)x\(inline.height)")
                        await self.audioHold?.mirrorArtwork(title: title, artist: artist, album: album,
                                                            artworkUrl: artworkUrl, inline: inline)
                        guard gen == self.generation, let script = EmbedNowPlaying.metadataScript(
                            title: title, artist: artist, album: album, artworkUrl: artworkUrl, inline: inline) else { return }
                        _ = await self.evaluate(script)
                    }
                }
                // Resumed after a pause: WebKit kept showing the track as paused
                // (a frozen scrubber) until the element was seeked, whatever the
                // position state said. A seek to where it already is fixes it.
                if isPlaying, !wasPlaying { _ = await evaluate("window.__pcResync && __pcResync()") }
                wasPlaying = isPlaying
                // The position on a change and every few seconds, not every poll.
                // Not repeated while paused: it fights the silent loop's own
                // position (the scrubber bounced to 0:00).
                if positionThrottle.shouldPublish(duration: duration, position: currentTime, playing: isPlaying) {
                    sendPosition()
                }
                drainRemoteCommands(await evaluate("__pcTakeRemote()") as? String)
            }
            if load.finished && !reportedFinish {
                reportedFinish = true
                NSLog("BandcampEmbed: finish at \(currentTime)s of \(duration)s")
                onTrackEnded?()
            }
            try? await Task.sleep(nanoseconds: UInt64(embed.POLL_MS) * 1_000_000)
        }
    }

    @MainActor func play() {
        tracker?.userSetPaused(paused: false, nowMs: Self.nowMs)
        webView?.evaluateJavaScript(BandcampEmbed.shared.PLAY_SCRIPT, completionHandler: nil)
        // After the play, so the page never goes without audio in between.
        webView?.evaluateJavaScript("window.__pcHold && __pcHold(false)", completionHandler: nil)
        isPlaying = true
        publishPosition()
    }

    @MainActor func pause() {
        tracker?.userSetPaused(paused: true, nowMs: Self.nowMs)
        // Before the pause, so the page never goes without audio in between.
        webView?.evaluateJavaScript("window.__pcHold && __pcHold(true)", completionHandler: nil)
        webView?.evaluateJavaScript(BandcampEmbed.shared.PAUSE_SCRIPT, completionHandler: nil)
        isPlaying = false
        publishPosition()
    }

    @MainActor func seek(to seconds: Double) {
        currentTime = max(0, seconds)
        tracker?.seeked(seconds: currentTime)
        webView?.evaluateJavaScript(BandcampEmbed.shared.seekScript(seconds: currentTime), completionHandler: nil)
        publishPosition()
    }

    /// The lock screen's position and play state, now (a play, pause or seek).
    @MainActor private func publishPosition() {
        _ = positionThrottle.shouldPublish(duration: duration, position: currentTime, playing: isPlaying)
        sendPosition()
    }

    /// The position to this page's Media Session. The holding page isn't sent
    /// it: it then reported the track as paused, and took the lock screen
    /// back on every update.
    @MainActor private func sendPosition() {
        guard let script = EmbedNowPlaying.positionScript(duration: duration, position: currentTime,
                                                          playing: isPlaying) else { return }
        webView?.evaluateJavaScript(script, completionHandler: nil)
    }

    /// Halt the track: leaving the page stops it, nothing is left paused. Releases
    /// WebKit's audio session too, unless [keepAudioSession] (another embedded
    /// track may follow).
    @MainActor func stop(keepAudioSession: Bool = false) async {
        generation += 1
        pollTask?.cancel()
        pollTask = nil
        tracker = nil
        isPlaying = false
        currentTime = 0
        if let webView, let blank = URL(string: "about:blank") { webView.load(URLRequest(url: blank)) }
        if !keepAudioSession { await audioHold?.holdAudio(false) }
    }


    // MARK: Lock screen

    /// WebKit publishes the lock screen for the page that's playing, and sends its
    /// buttons to the page. With no bridge into a third-party page, the handlers
    /// only queue the command, and the poll collects it (`drainRemoteCommands`,
    /// which accepts only these commands whatever the page puts there). The
    /// track's metadata and position come from `EmbedNowPlaying`.
    private static let nowPlayingScript = """
        (function () {
          window.__pcRemote = [];
          // A silent loop in this page while paused. With no audio iOS suspends the
          // page's process, which drops the lock screen to "Not Playing" and leaves
          // play with nothing to wake. Same page as the player, so unlike the
          // track-change hold in SoundCloud's page it reports nothing different.
          var silence = null, silenceUrl = null;
          window.__pcHold = function (on) {
            if (on) {
              if (!silence) {
                var rate = 8000, bytes = new Uint8Array(44 + rate), view = new DataView(bytes.buffer);
                var str = function (at, s) { for (var i = 0; i < s.length; i++) view.setUint8(at + i, s.charCodeAt(i)); };
                str(0, 'RIFF'); view.setUint32(4, 36 + rate, true); str(8, 'WAVE'); str(12, 'fmt ');
                view.setUint32(16, 16, true); view.setUint16(20, 1, true); view.setUint16(22, 1, true);
                view.setUint32(24, rate, true); view.setUint32(28, rate, true); view.setUint16(32, 1, true);
                view.setUint16(34, 8, true); str(36, 'data'); view.setUint32(40, rate, true);
                for (var b = 44; b < bytes.length; b++) bytes[b] = 128;
                // Never added to the document, so desktop's bcAudio() can't find it.
                silenceUrl = URL.createObjectURL(new Blob([bytes], { type: 'audio/wav' }));
                silence = new Audio(silenceUrl);
                silence.loop = true;
              }
              var p = silence.play();
              if (p && p.catch) p.catch(function () {});
            } else if (silence) {
              // Dropped, not just paused: a paused element stays the page's
              // now-playing one, and the lock screen / CarPlay then showed the
              // resumed track as paused.
              silence.pause();
              silence.removeAttribute('src');
              silence.load();
              silence = null;
              URL.revokeObjectURL(silenceUrl);
              silenceUrl = null;
            }
            return true;
          };
          window.__pcResync = function () {
            var el = document.querySelector('audio');
            if (el && !el.paused) el.currentTime = el.currentTime;
            return true;
          };
          window.__pcTakeRemote = function () { var c = window.__pcRemote; window.__pcRemote = []; return JSON.stringify(c); };
          var ms = navigator.mediaSession;
          if (!ms) return false;
          var actions = { play: 'play', pause: 'pause', nexttrack: 'next', previoustrack: 'previous' };
          Object.keys(actions).forEach(function (action) {
            try { ms.setActionHandler(action, function () { window.__pcRemote.push(actions[action]); }); } catch (e) {}
          });
          try { ms.setActionHandler('seekto', function (d) { if (d && typeof d.seekTime === 'number') window.__pcRemote.push(d.seekTime); }); } catch (e) {}
          return true;
        })()
        """

    @MainActor
    private func drainRemoteCommands(_ raw: String?) {
        guard let data = raw?.data(using: .utf8),
              let commands = try? JSONSerialization.jsonObject(with: data) as? [Any]
        else { return }
        for command in commands {
            if let name = command as? String, ["play", "pause", "next", "previous"].contains(name) {
                onRemoteCommand?(name)
            } else if let number = command as? NSNumber, number.doubleValue.isFinite, number.doubleValue >= 0 {
                onRemoteSeek?(number.doubleValue)
            }
        }
    }

    // MARK: Page

    @MainActor
    private func ensureWebView() -> WKWebView? {
        // In the car with no phone window at all: refuse, so the
        // next source plays (an embed out of a window stays at 0:00).
        if CarPlaySession.shared.blocksEmbeds {
            NSLog("BandcampEmbed: car connected with no phone window, refusing")
            return nil
        }
        if let webView { return webView }
        let delegate = NavigationDelegate()
        delegate.onFinished = { [weak self] navigation in
            guard let self, navigation === self.currentNavigation else { return }
            self.pageLoaded = true
        }
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.mediaTypesRequiringUserActionForPlayback = []
        let view = WKWebView(frame: CGRect(x: 0, y: 0, width: 400, height: 400), configuration: config)
        view.navigationDelegate = delegate
        view.isUserInteractionEnabled = false
        view.alpha = 0.01
        view.accessibilityElementsHidden = true
        #if DEBUG
        if #available(iOS 16.4, *) { view.isInspectable = true }
        #endif
        // In a window: an orphan view's media stays at 0:00 (tested for #513).
        guard let window = UIApplication.shared.connectedScenes
            .compactMap({ $0 as? UIWindowScene })
            .flatMap({ $0.windows })
            .first(where: { $0.isKeyWindow }) else {
            NSLog("BandcampEmbed: no window to host the view")
            return nil
        }
        window.addSubview(view)
        self.delegate = delegate
        webView = view
        return view
    }

    /// The script's result, or nil. The completion-handler form: the async one
    /// traps when a script's result is undefined.
    @MainActor
    private func evaluate(_ script: String) async -> Any? {
        guard let webView else { return nil }
        return await withCheckedContinuation { continuation in
            webView.evaluateJavaScript(script) { result, _ in continuation.resume(returning: result) }
        }
    }

    // MARK: WebKit delegate (separate object: WebKit doesn't retain it, and this keeps it off the @Observable)

    private final class NavigationDelegate: NSObject, WKNavigationDelegate {
        var onFinished: ((WKNavigation?) -> Void)?

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            onFinished?(navigation)
        }

        /// Only Bandcamp: the page is third party, and nothing here should wander.
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
            let url = action.request.url
            let host = url?.host ?? ""
            let allowed = url?.scheme == "about" || BandcampEmbed.shared.isAllowedHost(host: host)
            if !allowed { NSLog("BandcampEmbed: blocked navigation to \(host)") }
            decisionHandler(allowed ? .allow : .cancel)
        }
    }
}
