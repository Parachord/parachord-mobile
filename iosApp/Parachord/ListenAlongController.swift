import SwiftUI
import Shared

/// Listen Along (#235) — follow a friend's now-playing in real time. iOS port of
/// Android's `MainViewModel` listen-along loop.
///
/// Behavior: play the friend's current track, then poll every 15s and follow
/// track changes. Polling stays alive in the background because audio is playing
/// (the app's `audio` background mode), so no explicit wake-lock is needed —
/// once playback stops, the OS may suspend us, which is fine.
///
/// Two deliberate v1 simplifications vs. Android (tracked follow-ups):
///  • On a friend track-change we switch within the 15s poll window rather than
///    deferring to the current song's end (Android queues a "pending" track and
///    swaps at end via an onTrackEnded hook the iOS coordinator doesn't expose).
///  • A friend going offline stops *following* but lets the current song finish
///    naturally (we don't cut playback), approximating Android's deferred stop.
///
/// User-control safety: every queue replacement is gated on the coordinator's
/// context STILL being "listen-along". The moment the user plays anything else,
/// the next poll sees a different context and stops following — so listen-along
/// never hijacks a track the user chose.
@MainActor
@Observable
final class ListenAlongController {
    /// App-wide instance — bound to the coordinator once at the ContentView root,
    /// triggered from the sidebar, Collection friend rows, and the deep link.
    static let shared = ListenAlongController()
    static let listenAlongContext = "listen-along"

    private let container = IosContainer.companion.shared
    private weak var coordinator: QueuePlaybackCoordinator?
    private var pollTask: Task<Void, Never>?

    /// The friend currently being listened-along with (drives UI affordances).
    var friend: Friend?
    /// Track key of the last track we started, so we don't re-issue the same one.
    private var lastTrackKey: String?

    func bind(_ c: QueuePlaybackCoordinator) { coordinator = c }

    func isActive(_ f: Friend) -> Bool { friend?.id == f.id }

    /// While listening along: true when the friend has moved to a track DIFFERENT
    /// from the one we're playing — i.e. there's a newer song to catch up to, so
    /// Now Playing's Next button is enabled. False when in sync (nothing to advance
    /// to → Next disabled).
    var friendIsAhead: Bool {
        guard let f = friend, f.isOnAir,
              let name = f.cachedTrackName, let artist = f.cachedTrackArtist else { return false }
        return "\(name.lowercased())|\(artist.lowercased())" != lastTrackKey
    }

    /// Catch up to the friend's CURRENT track (Now Playing's Next button while
    /// listening along). No-op when already in sync.
    func advanceToFriend() {
        guard let f = friend else { return }
        playCurrent(f)
    }

    /// Toggle: start listening along with `f`, or stop if already on `f`.
    func toggle(_ f: Friend) {
        if isActive(f) { stop() } else { start(f) }
    }

    /// True when the friend went offline — let the current song finish, then stop.
    private var pendingOffline = false

    /// The user's queue at the moment listen-along began — restored on disconnect
    /// (desktop "YOUR QUEUE" suspend/resume). Captured ONCE on entering listen-along
    /// from a normal queue; preserved across friend-switches; cleared on restore.
    private struct QueueSuspension { let tracks: [Track]; let context: PlaybackContext? }
    private var suspended: QueueSuspension?

    /// The user's suspended queue — shown dimmed as "YOUR QUEUE" in the queue panel
    /// while listening along (it resumes on disconnect).
    var suspendedQueue: [Track] { suspended?.tracks ?? [] }

