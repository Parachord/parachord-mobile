import SwiftUI
import Shared
import JavaScriptCore
import AVFoundation
import Combine
import MediaPlayer

// MARK: - AVPlayer engine (phase 4.4)
//
// First-iteration iOS playback layer. Plays one URL at a time via
// `AVPlayer`, exposes the bare state SwiftUI needs (isPlaying,
// currentTime, duration, status), and wires `AVAudioSession` for the
// `.playback` category so audio keeps routing through the iPad's
// speaker / connected output instead of being treated as a UI sound.
//
// Scoped DELIBERATELY to single-track playback. The shared
// `PlaybackEngine` interface (queue management, shuffle, repeat,
// skip next/prev, PlaybackContext) is a follow-up — this class is
// the foundation those features sit on top of, not the full
// implementation.
//
// Background audio (`UIBackgroundModes: audio` + lock-screen
// `MPNowPlayingInfoCenter` / `MPRemoteCommandCenter`) is phase 4.5;
// for this commit playback stops when the app backgrounds, which is
// fine for the smoke-test demo.

@Observable
final class IosAVPlayer {
    enum Status: String {
        case idle, loading, ready, playing, paused, failed
    }

    private var player: AVPlayer?
    private var timeObserver: Any?
    private var statusObservation: NSKeyValueObservation?
    private var endObservation: NSObjectProtocol?
    private var remoteCommandsRegistered = false

    var status: Status = .idle
    var isPlaying = false
    var currentTime: Double = 0
    var duration: Double = 0
    var errorMessage: String?
    var loadedURL: URL?

    // Now-playing metadata, surfaced both to the lock screen via
    // MPNowPlayingInfoCenter and back to the UI as a read-back proof.
    var nowPlayingTitle: String = ""
    var nowPlayingArtist: String = ""

    /// Fired when the current item plays to its end. The queue
    /// coordinator (phase 4.6) sets this to auto-advance. Nil by
    /// default, so standalone single-track use just stops at the end.
    var onTrackEnded: (() -> Void)?
    /// The last keys we pushed to MPNowPlayingInfoCenter — displayed in
    /// the smoke-test card so the lock-screen wiring is observable in
    /// the simulator (where photographing the actual lock screen is
    /// awkward).
    var lastNowPlayingKeys: [String] = []

    init() {
        configureAudioSession()
        setupRemoteCommands()
    }

    deinit {
        teardown()
        tearDownRemoteCommands()
    }

