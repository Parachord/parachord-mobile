import SwiftUI
import Shared

// MARK: - Home's model (the shared session, watched)
//
// Home draws the app-wide `IosHomeSession` (`IosContainer.homeSession`), the
// shared `HomeSession` Android's Home draws, flattened into `IosHomePage`. No
// rules live here: the page says what shows, the session's queues say what a
// tap plays.

@MainActor @Observable
final class HomeModel {
    @ObservationIgnored let session = IosContainer.companion.shared.homeSession
    @ObservationIgnored private let watcher = FlowWatcher(scope: IosContainer.companion.shared.appScope)
    @ObservationIgnored private var pageSub: Cancellable?

    var page: IosHomePage?

    /// Starts watching the page (the view's creation).
    func start() {
        guard pageSub == nil else { return }
        pageSub = watcher.watch(flow: session.watch()) { [weak self] v in
            guard let p = v as? IosHomePage else { return }
            Task { @MainActor in self?.page = p }
        }
    }

    /// Stops the watch (the view went away).
    func stop() {
        pageSub?.cancel(); pageSub = nil
    }

    /// Plays a queue the session built: its tracks from the top under its
    /// context, or its message when it has none. `shuffle == false` keeps the
    /// current shuffle setting, so it is passed as nil, never as false.
    func play(_ q: IosHomeQueue, coordinator: QueuePlaybackCoordinator) {
        if !q.tracks.isEmpty {
            coordinator.setQueue(q.tracks, startIndex: 0, context: q.context, shuffle: q.shuffle ? true : nil)
        } else if let message = q.message {
            coordinator.notice = QueuePlaybackCoordinator.Notice(message: message, showInCar: false)
        }
    }

    /// Builds a queue off the main actor's way and plays it.
    func play(coordinator: QueuePlaybackCoordinator, _ build: @escaping (IosHomeSession) async throws -> IosHomeQueue) {
        let session = self.session
        Task { @MainActor in
            guard let q = try? await build(session) else { return }
            self.play(q, coordinator: coordinator)
        }
    }
}
