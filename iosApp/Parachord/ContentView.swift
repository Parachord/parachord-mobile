import SwiftUI
import Shared
import JavaScriptCore
import AVFoundation
import UIKit
import Combine
import MediaPlayer
import MusicKit
import AuthenticationServices
import CryptoKit

// MARK: - Audio session management (#322 — background playback)
//
// There is exactly ONE `AVAudioSession` per process. Our `AVPlayer` (local /
// soundcloud / direct) and `ApplicationMusicPlayer` (Apple Music) both share
// it; Spotify plays in its own app. The category *options* decide whether we
// interrupt other apps — the iOS analog of Android's `handleAudioFocus` flag.
//
// Ported from Android's external-playback survival (`PlaybackController` /
// `PlaybackService`, CLAUDE.md "External Playback Background Survival"):
//   • Local playback → `.playback`, no options: we ARE the audio, ducking
//     others is correct.
//   • External playback (Spotify / Apple Music) → `.playback` + `.mixWithOthers`
//     (== `handleAudioFocus = false`) PLUS a silent keepalive loop, so the app
//     keeps producing (silent) audio and iOS doesn't suspend it in the
//     background — without interrupting the real audio.

enum IosAudioSession {
    /// Own the session for OUR AVPlayer audio (non-mixable). Resets from any
    /// prior mixable "external" mode.
    static func configureForLocal() { apply(options: [], context: "local") }

    /// **Apple Music → mixable `.playback` (`.mixWithOthers`).** AM needs the
    /// silent keepalive to stay alive in the background (it renders out of
    /// process, so iOS otherwise suspends us and auto-advance freezes) — but our
    /// keepalive `AVAudioEngine` and `ApplicationMusicPlayer` then share ONE
    /// process session. On a NON-mixable session those two fight and AM stutters
    /// ~once a second (confirmed on-device with a SINGLE keepalive, #322).
    /// `.mixWithOthers` makes our engine register as SECONDARY so it no longer
    /// fights AM — the same config Spotify uses. Cost: MusicKit force-resets the
    /// session (options 1→0) once on the first background, briefly dropping the
    /// track — a one-time hiccup, far better than a per-second stutter. Order
    /// matters: configure mixable BEFORE `keepAlive.start()` so the engine comes
    /// up secondary.
    static func configureForAppleMusic() { apply(options: [.mixWithOthers], context: "applemusic") }

    /// **Spotify → mixable `.playback`.** Spotify plays in its OWN app, so our
    /// (silent-keepalive) session must NOT interrupt it — `.mixWithOthers` is the
    /// iOS analog of Android's `handleAudioFocus = false`. This is also what stops
    /// Spotify dropping out when Parachord returns to the foreground.
    static func configureForSpotify() { apply(options: [.mixWithOthers], context: "spotify") }

    private static func apply(options: AVAudioSession.CategoryOptions, context: String) {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default, options: options)
            try session.setActive(true)
            NSLog("PCAUDIO: SESSION_CONFIG context=\(context) \(IosAudioSessionMonitor.snapshot())")
        } catch {
            NSLog("PCAUDIO: SESSION_CONFIG_FAILED context=\(context) \(error.localizedDescription)")
        }
    }

    static func deactivate() {
        do {
            try AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        } catch {
            // Non-fatal — often "session still in use" during a fast handoff.
            NSLog("PCAUDIO: DEACTIVATE_FAILED \(error.localizedDescription)")
        }
    }
}

/// Silent-audio keepalive for EXTERNAL playback (#322) — the iOS port of
/// Android's `res/raw/silence.wav` loop (+ its partial WakeLock / WiFi lock).
/// When Spotify / Apple Music is the source, Parachord produces no audio
/// itself, so iOS would suspend the app in the background — freezing its
/// polling / auto-advance and (for `ApplicationMusicPlayer`, whose controller
/// is our process) pausing the music. A looped SILENT buffer at volume 0 keeps
/// `UIBackgroundModes: audio` satisfied so the app stays alive; iOS grants an
/// actively-playing app both CPU and network in the background, so there's no
/// separate wakelock/wifi-lock to port. The session is `.mixWithOthers` for
/// Spotify so the silence never interrupts the real audio.
///
/// **Resilience is the load-bearing part for background AUTO-ADVANCE.** Unlike
/// Android's FGS + wakelock (inherently persistent), an `AVAudioEngine` is
/// stopped by the system on audio-route / configuration changes (and after
/// interruptions). If it isn't restarted the app is suspended the moment the
/// screen locks and the poll loop dies — so the current track keeps playing (in
/// Spotify's app) but never advances. So this observes
/// `AVAudioEngineConfigurationChange` and re-arms itself, and the coordinator
/// re-arms it after interruptions (`resumeActiveEngine`). The buffer is
/// generated in code — no bundled asset to register in the Xcode project.
final class IosExternalKeepAlive {
    private let engine = AVAudioEngine()
    private let node = AVAudioPlayerNode()
    private let format = AVAudioFormat(standardFormatWithSampleRate: 44_100, channels: 2)
    private var running = false
    private var graphBuilt = false
    private var configObserver: NSObjectProtocol?

    deinit {
        if let o = configObserver { NotificationCenter.default.removeObserver(o) }
    }

    /// Start the silent loop and keep it alive across route/config changes.
    /// Idempotent — a no-op if already running (use `restart()` to force).
    func start() {
        guard !running else { return }
        running = true
        buildGraphIfNeeded()
        startEngineAndPlay()
        // A route/format change (headphones, AirPlay, Bluetooth) STOPS the
        // engine; restart it so we keep producing (silent) audio and the app
        // stays alive — the durability Android gets from the wakelock. CRITICAL:
        // only restart when the engine has actually STOPPED, and NEVER reconnect
        // a running engine here. Reconnecting a running AVAudioEngine itself
        // posts an AVAudioEngineConfigurationChange, so a reconnect-on-every-
        // notification loops many times a second and thrashes the shared audio
        // route — observed distorting Spotify and wedging the audio daemon until
        // a reboot (#322). Restart-only-when-stopped can't feed itself.
        configObserver = NotificationCenter.default.addObserver(
            forName: .AVAudioEngineConfigurationChange, object: engine, queue: .main
        ) { [weak self] _ in
            guard let self, self.running, !self.engine.isRunning else { return }
            NSLog("PCAUDIO: KEEPALIVE_CONFIG_CHANGE")
            self.startEngineAndPlay()
        }
        NSLog("PCAUDIO: KEEPALIVE_START")
    }

    /// Force the engine back to a playing state — used after an audio-session
    /// interruption, where the engine is stopped but `running` is still true.
    /// No-op if it's already running (never restart a healthy engine).
    func restart() {
        guard running else { start(); return }
        guard !engine.isRunning else { return }
        startEngineAndPlay()
        NSLog("PCAUDIO: KEEPALIVE_RESTART")
    }

    /// Attach + connect the silent node ONCE. Connecting only ever happens here,
    /// on a stopped engine — never on the config-change path (see `start`).
    private func buildGraphIfNeeded() {
        guard !graphBuilt, let format else { return }
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)
        node.volume = 0
        graphBuilt = true
    }

    /// Start the engine and (re)schedule the looping silent buffer. Does NOT
    /// reconnect the graph — safe to call after the engine stops.
    private func startEngineAndPlay() {
        guard let format, let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 44_100) else {
            NSLog("PCAUDIO: KEEPALIVE_BUFFER_FAILED")
            return
        }
        buffer.frameLength = buffer.frameCapacity   // zero-filled == silence
        do {
            if !engine.isRunning { try engine.start() }
            if !node.isPlaying {
                node.scheduleBuffer(buffer, at: nil, options: [.loops], completionHandler: nil)
                node.play()
            }
        } catch {
            NSLog("PCAUDIO: KEEPALIVE_START_FAILED \(error.localizedDescription)")
        }
    }

    /// Stop the silent loop. Idempotent.
    func stop() {
        guard running else { return }
        running = false
        if let o = configObserver {
            NotificationCenter.default.removeObserver(o)
            configObserver = nil
        }
        node.stop()
        engine.stop()
        NSLog("PCAUDIO: KEEPALIVE_STOP")
    }
}

/// Observes audio-session interruptions, route changes, and app-lifecycle
/// transitions, logging a `PCAUDIO:` trace (the "audio-logs" instrumentation
/// this branch is named for) and resuming the active engine when an
/// interruption ends with `.shouldResume` (e.g. after a phone call).
///
/// Not actor-isolated so the coordinator can construct it in its synchronous
/// init; every observer fires on `.main`, so the `onShouldResume` hand-off and
/// the AVAudioSession reads are main-thread-safe.
final class IosAudioSessionMonitor {
    private var tokens: [NSObjectProtocol] = []
    /// Invoked on interruption-ended-with-shouldResume. The coordinator resumes
    /// whichever engine is active.
    var onShouldResume: (() -> Void)?

    init() {
        let nc = NotificationCenter.default
        let session = AVAudioSession.sharedInstance()
        tokens.append(nc.addObserver(forName: AVAudioSession.interruptionNotification, object: session, queue: .main) { [weak self] note in
            self?.handleInterruption(note)
        })
        tokens.append(nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: session, queue: .main) { note in
            let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt) ?? 0
            NSLog("PCAUDIO: ROUTE_CHANGE reason=\(reason) \(IosAudioSessionMonitor.snapshot())")
        })
        for (name, label) in [
            (UIApplication.willResignActiveNotification, "WILL_RESIGN_ACTIVE"),
            (UIApplication.didEnterBackgroundNotification, "DID_ENTER_BACKGROUND"),
            (UIApplication.willEnterForegroundNotification, "WILL_ENTER_FOREGROUND"),
            (UIApplication.didBecomeActiveNotification, "DID_BECOME_ACTIVE"),
        ] {
            tokens.append(nc.addObserver(forName: name, object: nil, queue: .main) { _ in
                NSLog("PCAUDIO: \(label) \(IosAudioSessionMonitor.snapshot())")
            })
        }
    }

    deinit { tokens.forEach { NotificationCenter.default.removeObserver($0) } }

    private func handleInterruption(_ note: Notification) {
        guard let info = note.userInfo,
              let raw = info[AVAudioSessionInterruptionTypeKey] as? UInt,
              let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
        switch type {
        case .began:
            let reason = (info[AVAudioSessionInterruptionReasonKey] as? UInt).map { "\($0)" } ?? "?"
            NSLog("PCAUDIO: INTERRUPTION type=began reason=\(reason) \(IosAudioSessionMonitor.snapshot())")
        case .ended:
            let optsRaw = (info[AVAudioSessionInterruptionOptionKey] as? UInt) ?? 0
            let shouldResume = AVAudioSession.InterruptionOptions(rawValue: optsRaw).contains(.shouldResume)
            NSLog("PCAUDIO: INTERRUPTION type=ended shouldResume=\(shouldResume) \(IosAudioSessionMonitor.snapshot())")
            if shouldResume { onShouldResume?() }
        @unknown default:
            break
        }
    }

    /// One-line snapshot of the shared session for the PCAUDIO trace.
    static func snapshot() -> String {
        let s = AVAudioSession.sharedInstance()
        return "category=\(s.category.rawValue) options=\(s.categoryOptions.rawValue) otherAudioPlaying=\(s.isOtherAudioPlaying) secondaryHint=\(s.secondaryAudioShouldBeSilencedHint)"
    }
}

// MARK: - Spotify Connect (phase 4.8)
//
// iOS Spotify playback uses the Web API (Spotify Connect over HTTP),
// NOT the Spotify iOS SDK — exactly the architecture decision Android
// made. The HTTP surface (getDevices / transferPlayback / startPlayback
// / pause / seek / volume / getPlaybackState) ALREADY LIVES in the
// shared `SpotifyClient` and is reused unchanged. The only genuinely
// iOS-specific piece is the device-wake strategy, because Android's
// media-button broadcast to `com.spotify.music` has no iOS equivalent.
//
// ## Wake strategy
//
// Android wakes Spotify two ways (invisible media-button broadcast,
// then a launch-intent fallback). iOS app sandboxing blocks targeted
// media-key broadcasts entirely, so iOS has ONE path: the `spotify://`
// universal/scheme deep link via `UIApplication.open`. It briefly
// foregrounds Spotify (less invisible than Android's broadcast) but is
// the same fallback Android already uses when the broadcast fails on a
// killed Spotify. `canWakeSpotify` (canOpenURL) reports whether the
// Spotify app is installed at all.
//
// ## Verification status
//
// `canWakeSpotify` is verifiable: in the simulator Spotify isn't
// installed so it reports false; on a device with Spotify it reports
// true and `wakeSpotify()` foregrounds it. The actual play flow
// (ensureDevice → pickDevice → startPlayback via the shared
// SpotifyClient) needs an OAuth access token (phase 4.9) AND a Spotify
// Premium account, neither suppliable headlessly. The orchestration
// logic is platform-agnostic and largely already in the shared layer;
// this class is the thin iOS shim that adds wake + ties it together.

@MainActor
@Observable
final class IosSpotifyConnect {

    /// The Spotify app's URL scheme. `canOpenURL` requires it be listed
    /// in `LSApplicationQueriesSchemes` (Info.plist) to return true on a
    /// device where Spotify is installed.
    private static let spotifyScheme = "spotify://"

