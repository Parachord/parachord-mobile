import SwiftUI
import Shared

/// Spinoff (#231) — seed-based radio from the current track. iOS port of Android's
/// `PlaybackController.startSpinoffWithSeed` (Mode B, in-app).
///
/// Behavior matches Android exactly:
///  • The current song KEEPS PLAYING when you tap Spinoff. The pool is a separate
///    hidden list; it begins only when the current song ends or you tap Next
///    (Android's `kickStartFirstTrack=false`).
///  • The user's queue is NEVER modified — only the playback context is saved — so
///    it resumes intact once the pool exhausts (coordinator.exitSpinoff()).
///  • Pool tracks are metadata-only and resolve on-the-fly as each plays (same lazy
///    path as Listen Along; keeps resolution visibility-scoped, not a burst).
///
/// This controller only does the async pool fetch + loading state; all the
/// pool/mode/queue mechanics live in `QueuePlaybackCoordinator` (beginSpinoff /
/// advanceSpinoff / exitSpinoff), shared with the skipNext/autoAdvance paths.
@MainActor
@Observable
final class SpinoffController {
    static let shared = SpinoffController()

    private let container = IosContainer.companion.shared
    private weak var coordinator: QueuePlaybackCoordinator?
    private var task: Task<Void, Never>?

    /// True while fetching the pool — drives the Now Playing button spinner.
    var loading = false
    /// Set briefly when a seed yields no similar tracks, so the UI can alert.
    var lastEmptySeed: String?
    /// Set briefly when Last.fm's similar-tracks request itself failed (#534),
    /// so the alert says so instead of "no similar tracks".
    var lastFetchFailed = false

    func bind(_ c: QueuePlaybackCoordinator) { coordinator = c }

    /// The Spinoff (fork) button: toggle the spinoff station on/off (Android `toggleSpinoff`).
    func toggle() {
        guard let c = coordinator else { return }
        if c.spinoffMode { c.exitSpinoff(); return }
        guard !loading, let seed = c.currentTrack else { return }
        let artist = seed.artist, title = seed.title
        guard !artist.isEmpty, !title.isEmpty else { return }

        // Switching from Listen Along → Spinoff: end the session silently and
        // drop the suspended queue, so the two modes never overlap. Whatever
        // the context: a session still waiting for the friend's first track
        // leaves the user's own queue (and its context) playing.
        ListenAlongController.shared.endSilently()

        loading = true
        lastEmptySeed = nil
        lastFetchFailed = false
        task?.cancel()
        task = Task { @MainActor [weak self] in
            guard let self else { return }
            // Shared builder (#534): similar tracks, topped up from similar
            // artists' top tracks. Throws only when the similar-tracks request
            // failed, which is a different message from "none found".
            let pool: [Track]?
            do {
                pool = try await self.container.spinoffPool(seedArtist: artist, seedTitle: title)
            } catch {
                pool = nil
            }
            defer { self.loading = false }
            guard !Task.isCancelled else { return }
            guard let pool else { self.lastFetchFailed = true; return }
            guard !pool.isEmpty else { self.lastEmptySeed = title; return }
            // Don't interrupt the current song — beginSpinoff stashes the pool and
            // the next advance (song-end or Next) pulls from it. Banner includes the
            // artist: "Spinoff from <track> by <artist>".
            guard let c = self.coordinator else { return }
            c.beginSpinoff(pool: pool, displayName: PlaybackMessages.shared.spinoffFrom(title: title, artist: artist))
            c.notice = QueuePlaybackCoordinator.Notice(
                message: PlaybackMessages.shared.spinningOff(title: title, artist: artist), showInCar: false)
        }
    }
}