    /// Activate `.playback` category so audio plays through the
    /// device speaker (not the silent-ringer-respecting `.ambient`
    /// default) and stays alive across audio interruptions. Without
    /// this, AVPlayer plays through the receiver and respects the
    /// hardware mute switch.
    private func configureAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(
                .playback,
                mode: .default,
                options: []
            )
            try AVAudioSession.sharedInstance().setActive(true)
        } catch {
            errorMessage = "AVAudioSession setup failed: \(error.localizedDescription)"
        }
    }

    func load(
        url urlString: String,
        title: String = "",
        artist: String = ""
    ) {
        guard let url = URL(string: urlString) else {
            status = .failed
            errorMessage = "Invalid URL"
            return
        }
        teardown()
        nowPlayingTitle = title
        nowPlayingArtist = artist
        let item = AVPlayerItem(url: url)
        let newPlayer = AVPlayer(playerItem: item)
        // KVO on `status` — when item becomes `.readyToPlay`, durationis known
        // and we can publish it. `.failed` surfaces the error to the UI.
        statusObservation = item.observe(\.status, options: [.new]) { [weak self] item, _ in
            guard let self else { return }
            Task { @MainActor in
                switch item.status {
                case .readyToPlay:
                    self.status = .ready
                    self.duration = item.duration.seconds.isFinite
                        ? item.duration.seconds : 0
                    // Duration is now known — refresh the lock-screen
                    // payload so the scrubber shows the right length.
                    self.updateNowPlayingInfo()
                case .failed:
                    self.status = .failed
                    self.errorMessage = item.error?.localizedDescription
                        ?? "Item failed (unknown)"
                default:
                    break
                }
            }
        }
        // Periodic clock observer fires twice a second on the main
        // queue — fine granularity for a progress slider without
        // burning CPU.
        timeObserver = newPlayer.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
            queue: .main
        ) { [weak self] time in
            guard let self else { return }
            self.currentTime = time.seconds.isFinite ? time.seconds : 0
            if self.duration == 0,
               let d = newPlayer.currentItem?.duration.seconds,
               d.isFinite {
                self.duration = d
            }
            self.isPlaying = newPlayer.timeControlStatus == .playing
        }
        endObservation = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: item,
            queue: .main
        ) { [weak self] _ in
            self?.isPlaying = false
            self?.status = .paused
            // Auto-advance if a queue coordinator is attached;
            // otherwise the track just ends.
            self?.onTrackEnded?()
        }
        player = newPlayer
        loadedURL = url
        status = .loading
        errorMessage = nil
    }

    func play() {
        player?.play()
        isPlaying = true
        status = .playing
        updateNowPlayingInfo()
    }

    func pause() {
        player?.pause()
        isPlaying = false
        status = .paused
        updateNowPlayingInfo()
    }

    func togglePlayPause() {
        if isPlaying { pause() } else { play() }
    }

    func seek(to seconds: Double) {
        let target = CMTime(seconds: seconds, preferredTimescale: 600)
        player?.seek(to: target) { [weak self] _ in
            // Reflect the new position into the lock-screen scrubber.
            self?.updateNowPlayingInfo()
        }
    }

    func stop() {
        teardown()
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
        lastNowPlayingKeys = []
    }

    // MARK: - Lock screen / Control Center (phase 4.5)

    /// Push the current track + transport state into
    /// `MPNowPlayingInfoCenter`. This is what populates the lock
    /// screen, Control Center, the now-playing widget, CarPlay, and
    /// the AirPods/Watch transport surfaces. Called on load, play,
    /// pause, and seek so the elapsed-time scrubber stays in sync.
    private func updateNowPlayingInfo() {
        var info: [String: Any] = [:]
        info[MPMediaItemPropertyTitle] = nowPlayingTitle.isEmpty
            ? "Unknown Title" : nowPlayingTitle
        info[MPMediaItemPropertyArtist] = nowPlayingArtist.isEmpty
            ? "Unknown Artist" : nowPlayingArtist
        if duration > 0 {
            info[MPMediaItemPropertyPlaybackDuration] = duration
        }
        info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = currentTime
        // Rate drives whether the lock-screen UI shows a play or pause
        // glyph and whether the scrubber animates: 1.0 = playing,
        // 0.0 = paused.
        info[MPNowPlayingInfoPropertyPlaybackRate] = isPlaying ? 1.0 : 0.0
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
        lastNowPlayingKeys = info.keys.sorted().map { shortKey($0) }
    }

    /// Register lock-screen / remote transport command handlers
    /// (play, pause, toggle, skip-back/forward seek, scrub). Each maps
    /// to the same controls the in-app buttons drive. Idempotent.
    private func setupRemoteCommands() {
        guard !remoteCommandsRegistered else { return }
        let center = MPRemoteCommandCenter.shared()

        center.playCommand.addTarget { [weak self] _ in
            self?.play()
            return .success
        }
        center.pauseCommand.addTarget { [weak self] _ in
            self?.pause()
            return .success
        }
        center.togglePlayPauseCommand.addTarget { [weak self] _ in
            self?.togglePlayPause()
            return .success
        }
        center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let self,
                  let e = event as? MPChangePlaybackPositionCommandEvent
            else { return .commandFailed }
            self.seek(to: e.positionTime)
            return .success
        }
        // Skip ±15s — stand-ins until queue management (phase 4.6)
        // provides real next/previous track commands.
        center.skipForwardCommand.preferredIntervals = [15]
        center.skipForwardCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            self.seek(to: min(self.currentTime + 15, self.duration))
            return .success
        }
        center.skipBackwardCommand.preferredIntervals = [15]
        center.skipBackwardCommand.addTarget { [weak self] _ in
            guard let self else { return .commandFailed }
            self.seek(to: max(self.currentTime - 15, 0))
            return .success
        }
        remoteCommandsRegistered = true
    }

    private func tearDownRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.playCommand.removeTarget(nil)
        center.pauseCommand.removeTarget(nil)
        center.togglePlayPauseCommand.removeTarget(nil)
        center.changePlaybackPositionCommand.removeTarget(nil)
        center.skipForwardCommand.removeTarget(nil)
        center.skipBackwardCommand.removeTarget(nil)
        remoteCommandsRegistered = false
    }

    /// Trim the verbose `MPMediaItem*` / `MPNowPlayingInfo*` constant
    /// names down to a readable tail for the UI read-back panel.
    private func shortKey(_ key: String) -> String {
        key
            .replacingOccurrences(of: "MPMediaItemProperty", with: "")
            .replacingOccurrences(of: "MPNowPlayingInfoProperty", with: "")
    }

    private func teardown() {
        if let obs = timeObserver {
            player?.removeTimeObserver(obs)
            timeObserver = nil
        }
        statusObservation?.invalidate()
        statusObservation = nil
        if let endObs = endObservation {
            NotificationCenter.default.removeObserver(endObs)
            endObservation = nil
        }
        player?.pause()
        player = nil
        isPlaying = false
        currentTime = 0
        duration = 0
        loadedURL = nil
        status = .idle
        errorMessage = nil
    }
}

// MARK: - Queue playback coordinator (phase 4.6)
//
// Bridges the SHARED `QueueManager` (KMP, already in Shared.framework)
// to the iOS `IosAVPlayer`. No queue logic is reimplemented in Swift —
// `QueueManager` owns upNext / history / shuffle / setQueue / skipNext /
// skipPrevious / playFromQueue exactly as it does on Android. This
// coordinator just:
//   - holds the QueueManager + player
//   - on each queue mutation, reads `queueManager.snapshot.value`
//     (synchronous — we're the mutator, so no Flow collection needed)
//     and republishes it as Swift @Observable state for SwiftUI
//   - loads + plays the track QueueManager hands back
//   - auto-advances when the player reports a track ended
//   - routes the lock-screen skip commands to real next/previous track
//
// This is the iOS analogue of Android's PlaybackController queue half.
// Resolver routing (mapping Track.resolver → AVPlayer / MusicKit /
// Spotify Connect) is a follow-up; for now every track plays through
// AVPlayer via its `sourceUrl`.

@Observable
final class QueuePlaybackCoordinator {

    let player: IosAVPlayer
    private let queueManager = QueueManager()

    /// Republished from `QueueManager.snapshot.value` after every
    /// mutation so SwiftUI can render the up-next list reactively.
    var currentTrack: Track?
    var upNext: [Track] = []
    var shuffleEnabled = false

    init(player: IosAVPlayer) {
        self.player = player
        // Auto-advance: when AVPlayer reports the item ended, pull the
        // next track from the shared QueueManager and play it.
        self.player.onTrackEnded = { [weak self] in
            self?.skipNext()
        }
    }

