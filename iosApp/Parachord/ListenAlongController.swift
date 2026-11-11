import SwiftUI
import Shared

/// Listen Along (#235, #585): follow what a friend is playing. iOS's side of the
/// shared `ListenAlongSession` (Android's `ListenAlongDriver` is the other): the
/// session decides, this controller polls, plays, shows messages, and tells the
/// session what the coordinator reports through its hooks (`onTrackFinished`,
/// `onTrackUnplayable`, `onUserAction`).
///
/// The user's queue is suspended while a session runs (shown dimmed as
/// "YOUR QUEUE") and comes back, or is dropped, when the session ends.
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
    @ObservationIgnored private weak var coordinator: QueuePlaybackCoordinator?
    @ObservationIgnored private var pollTask: Task<Void, Never>?

    /// The friend being listened along with (drives UI affordances), refreshed by each poll.
    var friend: Friend?
    /// The friend is on a song we haven't played: Now Playing's Next catches up.
    private(set) var friendIsAhead = false

    @ObservationIgnored private var session: ListenAlongSession?
    /// The id of the track the session last played (`listenAlongTrack`).
    @ObservationIgnored private var playingId: String?
    /// True while the controller itself starts a play, so it isn't read as the user's.
    @ObservationIgnored private var driving = false
    /// Our song has ended and nothing has played since (the session waits for a
    /// poll), or the end is being handled right now.
    @ObservationIgnored private var atSongEnd = false

    /// The user's queue at the moment Listen Along began. Captured ONCE on
    /// entering Listen Along; kept when switching friends; cleared on end.
    private struct QueueSuspension { let tracks: [Track]; let context: PlaybackContext? }
    private var suspended: QueueSuspension?

    /// The user's suspended queue — shown dimmed as "YOUR QUEUE" while listening along.
    var suspendedQueue: [Track] { suspended?.tracks ?? [] }

    func bind(_ c: QueuePlaybackCoordinator) {
        coordinator = c
        // The coordinator calls these on the main thread.
        c.onTrackFinished = { [weak self] in
            MainActor.assumeIsolated { self?.ownTrackEnded() ?? false }
        }
        c.onTrackUnplayable = { [weak self] track in
            MainActor.assumeIsolated { self?.trackUnplayable(track) ?? false }
        }
        c.onUserAction = { [weak self] action in
            MainActor.assumeIsolated { self?.userAction(action) ?? false }
        }
    }

    func isActive(_ f: Friend) -> Bool { session != nil && friend?.id == f.id }

    /// Catch up to the friend's current track (Now Playing's Next while listening along).
    func advanceToFriend() {
        guard let s = session else { return }
        apply(s, s.onCatchUp())
    }

    /// Toggle: start listening along with `f`, or stop if already on `f`.
    func toggle(_ f: Friend) {
        if isActive(f) { stop() } else { start(f) }
    }

    /// Start following `f`. Starting with another friend replaces the session
    /// (the old one isn't told) and keeps the same suspended queue.
    func start(_ f: Friend) {
        guard let c = coordinator else { return }
        pollTask?.cancel(); pollTask = nil
        // Switching from a Spinoff: exit it first so its pool tears down and the
        // saved context is restored before we suspend the queue (#231).
        c.exitSpinoff()
        if suspended == nil { captureSuspension(c) }
        friend = f
        let s = ListenAlongSession(friendName: f.displayName)
        session = s
        playingId = nil
        atSongEnd = false
        let current = (ListenAlongSessionKt.listenAlongPollOf(friend: f) as? ListenAlongPollListening)?.track
        NSLog("LISTEN_ALONG start '%@' (current: %@)", f.displayName, current?.key ?? "none")
        apply(s, s.start(current: current))
        guard session === s else { return }
        pollTask = Task { @MainActor [weak self] in
            let interval = UInt64(ListenAlongSessionKt.LISTEN_ALONG_POLL_MS) * 1_000_000
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: interval)
                if Task.isCancelled { break }
                await self?.pollOnce(s)
            }
        }
    }

    /// The Listen Along toggle / stop button.
    func stop() {
        guard let s = session else { return }
        apply(s, s.onUserStopped())
    }

    /// Something else takes over (a Spinoff, a radio link): end with no message
    /// and drop the suspended queue.
    func endSilently() {
        guard session != nil else { return }
        NSLog("LISTEN_ALONG ended silently")
        _ = end(restore: false)
    }

    // MARK: - Coordinator hooks

    /// Our song ended. True (handled) whenever the session is following and the
    /// current track is its track; the coordinator then doesn't advance.
    private func ownTrackEnded() -> Bool {
        guard let s = session, isOurTrack(coordinator?.currentTrack?.id) else { return false }
        NSLog("LISTEN_ALONG our song ended")
        atSongEnd = true
        apply(s, s.onSongEnded())
        return true
    }

    /// Our track couldn't play. True when it's ours: no notice, no skip.
    private func trackUnplayable(_ track: Track) -> Bool {
        guard let s = session, isOurTrack(track.id) else { return false }
        NSLog("LISTEN_ALONG '%@' unplayable; waiting for the friend's next track", track.title)
        apply(s, s.onTrackUnplayable())
        return true
    }

    /// A user's playback action, before the coordinator acts on it. True when a
    /// play was started here (a restored queue, or the held friend track on a
    /// resume), so the coordinator doesn't also act on the skip or the resume.
    private func userAction(_ action: UserPlaybackAction) -> Bool {
        if driving { return false }
        guard let s = session else { return false }
        NSLog("LISTEN_ALONG user action %@", String(describing: action))
        switch action {
        case .skip: return apply(s, s.onUserSkipped())
        case .playSomethingElse: apply(s, s.onUserPlayedSomethingElse()); return false
        case .pause: apply(s, s.onUserPaused()); return false
        case .resume: return apply(s, s.onUserResumed())
        }
    }

    // MARK: - Polling

    private func pollOnce(_ s: ListenAlongSession) async {
        guard session === s, let f = friend else { return }
        let result: ListenAlongPollResult
        do {
            result = try await container.pollListenAlong(friend: f)
        } catch {
            if Task.isCancelled { return }
            result = ListenAlongPollResult(poll: ListenAlongPollFailed.shared, friend: nil)
        }
        // Cancelled, or the session ended or was replaced while the poll ran.
        if Task.isCancelled || session !== s { return }
        if let fresh = result.friend_ { friend = fresh }
        let playing = ourSongIsPlaying()
        NSLog("LISTEN_ALONG poll %@ (our song playing: %@)", pollName(result.poll), playing ? "yes" : "no")
        apply(s, s.onPoll(result: result.poll, isPlaying: playing))
    }

    private func pollName(_ p: ListenAlongPoll) -> String {
        if let l = p as? ListenAlongPollListening { return "listening '\(l.track.key)'" }
        if p is ListenAlongPollNotListening { return "not listening" }
        return "failed"
    }

    /// Whether Listen Along's OWN song plays: false until the session has played
    /// something (the user's queue can be playing while we wait for the friend's
    /// first song); true while a play starts (the only plays while a session runs
    /// are its own: any other ends it); else whether our track is the one playing.
    private func ourSongIsPlaying() -> Bool {
        guard let ours = playingId, let c = coordinator else { return false }
        if c.isStarting { return true }
        return c.isPlaying && c.currentTrack?.id == ours
    }

    private func isOurTrack(_ id: String?) -> Bool { id != nil && id == playingId }

    // MARK: - Effects

    /// Applies `effects` in order; returns true when one of them started a play.
    @discardableResult
    private func apply(_ s: ListenAlongSession, _ effects: [ListenAlongEffect]) -> Bool {
        var started = false
        for effect in effects {
            if let play = effect as? ListenAlongEffectPlay {
                let track = ListenAlongSessionKt.listenAlongTrack(track: play.track)
                NSLog("LISTEN_ALONG play '%@'", track.title)
                playingId = track.id
                atSongEnd = false
                driving = true
                coordinator?.setQueue([track], startIndex: 0, context: listenAlongContext(s))
                driving = false
                started = true
            } else if let message = effect as? ListenAlongEffectMessage {
                NSLog("LISTEN_ALONG notice '%@'", message.text)
                coordinator?.notice = QueuePlaybackCoordinator.Notice(message: message.text, showInCar: false)
            } else if let endEffect = effect as? ListenAlongEffectEnd {
                if end(restore: endEffect.restoreQueue) { started = true }
            }
        }
        friendIsAhead = session?.friendIsAhead == true
        return started
    }

    /// The Listen Along context. Its `id` carries the friend's identity
    /// ("friendId|username|service") so the Now Playing / queue banner can link
    /// to their profile (#235).
    private func listenAlongContext(_ s: ListenAlongSession) -> PlaybackContext {
        let id = friend.map { "\($0.id)|\($0.username)|\($0.service)" }
        return PlaybackContext(type: Self.listenAlongContext, name: s.friendName, id: id)
    }

    /// Ends the session; with `restore` the suspended queue plays again from its
    /// first track. True when it did.
    private func end(restore: Bool) -> Bool {
        pollTask?.cancel(); pollTask = nil
        session = nil
        playingId = nil
        friend = nil
        friendIsAhead = false
        let endedAtSongEnd = atSongEnd
        atSongEnd = false
        let s = suspended
        suspended = nil
        NSLog("LISTEN_ALONG ended (restore: %@)", restore ? "yes" : "no")
        guard restore, let s, let c = coordinator else { return false }
        if s.tracks.isEmpty {
            // Nothing to go back to. At the end of our song this is the end of
            // the queue: the track comes off screen. Otherwise (the toggle, a
            // skip, the friend gone while paused) the track is left as it is.
            c.clearQueue()
            if endedAtSongEnd { c.stopAndClear() }
            return false
        }
        driving = true
        c.setQueue(s.tracks, startIndex: 0, context: s.context)
        driving = false
        return true
    }

    /// Snapshot the user's queue (current track + up next + context) before
    /// Listen Along replaces it; empty when nothing was queued.
    private func captureSuspension(_ c: QueuePlaybackCoordinator) {
        guard c.playbackContext?.type != Self.listenAlongContext else {
            suspended = QueueSuspension(tracks: [], context: nil); return
        }
        let tracks = (c.currentTrack.map { [$0] } ?? []) + c.upNext
        suspended = QueueSuspension(tracks: tracks, context: c.playbackContext)
    }
}