    func start(_ f: Friend) {
        // Tear down any prior listen-along poll WITHOUT restoring the suspended
        // queue — switching friends stays in listen-along and keeps the same
        // suspension; only a full stop/disconnect resumes it.
        pollTask?.cancel(); pollTask = nil
        coordinator?.onTrackFinished = nil
        // Switching from a Spinoff → Listen Along: exit the spinoff first so its
        // separate pool tears down and the saved context is restored before we
        // suspend for listen-along (the two modes are mutually exclusive) (#231).
        coordinator?.exitSpinoff()
        // Suspend the user's queue the FIRST time we enter listen-along.
        if suspended == nil { captureSuspension() }
        friend = f
        lastTrackKey = nil
        pendingOffline = false
        // Defer track switches to the current song's END so each song plays fully
        // and scrobbles (#246). The coordinator calls this when a track finishes.
        coordinator?.onTrackFinished = { [weak self] in Task { @MainActor in await self?.onSongEnded() } }
        playCurrent(f)
        pollTask = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                if Task.isCancelled { break }
                await self?.pollOnce()
            }
        }
    }

    /// Stop listening along. `restore: true` (manual stop, friend offline, friend
    /// idle at song-end) RESUMES the user's suspended queue. `restore: false` (the
    /// user took control by playing something else) DISCARDS the suspension so we
    /// don't clobber the playback they just chose.
    func stop(restore: Bool = true) {
        pollTask?.cancel()
        pollTask = nil
        coordinator?.onTrackFinished = nil
        friend = nil
        lastTrackKey = nil
        pendingOffline = false
        if restore { restoreSuspension() } else { suspended = nil }
    }

    /// Snapshot the user's current queue (current track + up-next + context) before
    /// listen-along replaces it. Stores an empty snapshot when nothing was playing
    /// (or we're somehow already in listen-along) so restore just clears.
    private func captureSuspension() {
        guard let c = coordinator, c.playbackContext?.type != Self.listenAlongContext else {
            suspended = QueueSuspension(tracks: [], context: nil); return
        }
        let tracks = (c.currentTrack.map { [$0] } ?? []) + c.upNext
        suspended = QueueSuspension(tracks: tracks, context: c.playbackContext)
    }

    /// Resume the suspended queue on disconnect — re-queue from the track that was
    /// current (it restarts from the top; exact position restore is a follow-up).
    /// Clears playback when the user had nothing queued.
    private func restoreSuspension() {
        guard let s = suspended, let c = coordinator else { return }
        suspended = nil
        if s.tracks.isEmpty { c.clearQueue(); return }
        c.setQueue(s.tracks, startIndex: 0, context: s.context)
    }

    /// 15s poll: keep `friend` fresh (the "pending" track the NEXT song jumps to)
    /// and bail if the user took control. Does NOT switch playback mid-song — the
    /// current song plays to its end (so it scrobbles), then onSongEnded() jumps to
    /// the friend's latest track.
    private func pollOnce() async {
        guard let f = friend else { return }
        if let ctx = coordinator?.playbackContext, ctx.type != Self.listenAlongContext {
            stop(restore: false)  // user took control — keep their choice, don't resume our queue
            return
        }
        let refreshed: Friend?
        if f.transient {
            refreshed = (try? await container.fetchTransientFriendNowPlaying(service: f.service, user: f.username)) ?? nil
        } else {
            refreshed = (try? await container.refreshFriendActivity(friend: f)) ?? nil
        }
        guard let r = refreshed else { pendingOffline = true; return }
        friend = r
        pendingOffline = !r.isOnAir
    }

    /// The current listen-along song just finished (and scrobbled). Jump to the
    /// friend's LATEST track — or stop if they went offline / the user took over.
    private func onSongEnded() async {
        if let ctx = coordinator?.playbackContext, ctx.type != Self.listenAlongContext {
            stop(restore: false)  // user took control before this track ended — keep their choice
            return
        }
        guard let f = friend, !pendingOffline, f.isOnAir,
              let name = f.cachedTrackName, let artist = f.cachedTrackArtist else { stop(); return }
        // If the friend is STILL on the song we just finished (they haven't moved
        // on), do NOT repeat it — end the listen-along session. Android parity:
        // playFriendCurrentTrack dedups on the same trackKey (no replay) and
        // onTrackEnded has no pending track to play, so the session just ends.
        let currentKey = "\(name.lowercased())|\(artist.lowercased())"
        if currentKey == lastTrackKey { stop(); return }
        // Friend moved to a NEW track — follow it.
        playCurrent(f)
    }

    private func playCurrent(_ f: Friend) {
        guard let name = f.cachedTrackName, let artist = f.cachedTrackArtist else { return }
        let key = "\(name.lowercased())|\(artist.lowercased())"
        if key == lastTrackKey { return }   // same track still playing — nothing to do
        lastTrackKey = key
        let track = Self.makeTrack(f, name: name, artist: artist, key: key)
        // Encode the friend's identity in `id` ("friendId|username|service") so the
        // Now Playing / queue context banner can link to their profile (#235).
        coordinator?.setQueue(
            [track], startIndex: 0,
            context: PlaybackContext(type: Self.listenAlongContext, name: f.displayName,
                                     id: "\(f.id)|\(f.username)|\(f.service)"))
    }

    /// Metadata-only Track — the coordinator resolves it through the normal
    /// resolver pipeline at play time (cache → on-the-fly).
    private static func makeTrack(_ f: Friend, name: String, artist: String, key: String) -> Track {
        Track(
            id: "listen-along:\(key)", title: name, artist: artist,
            album: f.cachedTrackAlbum, albumId: nil, duration: 0,
            artworkUrl: f.cachedTrackArtworkUrl,
            sourceType: nil, sourceUrl: nil, addedAt: 0,
            resolver: nil, spotifyUri: nil, soundcloudId: nil,
            spotifyId: nil, appleMusicId: nil, isrc: nil, recordingMbid: nil,
            artistMbid: nil, releaseMbid: nil, crossResolverEnrichedAt: nil
        )
    }
}
