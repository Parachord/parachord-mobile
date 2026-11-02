import Foundation
import WebKit
import UIKit
import MediaPlayer
import Shared

// MARK: - SoundCloud widget engine (#495, desktop parachord#1209)
//
// SoundCloud's API no longer gives full tracks: `/stream` 429s or redirects to
// a 30-second preview, and the full HLS streams answer 403 "An active Artist
// Pro subscription is required". So SoundCloud plays through SoundCloud's own
// embeddable widget (`w.soundcloud.com/player`), which needs no sign-in, in a
// hidden WKWebView. Android does the same in a WebView (`SoundCloudWidgetBridge`).
//
// The page is `Resources/soundcloud-widget.html`, identical to Android's copy
// (a test keeps them equal), around desktop's `soundcloud-widget-player.js`,
// copied unchanged. The page is loaded from a string with the module inlined
// in place of its script tag, under an https base URL; events come back
// through the `soundCloudBridge` message handler, tagged with the load's
// generation so ones from a track we've moved past are dropped.
//
// A track the widget won't play never starts (the module reports it after
// 10 s), and one SoundCloud only lets it preview (a Go+ release) is refused by
// the page; `load` returns `.refused` for both, and the router falls through
// to the next ranked source.
//
// Background: WebKit renders the audio out of process, like Apple Music's
// RemotePlayerService, so the coordinator runs the silent keepalive on a
// mixable session for this engine (`applyEngineHandoff`), and this class
// publishes the lock-screen info itself.

@Observable
final class IosSoundCloudWidget {
    enum LoadResult: Equatable {
        case started
        case refused(String)
    }

    private(set) var isPlaying = false
    private(set) var currentTime: Double = 0
    private(set) var duration: Double = 0

    /// Fired when the current track plays to its end (the widget's `finish`).
    @ObservationIgnored var onTrackEnded: (() -> Void)?

    @ObservationIgnored private var webView: WKWebView?
    @ObservationIgnored private var bridge: Bridge?
    @ObservationIgnored private var pageReady = false
    @ObservationIgnored private var generation = 0
    @ObservationIgnored private var pendingResult: LoadResult?
    @ObservationIgnored private var nowPlayingTitle = ""
    @ObservationIgnored private var nowPlayingArtist = ""

    private static let baseURL = URL(string: "https://parachord.com/soundcloud-widget/")
    /// The module's own limit is 10 s; this backstop covers a throttled page.
    private static let loadTimeoutSec = 20.0
    private static let pageTimeoutSec = 15.0