    /// Establish a new queue and start playing the track at
    /// `startIndex`. Mirrors `PlaybackEngine.setQueue`.
    func setQueue(_ tracks: [Track], startIndex: Int) {
        let toPlay = queueManager.setQueue(
            tracks: tracks,
            startIndex: Int32(startIndex),
            context: nil,
            shuffle: shuffleEnabled
        )
        syncSnapshot()
        if let track = toPlay {
            playTrack(track)
        }
    }

    func skipNext() {
        guard let next = queueManager.skipNext(currentTrack: currentTrack) else {
            // Queue exhausted — stop cleanly.
            player.stop()
            currentTrack = nil
            syncSnapshot()
            return
        }
        syncSnapshot()
        playTrack(next)
    }

    func skipPrevious() {
        // Match desktop/Android: if we're more than ~3s into the track,
        // "previous" restarts the current track instead of going back.
        if player.currentTime > 3, currentTrack != nil {
            player.seek(to: 0)
            return
        }
        guard let prev = queueManager.skipPrevious(currentTrack: currentTrack) else {
            player.seek(to: 0)
            return
        }
        syncSnapshot()
        playTrack(prev)
    }

    /// User tapped a track in the up-next list.
    func playFromQueue(_ index: Int) {
        guard let track = queueManager.playFromQueue(
            index: Int32(index),
            currentTrack: currentTrack
        ) else { return }
        syncSnapshot()
        playTrack(track)
    }

    func toggleShuffle() {
        shuffleEnabled = queueManager.toggleShuffle()
        syncSnapshot()
    }

    private func playTrack(_ track: Track) {
        currentTrack = track
        guard let url = track.sourceUrl else {
            // No resolved URL — in production this is where resolver
            // routing kicks in. For the smoke test every track carries
            // a direct sourceUrl, so a nil here means skip ahead.
            skipNext()
            return
        }
        player.load(url: url, title: track.title, artist: track.artist)
        // Poll for ready, then play. Item.status flips asynchronously.
        Task { @MainActor in
            for _ in 0..<40 {
                if player.status == .ready || player.status == .paused {
                    player.play()
                    return
                }
                if player.status == .failed { return }
                try? await Task.sleep(nanoseconds: 150_000_000)
            }
        }
    }

    /// Pull the latest snapshot from the shared QueueManager and mirror
    /// it into Swift @Observable properties. Reads `.value` directly —
    /// safe because every call happens immediately after a mutation we
    /// performed on the same actor, so there's no need to collect the
    /// StateFlow asynchronously.
    private func syncSnapshot() {
        // Kotlin `StateFlow<QueueSnapshot>.value` bridges to Swift as
        // `Any?` (the generic erases), so cast back to the concrete
        // snapshot type.
        guard let snap = queueManager.snapshot.value as? QueueSnapshot else { return }
        upNext = snap.upNext
        shuffleEnabled = snap.shuffleEnabled
    }
}

// MARK: - JSC Polyfills (phase 4.2)
//
// Swift companion to `IosJsRuntime`. The Kotlin runtime stands up the
// JSContext and owns the lifecycle; this struct attaches the
// console / fetch / storage polyfills via the JSC subscript API that
// Kotlin/Native's bindings don't expose
// (`ctx["__nativeLog"] = { level, msg in ... }`).
//
// Polyfills route to Swift closures the call site provides, so a smoke-
// test harness can capture log lines into a SwiftUI @State buffer
// without the polyfill itself owning any UI concerns.

enum JsPolyfills {

    /// Attach a `console.log` / info / warn / error / debug polyfill
    /// that routes through `__nativeLog(level, message)` to the
    /// caller-provided `onLog` closure. Fires synchronously inside the
    /// next `evaluateScript`; if `onLog` updates SwiftUI state it MUST
    /// dispatch to the main actor itself.
    static func attachConsole(
        to context: JSContext,
        onLog: @escaping (_ level: String, _ message: String) -> Void
    ) {
        let nativeLog: @convention(block) (String, String) -> Void = { level, message in
            onLog(level, message)
        }
        context.setObject(
            nativeLog,
            forKeyedSubscript: "__nativeLog" as NSString
        )
        context.evaluateScript("""
            (function() {
                var levels = ['log', 'info', 'warn', 'error', 'debug'];
                if (typeof console === 'undefined') { console = {}; }
                levels.forEach(function(level) {
                    console[level] = function() {
                        var args = Array.prototype.slice.call(arguments);
                        __nativeLog(level, args.map(String).join(' '));
                    };
                });
            })();
        """)
    }

