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

    func bind(_ c: QueuePlaybackCoordinator) { coordinator = c }

    /// The ✨ button: toggle the spinoff station on/off (Android `toggleSpinoff`).
    func toggle() {
        guard let c = coordinator else { return }
        if c.spinoffMode { c.exitSpinoff(); return }
        guard !loading, let seed = c.currentTrack else { return }
        let artist = seed.artist, title = seed.title
        guard !artist.isEmpty, !title.isEmpty else { return }

        // Switching from Listen Along → Spinoff: stop following first (without
        // resuming the friend's track) so the two modes never overlap.
        if c.playbackContext?.type == ListenAlongController.listenAlongContext {
            ListenAlongController.shared.stop(restore: false)
        }

        loading = true
        lastEmptySeed = nil
        task?.cancel()
        task = Task { @MainActor [weak self] in
            guard let self else { return }
            let pool = (try? await self.container.spinoffPool(seedArtist: artist, seedTitle: title, limit: 30)) ?? []
            defer { self.loading = false }
            guard !Task.isCancelled else { return }
            guard !pool.isEmpty else { self.lastEmptySeed = title; return }
            // Don't interrupt the current song — beginSpinoff stashes the pool and
            // the next advance (song-end or Next) pulls from it. Banner includes the
            // artist: "Spinoff from <track> by <artist>".
            self.coordinator?.beginSpinoff(pool: pool, displayName: "Spinoff from \(title) by \(artist)")
        }
    }
}