    var spotifyInstalled: Bool = false
    var lastAction: String?

    /// Whether the Connect session is currently playing. Tracked optimistically
    /// (set on play/resume/pause) so the mini-player play/pause glyph reflects
    /// Connect state WITHOUT a per-500ms `getPlaybackState` poll (that ungated
    /// poll is exactly the kind of traffic that re-arms Spotify's rate limit).
    var isPlaying: Bool = false

    /// True when a Spotify OAuth token is present (mirrors the shared
    /// `getSpotifyConnectedFlow()`). Drives `canPlay` so the PlaybackRouter
    /// only routes to Spotify once auth is wired.
    private(set) var connected: Bool = false

    @ObservationIgnored
    private let connectWatcher = FlowWatcher(scope: IosContainer.companion.shared.appScope)
    @ObservationIgnored
    private var connectSubscription: Shared.Cancellable?

    // Spotify Connect device-resolution state (mirrors Android
    // SpotifyPlaybackHandler). Plumbing — not observable UI state.
    @ObservationIgnored private var deviceVerified = false
    @ObservationIgnored private var lastResolvedLocalId: String?
    /// Synthetic "this device" id persisted as the preferred device — the real
    /// id changes between Spotify sessions, so we persist this token instead.
    static let localDeviceId = "__local_device__"

    /// Non-nil while the device picker should be shown. Observed by the root
    /// view's `.sheet`. Mirrors Android's `devicePickerRequest` StateFlow.
    var pickerRequest: SpotifyPickerRequest?
    /// Bridges the async `play()` flow to the user's picker choice — the Swift
    /// analogue of Android's `CompletableDeferred<SpDevice?>`.
    @ObservationIgnored private var pickerContinuation: CheckedContinuation<SpDevice?, Never>?

    init() {
        // Observe token presence; held for the lifetime of this object so the
        // FlowWatcher + Cancellable aren't deallocated mid-collection.
        connectSubscription = connectWatcher.watch(
            flow: IosContainer.companion.shared.getSpotifyConnectedFlow()
        ) { [weak self] value in
            self?.connected = (value as? Bool) ?? ((value as? KotlinBoolean)?.boolValue ?? false)
        }
    }

    func refreshInstalled() {
        guard let url = URL(string: Self.spotifyScheme) else {
            spotifyInstalled = false
            return
        }
        spotifyInstalled = UIApplication.shared.canOpenURL(url)
    }

    /// Wake Spotify by foregrounding it via the `spotify://` deep link.
    /// The iOS equivalent of Android's media-button broadcast → launch
    /// intent fallback. After this, the shared SpotifyClient's
    /// getDevices() should report Spotify's local device as available
    /// for a Connect transfer.
    func wakeSpotify() {
        guard let url = URL(string: Self.spotifyScheme) else { return }
        UIApplication.shared.open(url, options: [:]) { [weak self] success in
            Task { @MainActor in
                self?.lastAction = success
                    ? "Opened spotify:// (Spotify foregrounded)"
                    : "Couldn't open spotify:// (app not installed)"
            }
        }
    }

    /// Wake Spotify by opening the TRACK deep link (`spotify:track:<id>`)
    /// instead of bare `spotify://`, so Spotify lands on the right track while
    /// it registers as a Connect device (avoiding a wrong-track auto-resume
    /// flash). Playback itself is then started via the Web API once the device
    /// appears. Falls back to the bare scheme if `trackUri` isn't a valid URL.
    func wakeSpotifyPlaying(_ trackUri: String) {
        guard let url = URL(string: trackUri) else { wakeSpotify(); return }
        UIApplication.shared.open(url, options: [:]) { [weak self] success in
            Task { @MainActor in
                self?.lastAction = success
                    ? "Opened \(trackUri) (testing autoplay)"
                    : "Couldn't open Spotify (app not installed)"
            }
        }
    }

    /// Whether Spotify playback can currently run. True once a Spotify OAuth
    /// token is present (observed from `getSpotifyConnectedFlow()`). The
    /// PlaybackRouter gates on this and falls through to the next source when
    /// false.
    var canPlay: Bool { connected }

    /// Start Spotify Connect playback on the device Parachord is running on
    /// (this iPad/iPhone), mirroring Android's `SpotifyPlaybackHandler`
    /// (CLAUDE.md "Spotify Connect — Device Wake & Playback"):
    ///
    /// 1. **Warm path** — a cached resolved local device still in the list →
    ///    `startPlayback` directly.
    /// 2. **Prefer the LOCAL device** — this device's own Spotify entry (by
    ///    name / Tablet|Smartphone type), NEVER an active *remote* (a phantom
    ///    Bedroom-TV / a just-closed desktop the API still lists). That phantom
    ///    pickup was the "succeeds into the void / nothing plays" bug.
    /// 3. **Resolve local** — if this device's Spotify isn't registered yet,
    ///    wake it (`spotify://` — iOS's only wake; no invisible broadcast) and
    ///    poll until it appears (cold launch ~15-20s), pausing once so Spotify
    ///    doesn't auto-resume its own last track.
    /// 4. `startPlayback` with `device_id` (no separate transfer — it activates
    ///    an inactive device). Single 502 retry.
    ///
    /// Returns `false` when no local device can be established → `PlaybackRouter`
    /// falls through to the next source in the user's resolver order, instead of
    /// "succeeding" onto a phantom remote.
    @MainActor
    func play(uri: String) async -> Bool {
        let client = IosContainer.companion.shared.spotifyClient
        let settings = IosContainer.companion.shared.settingsStore

        // 0. Honor the shared rate-limit cooldown BEFORE any Connect call.
        // The device/playback endpoints are ungated (they must work during
        // normal playback), so without this guard the play flow would flood
        // getDevices/startPlayback at a rate-limited account — keeping
        // Spotify's rolling abuse window hot and re-arming the cooldown so it
        // never clears. Android rule: never make ungated Spotify calls during
        // an abuse window. Return false → PlaybackRouter falls through.
        let cooldownMs = client.rateLimitRemainingMs()
        if cooldownMs > 0 {
            lastAction = "Spotify rate-limited — try again in ~\(Int(cooldownMs) / 1000)s"
            return false
        }

        // 1. Warm path — fire startPlayback DIRECTLY at the cached local device
        //    id, with NO getDevices() gate. A Spotify device that just finished a
        //    track drops out of GET /me/player/devices (that endpoint only lists
        //    ACTIVE sessions), so gating on presence made auto-transition between
        //    songs miss the warm path and fall to the cold path — which wakes /
        //    foregrounds Spotify on almost every track (#283). Manual skip didn't
        //    hit it because the previous track is still actively playing, so the
        //    device is still listed. Connect's startPlayback re-activates an
        //    idle-but-present device silently; only a genuinely stale id (Spotify
        //    restarted) fails → we clear + fall through to the cold resolve.
        //    Mirrors Android's "fire startPlayback directly without any device
        //    fetch" warm-path optimization.
        if deviceVerified, let warmId = lastResolvedLocalId {
            NSLog("PCSPOT: warm attempt device=\(warmId)")
            if await startPlayback(client, uri: uri, deviceId: warmId) {
                NSLog("PCSPOT: warm OK (no wake)")
                return true
            }
            NSLog("PCSPOT: warm FAILED — clearing + cold path")
            lastResolvedLocalId = nil
            deviceVerified = false
        } else {
            NSLog("PCSPOT: warm SKIPPED deviceVerified=\(deviceVerified) haveId=\(lastResolvedLocalId != nil)")
        }

        // 2. Resolve the target device (Android `pickDevice` parity).
        //    - preferred == "This device"  → wake this device.
        //    - preferred remote present     → control it silently.
        //    - stale preference             → clear, fall through.
        //    - no live device at all        → wake this device (only option).
        //    - otherwise (ambiguous)        → ASK via the picker; remember the
        //      choice (LOCAL token for this-device, real id for a remote).
        let preferred = (try? await settings.getPreferredSpotifyDeviceId()) ?? nil
        let usable = ((try? await client.getDevices())?.devices ?? []).filter { !$0.isRestricted }

        var target: SpDevice?
        if preferred == Self.localDeviceId {
            target = await resolveLocalDevice(client, playUri: uri)
        } else if let pid = preferred, let match = usable.first(where: { $0.id == pid }) {
            target = match
        } else {
            if let pid = preferred, !pid.isEmpty {
                try? await settings.clearPreferredSpotifyDeviceId() // stale
            }
            if usable.isEmpty {
                target = await resolveLocalDevice(client, playUri: uri)
            } else {
                // Offer the live devices plus a synthetic "This device".
                guard let chosen = await presentPicker(usable + [localDeviceEntry()]) else {
                    lastAction = "Playback cancelled"
                    return false
                }
                try? await settings.setPreferredSpotifyDeviceId(deviceId: chooseStableId(chosen))
                target = isLocalPlaceholder(chosen) ? await resolveLocalDevice(client, playUri: uri) : chosen
            }
        }

        guard let dev = target else {
            lastAction = "Couldn't find a Spotify device. Open Spotify and try again."
            return false
        }

        // 4. Play. (We're on the COLD path here — the warm path returns
        // early on success — so deviceVerified is currently false.)
        let ok = await startPlayback(client, uri: uri, deviceId: dev.id)
        if ok {
            // Quick verification on cold devices only (2 polls × 500ms),
            // mirroring Android's verifyPlaybackStarted. Optimistic: if it
            // can't confirm, proceed anyway (the device accepted the call).
            if !deviceVerified {
                let verified = await verifyPlaybackStarted(client)
                if !verified { lastAction = "Spotify playback started (unverified)" }
            }
            deviceVerified = true
            lastResolvedLocalId = dev.id
            // Sticky default (parity with Android): persist the SYNTHETIC
            // localDeviceId, never the real id (it changes between Spotify
            // sessions). Only when no preference is already set.
            let existing = (try? await settings.getPreferredSpotifyDeviceId()) ?? nil
            if existing == nil {
                try? await settings.setPreferredSpotifyDeviceId(deviceId: Self.localDeviceId)
            }
        }
        return ok
    }

    /// Synthetic "This device" entry injected into the picker (Android's
    /// `localDeviceEntry`). Picking it routes through `resolveLocalDevice`
    /// (wake this device's Spotify).
    private func localDeviceEntry() -> SpDevice {
        let isPad = UIDevice.current.userInterfaceIdiom == .pad
        return SpDevice(
            id: Self.localDeviceId,
            name: UIDevice.current.name,
            isActive: false,
            isRestricted: false,
            type: isPad ? "Tablet" : "Smartphone",
            volumePercent: nil
        )
    }

    private func isLocalPlaceholder(_ device: SpDevice) -> Bool { device.id == Self.localDeviceId }

    /// Persist LOCAL_DEVICE_ID for this device (synthetic OR a matched local
    /// smartphone) — its real id rotates between Spotify sessions; remotes keep
    /// their real id. Mirrors Android `chooseStableId`.
    private func chooseStableId(_ chosen: SpDevice) -> String {
        (isLocalPlaceholder(chosen) || isLocalRealDevice(chosen)) ? Self.localDeviceId : chosen.id
    }

    /// Present the device picker and await the user's choice (or nil on
    /// cancel) — the Swift analogue of Android's `CompletableDeferred.await()`.
    private func presentPicker(_ devices: [SpDevice]) async -> SpDevice? {
        pickerContinuation?.resume(returning: nil) // cancel any stale picker
        pickerContinuation = nil
        return await withCheckedContinuation { cont in
            pickerContinuation = cont
            pickerRequest = SpotifyPickerRequest(devices: devices)
        }
    }

    /// Called by the picker sheet when the user taps a device (or nil to
    /// cancel / dismiss). Mirrors Android's `onDevicePicked`. Idempotent.
    func onDevicePicked(_ device: SpDevice?) {
        pickerRequest = nil
        let cont = pickerContinuation
        pickerContinuation = nil
        cont?.resume(returning: device)
    }

    /// Pause the active Connect session via the Web API (PUT /me/player/pause).
    /// Used by the mini-player / Now Playing play-pause toggle when Spotify is
    /// the active engine. Optimistically flips `isPlaying` so the glyph updates
    /// immediately; reverts if the call fails.
    @MainActor
    func pause() async {
        isPlaying = false
        let resp = try? await IosContainer.companion.shared.spotifyClient.pausePlayback()
        if let code = resp?.status.value, !(200...204).contains(Int(code)) {
            isPlaying = true // couldn't pause — restore glyph to reality
        }
    }

    /// Resume the active Connect session (PUT /me/player/play with no body).
    @MainActor
    func resume() async {
        isPlaying = true
        let resp = try? await IosContainer.companion.shared.spotifyClient.resumePlayback()
        if let code = resp?.status.value, !(200...204).contains(Int(code)) {
            isPlaying = false
        }
    }

    /// Authoritative Web-API playback snapshot for the slow scrubber +
    /// auto-advance poll (position/duration in SECONDS). Returns nil on 204
    /// (no active device) / error. Callers must skip this during a rate-limit
    /// cooldown — check `rateLimitRemainingMs()` first (NOT a 500ms poll; this
    /// is the ungated endpoint that re-arms the limit if hammered).
    @MainActor
    func fetchPlaybackState() async -> SpotifyPlaybackSnapshot? {
        guard let s = (try? await IosContainer.companion.shared.spotifyClient.getPlaybackStateOrNull()) ?? nil else {
            return nil
        }
        return SpotifyPlaybackSnapshot(
            positionSec: (s.progressMs?.doubleValue ?? 0) / 1000.0,
            durationSec: (s.item?.durationMs?.doubleValue ?? 0) / 1000.0,
            isPlaying: s.isPlaying
        )
    }

