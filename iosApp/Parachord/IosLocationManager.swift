import CoreLocation

/// Thin CoreLocation wrapper for one-shot GPS city detection (#199).
///
/// Concerts only need city-level precision, so we ask for `reducedAccuracy`
/// and a single fix. The `detectViaGPS()` async entry point requests
/// When-In-Use authorization if needed, then resolves to coords or `nil` —
/// it NEVER throws. Any denial / restriction / timeout / error returns nil so
/// the caller cleanly falls back to the geoIP path.
@MainActor
final class IosLocationManager: NSObject, CLLocationManagerDelegate {

    private let manager = CLLocationManager()

    /// In-flight continuation for the current `requestLocation` / auth wait.
    /// Guarded so we resume exactly once (CheckedContinuation traps on double-resume).
    private var continuation: CheckedContinuation<(lat: Double, lon: Double)?, Never>?
    /// Set true once we've fired `requestLocation` while waiting on the auth
    /// callback, so the auth-change delegate doesn't request a second fix.
    private var didRequestFix = false
    private var timeoutTask: Task<Void, Never>?

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyReduced
    }

    /// True when When-In-Use/Always is ALREADY granted — checked WITHOUT prompting.
    /// Used to gate the silent cold-launch GPS auto-detect (Android parity): we only
    /// auto-detect when permission already exists, never popping a prompt on a browse.
    var isAuthorized: Bool {
        let s = manager.authorizationStatus
        return s == .authorizedWhenInUse || s == .authorizedAlways
    }

    /// Returns the device coordinates, or nil on denied/restricted/timeout/error.
    /// City-level accuracy; ~10s hard timeout so it can't hang the detect flow.
    func detectViaGPS() async -> (lat: Double, lon: Double)? {
        // Only one detect at a time — resolve a prior wait as nil if re-entered.
        if let pending = continuation {
            pending.resume(returning: nil)
            continuation = nil
        }
        timeoutTask?.cancel()
        didRequestFix = false

        let status = manager.authorizationStatus
        if status == .denied || status == .restricted {
            return nil
        }

        return await withCheckedContinuation { (cont: CheckedContinuation<(lat: Double, lon: Double)?, Never>) in
            self.continuation = cont

            // 10s safety timeout — CoreLocation can silently never call back.
            self.timeoutTask = Task { [weak self] in
                try? await Task.sleep(nanoseconds: 10_000_000_000)
                guard let self else { return }
                self.finish(nil)
            }

            switch status {
            case .authorizedWhenInUse, .authorizedAlways:
                self.didRequestFix = true
                self.manager.requestLocation()
            case .notDetermined:
                // Wait for the authorization callback; it fires requestLocation.
                self.manager.requestWhenInUseAuthorization()
            default:
                self.finish(nil)
            }
        }
    }

    /// Resume the continuation exactly once and tear down the timeout.
    private func finish(_ result: (lat: Double, lon: Double)?) {
        timeoutTask?.cancel()
        timeoutTask = nil
        guard let cont = continuation else { return }
        continuation = nil
        cont.resume(returning: result)
    }

    // MARK: - CLLocationManagerDelegate

    // CLLocationManager delivers delegate callbacks on the queue the manager was
    // created on — main here (this type is @MainActor, built on the main actor).
    // So the callbacks are `nonisolated` (to satisfy the protocol without a
    // main-actor-crossing warning, an error in Swift 6 mode) but immediately
    // hop back onto the main actor via assumeIsolated, which is sound because
    // we know they arrive on main.
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        MainActor.assumeIsolated {
            // Only act on the auth response while we're mid-detect and haven't yet
            // fired the fix (avoids reacting to unrelated authorization changes).
            guard continuation != nil, !didRequestFix else { return }
            switch manager.authorizationStatus {
            case .authorizedWhenInUse, .authorizedAlways:
                didRequestFix = true
                manager.requestLocation()
            case .denied, .restricted:
                finish(nil)
            default:
                break  // still .notDetermined — keep waiting for the prompt result
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        MainActor.assumeIsolated {
            guard let loc = locations.first else { finish(nil); return }
            finish((lat: loc.coordinate.latitude, lon: loc.coordinate.longitude))
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        MainActor.assumeIsolated {
            finish(nil)
        }
    }
}