    /// Attach a `window.fetch` polyfill that routes through
    /// `__nativeFetchAsync(callbackId, url, method, headersJson, body)`
    /// to a Swift `URLSession` call, then resolves the JS Promise via
    /// `window.__fetchCallbacks[callbackId](envelopeStr)`. Mirrors the
    /// Android `NativeBridge.fetchAsync` callback pattern in
    /// `bootstrap.html` byte-for-byte so .axe plugins written against
    /// the Android host work unmodified on iOS.
    ///
    /// The Swift block fires synchronously on whatever thread JS's
    /// `evaluateScript` was called from; the `URLSession.shared.data`
    /// work happens on the system's URL-loading queue; the callback
    /// dispatches BACK to main before invoking the JS callback,
    /// because `JSContext` is thread-bound to its creation thread
    /// (main in our setup).
    ///
    /// Captures `context` strongly — fine for the smoke test, but
    /// production should hold a weak ref or pin the closure's lifetime
    /// to the runtime's.
    static func attachFetch(to context: JSContext) {
        let nativeFetchAsync: @convention(block) (
            _ callbackId: String,
            _ url: String,
            _ method: String,
            _ headersJson: String,
            _ body: String
        ) -> Void = { callbackId, url, method, headersJson, body in
            guard let requestURL = URL(string: url) else {
                fireFetchCallback(
                    context: context,
                    callbackId: callbackId,
                    envelope: errorEnvelope("Invalid URL: \(url)")
                )
                return
            }
            var request = URLRequest(url: requestURL)
            request.httpMethod = method
            // headersJson is the JSON of the JS options.headers object —
            // a flat map of header-name → string-value. Parse defensively
            // (.axe plugins occasionally pass empty objects or arrays).
            if let headersData = headersJson.data(using: .utf8),
               let parsed = try? JSONSerialization.jsonObject(with: headersData),
               let headers = parsed as? [String: String] {
                for (name, value) in headers {
                    request.setValue(value, forHTTPHeaderField: name)
                }
            }
            if !body.isEmpty {
                request.httpBody = body.data(using: .utf8)
            }
            Task.detached {
                do {
                    let (data, response) = try await URLSession.shared.data(for: request)
                    let httpResponse = response as? HTTPURLResponse
                    let status = httpResponse?.statusCode ?? 0
                    let responseBody = String(data: data, encoding: .utf8) ?? ""
                    let envelope: [String: Any] = [
                        "ok": (200..<300).contains(status),
                        "status": status,
                        "body": responseBody,
                    ]
                    await MainActor.run {
                        fireFetchCallback(
                            context: context,
                            callbackId: callbackId,
                            envelope: envelope
                        )
                    }
                } catch {
                    await MainActor.run {
                        fireFetchCallback(
                            context: context,
                            callbackId: callbackId,
                            envelope: errorEnvelope(error.localizedDescription)
                        )
                    }
                }
            }
        }
        context.setObject(
            nativeFetchAsync,
            forKeyedSubscript: "__nativeFetchAsync" as NSString
        )
        // JS-side polyfill matches `app/src/main/assets/js/bootstrap.html`
        // for byte-compatibility with .axe plugins ported from Android.
        // The `window` alias is required because Android's WebView
        // provides `window` natively; JSC is pure JavaScript and only
        // has `globalThis`. Aliasing once at the top means every plugin
        // that does `window.fetch(...)` keeps working unmodified.
        context.evaluateScript("""
            (function() {
                if (typeof window === 'undefined') { globalThis.window = globalThis; }
                window.__fetchCallbacks = window.__fetchCallbacks || {};
                var __fetchIdCounter = 0;
                window.fetch = function(url, options) {
                    options = options || {};
                    var method = (options.method || 'GET').toUpperCase();
                    var headers = JSON.stringify(options.headers || {});
                    var body = typeof options.body === 'string' ? options.body
                             : options.body ? JSON.stringify(options.body) : '';
                    var callbackId = 'fetch_' + (++__fetchIdCounter);
                    return new Promise(function(resolve, reject) {
                        window.__fetchCallbacks[callbackId] = function(envelopeStr) {
                            delete window.__fetchCallbacks[callbackId];
                            try {
                                var parsed = JSON.parse(envelopeStr);
                                var responseBody = parsed.body || '';
                                if (parsed.error) {
                                    reject(new Error(parsed.error));
                                    return;
                                }
                                resolve({
                                    ok: parsed.ok,
                                    status: parsed.status,
                                    statusText: parsed.ok ? 'OK' : 'Error',
                                    text: function() { return Promise.resolve(responseBody); },
                                    json: function() {
                                        try { return Promise.resolve(JSON.parse(responseBody)); }
                                        catch (e) { return Promise.reject(e); }
                                    }
                                });
                            } catch (e) { reject(e); }
                        };
                        __nativeFetchAsync(callbackId, url, method, headers, body);
                    });
                };
            })();
        """)
    }

    /// Attach a `window.storage.get(key)` / `set(key, value)` polyfill
    /// backed by `UserDefaults`. Mirrors the Android
    /// `NativeBridge.storageGet` / `storageSet` allowlist —
    /// keys MUST start with `parachord.` or `plugin.` or the get
    /// returns null / the set is dropped. Without that allowlist,
    /// a compromised .axe plugin could read arbitrary settings.
    ///
    /// For a smoke-test demo we go straight to `UserDefaults.standard`.
    /// The Android production path goes through DataStore (typed,
    /// async, observable). When the shared `KvStore` /
    /// `SettingsStore` gets wired through Koin on iOS, this should
    /// swap to call into shared Kotlin so storage stays unified
    /// between native code and JS plugins.
    static func attachStorage(to context: JSContext) {
        let nativeStorageGet: @convention(block) (String) -> String? = { key in
            guard isAllowedStorageKey(key) else { return nil }
            return UserDefaults.standard.string(forKey: key)
        }
        let nativeStorageSet: @convention(block) (String, String) -> Void = { key, value in
            guard isAllowedStorageKey(key) else { return }
            UserDefaults.standard.set(value, forKey: key)
        }
        context.setObject(
            nativeStorageGet,
            forKeyedSubscript: "__nativeStorageGet" as NSString
        )
        context.setObject(
            nativeStorageSet,
            forKeyedSubscript: "__nativeStorageSet" as NSString
        )
        context.evaluateScript("""
            (function() {
                if (typeof window === 'undefined') { globalThis.window = globalThis; }
                window.storage = {
                    get: function(key) { return __nativeStorageGet(key); },
                    set: function(key, value) { __nativeStorageSet(key, value); }
                };
            })();
        """)
    }

