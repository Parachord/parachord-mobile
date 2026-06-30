import BackgroundTasks
import Shared

/// iOS background sync via `BGTaskScheduler` (#257 Phase 3). Foreground sync is a
/// launch + 15-min in-app timer (RootView); this runs the same maintenance —
/// collection `syncNow()` + hosted-XSPF `pollHostedPlaylists()` — when iOS grants
/// background time. Android's equivalent is the hourly `LibrarySyncWorker`.
///
/// `syncNow()` is gated internally on Settings → sync enabled (no-ops when off),
/// and both calls are idempotent + skip-if-held, so a background run is safe even
/// if it overlaps a foreground sync.
enum BackgroundSync {
    /// Must also be listed in Info.plist `BGTaskSchedulerPermittedIdentifiers`.
    static let taskId = "com.parachord.ios.refresh"

    /// Register the launch handler. Cheap — does NOT touch `IosContainer` (only the
    /// handler, which runs later, does), so it's safe from `App.init` before the
    /// first frame. MUST be called before the app finishes launching.
    static func register() {
        BGTaskScheduler.shared.register(forTaskWithIdentifier: taskId, using: nil) { task in
            handle(task as! BGAppRefreshTask)
        }
    }

    /// Ask iOS to schedule the next run. Call on launch + when entering background.
    /// The `earliestBeginDate` is a hint; iOS decides the actual timing from usage.
    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: taskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do { try BGTaskScheduler.shared.submit(request) }
        catch { NSLog("BackgroundSync: submit failed: \(error.localizedDescription)") }
    }

    private static func handle(_ task: BGAppRefreshTask) {
        schedule()   // chain the next run before we risk expiring
        let completion = CompletionGuard(task)
        let work = Task {
            let container = IosContainer.companion.shared
            _ = try? await container.syncNow()
            _ = try? await container.pollHostedPlaylists()
            completion.complete(true)
        }
        // If iOS expires our window, cancel the work and finish immediately so the
        // app isn't killed for overrunning.
        task.expirationHandler = {
            work.cancel()
            completion.complete(false)
        }
    }
}

/// Ensures `setTaskCompleted` is called exactly once across the success path and
/// the expiration handler (calling it twice is a programmer error).
private final class CompletionGuard: @unchecked Sendable {
    private let lock = NSLock()
    private var done = false
    private let task: BGTask
    init(_ task: BGTask) { self.task = task }
    func complete(_ success: Bool) {
        lock.lock(); defer { lock.unlock() }
        guard !done else { return }
        done = true
        task.setTaskCompleted(success: success)
    }
}
