import SwiftUI
import Shared
import JavaScriptCore
import AVFoundation
import Combine
import MediaPlayer
import MusicKit
import AuthenticationServices
import CryptoKit

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

        // 1. Warm path — cached local device still present.
        if deviceVerified, let warmId = lastResolvedLocalId {
            let devs = (try? await client.getDevices())?.devices ?? []
            if devs.contains(where: { $0.id == warmId }) {
                if await startPlayback(client, uri: uri, deviceId: warmId) { return true }
            }
            lastResolvedLocalId = nil
            deviceVerified = false
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
        let pollIntervalNs: UInt64 = 300_000_000
        // Spotify cold-launches in ~15-20s before it registers as a Connect
        // device, so poll up to ~20s (vs Android's 8s warm / 18s launch path).
        let maxPolls = 66 // ~20s at 300ms

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
            let player = ApplicationMusicPlayer.shared
            player.queue = [song]
            try await player.play()
            nowPlayingTitle = song.title
            isPlaying = true
            // Reset end-detection for the new track and (re)start the loop.
            duration = song.duration ?? 0
            currentTime = 0
            trackEndHandled = false
            observedPlaying = false
            startPollingIfNeeded()
            return true
        } catch {
            lastError = "Playback failed: \(error.localizedDescription)"
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
        PlaybackRouter(avPlayer: player, musicKit: musicKit, spotify: spotify)
    }

    /// Republished from `QueueManager.snapshot.value` after every
    /// mutation so SwiftUI can render the up-next list reactively.
    var currentTrack: Track?
    var upNext: [Track] = []
    var shuffleEnabled = false

    /// Which engine the current track is playing on. The UI reads unified
    /// state below so it doesn't care which one.
    var activeEngine: PlaybackEngineKind = .avPlayer

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
        }
    }
    var currentTime: Double {
        switch activeEngine {
        case .avPlayer: return player.currentTime
        case .musicKit: return musicKit.currentTime
        case .spotify: return spotifyPositionSec
        }
    }
    var duration: Double {
        switch activeEngine {
        case .avPlayer: return player.duration
        case .musicKit: return musicKit.duration
        case .spotify: return spotifyDurationSec
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
            if case .played(let kind) = result {
                activeEngine = kind
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
        resolverCache: IosTrackResolverCache
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
            self?.skipNext()
        }
        self.musicKit.onTrackEnded = { [weak self] in
            self?.skipNext()
        }
        // Lock-screen / Control-Center next & previous drive the QUEUE
        // (engine-agnostic), not a single player — the "real next/previous"
        // the AVPlayer's skip±15s stand-ins anticipated.
        setupQueueRemoteCommands()
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

    func clearQueue() {
        queueManager.clearQueue()
        syncSnapshot()
    }

    private func playTrack(_ track: Track) {
        currentTrack = track
        Task { @MainActor in
            // Direct-URL fast path (e.g. already-resolved / Dev sample tracks):
            // play straight through AVPlayer, no resolver round-trip.
            if let url = track.sourceUrl, !url.isEmpty,
               resolverCache.cached(artist: track.artist, title: track.title, album: track.album) == nil {
                activeEngine = .avPlayer
                player.load(url: url, title: track.title, artist: track.artist)
                startAVPlaybackWhenReady()
                return
            }

            // Two-layer resolution: cache (background pre-resolved) first,
            // on-the-fly fallback on a miss.
            var ranked = resolverCache.cached(artist: track.artist, title: track.title, album: track.album)
            if ranked == nil || ranked!.isEmpty {
                ranked = (try? await container.resolveSources(
                    artist: track.artist, title: track.title, album: track.album)) ?? []
            }

            let result = await router.play(ranked: ranked ?? [], title: track.title, artist: track.artist)
            switch result {
            case .played(let kind):
                activeEngine = kind
                if kind == .avPlayer { startAVPlaybackWhenReady() }
                if kind == .spotify {
                    spotifyPlaying = spotify.isPlaying
                    spotifyPositionSec = 0
                    spotifyDurationSec = 0
                    spotifyElapsedSec = 0
                    spotifyEndHandled = false
                    startSpotifyPolling()
                }
            case .noPlayableSource:
                // Resolved but no engine could play it (e.g. Apple-Music-only
                // match with no subscription) — advance to the next track.
                skipNext()
            }
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
            spotifyEndHandled = true
            skipNext()
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

        bridge.setObject(log, forKeyedSubscript: "log" as NSString)
        bridge.setObject(storageGet, forKeyedSubscript: "storageGet" as NSString)
        bridge.setObject(storageSet, forKeyedSubscript: "storageSet" as NSString)
        bridge.setObject(fetchAsync, forKeyedSubscript: "fetchAsync" as NSString)
        context.setObject(bridge, forKeyedSubscript: "NativeBridge" as NSString)
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
                    resolverCache: IosTrackResolverCache.shared
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
                album: nil
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