    // MARK: - Helpers

    private static func isAllowedStorageKey(_ key: String) -> Bool {
        return key.hasPrefix("parachord.") || key.hasPrefix("plugin.")
    }

    private static func errorEnvelope(_ message: String) -> [String: Any] {
        return [
            "ok": false,
            "status": 0,
            "body": "",
            "error": message,
        ]
    }

    /// Call back into JS to resolve a pending fetch. Calls the
    /// JSValue function directly via `JSValue.call(withArguments:)`
    /// — no string-escaping into `evaluateScript`, no risk of JS
    /// injection if the upstream returns funky body content.
    private static func fireFetchCallback(
        context: JSContext,
        callbackId: String,
        envelope: [String: Any]
    ) {
        guard let envelopeData = try? JSONSerialization.data(
            withJSONObject: envelope,
            options: []
        ),
        let envelopeStr = String(data: envelopeData, encoding: .utf8) else {
            return
        }
        let callbacks = context.objectForKeyedSubscript("__fetchCallbacks")
        let callback = callbacks?.objectForKeyedSubscript(callbackId)
        if let callback, !callback.isUndefined {
            callback.call(withArguments: [envelopeStr])
        }
    }
}

/// Phase 2 + 2.5 "Hello-shared" smoke test.
///
/// Phase 2 (the static, pure-math section): proves the
/// `Shared.framework` static binary links, ObjC interop exposes
/// Kotlin classes + companion constants, and top-level Kotlin
/// functions are reachable from Swift (`scoreConfidence` is
/// declared in `ResolverModels.kt` so it bridges as
/// `ResolverModelsKt.scoreConfidence`).
///
/// Phase 2.5 (the async section): proves Ktor's Darwin engine
/// fires real HTTP, kotlinx.serialization parses live JSON on iOS,
/// and Kotlin `suspend fun` bridges to Swift `async throws` end-to-
/// end. Calls MusicBrainz because it's the only first-party API
/// surface in the shared module that needs no auth.
struct ContentView: View {
    private let smokeTest = IosSmokeTest()
    private let threshold = ResolverScoring.companion.MIN_CONFIDENCE_THRESHOLD

    private let correctMatch = ResolverModelsKt.scoreConfidence(
        targetTitle: "Imagine",
        targetArtist: "John Lennon",
        matchedTitle: "Imagine",
        matchedArtist: "John Lennon"
    )

    private let wrongArtist = ResolverModelsKt.scoreConfidence(
        targetTitle: "Imagine",
        targetArtist: "John Lennon",
        matchedTitle: "Imagine",
        matchedArtist: "A Perfect Circle"
    )

    private let noMatch = ResolverModelsKt.scoreConfidence(
        targetTitle: "Imagine",
        targetArtist: "John Lennon",
        matchedTitle: "Smells Like Teen Spirit",
        matchedArtist: "Nirvana"
    )

    @State private var artistQuery = "Radiohead"
    @State private var artists: [SmokeTestArtist] = []
    @State private var loading = false
    @State private var error: String?

    @State private var mosaicURL: URL?
    @State private var mosaicLoading = false
    @State private var mosaicError: String?

    @State private var jsResults: [(label: String, value: String)] = []
    @State private var jsError: String?
    @State private var jsConsoleOutput: [String] = []
    @State private var jsPolyfillsAttached = false

