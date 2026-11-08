import Observation
import UIKit

// MARK: - CarPlay session (car-only playback rules)
//
// Whether a CarPlay screen is connected. The CarPlay scene delegate calls
// `connected()` / `disconnected()`; playback reads `isConnected` to apply the
// rules that only hold in the car (see iosApp/AGENTS.md → "Playback in the
// car"):
//
// - SoundCloud and Bandcamp play in a hidden web view attached to the phone's
//   key window. With the car connected and no phone window in the foreground
//   (`blocksEmbeds`), Bandcamp and a SoundCloud page that doesn't exist yet
//   count as refused, so the next source plays. An existing SoundCloud page
//   plays as on the phone.
// - Spotify never opens the Spotify app, never shows the device picker and
//   only uses the phone's own Spotify device (`IosSpotifyConnect.play`).
// - A track only Spotify could have played stops with
//   `CarMessages.OPEN_SPOTIFY_ON_PHONE` instead of being skipped.
// - With no phone window (`blocksEmbeds`), the third track in a row that
//   nothing could play stops with `CarMessages.OPEN_PARACHORD_ON_PHONE`
//   (`CarUnplayableRun`, counted from the connect or the last tap), and so does
//   a tapped track whose embed had no phone window to play in.
//
// With the car disconnected every path behaves as it does on the phone.

@MainActor
@Observable
final class CarPlaySession {
    static let shared = CarPlaySession()

    private(set) var isConnected = false
    /// Goes up on every connect, so playback can tell a new car session from
    /// the one it last saw (the unplayable run restarts on connect).
    @ObservationIgnored private(set) var connectionCount = 0

    private init() {}

    func connected() {
        isConnected = true
        connectionCount += 1
        NSLog("CARPLAY connected")
    }

    func disconnected() {
        isConnected = false
        NSLog("CARPLAY disconnected")
    }

    /// True when no new SoundCloud / Bandcamp web view may be created: the car
    /// is connected and the phone has no window in the foreground to host one
    /// (locked, or the app in the background). With the app open on the phone
    /// they play as usual.
    var blocksEmbeds: Bool {
        isConnected && !Self.phoneHasForegroundKeyWindow
    }

    /// A phone (not CarPlay) window scene that's foreground-active and has a
    /// key window.
    private static var phoneHasForegroundKeyWindow: Bool {
        UIApplication.shared.connectedScenes.contains { scene in
            guard scene.activationState == .foregroundActive,
                  scene.session.role == .windowApplication,
                  let windowScene = scene as? UIWindowScene else { return false }
            return windowScene.windows.contains { $0.isKeyWindow }
        }
    }
}
