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
// audio-session hold lives in SoundCloud's page, which stays loaded: see
// `iosApp/AGENTS.md`, "Bandcamp through its embed".

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
    func load(embedUrl: String, volume: Float, title: String, artist: String, artworkUrl: String?) async -> LoadResult {
        guard let url = URL(string: embedUrl), let view = ensureWebView() else { return .refused("page") }
        // Before anything else: released and re-requested with the screen locked,
        // iOS refuses WebKit's session and the track plays silent.
        await audioHold?.holdAudio(true)

        pollTask?.cancel()
        generation += 1
        let gen = generation
        let load = BandcampEmbedTracker(startedAtMs: Self.nowMs)
        tracker = load
        isPlaying = false
        currentTime = 0
        duration = 0
        pageLoaded = false
        currentNavigation = view.load(URLRequest(url: url))
        pollTask = Task { @MainActor [weak self] in
            await self?.pollLoop(gen: gen, load: load, embedUrl: embedUrl, volume: volume,
                                 title: title, artist: artist, artworkUrl: artworkUrl)
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
                          title: String, artist: String, artworkUrl: String?) async {
        let embed = BandcampEmbed.shared
        var installed = false
        var clicked = false
        var announced = false
        var reportedFinish = false
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
                    _ = await evaluate(Self.nowPlayingScript(title: title, artist: artist, artworkUrl: artworkUrl))
                }
                _ = await evaluate("__pcPosition(\(duration), \(currentTime), \(isPlaying))")
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
        isPlaying = true
    }

    @MainActor func pause() {
        tracker?.userSetPaused(paused: true, nowMs: Self.nowMs)
        webView?.evaluateJavaScript(BandcampEmbed.shared.PAUSE_SCRIPT, completionHandler: nil)
        isPlaying = false
    }

    @MainActor func seek(to seconds: Double) {
        currentTime = max(0, seconds)
        tracker?.seeked(seconds: currentTime)
        webView?.evaluateJavaScript(BandcampEmbed.shared.seekScript(seconds: currentTime), completionHandler: nil)
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
    /// which accepts only these commands whatever the page puts there).
    private static func nowPlayingScript(title: String, artist: String, artworkUrl: String?) -> String {
        """
        (function () {
          window.__pcRemote = [];
          window.__pcTakeRemote = function () { var c = window.__pcRemote; window.__pcRemote = []; return JSON.stringify(c); };
          window.__pcPosition = function (duration, position, playing) {
            var ms = navigator.mediaSession;
            if (!ms) return false;
            try { ms.playbackState = playing ? 'playing' : 'paused'; } catch (e) {}
            if (ms.setPositionState && duration > 0) {
              try { ms.setPositionState({ duration: duration, position: Math.min(Math.max(position, 0), duration), playbackRate: 1 }); } catch (e) {}
            }
            return true;
          };
          var ms = navigator.mediaSession;
          if (!ms || typeof MediaMetadata === 'undefined') return false;
          var artwork = \(json(artworkUrl ?? ""));
          ms.metadata = new MediaMetadata({ title: \(json(title)), artist: \(json(artist)), artwork: artwork ? [{ src: artwork, sizes: '512x512' }] : [] });
          var actions = { play: 'play', pause: 'pause', nexttrack: 'next', previoustrack: 'previous' };
          Object.keys(actions).forEach(function (action) {
            try { ms.setActionHandler(action, function () { window.__pcRemote.push(actions[action]); }); } catch (e) {}
          });
          try { ms.setActionHandler('seekto', function (d) { if (d && typeof d.seekTime === 'number') window.__pcRemote.push(d.seekTime); }); } catch (e) {}
          return true;
        })()
        """
    }

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

    private static func json(_ s: String) -> String {
        (try? String(data: JSONEncoder().encode(s), encoding: .utf8)) ?? "\"\""
    }

    // MARK: Page

    @MainActor
    private func ensureWebView() -> WKWebView? {
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