    /// Seek the Connect session to `toSec` (Web API PUT /me/player/seek).
    @MainActor
    func seek(toSec: Double) async {
        _ = try? await IosContainer.companion.shared.spotifyClient.seekPlayback(positionMs: Int64(max(0, toSec) * 1000))
    }

    /// User-initiated device re-pick (the Connect "speaker" affordance).
    /// Fetches live devices, shows the picker (live devices + "This device"),
    /// persists the choice, and clears the warm-path cache so the NEXT play
    /// targets it. Lets the user escape a remembered device (e.g. switch from
    /// "This device", which must foreground Spotify, to a Mac that plays
    /// silently). Does not start playback itself.
    @MainActor
    func chooseDevice() async {
        let client = IosContainer.companion.shared.spotifyClient
        let settings = IosContainer.companion.shared.settingsStore
        if client.rateLimitRemainingMs() > 0 {
            lastAction = "Spotify rate-limited — try again shortly"
            return
        }
        let usable = ((try? await client.getDevices())?.devices ?? []).filter { !$0.isRestricted }
        guard let chosen = await presentPicker(usable + [localDeviceEntry()]) else { return }
        try? await settings.setPreferredSpotifyDeviceId(deviceId: chooseStableId(chosen))
        deviceVerified = false
        lastResolvedLocalId = nil
        lastAction = isLocalPlaceholder(chosen) ? "Spotify target: This device" : "Spotify target: \(chosen.name)"
    }

    /// Wake this device's Spotify and poll until its own Connect device
    /// registers. Always wakes — remote devices appear in the API without the
    /// LOCAL Spotify running, so finding *a* device isn't enough (mirrors
    /// Android `resolveLocalDevice`). iOS wake = foreground `spotify://`.
    @MainActor
    private func resolveLocalDevice(_ client: SpotifyClient, playUri: String) async -> SpDevice? {
        // FAST PATH (#232): if THIS device's Spotify is ALREADY a live Connect
        // device, use it directly and NEVER foreground Spotify. iOS wake =
        // foregrounding the Spotify app; the warm path in play() misses whenever
        // `deviceVerified` was reset (Spotify briefly dropped from the device
        // list between tracks), and without this guard every such play fell
        // straight to wakeSpotifyPlaying — the "opens Spotify on virtually every
        // track" bug. Match the LOCAL device by name (isLocalRealDevice), never a
        // generic remote phone/tablet (a remote registers without local Spotify
        // running, so finding *a* device isn't enough — see the poll fallback).
        if client.rateLimitRemainingMs() == 0 {
            let live = ((try? await client.getDevices())?.devices ?? []).filter { !$0.isRestricted }
            if let local = live.first(where: { isLocalRealDevice($0) }) { return local }
        }

        // Poll at 1s (not 300ms) so a cold play makes ~18 getDevices calls over
        // ~18s instead of 66 — those ungated /v1/me/player/devices calls add up
        // fast and contribute to Spotify's shared-key abuse window. 1s
        // granularity is plenty for device discovery.
        let pollIntervalNs: UInt64 = 1_000_000_000
        let maxPolls = 18 // ~18s at 1s (Spotify cold-launches in ~15-20s)

        // CRITICAL: waking Spotify foregrounds it, which BACKGROUNDS Parachord —
        // and iOS suspends a backgrounded app within a few seconds, freezing
        // this poll loop so the device never registers. A background-task
        // assertion buys ~30s of continued execution so the wake completes.
        let bgTask = UIApplication.shared.beginBackgroundTask(withName: "spotify-device-wake")
        defer { if bgTask != .invalid { UIApplication.shared.endBackgroundTask(bgTask) } }

        // Wake Spotify by opening the TRACK deep link (spotify:track:<id>),
        // which sends Spotify straight to the right track — so we no longer need
        // the old wrong-track-auto-resume pause. Cold first-play still
        // foregrounds Spotify (iOS has no invisible wake; the SDK's
        // authorizeAndPlayURI would foreground it too), but the background-task
        // assertion above lets this poll complete so the device registers and
        // the Web API startPlayback (below) plays it reliably; subsequent plays
        // hit the warm path silently. Verified on-device 2026-06-08.
        wakeSpotifyPlaying(playUri)
        try? await Task.sleep(nanoseconds: 1_500_000_000) // let Spotify start

        for _ in 0..<maxPolls {
            // Bail early if a 429 lands mid-wait — keep parity with the
            // cooldown guard in play() and stop hammering during a window.
            if client.rateLimitRemainingMs() > 0 { return nil }
            let usable = ((try? await client.getDevices())?.devices ?? []).filter { !$0.isRestricted }
            if let local = usable.first(where: { isLocalRealDevice($0) })
                ?? usable.first(where: { isTabletOrPhone($0) }) {
                return local
            }
            try? await Task.sleep(nanoseconds: pollIntervalNs)
        }
        return nil
    }

    /// `startPlayback` with a single 502 retry. True on 2xx (204 = success).
    @MainActor
    private func startPlayback(_ client: SpotifyClient, uri: String, deviceId: String) async -> Bool {
        func attempt() async -> Int {
            do {
                let resp = try await client.startPlayback(
                    body: SpPlaybackRequest(uris: [uri], contextUri: nil),
                    deviceId: deviceId
                )
                return Int(resp.status.value)
            } catch {
                lastAction = "Spotify play error: \(error.localizedDescription)"
                return -1
            }
        }
        var status = await attempt()
        if status == 502 {
            try? await Task.sleep(nanoseconds: 1_000_000_000)
            status = await attempt()
        }
        NSLog("PCSPOT: startPlayback status=\(status) device=\(deviceId)")
        switch status {
        case 200...204: lastAction = "Spotify playback started"; isPlaying = true; return true
        case 403: lastAction = "Spotify Premium required"; return false
        case 429: lastAction = "Spotify rate-limited"; return false
        default:
            if status >= 0 { lastAction = "Spotify play failed (status \(status))" }
            return false
        }
    }

    /// Quick confirmation that playback actually started on a freshly-resolved
    /// (cold) device — 2 polls × 500ms via the typed playback-state endpoint,
    /// mirroring Android's `verifyPlaybackStarted`. Optimistic: returns false
    /// if it can't confirm within 1s, and the caller proceeds anyway.
    @MainActor
    private func verifyPlaybackStarted(_ client: SpotifyClient) async -> Bool {
        for _ in 0..<2 {
            try? await Task.sleep(nanoseconds: 500_000_000)
            if let state = try? await client.getPlaybackStateOrNull(), state.isPlaying {
                return true
            }
        }
        return false
    }

    /// True when [device] is THIS device's own Spotify entry — matched by name
    /// (Spotify registers the device's user-set name; `UIDevice.current.name`)
    /// AND a handheld/tablet type. iOS has no `Build.MODEL`; the name is the
    /// reliable signal. `resolveLocalDevice` falls back to the type alone.
    private func isLocalRealDevice(_ device: SpDevice) -> Bool {
        let myName = UIDevice.current.name.lowercased()
        guard !myName.isEmpty else { return false }
        let devName = device.name.lowercased()
        return (devName.contains(myName) || myName.contains(devName)) && isTabletOrPhone(device)
    }

    /// A handheld/tablet device type — plausibly THIS device, not a remote
    /// Computer / TV / Speaker phantom.
    private func isTabletOrPhone(_ device: SpDevice) -> Bool {
        device.type == "Smartphone" || device.type == "Tablet"
    }
}

// MARK: - Spotify device picker (Android SpotifyDevicePickerDialog parity)

/// Identifiable wrapper so the root view can drive a `.sheet(item:)`.
struct SpotifyPickerRequest: Identifiable {
    let id = UUID()
    let devices: [SpDevice]
}

/// Authoritative Connect playback snapshot (seconds) for the scrubber +
/// auto-advance poll.
struct SpotifyPlaybackSnapshot {
    let positionSec: Double
    let durationSec: Double
    let isPlaying: Bool
}

/// "Choose Spotify Device" sheet — mirrors Android's `SpotifyDevicePickerDialog`
/// (type emoji, name, "This device" label, active badge). Shown when there are
/// live Connect devices but no remembered preference; the choice is persisted.
struct SpotifyDevicePickerSheet: View {
    let request: SpotifyPickerRequest
    let onPick: (SpDevice?) -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    ForEach(request.devices, id: \.id) { device in
                        Button { onPick(device) } label: { row(device) }
                            .buttonStyle(.plain)
                    }
                } header: {
                    Text("Where would you like to play?")
                } footer: {
                    // #285: set expectations for the "This device" cold-launch —
                    // Spotify has to come to the foreground once to start, then
                    // the rest of the queue plays silently via Connect.
                    Text("Choosing “This device” opens Spotify once to start playback — then come back to Parachord to keep listening. The rest of your queue plays without leaving the app.")
                }
            }
            .navigationTitle("Choose Spotify Device")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { onPick(nil) }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    @ViewBuilder
    private func row(_ device: SpDevice) -> some View {
        let isLocal = device.id == IosSpotifyConnect.localDeviceId
        HStack(spacing: 12) {
            Text(Self.emoji(device.type)).font(.title3)
            VStack(alignment: .leading, spacing: 2) {
                Text(isLocal ? "This device" : device.name)
                    .font(.body).foregroundStyle(.primary).lineLimit(1)
                if isLocal {
                    Text(device.name).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                } else if let vol = device.volumePercent {
                    Text("Volume \(vol)%").font(.caption).foregroundStyle(.secondary)
                }
            }
            Spacer(minLength: 0)
            if device.isActive {
                Text("Active").font(.caption2)
                    .foregroundStyle(Color(red: 0.49, green: 0.23, blue: 0.93))
            }
        }
        .contentShape(Rectangle())
        .padding(.vertical, 4)
    }

    /// Type → emoji, matching Android's `deviceTypeEmoji`.
    private static func emoji(_ type: String) -> String {
        switch type {
        case "Computer": return "💻"
        case "Smartphone": return "📱"
        case "Tablet": return "📱"
        case "Speaker": return "🔊"
        case "TV", "CastVideo": return "📺"
        case "CastAudio": return "🔊"
        case "GameConsole": return "🎮"
        case "AVR": return "🎵"
        default: return "🎶"
        }
    }
}

// MARK: - OAuth (phase 4.9)
//
// PKCE + state OAuth via `ASWebAuthenticationSession`, the iOS analogue
// of Android's Chrome-Custom-Tabs `OAuthManager`. ASWebAuthenticationSession
// is strictly nicer than the Android flow: it presents an ephemeral
// in-app Safari sheet, intercepts the `parachord://` redirect itself
// (no dedicated RedirectUriReceiverActivity / intent-filter dance), and
// hands the callback URL straight back to a completion handler.
//
// ## Verification status
//
// Compiles and the session can be constructed/launched, but a full
// round-trip needs things that can't be supplied headlessly:
//   - A real `client_id` (Android sources these from BuildConfig /
//     AppConfig; the iOS app will read them from the same shared
//     `AppConfig` once wired through). The placeholder below makes the
//     authorize page render Spotify's "invalid client" rather than a
//     real consent screen.
//   - The redirect URI (`parachord://auth/callback/spotify`) registered
//     in the Spotify dashboard.
//   - An interactive login.
//
// So this is reusable infrastructure: the PKCE generation, state CSRF
// param, URL building, session presentation, and callback parsing are
// all correct and match Android's `OAuthManager` byte-for-byte on the
// wire. Token exchange (the POST to /api/token) routes through the
// shared HTTP layer and isn't reimplemented here.

struct OAuthConfig {
    let service: String
    let authorizeURL: String
    let clientId: String
    let scopes: [String]
    /// Custom-scheme redirect. ASWebAuthenticationSession matches on the
    /// scheme (`parachord`) and returns the full URL.
    let redirectURI: String
    let callbackScheme: String

    /// Spotify config — scopes match Android's OAuthManager exactly so
    /// a token minted on either platform grants the same access.
    static func spotify(clientId: String) -> OAuthConfig {
        OAuthConfig(
            service: "spotify",
            authorizeURL: "https://accounts.spotify.com/authorize",
            clientId: clientId,
            scopes: [
                "user-read-playback-state",
                "user-modify-playback-state",
                "user-read-private",
                "user-library-read",
                "user-library-modify",
                "user-follow-read",
                "user-follow-modify",
                "playlist-read-private",
                "playlist-read-collaborative",
                "playlist-modify-public",
                "playlist-modify-private",
            ],
            redirectURI: "parachord://auth/callback/spotify",
            callbackScheme: "parachord"
        )
    }
}

struct OAuthResult {
    let code: String
    let codeVerifier: String
    let state: String
}

enum OAuthError: Error, LocalizedError {
    case cancelled
    case stateMismatch
    case missingCode
    case sessionFailed(String)

    var errorDescription: String? {
        switch self {
        case .cancelled: "Authentication cancelled"
        case .stateMismatch: "State mismatch (possible CSRF) — aborted"
        case .missingCode: "No authorization code in callback"
        case .sessionFailed(let m): "Auth session failed: \(m)"
        }
    }
}