    /// Load `link` (a soundcloud.com page or an api.soundcloud.com track URL)
    /// and start it at `volume` (0...1). Returns once the position has moved,
    /// or `.refused` when the widget won't play it here.
    @MainActor
    func load(link: String, volume: Float, title: String, artist: String) async -> LoadResult {
        guard await ensurePage() else { return .refused("page") }
        generation += 1
        let gen = generation
        pendingResult = nil
        isPlaying = false
        currentTime = 0
        duration = 0
        nowPlayingTitle = title
        nowPlayingArtist = artist
        let linkJson = (try? String(data: JSONEncoder().encode(link), encoding: .utf8)) ?? "\"\""
        // Keep WebKit's audio session in use between tracks (see the page's
        // scHoldAudio): released and re-requested with the screen locked, iOS
        // refuses it and the next track plays silent.
        webView?.evaluateJavaScript("scHoldAudio(true)", completionHandler: nil)
        webView?.evaluateJavaScript("scLoad(\(gen), \(linkJson), \(min(max(volume, 0), 1)))", completionHandler: nil)

        let started = Date()
        let deadline = Date().addingTimeInterval(Self.loadTimeoutSec)
        while Date() < deadline {
            if gen != generation { return .refused("superseded") }
            if let result = pendingResult {
                NSLog("SoundCloudWidget: '\(title)' \(result) after \(String(format: "%.1f", Date().timeIntervalSince(started)))s")
                // A refused track: keep the audio session, the queue may move
                // on to another SoundCloud track.
                if case .refused = result { stop(keepAudioSession: true) }
                return result
            }
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        if gen == generation { stop(keepAudioSession: true) }
        NSLog("SoundCloudWidget: '\(title)' didn't start in \(Int(Self.loadTimeoutSec))s")
        return .refused("unplayable")
    }

    @MainActor func play() {
        webView?.evaluateJavaScript("scPlay()", completionHandler: nil)
        isPlaying = true
        updateNowPlaying()
    }

    @MainActor func pause() {
        webView?.evaluateJavaScript("scPause()", completionHandler: nil)
        isPlaying = false
        updateNowPlaying()
    }

    @MainActor func seek(to seconds: Double) {
        currentTime = max(0, seconds)
        webView?.evaluateJavaScript("scSeek(\(currentTime))", completionHandler: nil)
        updateNowPlaying()
    }

    /// Halt the track: the page blanks the widget, so nothing is left paused.
    /// Releases WebKit's audio session too, unless [keepAudioSession] (another
    /// SoundCloud track may follow).
    @MainActor func stop(keepAudioSession: Bool = false) {
        generation += 1
        isPlaying = false
        currentTime = 0
        guard let webView else { return }
        webView.evaluateJavaScript("scStop()", completionHandler: nil)
        if !keepAudioSession { webView.evaluateJavaScript("scHoldAudio(false)", completionHandler: nil) }
    }

    // MARK: Page

    @MainActor
    private func ensurePage() async -> Bool {
        if webView == nil {
            guard let htmlURL = Bundle.main.url(forResource: "soundcloud-widget", withExtension: "html"),
                  let jsURL = Bundle.main.url(forResource: "soundcloud-widget-player", withExtension: "js"),
                  let page = try? String(contentsOf: htmlURL, encoding: .utf8),
                  let module = try? String(contentsOf: jsURL, encoding: .utf8)
            else {
                NSLog("SoundCloudWidget: page or module missing from the bundle")
                return false
            }
            let html = page.replacingOccurrences(
                of: "<script src=\"soundcloud-widget-player.js\"></script>",
                with: "<script>\n\(module)\n</script>"
            )
            let bridge = Bridge(owner: self)
            let config = WKWebViewConfiguration()
            config.allowsInlineMediaPlayback = true
            config.mediaTypesRequiringUserActionForPlayback = []
            config.userContentController.add(bridge, name: "soundCloudBridge")
            let wv = WKWebView(frame: CGRect(x: 0, y: 0, width: 1, height: 1), configuration: config)
            wv.navigationDelegate = bridge
            wv.isUserInteractionEnabled = false
            wv.alpha = 0.01
            wv.accessibilityElementsHidden = true
            #if DEBUG
            if #available(iOS 16.4, *) { wv.isInspectable = true }
            #endif
            // In a window so WebKit treats it as live (an orphan view's timers
            // and media can be throttled).
            Self.keyWindow()?.addSubview(wv)
            self.bridge = bridge
            webView = wv
            pageReady = false
            wv.loadHTMLString(html, baseURL: Self.baseURL)
        }
        let deadline = Date().addingTimeInterval(Self.pageTimeoutSec)
        while !pageReady && Date() < deadline {
            try? await Task.sleep(nanoseconds: 100_000_000)
        }
        if !pageReady { NSLog("SoundCloudWidget: page didn't load") }
        return pageReady
    }

    @MainActor
    private static func keyWindow() -> UIWindow? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow } ??
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first
    }

    // MARK: Events

    @MainActor
    fileprivate func onEvent(_ raw: String) {
        guard let data = raw.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let gen = obj["gen"] as? Int,
              let type = obj["type"] as? String
        else { return }
        let number = (obj["value"] as? NSNumber)?.doubleValue
        let text = obj["value"] as? String
        if type == "pageReady" {
            pageReady = true
            return
        }
        guard gen == generation else { return }
        switch type {
        case "started":
            isPlaying = true
            if let s = number, s > 0 { duration = s }
            pendingResult = .started
            updateNowPlaying()
        case "failed":
            pendingResult = .refused(text ?? "unplayable")
        case "progress":
            if let s = number { currentTime = s }
        case "duration":
            if let s = number, s > 0 { duration = s; updateNowPlaying() }
        case "play":
            isPlaying = true
            updateNowPlaying()
        case "pause":
            isPlaying = false
            updateNowPlaying()
        case "finish":
            isPlaying = false
            NSLog("SoundCloudWidget: finish at \(currentTime)s of \(duration)s")
            onTrackEnded?()
        default:
            break
        }
    }

    /// Lock screen / Control Center for this engine: WebKit's own media
    /// session would show the page, not the track.
    @MainActor
    private func updateNowPlaying() {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: nowPlayingTitle,
            MPMediaItemPropertyArtist: nowPlayingArtist,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: currentTime,
            MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? 1.0 : 0.0,
        ]
        if duration > 0 { info[MPMediaItemPropertyPlaybackDuration] = duration }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    // MARK: WebKit delegate (separate object: WebKit retains its handlers)

    private final class Bridge: NSObject, WKScriptMessageHandler, WKNavigationDelegate {
        weak var owner: IosSoundCloudWidget?
        init(owner: IosSoundCloudWidget) { self.owner = owner }

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let raw = message.body as? String else { return }
            Task { @MainActor [weak owner] in owner?.onEvent(raw) }
        }

        /// Only our page and SoundCloud: keeps other pages away from the bridge.
        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction,
                     decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
            let url = action.request.url
            let host = url?.host?.lowercased() ?? ""
            let allowed = url?.scheme == "about" ||
                host == "parachord.com" ||
                ["soundcloud.com", "sndcdn.com"].contains { host == $0 || host.hasSuffix(".\($0)") }
            if !allowed { NSLog("SoundCloudWidget: blocked navigation to \(host)") }
            decisionHandler(allowed ? .allow : .cancel)
        }
    }
}