    @State private var avPlayer = IosAVPlayer()
    @State private var queue: QueuePlaybackCoordinator?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header
                resolverScoringCard
                queueCard
                avPlayerCard
                jsRuntimeCard
                mosaicSmokeTestCard
                ktorSmokeTestCard
            }
            .padding()
        }
    }

    // MARK: - Phase 2 (sync)

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Parachord")
                .font(.largeTitle.weight(.semibold))
            Text("Hello, shared 👋")
                .font(.title3)
                .foregroundStyle(.secondary)
        }
    }

    private var resolverScoringCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Resolver scoring (shared module, sync)")
                .font(.headline)

            row(label: "MIN_CONFIDENCE_THRESHOLD", value: threshold)
            Divider()
            row(label: "Imagine / Lennon → Imagine / Lennon", value: correctMatch)
            row(label: "Imagine / Lennon → Imagine / A Perfect Circle", value: wrongArtist)
            row(label: "Imagine / Lennon → Teen Spirit / Nirvana", value: noMatch)
        }
        .padding()
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private var expectedSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Expected:")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            Text("• Threshold: 0.6")
                .font(.caption)
            Text("• Both-match: 0.95")
                .font(.caption)
            Text("• Single-axis match: 0.50 (filtered by threshold)")
                .font(.caption)
            Text("• Neither: 0.50 (filtered by threshold)")
                .font(.caption)
        }
        .foregroundStyle(.secondary)
    }

    // MARK: - Phase 2.5 (async / Ktor)

    private var ktorSmokeTestCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("MusicBrainz search (Ktor Darwin, async)")
                .font(.headline)

            HStack(spacing: 8) {
                TextField("Artist name", text: $artistQuery)
                    .textFieldStyle(.roundedBorder)
                    .autocorrectionDisabled()

                Button {
                    Task { await runSearch() }
                } label: {
                    if loading {
                        ProgressView()
                    } else {
                        Text("Search")
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(loading || artistQuery.trimmingCharacters(in: .whitespaces).isEmpty)
            }

            if let error {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            ForEach(artists, id: \.id) { artist in
                VStack(alignment: .leading, spacing: 2) {
                    HStack {
                        Text(artist.name)
                            .font(.body.weight(.medium))
                        if let topTag = artist.topTag {
                            Text(topTag)
                                .font(.caption2)
                                .padding(.horizontal, 6)
                                .padding(.vertical, 2)
                                .background(Color.accentColor.opacity(0.15))
                                .clipShape(Capsule())
                        }
                    }
                    if let disambig = artist.disambiguation {
                        Text(disambig)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 4)
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        // Auto-run for the prefilled query on first appear. Subsequent
        // queries come from the Search button. This is meant as nice
        // UX *and* makes the async path observable end-to-end without
        // needing to script a synthetic tap in the screenshot harness.
        .task {
            if artists.isEmpty && error == nil && !loading {
                await runSearch()
            }
        }
    }

    private func runSearch() async {
        loading = true
        error = nil
        artists = []
        do {
            let results = try await smokeTest.searchArtists(
                query: artistQuery,
                limit: 5
            )
            artists = results
        } catch {
            self.error = "Error: \(error.localizedDescription)"
        }
        loading = false
    }

    // MARK: - Phase 4 (CoreGraphics mosaic)

    private var mosaicSmokeTestCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Playlist mosaic (CoreGraphics, async)")
                .font(.headline)

            Text(
                "Downloads 4 images via Ktor, composites them 2×2 at 600×600 " +
                "via UIGraphicsImageRenderer, writes JPEG to Application Support. " +
                "Returns the file:// URL that ImageEnrichmentService would store."
            )
            .font(.caption)
            .foregroundStyle(.secondary)

            HStack(spacing: 8) {
                Button {
                    Task { await runMosaic() }
                } label: {
                    if mosaicLoading {
                        ProgressView()
                    } else {
                        Text("Compose Mosaic")
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(mosaicLoading)

                if let mosaicURL {
                    Text(mosaicURL.lastPathComponent)
                        .font(.caption.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                }
            }

            if let mosaicError {
                Text(mosaicError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            if let mosaicURL,
               let uiImage = UIImage(contentsOfFile: mosaicURL.path) {
                Image(uiImage: uiImage)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
                    .frame(maxWidth: 300)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        // Auto-run on first appear so the screenshot harness can
        // observe the full pipeline (download + composite + write +
        // render) without a synthetic tap. Subsequent runs come from
        // the button.
        .task {
            if mosaicURL == nil && mosaicError == nil && !mosaicLoading {
                await runMosaic()
            }
        }
    }

    // MARK: - Phase 4.4 (AVPlayer)

    /// Stable, free-to-stream test track. SoundHelix hosts a set of
    /// algorithmic compositions that have been online for years and
    /// don't require any auth — perfect for proving AVPlayer + audio
    /// session work in our setup without depending on a resolved
    /// Spotify / Apple Music / SoundCloud URL.
    private static let testAudioURL =
        "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"

    private var avPlayerCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("AVPlayer (Phase 4.4)")
                .font(.headline)
            Text(
                "Direct AVFoundation playback of an HTTPS audio stream " +
                "via the `.playback` AVAudioSession category. Foundation " +
                "for the iOS half of `PlaybackEngine` — queue, shuffle, " +
                "repeat, skip next/prev, and lock-screen now-playing are " +
                "follow-ups (phase 4.4.5 and 4.5)."
            )
            .font(.caption)
            .foregroundStyle(.secondary)

            HStack(spacing: 8) {
                Button(loadButtonLabel) {
                    if avPlayer.status == .idle || avPlayer.status == .failed {
                        avPlayer.load(
                            url: Self.testAudioURL,
                            title: "SoundHelix Song 1",
                            artist: "T. Schürger"
                        )
                    } else {
                        avPlayer.stop()
                    }
                }
                .buttonStyle(.bordered)

                Button(avPlayer.isPlaying ? "Pause" : "Play") {
                    avPlayer.togglePlayPause()
                }
                .buttonStyle(.borderedProminent)
                .disabled(avPlayer.status == .idle
                          || avPlayer.status == .loading
                          || avPlayer.status == .failed)

                Spacer()

                Text("status: \(avPlayer.status.rawValue)")
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            }

            if avPlayer.duration > 0 {
                Slider(
                    value: Binding(
                        get: { avPlayer.currentTime },
                        set: { avPlayer.seek(to: $0) }
                    ),
                    in: 0...avPlayer.duration
                )
                .tint(.accentColor)

                HStack {
                    Text(Self.formatTime(avPlayer.currentTime))
                    Spacer()
                    Text(Self.formatTime(avPlayer.duration))
                }
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
            }

            if let error = avPlayer.errorMessage {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            if !avPlayer.lastNowPlayingKeys.isEmpty {
                Divider()
                Text("MPNowPlayingInfoCenter (phase 4.5)")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                Text("\(avPlayer.nowPlayingTitle) — \(avPlayer.nowPlayingArtist)")
                    .font(.caption)
                Text("keys: \(avPlayer.lastNowPlayingKeys.joined(separator: ", "))")
                    .font(.caption2.monospaced())
                    .foregroundStyle(.secondary)
                Text("+ MPRemoteCommandCenter: play / pause / toggle / seek / skip±15s")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        // No auto-play here in phase 4.6 — the queue card below owns the
        // single shared `avPlayer` and drives it through a multi-track
        // queue. This card is now the transport + now-playing view of
        // whatever the queue is playing.
    }

    // MARK: - Phase 4.6 (queue coordinator)

    /// Three stable free-stream SoundHelix tracks for the queue demo.
    private static let queueTracks: [(title: String, url: String)] = [
        ("SoundHelix Song 1", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3"),
        ("SoundHelix Song 2", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-2.mp3"),
        ("SoundHelix Song 3", "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-3.mp3"),
    ]

    private var queueCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Queue (Phase 4.6)")
                .font(.headline)
            queueBlurb
            if let queue {
                queueControls(queue)
                queueNowPlaying(queue)
                queueUpNext(queue)
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        // Stand up the coordinator (wrapping the shared avPlayer) on
        // first appear and load a 3-track queue. Auto-plays track 1
        // immediately; the queue drives auto-advance on track end.
        .task {
            if queue == nil {
                let coordinator = QueuePlaybackCoordinator(player: avPlayer)
                queue = coordinator
                let tracks = Self.queueTracks.enumerated().map { idx, t in
                    Self.makeTrack(id: "smoke-queue-\(idx)", title: t.title, url: t.url)
                }
                coordinator.setQueue(tracks, startIndex: 0)
            }
        }
    }

    @ViewBuilder
    private var queueBlurb: some View {
        Text(
            "Shared KMP `QueueManager` drives the iOS `IosAVPlayer` " +
            "through a 3-track queue. No queue logic reimplemented in " +
            "Swift — setQueue / skipNext / skipPrevious / playFromQueue " +
            "/ shuffle all run in the shared module exactly as on Android."
        )
        .font(.caption)
        .foregroundStyle(.secondary)
    }

    @ViewBuilder
    private func queueControls(_ queue: QueuePlaybackCoordinator) -> some View {
        HStack(spacing: 8) {
            Button("◀ prev") { queue.skipPrevious() }
                .buttonStyle(.bordered)
            Button("next ▶") { queue.skipNext() }
                .buttonStyle(.bordered)
            Button(queue.shuffleEnabled ? "shuffle: on" : "shuffle: off") {
                queue.toggleShuffle()
            }
            .buttonStyle(.bordered)
            .tint(queue.shuffleEnabled ? .accentColor : .gray)
            Spacer()
        }
    }

    @ViewBuilder
    private func queueNowPlaying(_ queue: QueuePlaybackCoordinator) -> some View {
        if let current = queue.currentTrack {
            HStack {
                Image(systemName: "play.fill")
                    .font(.caption)
                    .foregroundStyle(Color.accentColor)
                Text("Now: \(current.title)")
                    .font(.callout.weight(.medium))
            }
        }
    }

    @ViewBuilder
    private func queueUpNext(_ queue: QueuePlaybackCoordinator) -> some View {
        if !queue.upNext.isEmpty {
            Text("Up Next (\(queue.upNext.count))")
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
            ForEach(Array(queue.upNext.enumerated()), id: \.offset) { index, track in
                Button {
                    queue.playFromQueue(index)
                } label: {
                    HStack {
                        Text("\(index + 1).")
                            .font(.caption.monospaced())
                            .foregroundStyle(.secondary)
                        Text(track.title)
                            .font(.callout)
                        Spacer()
                    }
                }
                .buttonStyle(.plain)
            }
        } else if queue.currentTrack != nil {
            Text("queue exhausted")
                .font(.caption)
                .foregroundStyle(.secondary)
        }
    }

    /// Build a shared `Track` for the queue demo. Broken out of the
    /// `.map` closure because the 19-parameter Kotlin-bridged
    /// initializer overwhelms Swift's inline type-checker inside a
    /// trailing closure. An explicit return type + statement context
    /// keeps the build fast.
    private static func makeTrack(id: String, title: String, url: String) -> Track {
        return Track(
            id: id,
            title: title,
            artist: "T. Schürger",
            album: nil,
            albumId: nil,
            duration: nil,
            artworkUrl: nil,
            sourceType: "direct",
            sourceUrl: url,
            addedAt: 0,
            resolver: "direct",
            spotifyUri: nil,
            soundcloudId: nil,
            spotifyId: nil,
            appleMusicId: nil,
            recordingMbid: nil,
            artistMbid: nil,
            releaseMbid: nil,
            crossResolverEnrichedAt: nil
        )
    }

    private var loadButtonLabel: String {
        switch avPlayer.status {
        case .idle, .failed: "Load track"
        default: "Stop"
        }
    }

    private static func formatTime(_ seconds: Double) -> String {
        guard seconds.isFinite, seconds >= 0 else { return "0:00" }
        let s = Int(seconds)
        return String(format: "%d:%02d", s / 60, s % 60)
    }

    // MARK: - Phase 4.1 (JavaScriptCore)

    private var jsRuntimeCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("JavaScriptCore runtime (Phase 4.1)")
                .font(.headline)

            Text(
                "Stands up a JSContext via shared `IosJsRuntime`, then runs " +
                "synchronous + async JS through `evaluate(script)`. Polyfills " +
                "for fetch/console/storage land in a follow-up — the binding " +
                "gap for JS↔Kotlin callbacks is documented in IosJsRuntime.kt."
            )
            .font(.caption)
            .foregroundStyle(.secondary)

            ForEach(jsResults, id: \.label) { result in
                HStack(alignment: .top) {
                    Text(result.label)
                        .font(.callout.monospaced())
                    Spacer()
                    Text(result.value)
                        .font(.callout.monospacedDigit())
                        .foregroundStyle(.green)
                }
            }

            if let jsError {
                Text(jsError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }

            if !jsConsoleOutput.isEmpty {
                Divider()
                Text("console.log polyfill (phase 4.2)")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                VStack(alignment: .leading, spacing: 2) {
                    ForEach(Array(jsConsoleOutput.enumerated()), id: \.offset) { _, line in
                        Text(line)
                            .font(.caption.monospaced())
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(8)
                .background(Color.black.opacity(0.06))
                .clipShape(RoundedRectangle(cornerRadius: 6))
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .task {
            if jsResults.isEmpty && jsError == nil {
                await runJsEvaluations()
            }
        }
    }

    private func runJsEvaluations() async {
        do {
            // Phase 4.2: stand up the runtime so `nativeContext` is
            // populated, then attach Swift-side polyfills BEFORE
            // running any script that uses `console.log`.
            try await smokeTest.ensureJsRuntimeReady()
            if !jsPolyfillsAttached,
               let ctx = smokeTest.jsRuntime.nativeContext {
                JsPolyfills.attachConsole(to: ctx) { level, message in
                    // JS callback fires synchronously during
                    // `evaluateScript`, on whatever thread Kotlin's
                    // coroutine ended up on — bounce to main before
                    // mutating SwiftUI state.
                    Task { @MainActor in
                        jsConsoleOutput.append("[\(level)] \(message)")
                    }
                }
                JsPolyfills.attachFetch(to: ctx)
                JsPolyfills.attachStorage(to: ctx)
                jsPolyfillsAttached = true
            }

            // Drive four cases:
            //   1. Synchronous arithmetic — runtime alive
            //   2. JSON.stringify — built-in globals work
            //   3. Synchronous IIFE returning a string — the pattern
            //      .axe plugins use when they're not awaiting native
            //      callbacks
            //   4. console.log via the polyfill — JS calls
            //      `__nativeLog(level, message)`, which the Swift
            //      block fires, which appends a line to the
            //      `jsConsoleOutput` panel below. This is the JS→Swift
            //      callback path the rest of phase 4 (fetch, storage,
            //      MBID resolution) all sit on top of.
            let cases: [(label: String, script: String)] = [
                ("2 + 40", "2 + 40"),
                ("JSON.stringify({a:1,b:[2,3]})", "JSON.stringify({a:1,b:[2,3]})"),
                ("IIFE returning string", "(function() { var x = 21; return JSON.stringify({status: 'ok', value: x * 2}); })()"),
                (
                    "console.log → polyfill",
                    """
                    (function() {
                        console.log('hello from JS', 42, {plug: 'in'});
                        console.warn('warn-level message');
                        console.error('error-level message');
                        return 'logged ' + 3 + ' lines';
                    })()
                    """
                ),
                (
                    "storage.set + get round-trip",
                    """
                    (function() {
                        var key = 'parachord.smoke-test.phase4_3';
                        var value = 'roundtrip-' + Date.now();
                        storage.set(key, value);
                        var got = storage.get(key);
                        return JSON.stringify({set: value, got: got, match: got === value});
                    })()
                    """
                ),
                (
                    "storage allowlist (no prefix)",
                    """
                    (function() {
                        storage.set('attacker.steals.spotify.token', 'pwned');
                        var got = storage.get('attacker.steals.spotify.token');
                        return JSON.stringify({got: got === null ? 'null' : got});
                    })()
                    """
                ),
                (
                    "fetch → console (async)",
                    """
                    (function() {
                        fetch('https://musicbrainz.org/ws/2/artist?query=radiohead&limit=1&fmt=json', {
                            headers: { 'Accept': 'application/json' }
                        })
                        .then(function(r) { return r.json(); })
                        .then(function(data) {
                            var first = (data.artists && data.artists[0]) || {};
                            console.log('fetch ok →', first.name || '<no name>', '(' + (first['sort-name'] || '?') + ')');
                        })
                        .catch(function(e) {
                            console.error('fetch failed:', e.message);
                        });
                        return 'fetching… (check console below)';
                    })()
                    """
                ),
            ]
            var collected: [(label: String, value: String)] = []
            for (label, script) in cases {
                let result = try await smokeTest.evaluateJs(script: script)
                collected.append((label: label, value: result ?? "<null>"))
            }
            jsResults = collected
        } catch {
            jsError = "Error: \(error.localizedDescription)"
        }
    }

    private func runMosaic() async {
        mosaicLoading = true
        mosaicError = nil
        // picsum.photos has stable, deterministic 300×300 endpoints that
        // serve real photo content — perfect for proving the composite
        // pipeline without depending on MB/CAA's release-group MBIDs being
        // exactly right.
        let urls = [
            "https://picsum.photos/id/1/300/300",
            "https://picsum.photos/id/100/300/300",
            "https://picsum.photos/id/200/300/300",
            "https://picsum.photos/id/300/300/300",
        ]
        // Unique ID per tap so the SwiftUI image cache doesn't show a
        // stale result when re-running.
        let id = "smoke-\(Int(Date().timeIntervalSince1970))"
        do {
            if let pathString = try await smokeTest.composeMosaic(
                playlistId: id,
                urls: urls
            ) {
                // Kotlin returns a "file://<absolute path>" string; parse it.
                mosaicURL = URL(string: pathString)
                if mosaicURL == nil {
                    mosaicError = "Couldn't parse returned path: \(pathString)"
                }
            } else {
                mosaicError = "Mosaic returned null — likely a download or encode failure."
            }
        } catch {
            mosaicError = "Error: \(error.localizedDescription)"
        }
        mosaicLoading = false
    }

    // MARK: - Helpers

    private func row(label: String, value: Double) -> some View {
        HStack {
            Text(label)
                .font(.callout)
            Spacer()
            Text(String(format: "%.2f", value))
                .font(.callout.monospacedDigit())
                .foregroundStyle(value >= threshold ? .green : .red)
        }
    }
}

#Preview {
    ContentView()
}
