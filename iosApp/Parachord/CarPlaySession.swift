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
//   key window. With the car connected and no phone window at all
//   (`blocksEmbeds`: the car launched the app and the phone never showed it),
//   Bandcamp and a SoundCloud page that doesn't exist yet count as refused, so
//   the next source plays. With a phone window, locked or behind another app,
//   they play as on the phone; so does an existing SoundCloud page.
// - Spotify never opens the Spotify app, never shows the device picker and
//   only uses the phone's own Spotify device (`IosSpotifyConnect.play`).
// - A track only Spotify could have played stops with
//   `CarMessages.OPEN_SPOTIFY_ON_PHONE` instead of being skipped.
// - With Parachord not in front on the phone (`phoneAppNotInFront`: locked,
//   behind another app, or never shown), the third track in a row that nothing
//   could play stops with `CarMessages.OPEN_PARACHORD_ON_PHONE`
//   (`CarUnplayableRun`, counted from the connect or the last tap), and so does
//   a tapped track whose SoundCloud or Bandcamp embed was refused or didn't start.
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
    /// is connected and the app has no phone window to host one. That's a
    /// launch by the car that the phone never showed: iOS gives the app only
    /// the CarPlay scene and won't open a window from the background. A phone
    /// window that exists counts even with the phone locked or another app in
    /// front, since the hidden web view plays there just as it does on the phone.
    var blocksEmbeds: Bool {
        isConnected && !Self.phoneHasKeyWindow
    }

    /// A connected phone (not CarPlay) window scene with a key window, in any
    /// activation state: the embeds attach to that key window.
    private static var phoneHasKeyWindow: Bool {
        phoneKeyWindowScene { $0 != .unattached }
    }

    /// True when the car is connected and Parachord isn't the app in front on
    /// the phone (locked, behind another app, or never shown). Then nobody can
    /// act on a phone message, and an embed that times out may be one iOS
    /// wouldn't let start while locked, so the unplayable-run stop and the
    /// car's "open Parachord on your phone" message apply. Wider than
    /// `blocksEmbeds`, which only stops new web views from being created.
    var phoneAppNotInFront: Bool {
        isConnected && !Self.phoneKeyWindowScene { $0 == .foregroundActive }
    }

    private static func phoneKeyWindowScene(_ state: (UIScene.ActivationState) -> Bool) -> Bool {
        UIApplication.shared.connectedScenes.contains { scene in
            guard state(scene.activationState),
                  scene.session.role == .windowApplication,
                  let windowScene = scene as? UIWindowScene else { return false }
            return windowScene.windows.contains { $0.isKeyWindow }
        }
    }
}
