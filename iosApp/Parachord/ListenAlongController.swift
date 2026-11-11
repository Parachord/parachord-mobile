import SwiftUI
import Shared

/// Listen Along (#235, #585): follow what a friend is playing. The rules and the
/// host logic are shared (`ListenAlongSession`, `ListenAlongHost`, Android uses the
/// same host); this controller keeps only what's iOS's: the 15 s poll loop, the
/// observable UI state, and the glue from the coordinator's hooks
/// (`onTrackFinished`, `onTrackUnplayable`, `onUserAction`, `onTrackStarting`) into
/// the host and from the host (`ListenAlongPlatform`) back to the coordinator.
///
/// Polling keeps running in the background because audio plays (the app's
/// `audio` background mode and the coordinator's silent keepalive).
@MainActor
@Observable
final class ListenAlongController {
    /// App-wide instance — bound to the coordinator once at app start
    /// (`AppBootstrap`), triggered from the sidebar, friend rows and the deep link.
    static let shared = ListenAlongController()
    nonisolated static let listenAlongContext = SpinoffSessionKt.LISTEN_ALONG_CONTEXT

    private let container = IosContainer.companion.shared
    @ObservationIgnored fileprivate weak var coordinator: QueuePlaybackCoordinator?
    @ObservationIgnored private var pollTask: Task<Void, Never>?
    @ObservationIgnored private var platform: Platform!
    @ObservationIgnored private var host: ListenAlongHost!

    /// The friend being listened along with (drives UI affordances), refreshed by each poll.
    var friend: Friend?
    /// The friend is on a song we haven't played: Now Playing's Next catches up.
    private(set) var friendIsAhead = false
    /// The user's suspended queue — shown dimmed as "YOUR QUEUE" while listening along.
    private(set) var suspendedQueue: [Track] = []

    init() {
        let p = Platform()
        platform = p
        host = ListenAlongHost(platform: p)
        p.owner = self
    }

    func bind(_ c: QueuePlaybackCoordinator) {
        coordinator = c
        // The coordinator calls these on the main thread.
        c.onTrackFinished = { [weak self] in
            MainActor.assumeIsolated { self?.host.onOwnTrackEnded() ?? false }
        }
        c.onTrackUnplayable = { [weak self] track in
            MainActor.assumeIsolated { self?.host.onTrackUnplayable(trackId: track.id) ?? false }
        }
        c.onUserAction = { [weak self] action in
            MainActor.assumeIsolated {
                guard let self, self.host.isActive else { return false }
                NSLog("LISTEN_ALONG user action %@", action.name)
                return self.host.onUserAction(action: action)
            }
        }
        c.onTrackStarting = { [weak self] track in
            MainActor.assumeIsolated { self?.host.onTrackStarting(trackId: track.id) }
        }
    }

    func isActive(_ f: Friend) -> Bool { host.isActive && friend?.id == f.id }

    /// Catch up to the friend's current track (Now Playing's Next while listening along).
    func advanceToFriend() { host.catchUp() }

    /// Toggle: start listening along with `f`, or stop if already on `f`.
    func toggle(_ f: Friend) {
        if isActive(f) { stop() } else { start(f) }
    }

    /// Start following `f`. Starting with another friend replaces the session
    /// (the old one isn't told) and keeps the same suspended queue; a running
    /// spinoff ends first (the host asks).
    func start(_ f: Friend) {
        guard coordinator != nil else { return }
        pollTask?.cancel(); pollTask = nil
        friend = f
        let current = (ListenAlongSessionKt.listenAlongPollOf(friend: f) as? ListenAlongPollListening)?.track
        // The context's `id` carries the friend's identity ("friendId|username|service")
        // so the Now Playing / queue banner can link to their profile (#235).
        host.start(friendName: f.displayName, current: current, contextId: "\(f.id)|\(f.username)|\(f.service)")
        guard host.isActive else { return }
        pollTask = Task { @MainActor [weak self] in
            let interval = UInt64(ListenAlongSessionKt.LISTEN_ALONG_POLL_MS) * 1_000_000
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: interval)
                if Task.isCancelled { break }
                await self?.pollOnce(f)
            }
        }
    }

    /// The Listen Along toggle / stop button.
    func stop() { host.stop() }

    /// Something else takes over (a radio link): end with no message and drop
    /// the suspended queue. A spinoff reports itself through `onUserAction`.
    func endSilently() { host.endSilently() }

    // MARK: - Polling

    private func pollOnce(_ f: Friend) async {
        guard host.isActive, friend?.id == f.id else { return }
        let result: ListenAlongPollResult
        do {
            result = try await container.pollListenAlong(friend: friend ?? f)
        } catch {
            if Task.isCancelled { return }
            result = ListenAlongPollResult(poll: ListenAlongPollFailed.shared, friend: nil)
        }
        // Cancelled, or the session ended or was replaced while the poll ran.
        if Task.isCancelled || !host.isActive || friend?.id != f.id { return }
        if let fresh = result.friend_ { friend = fresh }
        NSLog("LISTEN_ALONG poll %@", String(describing: result.poll))
        host.onPoll(result: result.poll)
    }

    // MARK: - The host's platform

    fileprivate func refreshState() {
        friendIsAhead = host.friendIsAhead
        suspendedQueue = host.suspendedQueue
    }

    fileprivate func sessionEnded() {
        pollTask?.cancel(); pollTask = nil
        friend = nil
    }

    /// `ListenAlongPlatform` over the coordinator. A Kotlin interface needs an
    /// NSObject to conform from Swift; the host calls it on the main thread.
    private final class Platform: NSObject, ListenAlongPlatform {
        weak var owner: ListenAlongController?

        private var c: QueuePlaybackCoordinator? {
            MainActor.assumeIsolated { owner?.coordinator }
        }

        func play(track: Track, context: PlaybackContext) {
            NSLog("LISTEN_ALONG play '%@'", track.title)
            c?.setQueue([track], startIndex: 0, context: context)
        }

        func currentQueue() -> ListenAlongSuspendedQueue {
            guard let c else { return ListenAlongSuspendedQueue(tracks: [], context: nil) }
            let tracks = (c.currentTrack.map { [$0] } ?? []) + c.upNext
            return ListenAlongSuspendedQueue(tracks: tracks, context: c.playbackContext)
        }

        func exitSpinoff() { c?.exitSpinoff() }

        func restoreQueue(queue: ListenAlongSuspendedQueue, startPlayback: Bool) {
            NSLog("LISTEN_ALONG restore queue (play: %@)", startPlayback ? "yes" : "no")
            if startPlayback {
                c?.setQueue(queue.tracks, startIndex: 0, context: queue.context)
            } else {
                c?.setQueuePaused(queue.tracks, context: queue.context)
            }
        }

        func clearQueue() { c?.clearQueue() }
        func stopAndClear() { c?.stopAndClear() }

        func showMessage(text: String) {
            NSLog("LISTEN_ALONG notice '%@'", text)
            c?.notice = QueuePlaybackCoordinator.Notice(message: text, showInCar: false)
        }

        func releaseHold() { c?.releaseListenerHold() }

        func ended() {
            NSLog("LISTEN_ALONG ended")
            MainActor.assumeIsolated { owner?.sessionEnded() }
        }

        func stateChanged() {
            MainActor.assumeIsolated { owner?.refreshState() }
        }

        func isPlaying() -> Bool { c?.isPlaying ?? false }
        func isPlayStarting() -> Bool { c?.isStarting ?? false }
        func currentTrackId() -> String? { c?.currentTrack?.id }
    }
}
