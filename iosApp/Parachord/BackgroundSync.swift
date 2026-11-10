import BackgroundTasks
import Network
import Shared
import UIKit

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
        BGTaskScheduler.shared.register(forTaskWithIdentifier: enrichmentTaskId, using: nil) { task in
            handleEnrichment(task as! BGProcessingTask)
        }
    }

    // ── Cross-resolver enrichment (Android `CrossResolverEnrichmentWorker`) ──
    //
    // The shared slow trickle: up to 20 local-file tracks per run, 3 s apart,
    // each looked up on Spotify / Apple Music / SoundCloud so its row gains those
    // ids and Achordion gets the links. Android runs it every 24 h on an
    // unmetered network with the battery not low. iOS can't require Wi-Fi of a
    // BGProcessingTask, so the handler checks instead: no run on an expensive
    // (cellular, hotspot) or constrained (Low Data Mode) path, in Low Power Mode,
    // or under 20% battery off the charger.

    /// Must also be listed in Info.plist `BGTaskSchedulerPermittedIdentifiers`.
    static let enrichmentTaskId = "com.parachord.ios.enrichment"

    static func scheduleEnrichment() {
        let request = BGProcessingTaskRequest(identifier: enrichmentTaskId)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        request.earliestBeginDate = Date(timeIntervalSinceNow: 24 * 3600)
        do { try BGTaskScheduler.shared.submit(request) }
        catch { NSLog("BackgroundSync: enrichment submit failed: %@", error.localizedDescription) }
    }

    private static func handleEnrichment(_ task: BGProcessingTask) {
        scheduleEnrichment()
        let completion = CompletionGuard(task)
        let work = Task { @MainActor in
            await runEnrichment()
            completion.complete(true)
        }
        task.expirationHandler = {
            work.cancel()
            completion.complete(false)
        }
    }

    /// One enrichment run, when the constraints allow it.
    @MainActor
    static func runEnrichment() async {
        guard await enrichmentAllowed() else {
            NSLog("BackgroundSync: enrichment skipped (metered network or low battery)")
            return
        }
        // A background launch has no window: the bootstrap loads the
        // plugins the resolvers run on (as a CarPlay launch does).
        await AppBootstrap.shared.start()
        let visited = (try? await IosContainer.companion.shared.runCrossResolverEnrichment())?.intValue ?? 0
        NSLog("BackgroundSync: enrichment visited %d track(s)", visited)
    }

    /// Android's constraints: an unmetered network and a battery that isn't low.
    @MainActor
    private static func enrichmentAllowed() async -> Bool {
        if ProcessInfo.processInfo.isLowPowerModeEnabled { return false }
        let device = UIDevice.current
        let wasMonitoring = device.isBatteryMonitoringEnabled
        device.isBatteryMonitoringEnabled = true
        let level = device.batteryLevel, state = device.batteryState
        device.isBatteryMonitoringEnabled = wasMonitoring
        if level >= 0 && level < 0.2 && state == .unplugged { return false }
        return await currentPathIsUnmetered()
    }

    /// One reading of the network path: satisfied, not expensive, not constrained.
    private static func currentPathIsUnmetered() async -> Bool {
        await withCheckedContinuation { cont in
            let monitor = NWPathMonitor()
            let once = OnceFlag()
            monitor.pathUpdateHandler = { path in
                guard once.claim() else { return }
                monitor.cancel()
                cont.resume(returning: path.status == .satisfied && !path.isExpensive && !path.isConstrained)
            }
            monitor.start(queue: DispatchQueue(label: "com.parachord.enrichment-path"))
        }
    }

    /// Ask iOS to schedule the next run. Call on launch + when entering background.
    /// The `earliestBeginDate` is a hint; iOS decides the actual timing from usage.
    static func schedule() {
        let request = BGAppRefreshTaskRequest(identifier: taskId)
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do { try BGTaskScheduler.shared.submit(request) }
        // %@ + arg, never an interpolated format string: a '%' in the error text
        // would otherwise be read as a format specifier and crash (see Log in
        // Platform.ios.kt).
        catch { NSLog("BackgroundSync: submit failed: %@", error.localizedDescription) }
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

/// True for the first caller only.
private final class OnceFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var claimed = false
    func claim() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if claimed { return false }
        claimed = true
        return true
    }
}
