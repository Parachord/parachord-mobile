import Foundation
import Observation
import JavaScriptCore
import Shared

/// Everything playback needs, started from the app level rather than from a
/// SwiftUI view. CarPlay can launch the app with the phone locked and no phone
/// window scene, so nothing here may depend on `ContentView` appearing.
///
/// `start()` is idempotent: the first caller runs it, and every later or
/// concurrent caller awaits that same run. The phone's views keep their UI-only
/// work (banners, sheets, sync timers) and just `await start()`.
///
/// Order matters:
/// 1. The `.axe` plugin host (the NativeBridge installer, then the plugins).
///    Without it nothing resolves, and `IosJsRuntime.initialize()` is
///    idempotent, so a resolve that ran first would leave it plugin-less.
///    Then `ResolverPrefs` (resolver order and active set).
/// 2. `AppPlayback.shared`, the one coordinator per process (#322).
/// 3. The Spinoff, Listen Along and chat playback binds to that coordinator.
/// 4. The persisted queue, restored paused (#220).
/// 5. The current track's sources, re-resolved whenever it changes, so its
///    artwork is ready.
@MainActor
final class AppBootstrap {
    static let shared = AppBootstrap()

    private var run: Task<Void, Never>?
    private var artworkTrackId: String?

    private init() {}

    func start() async {
        if run == nil {
            run = Task { @MainActor in await self.perform() }
        }
        await run?.value
    }

    private func perform() async {
        NSLog("APP_BOOTSTRAP started")
        let container = IosContainer.companion.shared

        // The installer must be set BEFORE the plugins load:
        // IosJsRuntime.initialize() only loads the resolvers when it's present.
        container.pluginJsRuntime.nativeBridgeInstaller = { ctx in
            JsPolyfills.installNativeBridge(to: ctx)
        }
        // FairPlay runs only on a device (#794): in the simulator the DRM pair
        // isn't a path, and a plug-in's plain stream plays first.
        container.fairPlayAvailable = IosFairPlayKeys.isAvailable
        // The webSession host bridge's web view (#779): amazonmusic.axe needs
        // it, and the shared engine is built on first use, so it's set before
        // the plugins load.
        if container.webSessionView == nil {
            container.webSessionView = WebSessionWebView()
        }
        // A plan change (a purchases-only plan, an upgrade) clears the
        // resolver's saved sources (shared limitTransition, #1494). Set before
        // the plugins load: their status is read once they have.
        container.resolverSourcesClearer = { id, sourcesToo in
            Task { @MainActor in IosTrackResolverCache.shared.clearResolver(id, sourcesToo: sourcesToo.boolValue) }
        }
        _ = try? await container.loadPluginsAndList()
        // A plug-in set up in its sheet's fields (Subsonic, #670) must be in
        // `active_resolvers`, or the coordinator never asks it.
        try? await container.seedConfiguredPluginResolvers()

        // The resolver order + active set mirror. Besides the badges it drives
        // the additive re-resolve when a resolver is enabled, which a CarPlay-only
        // session needs too. Idempotent.
        ResolverPrefs.shared.start()

        let coordinator = AppPlayback.shared.coordinator
        ListenAlongController.shared.bind(coordinator)
        SpinoffController.shared.bind(coordinator)
        bindChatPlayback(coordinator)

        await coordinator.restoreQueue()

        watchCurrentTrackArtwork(coordinator)
    }

    /// Wire the DJ-chat tool surface to the playback coordinator (#223). The
    /// shared IosDjToolExecutor calls these closures; each hops to the main actor
    /// and drives the coordinator (fire-and-forget, like the spinoff/scrobble bridges).
    private func bindChatPlayback(_ coord: QueuePlaybackCoordinator) {
        IosContainer.companion.shared.bindChatPlayback(
            onPlayTrack: { track in Task { @MainActor in
                coord.setQueue([track], startIndex: 0,
                               context: PlaybackContext(type: "chat", name: "Shuffleupagus", id: nil)) } },
            onAddToQueue: { track in Task { @MainActor in coord.addToQueue(track) } },
            onClearQueue: { Task { @MainActor in coord.clearQueue() } },
            onPause: { Task { @MainActor in if coord.isPlaying { coord.togglePlayPause() } } },
            onResume: { Task { @MainActor in if !coord.isPlaying { coord.togglePlayPause() } } },
            onSkipNext: { Task { @MainActor in coord.skipNext() } },
            onSkipPrevious: { Task { @MainActor in coord.skipPrevious() } },
            onSetShuffle: { on in Task { @MainActor in coord.setShuffle(on.boolValue) } }
        )
    }

    /// Resolve the current track as soon as it changes (incl. natural
    /// auto-advance, where the next track may not have been pre-resolved), so
    /// its artwork is ready and the mini player crossfades reliably. Fires once
    /// per track id, like the `.task(id:)` it replaces. The resolver cache is
    /// @Observable, so views showing `pcTrackArt` re-render when it lands.
    private func watchCurrentTrackArtwork(_ coord: QueuePlaybackCoordinator) {
        let track = withObservationTracking {
            coord.currentTrack
        } onChange: { [weak self] in
            // onChange fires before the new value is set; re-read on the next turn.
            Task { @MainActor in self?.watchCurrentTrackArtwork(coord) }
        }
        let id = track?.id
        guard id != artworkTrackId else { return }
        artworkTrackId = id
        if let t = track {
            IosTrackResolverCache.shared.resolve(
                ResolveRequest(artist: t.artist, title: t.title, album: t.album), order: 0)
        }
    }
}