@MainActor
final class IosOAuthManager: NSObject, ASWebAuthenticationPresentationContextProviding {

    private var session: ASWebAuthenticationSession?

    /// Run the full PKCE authorize step. Returns the authorization
    /// `code` + the `codeVerifier` (caller exchanges them for tokens
    /// via the shared HTTP layer). Throws on cancel / state mismatch /
    /// missing code.
    func authorize(_ config: OAuthConfig) async throws -> OAuthResult {
        let verifier = Self.makeCodeVerifier()
        let challenge = Self.codeChallenge(for: verifier)
        let state = Self.makeState()

        var components = URLComponents(string: config.authorizeURL)!
        components.queryItems = [
            URLQueryItem(name: "client_id", value: config.clientId),
            URLQueryItem(name: "response_type", value: "code"),
            URLQueryItem(name: "redirect_uri", value: config.redirectURI),
            URLQueryItem(name: "scope", value: config.scopes.joined(separator: " ")),
            URLQueryItem(name: "code_challenge_method", value: "S256"),
            URLQueryItem(name: "code_challenge", value: challenge),
            URLQueryItem(name: "state", value: state),
        ]
        let authURL = components.url!

        let callbackURL: URL = try await withCheckedThrowingContinuation { cont in
            let session = ASWebAuthenticationSession(
                url: authURL,
                callbackURLScheme: config.callbackScheme
            ) { url, error in
                if let error {
                    let nsError = error as NSError
                    if nsError.code == ASWebAuthenticationSessionError.canceledLogin.rawValue {
                        cont.resume(throwing: OAuthError.cancelled)
                    } else {
                        cont.resume(throwing: OAuthError.sessionFailed(error.localizedDescription))
                    }
                    return
                }
                guard let url else {
                    cont.resume(throwing: OAuthError.missingCode)
                    return
                }
                cont.resume(returning: url)
            }
            session.presentationContextProvider = self
            // Ephemeral = don't share cookies with Safari, so each auth
            // starts clean (matches the Custom-Tabs incognito feel).
            session.prefersEphemeralWebBrowserSession = true
            self.session = session
            session.start()
        }

        // Parse + validate the callback.
        let comps = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false)
        let returnedState = comps?.queryItems?.first { $0.name == "state" }?.value
        guard returnedState == state else { throw OAuthError.stateMismatch }
        guard let code = comps?.queryItems?.first(where: { $0.name == "code" })?.value else {
            throw OAuthError.missingCode
        }
        return OAuthResult(code: code, codeVerifier: verifier, state: state)
    }

    /// Last.fm web-auth (NOT OAuth2/PKCE — Last.fm's own flow): open
    /// `last.fm/api/auth/?api_key&cb=`, intercept the `parachord://` redirect,
    /// and return the `token` query param. The caller exchanges it for a
    /// session key via the shared `LastFmClient.getSession` (#193). Mirrors
    /// Android's OAuthManager.launchLastFmAuth + handleLastFmCallback.
    func authorizeLastFm(apiKey: String) async throws -> String {
        var components = URLComponents(string: "https://www.last.fm/api/auth/")!
        components.queryItems = [
            URLQueryItem(name: "api_key", value: apiKey),
            URLQueryItem(name: "cb", value: "parachord://auth/callback/lastfm"),
        ]
        let authURL = components.url!

        let callbackURL: URL = try await withCheckedThrowingContinuation { cont in
            let session = ASWebAuthenticationSession(
                url: authURL,
                callbackURLScheme: "parachord"
            ) { url, error in
                if let error {
                    let nsError = error as NSError
                    if nsError.code == ASWebAuthenticationSessionError.canceledLogin.rawValue {
                        cont.resume(throwing: OAuthError.cancelled)
                    } else {
                        cont.resume(throwing: OAuthError.sessionFailed(error.localizedDescription))
                    }
                    return
                }
                guard let url else { cont.resume(throwing: OAuthError.missingCode); return }
                cont.resume(returning: url)
            }
            session.presentationContextProvider = self
            session.prefersEphemeralWebBrowserSession = true
            self.session = session
            session.start()
        }

        let comps = URLComponents(url: callbackURL, resolvingAgainstBaseURL: false)
        guard let token = comps?.queryItems?.first(where: { $0.name == "token" })?.value else {
            throw OAuthError.missingCode
        }
        return token
    }

    // MARK: ASWebAuthenticationPresentationContextProviding

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        // Grab the active window scene's key window as the presentation
        // anchor.
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first { $0.activationState == .foregroundActive }
        return scene?.keyWindow ?? ASPresentationAnchor()
    }

    /// Generate a sample PKCE verifier + S256 challenge + state so the
    /// smoke-test card can prove the crypto path (SecRandom → SHA256 →
    /// base64url) works without a real client_id or network round-trip.
    /// Returns (verifier, challenge, state) — the challenge being a
    /// correct base64url(SHA256(verifier)) is the verifiable property.
    static func samplePKCE() -> (verifier: String, challenge: String, state: String) {
        let verifier = makeCodeVerifier()
        return (verifier, codeChallenge(for: verifier), makeState())
    }

    // MARK: PKCE primitives (match Android's OAuthManager)

    /// 64-char high-entropy verifier, RFC 7636 unreserved charset.
    private static func makeCodeVerifier() -> String {
        randomURLSafe(byteCount: 48)
    }

    /// base64url(SHA256(verifier)) — the S256 challenge.
    private static func codeChallenge(for verifier: String) -> String {
        let digest = SHA256.hash(data: Data(verifier.utf8))
        return Data(digest).base64URLEncoded()
    }

    /// 192-bit random state param for CSRF protection (matches Android).
    private static func makeState() -> String {
        randomURLSafe(byteCount: 24)
    }

    private static func randomURLSafe(byteCount: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        _ = SecRandomCopyBytes(kSecRandomDefault, byteCount, &bytes)
        return Data(bytes).base64URLEncoded()
    }
}

private extension Data {
    /// base64url without padding (RFC 4648 §5) — the encoding PKCE and
    /// OAuth state both use.
    func base64URLEncoded() -> String {
        base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }
}

// `import MusicKit` introduces `MusicKit.Track`, which collides with the
// shared module's `Track` model. Disambiguate every plain `Track` in
// this file to the KMP one. (MusicKit's own Track is never used here —
// we resolve catalog items as `Song`.)
typealias Track = Shared.Track

// MARK: - MusicKit player (phase 4.7)
//
// Native Apple Music playback via the MusicKit framework's
// `ApplicationMusicPlayer.shared`. This is dramatically simpler than
// Android's approach (a hidden WebView hosting MusicKit JS with all
// the WebView-lifecycle / GPU-layer / background-ANR workarounds in
// CLAUDE.md) — on iOS, MusicKit is a first-class framework.
//
// ## Verification status
//
// This class compiles and `authorizationStatus` (read-only) works
// with just the `NSAppleMusicUsageDescription` Info.plist string.
// Three things gate ACTUAL catalog playback, none of which can be
// done headlessly:
//   1. The MusicKit *entitlement* — enable via Xcode → Signing &
//      Capabilities → + Capability → MusicKit (adds
//      `com.apple.developer.musickit` + updates the provisioning
//      profile). Deliberately NOT added to the build here because an
//      entitlement the provisioning profile doesn't include breaks
//      code-signing.
//   2. `MusicAuthorization.request()` showing the system prompt and
//      the user granting access.
//   3. An active Apple Music subscription + a signed-in account on the
//      device (the simulator usually has neither).
//
// So `requestAuthorization()` + `play(appleMusicId:)` are written and
// correct, but the smoke-test card only exercises the read-only
// `currentStatus` until the capability is enabled on a real device.

@Observable
final class IosMusicKitPlayer {

    /// Current Apple Music authorization. Read-only access works with
    /// just the usage-description string; no entitlement needed.
    var authorizationStatus: String = MusicAuthorization.currentStatus.shortName

    var nowPlayingTitle: String = ""
    var isPlaying = false
    var lastError: String?

    // Published from the state-polling loop so the coordinator's unified
    // now-playing state (Now Playing scrubber) works for the MusicKit engine.
    var currentTime: Double = 0
    var duration: Double = 0

    /// Fired once when the current track plays to its natural end. The queue
    /// coordinator sets this to auto-advance — the MusicKit analogue of
    /// `IosAVPlayer.onTrackEnded`. `ApplicationMusicPlayer` has no native
    /// end callback, so we detect it from the polling loop.
    var onTrackEnded: (() -> Void)?

    // Poll-loop state.
    private var pollTask: Task<Void, Never>?
    private var trackEndHandled = false   // fire onTrackEnded once per track
    private var observedPlaying = false   // require .playing before honoring .stopped

    /// Request Apple Music access. Shows the system prompt IF the
    /// MusicKit entitlement is present; without it this resolves to
    /// `.denied` (or errors) — see the verification note above.
    @MainActor
    func requestAuthorization() async {
        let status = await MusicAuthorization.request()
        authorizationStatus = status.shortName
    }

    /// Play an Apple Music catalog song by its numeric catalog ID
    /// (the `appleMusicId` field already on `Track`). Resolves the ID
    /// to a `Song` via a catalog request, then hands it to the shared
    /// application player.
    /// Returns true when playback actually started — the PlaybackRouter uses
    /// this to decide whether to fall through to the next ranked source.
    @MainActor
    @discardableResult
    func play(appleMusicId: String) async -> Bool {
        lastError = nil
        guard MusicAuthorization.currentStatus == .authorized else {
            lastError = "Not authorized for Apple Music"
            return false
        }
        do {
            let request = MusicCatalogResourceRequest<Song>(
                matching: \.id,
                equalTo: MusicItemID(appleMusicId)
            )
            let response = try await request.response()
            guard let song = response.items.first else {
                lastError = "Catalog song \(appleMusicId) not found"
                return false
            }
            // SUPPRESS end-detection ACROSS the queue swap, BEFORE mutating the
            // shared player. Swapping `queue` flips `playbackStatus` to .stopped
            // for the ~½s the new item takes to load; if the running poll loop
            // ticks during that window with a STALE `observedPlaying == true`
            // (from the outgoing track), it fires a phantom onTrackEnded →
            // skipNext → a SECOND play() that interrupts THIS queue
            // ("MPMusicPlayerControllerErrorDomain.2 Queue was interrupted by
            // another queue"), and the tapped track silently fails. Guarding
            // here — not after the await (the old bug) — closes that race.
            // Tapping any out-of-queue track while Apple Music is playing hit it.
            trackEndHandled = true
            observedPlaying = false
            currentTime = 0
            duration = 0
            let player = ApplicationMusicPlayer.shared
            player.queue = [song]
            NSLog("PCAUDIO: AM play() calling ApplicationMusicPlayer.play id=\(appleMusicId)")
            try await player.play()
            NSLog("PCAUDIO: AM play() started OK id=\(appleMusicId)")
            nowPlayingTitle = song.title
            isPlaying = true
            duration = song.duration ?? 0
            currentTime = 0
            // Re-arm end-detection now that the new track is actually playing.
            trackEndHandled = false
            observedPlaying = false
            startPollingIfNeeded()
            return true
        } catch {
            NSLog("PCAUDIO: AM play() FAILED id=\(appleMusicId) err=\(error.localizedDescription)")
            lastError = "Playback failed: \(error.localizedDescription)"
            // Re-arm so a transient failure can't leave end-detection
            // permanently suppressed for the next successful play.
            trackEndHandled = false
            observedPlaying = false
            return false
        }
    }

    @MainActor
    func pause() {
        ApplicationMusicPlayer.shared.pause()
        isPlaying = false
    }

    /// Resume the current track WITHOUT resetting the queue (re-`play()`ing by
    /// id would restart from 0). Used by the coordinator's toggle + lock-screen
    /// play command.
    @MainActor
    func resume() {
        Task { @MainActor in
            try? await ApplicationMusicPlayer.shared.play()
            isPlaying = true
        }
    }

    /// Seek the Apple Music player to `seconds` (scrubber). The polling loop
    /// republishes `currentTime`, but set it optimistically for instant UI.
    @MainActor
    func seek(to seconds: Double) {
        ApplicationMusicPlayer.shared.playbackTime = seconds
        currentTime = seconds
    }

    // ── State polling (auto-advance + scrubber) ────────────────────────
    //
    // Mirrors Android's startAppleMusicStatePolling: a 500ms main-actor loop
    // reads ApplicationMusicPlayer state and (1) publishes position/duration,
    // (2) fires onTrackEnded when a track finishes. NEVER blocks on async work
    // — it only reads state. Detection no-ops once handled for a track
    // (trackEndHandled) and won't false-fire on our own queue swaps (requires
    // .playing observed before honoring .stopped).

    @MainActor
    private func startPollingIfNeeded() {
        guard pollTask == nil else { return }
        pollTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 500_000_000)
                guard let self else { return }
                self.tick()
            }
        }
    }

    @MainActor
    private func tick() {
        let amp = ApplicationMusicPlayer.shared
        let status = amp.state.playbackStatus
        currentTime = amp.playbackTime
        if status == .playing { observedPlaying = true }

        // End detection: primary = playing→stopped (single-song queue ends);
        // safety net = within 1s of a known duration.
        let naturalStop = observedPlaying && status == .stopped
        let nearEnd = duration > 0 && currentTime > 0 && (duration - currentTime) < 1.0
        if (naturalStop || nearEnd) && !trackEndHandled {
            trackEndHandled = true
            onTrackEnded?()
        }

        // Publish isPlaying — but HOLD it "playing" through the inter-track
        // handoff (trackEndHandled): MusicKit reports the finished track as
        // .stopped for the ~½s the next track takes to resolve + start, which
        // would blink the play/pause glyph. play() resets trackEndHandled and
        // sets isPlaying for the new track.
        if !trackEndHandled {
            isPlaying = (status == .playing)
        }
    }
}

private extension MusicAuthorization.Status {
    var shortName: String {
        switch self {
        case .notDetermined: "notDetermined"
        case .denied: "denied"
        case .restricted: "restricted"
        case .authorized: "authorized"
        @unknown default: "unknown"
        }
    }
}

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

    /// Set the `.playback` category so OUR audio plays through the device
    /// speaker (not the silent-ringer-respecting `.ambient` default) and
    /// ignores the hardware mute switch. **Category only — NOT `setActive`.**
    ///
    /// #322: there is ONE `AVAudioSession` per process. Activating a
    /// non-mixable `.playback` session at launch — while we produce no audio
    /// ourselves (Spotify plays in its own app; Apple Music via
    /// `ApplicationMusicPlayer`) — (a) interrupts Spotify the moment Parachord
    /// becomes active, and (b) makes iOS suspend the app in the background for
    /// holding an active-but-silent session, which pauses Apple Music. So we
    /// only *activate* the session when our own AVPlayer is about to play
    /// (`play()` → `IosAudioSession.configureForLocal`); for external engines the
    /// coordinator switches to the mixable session + silent keepalive instead.
    /// Setting the category here is inert until activation, so it's safe at init.
    private func configureAudioSession() {
        do {
            try AVAudioSession.sharedInstance().setCategory(
                .playback,
                mode: .default,
                options: []
            )
        } catch {
            errorMessage = "AVAudioSession setup failed: \(error.localizedDescription)"
        }
    }

    /// Stop our AVPlayer's audio WITHOUT touching the shared session or
    /// `MPNowPlayingInfoCenter` — used when handing playback off to an external
    /// engine (Spotify / Apple Music). The coordinator reconfigures the session
    /// for that engine and starts the silent keepalive itself
    /// (`IosAudioSession.configureForSpotify` / `configureForAppleMusic` +
    /// `IosExternalKeepAlive`), so we must NOT deactivate here: the app has to
    /// keep a live session to survive backgrounding (Android `silence.wav` parity).
    func haltAudio() {
        teardown()
    }

    /// Imported local files are referenced container-relative (`pcfile://…`) or,
    /// for rows written before that, as an absolute `file://` embedding an OLD
    /// app-container UUID. iOS changes the data-container UUID across reinstalls /
    /// App Store updates (it migrates the files, but a stored absolute path then
    /// points at the gone container). Re-root any imported-file reference to the
    /// CURRENT Documents dir; pass everything else (ipod-library://, http://, a
    /// current file://) through unchanged.
    static func resolveLocalImportPath(_ s: String) -> String {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        if s.hasPrefix("pcfile://") {
            return docs.appendingPathComponent(String(s.dropFirst(9))).absoluteString
        }
        if let r = s.range(of: "/Documents/localfiles/") {
            return docs.appendingPathComponent("localfiles/" + String(s[r.upperBound...])).absoluteString
        }
        return s
    }

    func load(
        url urlString: String,
        title: String = "",
        artist: String = ""
    ) {
        guard let url = URL(string: Self.resolveLocalImportPath(urlString)) else {
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
        // Take the shared session in LOCAL mode (`.playback`, non-mixable) right
        // before producing audio (#322 — we don't hold it at launch, and we
        // reset from any prior mixable "external" mode so our own audio ducks
        // others as it should).
        IosAudioSession.configureForLocal()
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
        // Release the shared session so other apps can resume (#322). The
        // coordinator stops the silent keepalive around this for external
        // playback; for local playback this is the final release.
        IosAudioSession.deactivate()
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
// playTrack closes the loop: cache → on-the-fly resolve → PlaybackRouter →
// the right engine (AVPlayer / MusicKit / Spotify Connect), with unified
// engine-agnostic now-playing state for the UI.

@Observable
final class QueuePlaybackCoordinator {

    let player: IosAVPlayer
    let musicKit: IosMusicKitPlayer
    let spotify: IosSpotifyConnect
    private let resolverCache: IosTrackResolverCache
    private let container = IosContainer.companion.shared
    private let queueManager = QueueManager()

    private var router: PlaybackRouter {
        PlaybackRouter(avPlayer: player, musicKit: musicKit, spotify: spotify,
                       nonStreamingResolvers: nonStreamingResolvers)
    }

    /// Resolver ids that open in the browser instead of streaming (stream:false,
    /// e.g. Bandcamp). Seeded with the known default so it holds before the .axe
    /// layer reports; refreshed from plugin capabilities in `init`.
    @ObservationIgnored private var nonStreamingResolvers: Set<String> = ["bandcamp"]
    /// The last URL handed to the browser, so the mini-player play button can
    /// re-open it for a Bandcamp track.
    @ObservationIgnored private var browserUrl: String?

    /// The in-flight `playTrack` task. Each new start cancels the previous one
    /// so only the LATEST request reaches the router. Without this, two
    /// overlapping starts (rapid tap B then C before B resolves; or a user tap
    /// racing an auto-advance `skipNext`) both call `router.play` →
    /// `ApplicationMusicPlayer.play()` and the second interrupts the first's
    /// queue ("Queue was interrupted by another queue"), silently dropping a
    /// track. Resolution is async, so the window is real on a slow lookup.
    @ObservationIgnored private var playTask: Task<Void, Never>?

    /// 1s engine-agnostic tick that pushes the playback snapshot to the shared
    /// ScrobbleManager (#193). Cancelled with the coordinator.
    @ObservationIgnored private var scrobblePublishTask: Task<Void, Never>?

    /// Republished from `QueueManager.snapshot.value` after every
    /// mutation so SwiftUI can render the up-next list reactively.
    var currentTrack: Track?
    var upNext: [Track] = []
    var shuffleEnabled = false
    /// Where the current queue was populated from (#209) — drives the queue
    /// panel's "Playing from: …" context banner/link. Nil for ad-hoc queues.
    var playbackContext: PlaybackContext?

    /// Listen Along (#235/#246): when set, a NATURAL track-end defers to this hook
    /// (the controller plays the friend's next track) instead of the single-track
    /// queue just stopping — so each listen-along song plays to its end and
    /// scrobbles. Nil for normal playback. Set on start, cleared on stop.
    var onTrackFinished: (() -> Void)?

    /// Repeat mode (#221). off → all → one. Honored by AUTO-advance only
    /// (a track ending) — the manual Next button + lock-screen next always
    /// advance one track. Repeat is a coordinator concern (not in the shared
    /// QueueManager), matching Android where ExoPlayer owns repeat.
    enum RepeatMode { case off, all, one }
    var repeatMode: RepeatMode = .off
    /// Full track list of the current queue, retained so repeat-ALL can wrap
    /// back to the start when the queue is exhausted.
    @ObservationIgnored private var originalQueue: [Track] = []

    // ── Spinoff (#231) — Android PlaybackController parity ──────────────────
    // The pool is a SEPARATE hidden list; the user's QueueManager queue is NEVER
    // modified — only the playback CONTEXT is saved + restored — so it resumes
    // intact after the pool exhausts. On start the current song KEEPS PLAYING;
    // skipNext()/autoAdvance() pull from the pool when it ends (matches Android's
    // kickStartFirstTrack=false in-app path). Mutually exclusive with Listen Along.
    @ObservationIgnored private var spinoffPool: [Track] = []
    @ObservationIgnored private var preSpinoffContext: PlaybackContext?
    // Radio (#256): a refill source for deep-link stations (Mode B artist-only /
    // Mode C with refill). When the pool runs low, top it up from this URL via the
    // shared resolver. nil for the in-app ✨ spinoff (finite similar-track pool).
    @ObservationIgnored private var spinoffRefillUrl: String?
    @ObservationIgnored private var spinoffRefill: ((String) async -> [Track])?
    @ObservationIgnored private var spinoffRefilling = false
    /// True while a spinoff station is active — observed; gates the ✨ button, the
    /// "YOUR QUEUE" suspended queue display, and the "Spinoff from X" peek.
    var spinoffMode = false
    /// Last.fm availability for the CURRENT track: nil = unchecked (button enabled),
    /// true = has similar tracks, false = none (button DISABLED). Refreshed on every
    /// track change (Android's checkSpinoffAvailability, limit=1).
    var spinoffAvailable: Bool? = nil
    @ObservationIgnored private var spinoffCheckTask: Task<Void, Never>?

    /// Enter spinoff: stash the pool, save the current context, set the spinoff
    /// banner. Does NOT touch playback — the current song finishes first, then the
    /// pool plays. No-op on an empty pool.
    func beginSpinoff(pool: [Track], displayName: String) {
        guard !pool.isEmpty else { return }
        preSpinoffContext = playbackContext
        spinoffPool = pool
        spinoffMode = true
        queueManager.setContext(context: PlaybackContext(type: "spinoff", name: displayName, id: nil))
        syncSnapshot()
    }

    /// Start a deep-link RADIO station (#256): kick the first track immediately
    /// ("start fresh radio now"), suspend the user queue like spinoff, and top up
    /// from [refill]/[refillUrl] as the pool drains. Distinct from beginSpinoff,
    /// which keeps the current song playing and has a finite pool.
    func beginRadio(pool: [Track], displayName: String, refillUrl: String?, refill: ((String) async -> [Track])?) {
        guard !pool.isEmpty else { return }
        preSpinoffContext = playbackContext
        spinoffPool = Array(pool.dropFirst())
        spinoffRefillUrl = refillUrl
        spinoffRefill = refill
        spinoffMode = true
        queueManager.setContext(context: PlaybackContext(type: "spinoff", name: displayName, id: nil))
        playTrack(pool[0])
        syncSnapshot()
    }

    /// Pull the next pool track if a spinoff is active. Returns true when it handled
    /// the advance (caller returns). On pool-exhaustion it exits spinoff and returns
    /// false so the caller falls through to the normal queue ("return to queue").
    private func advanceSpinoff() -> Bool {
        guard spinoffMode else { return false }
        if spinoffPool.count <= 1 { refillSpinoffIfNeeded() }   // top up a draining radio
        if !spinoffPool.isEmpty {
            playTrack(spinoffPool.removeFirst())
            return true
        }
        exitSpinoff()
        return false
    }

    /// Async top-up for a deep-link radio station whose pool is draining (#256).
    /// Fire-and-forget; appends to the pool so the next advance has tracks.
    private func refillSpinoffIfNeeded() {
        guard spinoffMode, !spinoffRefilling, let url = spinoffRefillUrl, let refill = spinoffRefill else { return }
        spinoffRefilling = true
        Task { @MainActor in
            let more = await refill(url)
            spinoffRefilling = false
            if spinoffMode { spinoffPool.append(contentsOf: more) }
        }
    }

    /// Exit spinoff: drop the pool, restore the saved context. The queue was never
    /// modified, so the next skipNext resumes it. Also the ✨ button's toggle-off.
    func exitSpinoff() {
        guard spinoffMode else { return }
        spinoffPool.removeAll()
        spinoffRefillUrl = nil
        spinoffRefill = nil
        spinoffMode = false
        queueManager.setContext(context: preSpinoffContext)
        preSpinoffContext = nil
        syncSnapshot()
    }

    /// Refresh `spinoffAvailable` for `track` (Android parity: a limit=1
    /// getSimilarTracks on every track change). Skipped during spinoff; nil while
    /// in flight or on error (button stays enabled until proven unavailable).
    func checkSpinoffAvailability(for track: Track) {
        guard !spinoffMode else { return }
        spinoffCheckTask?.cancel()
        spinoffAvailable = nil
        let artist = track.artist, title = track.title
        guard !artist.isEmpty, !title.isEmpty else { return }
        spinoffCheckTask = Task { @MainActor [weak self] in
            guard let container = self?.container else { return }
            let ok = try? await container.spinoffAvailable(seedArtist: artist, seedTitle: title)
            if Task.isCancelled { return }
            self?.spinoffAvailable = ok?.boolValue
        }
    }

    /// Which engine the current track is playing on. The UI reads unified
    /// state below so it doesn't care which one.
    var activeEngine: PlaybackEngineKind = .avPlayer

    // ── Background survival (#322) ─────────────────────────────────────
    /// Silent-audio keepalive that keeps the app alive in the background during
    /// EXTERNAL playback (Spotify / Apple Music) — Android `silence.wav` parity.
    @ObservationIgnored private let keepAlive = IosExternalKeepAlive()
    /// Logs the PCAUDIO interruption/lifecycle trace and resumes the active
    /// engine after a resumable interruption (e.g. a phone call).
    @ObservationIgnored private var audioMonitor: IosAudioSessionMonitor?

    /// True from the moment a track is tapped until its engine starts (resolve
    /// + route can take a beat). The mini-player shows a spinner so the tap
    /// feels responsive instead of the play/pause glyph lagging behind.
    var isStarting = false

    /// Mirror of `spotify.isPlaying`, updated from the @MainActor contexts that
    /// drive Connect (play start + toggle). Lets the nonisolated `isPlaying`
    /// getter below read Spotify state without crossing into the @MainActor
    /// `IosSpotifyConnect` from a nonisolated getter.
    private var spotifyPlaying = false
    /// Connect position/duration (seconds) for the scrubber, fed by the slow
    /// poll + local interpolation in `startSpotifyPolling`.
    private var spotifyPositionSec: Double = 0
    private var spotifyDurationSec: Double = 0
    /// Seconds since the current Connect track started (grace + interpolation).
    private var spotifyElapsedSec = 0
    /// Once-per-track guard so auto-advance fires a single `skipNext`.
    private var spotifyEndHandled = false
    @ObservationIgnored private var spotifyPollTask: Task<Void, Never>?

    // ── Unified now-playing state (engine-agnostic) ────────────────────
    // AVPlayer and MusicKit both expose real position/duration (MusicKit via
    // its state-polling loop). Spotify is play-state-only for now (stubbed).
    var isPlaying: Bool {
        switch activeEngine {
        case .avPlayer: return player.isPlaying
        case .musicKit: return musicKit.isPlaying
        case .spotify: return spotifyPlaying
        case .browser: return false   // external — we don't manage its playback
        }
    }
    var currentTime: Double {
        switch activeEngine {
        case .avPlayer: return player.currentTime
        case .musicKit: return musicKit.currentTime
        case .spotify: return spotifyPositionSec
        case .browser: return 0
        }
    }
    var duration: Double {
        switch activeEngine {
        case .avPlayer: return player.duration
        case .musicKit: return musicKit.duration
        case .spotify: return spotifyDurationSec
        case .browser: return 0
        }
    }

    /// Engine-agnostic seek (scrubber). Routes to the active engine — AVPlayer
    /// inline, MusicKit / Spotify on the main actor (the latter via the Web API).
    func seek(to seconds: Double) {
        switch activeEngine {
        case .avPlayer:
            player.seek(to: seconds)
        case .musicKit:
            Task { @MainActor in musicKit.seek(to: seconds) }
        case .spotify:
            spotifyPositionSec = seconds // optimistic; resync corrects drift
            Task { @MainActor in await spotify.seek(toSec: seconds) }
        case .browser:
            break   // external playback — nothing to seek
        }
    }

    // ── Resolver deck (Now Playing "Playing From") ─────────────────────

    /// The resolver currently producing audio, for the Now Playing deck.
    @MainActor var activeResolverName: String {
        switch activeEngine {
        case .spotify: return "spotify"
        case .musicKit: return "applemusic"
        case .avPlayer:
            // AVPlayer plays soundcloud / localfiles / direct — surface the
            // best cached URL-based source for the current track.
            guard let t = currentTrack,
                  let srcs = resolverCache.cached(artist: t.artist, title: t.title, album: t.album)
            else { return "localfiles" }
            return srcs.first(where: { ["soundcloud", "bandcamp", "localfiles", "direct"].contains($0.resolver) })?.resolver ?? "localfiles"
        case .browser:
            // The stream:false source we opened (Bandcamp et al).
            guard let t = currentTrack,
                  let srcs = resolverCache.cached(artist: t.artist, title: t.title, album: t.album)
            else { return "bandcamp" }
            return srcs.first(where: { nonStreamingResolvers.contains($0.resolver) })?.resolver ?? "bandcamp"
        }
    }

    /// Resolvers that resolved the current track above the confidence floor,
    /// in ranked order (deduped) — the deck's selectable sources.
    @MainActor func availableResolvers() -> [String] {
        guard let t = currentTrack,
              let srcs = resolverCache.cached(artist: t.artist, title: t.title, album: t.album)
        else { return [] }
        var seen = Set<String>(); var out: [String] = []
        for s in srcs where (s.confidence?.doubleValue ?? 0) >= 0.6 {
            if seen.insert(s.resolver).inserted { out.append(s.resolver) }
        }
        return out
    }

    /// Hand the shared process audio session off when the active engine changes
    /// (#322). Only OUR AVPlayer holds a non-mixable session; Spotify and Apple
    /// Music run on a MIXABLE session + a silent keepalive so the app survives
    /// backgrounding without interrupting them (Android external-playback
    /// parity). Also stops the previous engine's audio so two never overlap.
    @MainActor
    private func applyEngineHandoff(from previousEngine: PlaybackEngineKind, to kind: PlaybackEngineKind) async {
        NSLog("PCAUDIO: handoff \(previousEngine) → \(kind)")
        guard previousEngine != kind else { return }
        switch kind {
        case .avPlayer:
            // Our own audio — drop the external keepalive; player.play()
            // (startAVPlaybackWhenReady) reconfigures the session to local mode.
            keepAlive.stop()
            if previousEngine == .musicKit { musicKit.pause() }
            if previousEngine == .spotify { await spotify.pause() }
        case .musicKit:
            // Apple Music via ApplicationMusicPlayer. Stop our AVPlayer audio,
            // keep the session NON-mixable (what MusicKit wants), and run the
            // silent keepalive: AM's audio renders OUT OF PROCESS
            // (RemotePlayerService), so from iOS's background-execution view our
            // app isn't "producing audio" and gets suspended — freezing the
            // IosMusicKitPlayer tick loop so the track never auto-advances until
            // you foreground (#322). Our silent AVAudioEngine keeps the app
            // alive so the tick loop runs and advances in the background. (The
            // earlier once-a-second AM stutter was the DOUBLED keepalive from a
            // second coordinator — now impossible via the AppPlayback singleton.)
            player.haltAudio()
            if previousEngine == .spotify { await spotify.pause() }
            IosAudioSession.configureForAppleMusic()
            keepAlive.start()
        case .spotify:
            // Spotify plays in its own app — mixable session + keepalive so we
            // neither interrupt it nor get suspended (breaking auto-advance).
            player.haltAudio()
            if previousEngine == .musicKit { musicKit.pause() }
            IosAudioSession.configureForSpotify()
            keepAlive.start()
        case .browser:
            keepAlive.stop()
        }
    }

    /// Resume the currently-active engine after a resumable audio-session
    /// interruption ended (#322 — wired from `IosAudioSessionMonitor`). For the
    /// external engines this ALSO re-activates the session and re-arms the silent
    /// keepalive: an interruption stops our `AVAudioEngine`, and if we don't
    /// restart it the app is suspended and background auto-advance stops.
    @MainActor
    private func resumeActiveEngine() {
        switch activeEngine {
        case .avPlayer:
            if !player.isPlaying { player.play() }
        case .musicKit:
            IosAudioSession.configureForAppleMusic()
            keepAlive.restart()
            musicKit.resume()
        case .spotify:
            Task { @MainActor in
                IosAudioSession.configureForSpotify()
                keepAlive.restart()
                await spotify.resume()
                spotifyPlaying = spotify.isPlaying
            }
        case .browser:
            break
        }
    }

    /// Switch the current track to a specific resolver's source (deck tap):
    /// re-route the SAME track through the existing router with just that
    /// source, so playback moves to the chosen service.
    @MainActor func switchResolver(_ resolver: String) {
        guard let t = currentTrack,
              let srcs = resolverCache.cached(artist: t.artist, title: t.title, album: t.album),
              let chosen = srcs.first(where: { $0.resolver == resolver })
        else { return }
        Task { @MainActor in
            let result = await router.play(ranked: [chosen], title: t.title, artist: t.artist)
            if case .played(let kind, let playedResolver) = result {
                let previousEngine = activeEngine
                activeEngine = kind
                await applyEngineHandoff(from: previousEngine, to: kind)
                // Restamp the now-playing track's origin to the resolver the user
                // switched to, so the scrobble reflects the service actually
                // streaming after a manual deck tap (#260 / #276).
                currentTrack = container.trackWithResolvedSources(track: t, sources: srcs, playedResolver: playedResolver)
                container.updateScrobbleSources(trackId: t.id, sources: srcs)
                if kind == .avPlayer { startAVPlaybackWhenReady() }
                if kind == .spotify {
                    spotifyPlaying = spotify.isPlaying
                    spotifyPositionSec = 0; spotifyDurationSec = 0
                    spotifyElapsedSec = 0; spotifyEndHandled = false
                    startSpotifyPolling()
                }
            }
        }
    }

    init(
        player: IosAVPlayer,
        musicKit: IosMusicKitPlayer,
        spotify: IosSpotifyConnect,
        resolverCache: IosTrackResolverCache,
        installLifecycleMonitor: Bool = true
    ) {
        self.player = player
        self.musicKit = musicKit
        self.spotify = spotify
        self.resolverCache = resolverCache
        // Auto-advance: both engines pull the next queued track when the
        // current one ends. AVPlayer fires onTrackEnded from its end
        // notification; MusicKit fires it from its state-polling loop
        // (ApplicationMusicPlayer has no native end callback).
        self.player.onTrackEnded = { [weak self] in
            self?.autoAdvance()
        }
        self.musicKit.onTrackEnded = { [weak self] in
            self?.autoAdvance()
        }
        // Lock-screen / Control-Center next & previous drive the QUEUE
        // (engine-agnostic), not a single player — the "real next/previous"
        // the AVPlayer's skip±15s stand-ins anticipated.
        setupQueueRemoteCommands()
        // #322: observe audio-session interruptions + app lifecycle (the PCAUDIO
        // "audio-logs" trace) and resume the active engine after a resumable
        // interruption. Observers fire on .main; hop onto the main actor to
        // touch engine state. ONLY the primary (AppPlayback) coordinator installs
        // this — the Dev-tab smoke-test coordinator passes false so we don't get
        // two monitors (doubled PCAUDIO logs + a stray resume on the idle Dev
        // player).
        if installLifecycleMonitor {
            let monitor = IosAudioSessionMonitor()
            monitor.onShouldResume = { [weak self] in
                Task { @MainActor in self?.resumeActiveEngine() }
            }
            self.audioMonitor = monitor
        }
        // Refresh the stream:false resolver set from plugin capabilities (the
        // seeded "bandcamp" default covers the pre-load window).
        Task { @MainActor [weak self] in
            if let ids = try? await IosContainer.companion.shared.nonStreamingResolverIds(), !ids.isEmpty {
                self?.nonStreamingResolvers = Set(ids.map { String(describing: $0) })
            }
        }
        // Scrobbling (#193): publish playback snapshots to the shared
        // ScrobbleManager (LB / Last.fm / Libre.fm). Engine-agnostic 1s tick —
        // the unified getters abstract AVPlayer / MusicKit / Spotify. The shared
        // manager handles now-playing + the max(30s, min(½,4m)) threshold and
        // the scrobbling-enabled gate, exactly like Android.
        let scrobbleContainer = IosContainer.companion.shared
        scrobbleContainer.startScrobbling()
        scrobblePublishTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                if let self {
                    scrobbleContainer.updateScrobbleState(
                        currentTrack: self.currentTrack,
                        isPlaying: self.isPlaying,
                        positionMs: Int64(self.currentTime * 1000),
                        durationMs: Int64(self.duration * 1000)
                    )
                }
                try? await Task.sleep(nanoseconds: 1_000_000_000)
            }
        }
    }

    /// Register engine-agnostic next/previous-track commands on the shared
    /// remote command center so lock-screen / Control-Center / headphone
    /// next-prev advance our queue regardless of which engine is active.
    /// (Per-engine play/pause + now-playing info stay where they are:
    /// AVPlayer self-registers them; ApplicationMusicPlayer drives the lock
    /// screen natively during Apple Music playback.)
    private func setupQueueRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        center.nextTrackCommand.isEnabled = true
        center.nextTrackCommand.addTarget { [weak self] _ in
            self?.skipNext()
            return .success
        }
        center.previousTrackCommand.isEnabled = true
        center.previousTrackCommand.addTarget { [weak self] _ in
            self?.skipPrevious()
            return .success
        }
    }

    /// Engine-agnostic play/pause toggle for the mini player + Now Playing.
    func togglePlayPause() {
        switch activeEngine {
        case .avPlayer:
            player.isPlaying ? player.pause() : player.play()
        case .musicKit:
            // Resume in place rather than re-playing by id (which restarts
            // the track from 0). pause()/resume() are @MainActor.
            Task { @MainActor in
                if musicKit.isPlaying { musicKit.pause() } else { musicKit.resume() }
            }
        case .spotify:
            // Control the Spotify Connect session over the Web API
            // (pause / resume) — NOT a local engine.
            Task { @MainActor in
                if spotify.isPlaying { await spotify.pause() } else { await spotify.resume() }
                spotifyPlaying = spotify.isPlaying
            }
        case .browser:
            // Re-open the Bandcamp page (playback happens in the browser).
            if let url = browserUrl, let u = URL(string: url) {
                Task { @MainActor in _ = await UIApplication.shared.open(u) }
            }
        }
    }

    /// Establish a new queue and start playing the track at
    /// `startIndex`. Mirrors `PlaybackEngine.setQueue`.
    func setQueue(_ tracks: [Track], startIndex: Int, context: PlaybackContext? = nil) {
        // Spinoff (#231): the user starting their own playback ends any active
        // spinoff (it's not the pool driving this setQueue). Exclude the spinoff's
        // own context so beginSpinoff's setContext path isn't mistaken for takeover.
        if spinoffMode, context?.type != "spinoff" { exitSpinoff() }
        originalQueue = tracks   // retained for repeat-ALL wrap (#221)
        let toPlay = queueManager.setQueue(
            tracks: tracks,
            startIndex: Int32(startIndex),
            context: context,
            shuffle: shuffleEnabled
        )
        syncSnapshot()
        if let track = toPlay {
            playTrack(track)
        }
    }

    func skipNext() {
        // Spinoff (#231): pull from the separate pool, bypassing the queue. When the
        // pool exhausts, advanceSpinoff() exits spinoff and returns false so we fall
        // through to the user's (never-modified) queue, which resumes here.
        if advanceSpinoff() { return }
        guard let next = queueManager.skipNext(currentTrack: currentTrack) else {
            // Queue exhausted — stop cleanly. Halt whichever engine is active,
            // drop the external keepalive, then release the session (#322).
            let engine = activeEngine
            Task { @MainActor in
                switch engine {
                case .musicKit: musicKit.pause()
                case .spotify: await spotify.pause()
                case .avPlayer, .browser: break
                }
            }
            keepAlive.stop()
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

    /// Cycle repeat OFF → ALL → ONE → OFF (#221).
    func cycleRepeatMode() {
        repeatMode = switch repeatMode {
        case .off: .all
        case .all: .one
        case .one: .off
        }
    }

    /// Auto-advance when a track ENDS (wired to every engine's onTrackEnded),
    /// honoring repeat mode. The manual Next button keeps calling skipNext()
    /// directly so it always advances regardless of repeat.
    func autoAdvance() {
        // Listen Along owns end-of-track: let the controller pick the friend's
        // next song so the just-finished one plays fully (and scrobbles), #246.
        if let hook = onTrackFinished { hook(); return }
        // Spinoff (#231): the just-finished song was the seed (or a pool track) —
        // pull the next pool track. On exhaustion this exits spinoff and returns
        // false, falling through to repeat/queue handling below.
        if advanceSpinoff() { return }
        switch repeatMode {
        case .one:
            // Replay the just-finished track.
            if let cur = currentTrack { playTrack(cur) } else { skipNext() }
        case .all:
            if let next = queueManager.skipNext(currentTrack: currentTrack) {
                syncSnapshot()
                playTrack(next)
            } else if !originalQueue.isEmpty {
                setQueue(originalQueue, startIndex: 0)   // wrap to the top
            } else {
                let engine = activeEngine
                Task { @MainActor in
                    switch engine {
                    case .musicKit: musicKit.pause()
                    case .spotify: await spotify.pause()
                    case .avPlayer, .browser: break
                    }
                }
                keepAlive.stop()          // #322: drop the external keepalive
                player.stop(); currentTrack = nil; syncSnapshot()
            }
        case .off:
            skipNext()
        }
    }

    func clearQueue() {
        queueManager.clearQueue()
        syncSnapshot()
    }

    /// Remove a single track from the up-next queue by its upNext index (#220).
    /// Same index space as playFromQueue.
    func removeFromQueue(_ index: Int) {
        queueManager.removeFromQueue(index: Int32(index))
        syncSnapshot()
    }

    /// Drag-reorder an up-next item (#220). `to` is the destination in the
    /// shared QueueManager's POST-removal index space — callers translating from
    /// SwiftUI's `.onMove` (pre-removal `toOffset`) must adjust before calling.
    func moveInQueue(from: Int, to: Int) {
        queueManager.moveInQueue(fromIndex: Int32(from), toIndex: Int32(to))
        syncSnapshot()
    }

    /// Insert a track right after the current one (context-menu "Play Next").
    func playNext(_ track: Track) {
        queueManager.insertNext(tracks: [track])
        syncSnapshot()
    }

    /// Append a track to the end of the queue (context-menu "Add to Queue").
    func addToQueue(_ track: Track) {
        queueManager.addToQueue(tracks: [track])
        syncSnapshot()
    }

    private func playTrack(_ track: Track) {
        currentTrack = track
        // Refresh whether spinoff is available for this track (#231) — no-op during
        // spinoff (the pool tracks don't re-check; the ✨ button shows "exit").
        checkSpinoffAvailability(for: track)
        isStarting = true
        // Supersede any in-flight start so only the LATEST request reaches the
        // router (rapid tap B-then-C before B resolves; or a user tap racing an
        // auto-advance skipNext). Two starts each awaiting resolution would both
        // call router.play → ApplicationMusicPlayer.play(), and the second
        // interrupts the first's queue, silently dropping a track.
        playTask?.cancel()
        playTask = Task { @MainActor in
            // Enrich the recording MBID in the background at playback start
            // (mirrors Android's PlaybackController.enrichInBackground, #215) so
            // it's cached/persisted by scrobble time and the scrobble path's
            // getRecordingMbid hits the cache instead of a live mapper call.
            // Unconditional + fire-and-forget, so it covers every engine
            // including the direct-URL fast path below.
            container.enrichTrackMbidInBackground(track: track)

            // Direct-URL fast path (e.g. already-resolved / Dev sample tracks):
            // play straight through AVPlayer, no resolver round-trip.
            if let url = track.sourceUrl, !url.isEmpty,
               resolverCache.cached(artist: track.artist, title: track.title, album: track.album) == nil {
                activeEngine = .avPlayer
                player.load(url: url, title: track.title, artist: track.artist)
                startAVPlaybackWhenReady()
                isStarting = false
                return
            }

            // Two-layer resolution: cache (background pre-resolved) first,
            // on-the-fly fallback on a miss.
            var ranked = resolverCache.cached(artist: track.artist, title: track.title, album: track.album)
            if ranked == nil || ranked!.isEmpty {
                ranked = (try? await container.resolveSources(
                    artist: track.artist, title: track.title, album: track.album,
                    // Play-time fallback also benefits from the #211 hint when the
                    // track already carries streaming IDs.
                    spotifyId: track.spotifyId, appleMusicId: track.appleMusicId)) ?? []
            }
            // A newer playTrack superseded us during the async resolve — don't
            // route a stale track over the one the user actually wants.
            if Task.isCancelled { return }

            let result = await router.play(ranked: ranked ?? [], title: track.title, artist: track.artist)
            // Superseded while the engine was starting — skip the stale UI
            // state updates (the newer start owns activeEngine now).
            if Task.isCancelled { return }
            switch result {
            case .played(let kind, let playedResolver):
                let previousEngine = activeEngine
                activeEngine = kind
                await applyEngineHandoff(from: previousEngine, to: kind)
                // Enrich the now-playing track with the resolved streaming IDs so
                // the scrobble path carries them: achordion's played-source
                // confidence, the native Achordion submit (#215), and LB source
                // enrichment all read spotifyId/appleMusicId/soundcloudId off the
                // Track. Without this, an Apple-Music-streamed track reaches scrobble
                // with all IDs null → achordion confidence 0.00, zero submit links.
                // Same `id` (additive copy) so it doesn't re-fire now-playing.
                currentTrack = container.trackWithResolvedSources(track: track, sources: ranked ?? [], playedResolver: playedResolver)
                // Hand the full resolved-source set to the scrobbler (off-main) so the
                // native Achordion submit sends EVERY high-confidence per-service link
                // (incl. Bandcamp), not just the flat-field trio (#276).
                container.updateScrobbleSources(trackId: track.id, sources: ranked ?? [])
                if kind == .avPlayer { startAVPlaybackWhenReady() }
                if kind == .spotify {
                    spotifyPlaying = spotify.isPlaying
                    spotifyPositionSec = 0
                    spotifyDurationSec = 0
                    spotifyElapsedSec = 0
                    spotifyEndHandled = false
                    startSpotifyPolling()
                }
            case .openBrowser(let url):
                // Bandcamp (stream:false): stop the prior engine's audio, show
                // the track in the mini player (not playing), open the URL.
                switch activeEngine {
                case .avPlayer: player.pause()
                case .musicKit: musicKit.pause()
                case .spotify: await spotify.pause()
                case .browser: break
                }
                activeEngine = .browser
                browserUrl = url
                if let u = URL(string: url) { _ = await UIApplication.shared.open(u) }

            case .noPlayableSource:
                // Resolved but no engine could play it (e.g. Apple-Music-only
                // match with no subscription) — advance to the next track.
                skipNext()
            }
            isStarting = false
        }
    }

    /// Slow Spotify Connect state loop for the scrubber + auto-advance.
    ///
    /// A 1s local tick interpolates the position (free, no API), with an
    /// AUTHORITATIVE Web-API resync only every ~8s and only while playing —
    /// deliberately NOT a 500ms poll (that ungated endpoint re-arms the rate
    /// limit). Skips the resync entirely during a cooldown. Self-cancels when
    /// the active engine is no longer Spotify.
    private func startSpotifyPolling() {
        guard spotifyPollTask == nil else { return } // idempotent across tracks
        spotifyPollTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 1_000_000_000)
                guard let self else { return }
                guard self.activeEngine == .spotify else {
                    self.spotifyPollTask = nil
                    return
                }
                self.spotifyElapsedSec += 1
                if self.spotifyPlaying { self.spotifyPositionSec += 1 } // interpolate
                // Heartbeat: if these keep printing while the iPad is LOCKED, the
                // app is alive and the poll loop runs (issue is elsewhere). If
                // they STOP on lock and resume on foreground, the app is being
                // suspended during Spotify playback. (#322 cross-service debug)
                NSLog("PCSPOT: poll tick elapsed=\(self.spotifyElapsedSec) pos=\(Int(self.spotifyPositionSec)) dur=\(Int(self.spotifyDurationSec)) playing=\(self.spotifyPlaying)")

                let resyncDue = self.spotifyElapsedSec == 2 || self.spotifyElapsedSec % 8 == 0
                let cooldown = IosContainer.companion.shared.spotifyClient.rateLimitRemainingMs()
                if resyncDue, self.spotifyPlaying, cooldown == 0 {
                    if let snap = await self.spotify.fetchPlaybackState() {
                        if snap.durationSec > 0 { self.spotifyDurationSec = snap.durationSec }
                        self.spotifyPositionSec = snap.positionSec
                        self.spotifyPlaying = snap.isPlaying
                    }
                }
                self.checkSpotifyEnd()
            }
        }
    }

    /// Auto-advance detection for Connect, mirroring Android `isOurTrackDone`:
    /// advance EARLY (within 2s of the end, while still playing) to preempt
    /// Spotify's own autoplay; also fire when stopped at/near the end. A 5s
    /// grace after start avoids false positives from transient post-`play`
    /// state. Fires `skipNext` once per track.
    private func checkSpotifyEnd() {
        guard !spotifyEndHandled, spotifyDurationSec > 0, spotifyElapsedSec >= 5 else { return }
        let nearEnd = spotifyPositionSec >= spotifyDurationSec - 2 && spotifyPlaying
        let finished = !spotifyPlaying && spotifyPositionSec >= spotifyDurationSec - 1
        if nearEnd || finished {
            NSLog("PCSPOT: end detected (nearEnd=\(nearEnd) finished=\(finished)) → autoAdvance")
            spotifyEndHandled = true
            autoAdvance()
        }
    }

    /// Poll the async AVPlayerItem status, then play once it's ready.
    private func startAVPlaybackWhenReady() {
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
        playbackContext = snap.playbackContext   // #209 queue-source banner
        // Persist after every queue mutation (#220). Debounced + gated on the
        // setting inside persistSoon, so this is cheap to call on every sync.
        persistSoon()
    }

    // MARK: - Queue persistence (#220)

    /// Debounced save: coalesce bursts of mutations into one write ~500 ms later
    /// (mirrors Android's QueuePersistence debounce). Cancels the prior pending
    /// save so only the latest state is written.
    @ObservationIgnored private var persistTask: Task<Void, Never>?
    private func persistSoon() {
        persistTask?.cancel()
        persistTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 500_000_000)
            if Task.isCancelled { return }
            await self?.saveQueueNow()
        }
    }

    private func saveQueueNow() async {
        guard (try? await container.settingsStore.isPersistQueueEnabled()) == true else { return }
        guard let snap = queueManager.snapshot.value as? QueueSnapshot else { return }
        let state = PersistedQueueState(
            currentTrack: currentTrack,
            upNext: snap.upNext,
            playHistory: queueManager.history,
            playbackContext: snap.playbackContext,
            shuffleEnabled: snap.shuffleEnabled,
            originalOrder: queueManager.savedOriginalOrder)
        let jsonStr = QueuePersistenceCodec.shared.encode(state: state)
        try? await container.settingsStore.setPersistedQueueState(json: jsonStr)
    }

    /// Restore the saved queue on launch (paused — never auto-plays, Android
    /// parity). Sets currentTrack + upNext/shuffle/context from the persisted
    /// blob if the "Remember queue" setting is on and a blob exists.
    func restoreQueue() async {
        guard (try? await container.settingsStore.isPersistQueueEnabled()) == true else { return }
        guard let jsonStr = try? await container.settingsStore.getPersistedQueueState(),
              let state = QueuePersistenceCodec.shared.decode(jsonStr: jsonStr) else { return }
        if state.upNext.isEmpty && state.currentTrack == nil { return }
        queueManager.restoreState(
            restoredUpNext: state.upNext,
            restoredHistory: state.playHistory,
            restoredOriginalOrder: state.originalOrder,
            context: state.playbackContext,
            shuffle: state.shuffleEnabled)
        await MainActor.run {
            self.currentTrack = state.currentTrack
            self.syncSnapshot()
        }
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

    /// Attach the `NativeBridge` object the bootstrap polyfills (in
    /// `IosJsRuntime.BOOTSTRAP_JS`) reference — `log` / `fetchAsync` /
    /// `storageGet` / `storageSet`, matching Android's `@JavascriptInterface`
    /// surface so the same bootstrap + `.axe` plugins run unmodified.
    ///
    /// Called by the iOS plugin host (`IosContainer.pluginJsRuntime`) via
    /// the `nativeBridgeInstaller` hook, BEFORE the bootstrap JS runs.
    static func installNativeBridge(to context: JSContext) {
        let bridge = JSValue(newObjectIn: context)!

        let log: @convention(block) (String, String) -> Void = { level, message in
            // NSLog (not print) so .axe / resolver-loader console output is
            // captured by `xcrun simctl log stream` for on-device debugging.
            NSLog("[plugin:%@] %@", level, message)
        }
        let storageGet: @convention(block) (String) -> String? = { key in
            guard isAllowedStorageKey(key) else { return nil }
            return UserDefaults.standard.string(forKey: key)
        }
        let storageSet: @convention(block) (String, String) -> Void = { key, value in
            guard isAllowedStorageKey(key) else { return }
            UserDefaults.standard.set(value, forKey: key)
        }
        let fetchAsync: @convention(block) (String, String, String, String, String) -> Void = {
            callbackId, url, method, headersJson, body in
            performFetch(
                context: context,
                callbackId: callbackId,
                url: url, method: method, headersJson: headersJson, body: body
            )
        }
        // achordion LOVE path (#215): JS `window.resolveMbidForLove(track)` →
        // shared MbidEnrichmentService → resume the promise. The shared
        // IosContainer owns the parse/short-circuit/mapper logic (mirrors
        // Android's NativeBridge.resolveMbidForLove); this block is the thin
        // async bridge. JSContext is main-thread-bound, so the callback fires
        // on MainActor — same discipline as performFetch.
        let resolveMbidForLove: @convention(block) (String, String) -> Void = { callbackId, trackJson in
            Task {
                let mbid = (try? await IosContainer.companion.shared.resolveMbidForLove(trackJson: trackJson)) ?? nil
                await MainActor.run {
                    fireMbidCallback(context: context, callbackId: callbackId, mbid: mbid)
                }
            }
        }

        bridge.setObject(log, forKeyedSubscript: "log" as NSString)
        bridge.setObject(storageGet, forKeyedSubscript: "storageGet" as NSString)
        bridge.setObject(storageSet, forKeyedSubscript: "storageSet" as NSString)
        bridge.setObject(fetchAsync, forKeyedSubscript: "fetchAsync" as NSString)
        bridge.setObject(resolveMbidForLove, forKeyedSubscript: "resolveMbidForLove" as NSString)
        context.setObject(bridge, forKeyedSubscript: "NativeBridge" as NSString)
    }

    /// Resume the `window.resolveMbidForLove` promise (#215). Mirrors
    /// `fireFetchCallback`: invoke `window.__resolveMbidCallbacks[callbackId]`
    /// with the MBID string, or no args when null (JS `mbidOrNull || null`
    /// resolves to null). Must run on the JSContext's thread (main).
    private static func fireMbidCallback(context: JSContext, callbackId: String, mbid: String?) {
        let callbacks = context.objectForKeyedSubscript("__resolveMbidCallbacks")
        let callback = callbacks?.objectForKeyedSubscript(callbackId)
        if let callback, !callback.isUndefined {
            callback.call(withArguments: mbid != nil ? [mbid!] : [])
        }
    }

    /// Shared fetch implementation: URLSession → `{ok,status,body}`
    /// envelope (matching Android's `NativeBridge.fetchAsync`) → resolve
    /// the JS Promise via `window.__fetchCallbacks[callbackId]`.
    private static func performFetch(
        context: JSContext,
        callbackId: String,
        url: String, method: String, headersJson: String, body: String
    ) {
        guard let requestURL = URL(string: url) else {
            fireFetchCallback(context: context, callbackId: callbackId,
                              envelope: errorEnvelope("Invalid URL: \(url)"))
            return
        }
        var request = URLRequest(url: requestURL)
        request.httpMethod = method
        if let headersData = headersJson.data(using: .utf8),
           let parsed = try? JSONSerialization.jsonObject(with: headersData),
           let headers = parsed as? [String: String] {
            for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        }
        if !body.isEmpty { request.httpBody = body.data(using: .utf8) }
        Task.detached {
            do {
                let (data, response) = try await URLSession.shared.data(for: request)
                let status = (response as? HTTPURLResponse)?.statusCode ?? 0
                let envelope: [String: Any] = [
                    "ok": (200..<300).contains(status),
                    "status": status,
                    "body": String(data: data, encoding: .utf8) ?? "",
                ]
                await MainActor.run {
                    fireFetchCallback(context: context, callbackId: callbackId, envelope: envelope)
                }
            } catch {
                await MainActor.run {
                    fireFetchCallback(context: context, callbackId: callbackId,
                                      envelope: errorEnvelope(error.localizedDescription))
                }
            }
        }
    }

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
/// Phase 1–4 smoke-test harness. Kept as a "Dev" tab in the real app
/// shell so the platform-actual proofs (resolver scoring, JSC, mosaic,
/// AVPlayer, queue, MusicKit, Spotify Connect, OAuth) stay one tap away
/// while real screens get built out in phase 5.
struct DevSmokeTestView: View {
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

    @State private var pluginIds: [String] = []
    @State private var pluginResolveResult: String?
    @State private var pluginLoading = false
    @State private var pluginError: String?

    @State private var avPlayer = IosAVPlayer()
    @State private var queue: QueuePlaybackCoordinator?
    @State private var musicKit = IosMusicKitPlayer()
    @State private var oauthManager = IosOAuthManager()
    @State private var pkceSample: (verifier: String, challenge: String, state: String)?
    @State private var oauthError: String?
    @State private var spotifyConnect = IosSpotifyConnect()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                header
                resolverScoringCard
                pluginHostCard
                queueCard
                avPlayerCard
                musicKitCard
                spotifyConnectCard
                oauthCard
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
                let coordinator = QueuePlaybackCoordinator(
                    player: avPlayer,
                    musicKit: IosMusicKitPlayer(),
                    spotify: IosSpotifyConnect(),
                    resolverCache: IosTrackResolverCache.shared,
                    // Dev-tab smoke test — don't install the audio-session monitor
                    // (the primary AppPlayback coordinator owns it). #322.
                    installLifecycleMonitor: false
                )
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
            isrc: nil,
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

    // MARK: - Phase 4.7 (MusicKit)

    private var musicKitCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("MusicKit (Phase 4.7)")
                .font(.headline)
            Text(
                "Native Apple Music via `ApplicationMusicPlayer.shared` — " +
                "no WebView/JS bridge like Android. Auth status reads with " +
                "just the usage-description string; requesting access + " +
                "catalog playback need the MusicKit capability (Xcode → " +
                "Signing & Capabilities → + MusicKit), an Apple Music " +
                "subscription, and a real device."
            )
            .font(.caption)
            .foregroundStyle(.secondary)

            HStack {
                Text("authorization:")
                    .font(.callout)
                Text(musicKit.authorizationStatus)
                    .font(.callout.monospaced())
                    .foregroundStyle(
                        musicKit.authorizationStatus == "authorized" ? .green : .orange
                    )
                Spacer()
                Button("Request") {
                    Task { await musicKit.requestAuthorization() }
                }
                .buttonStyle(.bordered)
            }

            if let error = musicKit.lastError {
                Text(error)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Phase 4.8 (Spotify Connect)

    private var spotifyConnectCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Spotify Connect (Phase 4.8)")
                .font(.headline)
            Text(
                "Web API (Connect over HTTP), not the Spotify iOS SDK — " +
                "same decision as Android. The HTTP surface (getDevices / " +
                "transfer / startPlayback / pause / seek) is reused from " +
                "the shared SpotifyClient unchanged; only the device-wake " +
                "deep link is iOS-specific. Play needs an OAuth token + " +
                "Premium account."
            )
            .font(.caption)
            .foregroundStyle(.secondary)

            HStack {
                Text("Spotify app installed:")
                    .font(.callout)
                Text(spotifyConnect.spotifyInstalled ? "yes" : "no")
                    .font(.callout.monospaced())
                    .foregroundStyle(spotifyConnect.spotifyInstalled ? .green : .orange)
                Spacer()
                Button("Wake") { spotifyConnect.wakeSpotify() }
                    .buttonStyle(.bordered)
                    .disabled(!spotifyConnect.spotifyInstalled)
            }

            if let action = spotifyConnect.lastAction {
                Text(action)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .task {
            spotifyConnect.refreshInstalled()
        }
    }

    // MARK: - Phase 4.9 (OAuth / ASWebAuthenticationSession)

    private var oauthCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("OAuth — PKCE (Phase 4.9)")
                .font(.headline)
            Text(
                "PKCE + state via `ASWebAuthenticationSession`. The crypto " +
                "path (SecRandom → SHA256 → base64url) is verifiable here; " +
                "the full authorize round-trip needs a real client_id, a " +
                "registered parachord:// redirect, and an interactive login."
            )
            .font(.caption)
            .foregroundStyle(.secondary)

            if let pkce = pkceSample {
                VStack(alignment: .leading, spacing: 4) {
                    pkceRow("verifier", pkce.verifier)
                    pkceRow("challenge", pkce.challenge)
                    pkceRow("state", pkce.state)
                    Text("challenge = base64url(SHA256(verifier)) ✓")
                        .font(.caption2)
                        .foregroundStyle(.green)
                }
                .padding(8)
                .background(Color.black.opacity(0.06))
                .clipShape(RoundedRectangle(cornerRadius: 6))
            }

            if let oauthError {
                Text(oauthError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .task {
            if pkceSample == nil {
                pkceSample = IosOAuthManager.samplePKCE()
            }
        }
    }

    private func pkceRow(_ label: String, _ value: String) -> some View {
        HStack(alignment: .top) {
            Text(label)
                .font(.caption2.monospaced())
                .foregroundStyle(.secondary)
                .frame(width: 72, alignment: .leading)
            Text(value)
                .font(.caption2.monospaced())
                .lineLimit(1)
                .truncationMode(.middle)
        }
    }

    // MARK: - Cross-platform .axe plugin host

    private var pluginHostCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(".axe plugin host (cross-platform)")
                .font(.headline)

            Text(
                "Loads resolver-loader.js + the bundled .axe plugins into " +
                "JavaScriptCore via the SHARED PluginManager — the same host " +
                "as desktop + Android. Swift provides NativeBridge " +
                "(log/fetch/storage); the plugins run unmodified."
            )
            .font(.caption)
            .foregroundStyle(.secondary)

            if pluginLoading {
                ProgressView()
            }

            if !pluginIds.isEmpty {
                Text("Loaded \(pluginIds.count) plugins:")
                    .font(.caption.weight(.semibold))
                Text(pluginIds.joined(separator: ", "))
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
            }

            if let pluginResolveResult {
                Divider()
                Text("resolve(\"Spoon\", \"The Underdog\"):")
                    .font(.caption.weight(.semibold))
                Text(pluginResolveResult)
                    .font(.caption.monospaced())
                    .foregroundStyle(pluginResolveResult.hasPrefix("error") ? .red : .green)
                    .lineLimit(4)
            }

            if let pluginError {
                Text(pluginError)
                    .font(.caption)
                    .foregroundStyle(.red)
            }
        }
        .padding()
        .background(Color(.systemGray6))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .task {
            if pluginIds.isEmpty && pluginError == nil {
                await runPluginHost()
            }
        }
    }

    private func runPluginHost() async {
        pluginLoading = true
        let container = IosContainer.companion.shared
        // Attach the NativeBridge the bootstrap polyfills reference, BEFORE
        // PluginManager initializes the runtime.
        container.pluginJsRuntime.nativeBridgeInstaller = { ctx in
            JsPolyfills.installNativeBridge(to: ctx)
        }
        do {
            let ids = try await container.loadPluginsAndList()
            pluginIds = ids
            // Drive the full iOS resolver pipeline: IosResolverCoordinator
            // fans out resolve() across stream-capable resolvers concurrently
            // (unique JSC result keys, no clobber), re-scores via
            // scoreConfidence, and ranks via selectRanked. Proves the
            // coordinator end-to-end before the UI layers sit on top.
            let ranked = try await container.resolveSources(
                artist: "Spoon",
                title: "The Underdog",
                album: nil,
                spotifyId: nil,
                appleMusicId: nil
            )
            if ranked.isEmpty {
                pluginResolveResult = "(no source above 0.60 floor)"
            } else {
                pluginResolveResult = ranked
                    .map { "\($0.resolver)@\(String(format: "%.2f", $0.confidence?.doubleValue ?? 0))" }
                    .joined(separator: ", ")
            }
        } catch {
            pluginError = "error: \(error.localizedDescription)"
        }
        pluginLoading = false
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
    DevSmokeTestView()
}
