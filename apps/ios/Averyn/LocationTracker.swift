import CoreLocation
import Foundation
import Shared

/// Thin native adapter (TDD-0001 §5.1): converts CoreLocation callbacks into `LocationSample`s and drives
/// the shared `ActivityRecorder`. All tracking logic — state machine, quality, metrics, storage — lives in
/// `shared/`; this class only talks to `CLLocationManager`.
final class LocationTracker: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private let recorder: ActivityRecorder
    /// Keeps the app running (and allowed to start location updates) while it is in the background (iOS 17).
    private var session: CLBackgroundActivitySession?

    /// Called when something the user must know happens (permission lost, accuracy reduced). Any queue.
    var onIssue: ((String) -> Void)?

    init(recorder: ActivityRecorder) {
        self.recorder = recorder
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = kCLDistanceFilterNone
        manager.activityType = .fitness
        // Background modes (TDD-0001 §5.5): keep recording with the screen locked / app backgrounded.
        manager.allowsBackgroundLocationUpdates = true
        manager.pausesLocationUpdatesAutomatically = false
        manager.showsBackgroundLocationIndicator = true
    }

    /// Recording can continue with the screen locked and be relaunched by the system: "Always" and precise.
    var canRecordInBackground: Bool {
        manager.authorizationStatus == .authorizedAlways && manager.accuracyAuthorization == .fullAccuracy
    }

    /// A short, fixed vocabulary for the diagnostics report (never free text).
    var permissionLabel: String {
        let precise = manager.accuracyAuthorization == .fullAccuracy
        switch manager.authorizationStatus {
        case .authorizedAlways: return precise ? "ALWAYS_PRECISE" : "ALWAYS_APPROXIMATE"
        case .authorizedWhenInUse: return precise ? "WHEN_IN_USE_PRECISE" : "WHEN_IN_USE_APPROXIMATE"
        case .denied, .restricted: return "DENIED"
        default: return "NOT_DETERMINED"
        }
    }

    func start() {
        manager.requestAlwaysAuthorization()
        // The background session and significant-change monitoring are what let iOS bring a terminated app back
        // to continue recording (TDD-0006 §5.1): a relaunch runs `AppModel.resumeInterruptedIfRecent()`.
        session = CLBackgroundActivitySession()
        manager.startUpdatingLocation()
        manager.startMonitoringSignificantLocationChanges()
    }

    func stop() {
        manager.stopMonitoringSignificantLocationChanges()
        manager.stopUpdatingLocation()
        session?.invalidate()
        session = nil
    }

    // F9: a permission change mid-activity is surfaced, not a silent stop. No auto-resume on re-grant —
    // the user resumes explicitly, which is less surprising than the app deciding on its own. A change while
    // still PREPARING (no fix yet) has no "resume" to offer, so it fails outright instead of pausing —
    // otherwise the activity would be stuck showing "Waiting for GPS…" forever.
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let denied = manager.authorizationStatus == .denied || manager.authorizationStatus == .restricted
        let reduced = manager.accuracyAuthorization == .reducedAccuracy
        guard denied || reduced else { return }
        let reason: Reason = denied ? .permissionRevoked : .permissionDowngraded
        switch recorder.currentState {
        case .recording:
            recorder.pause(elapsedRealtimeMs: nowElapsedRealtimeMs(), timeMs: nowTimeMs(), reason: reason)
        case .preparing:
            recorder.fail(elapsedRealtimeMs: nowElapsedRealtimeMs(), timeMs: nowTimeMs(), reason: reason)
            // No fix will ever arrive: stop CoreLocation so the blue indicator and GPS turn off.
            stop()
        default:
            return
        }
        onIssue?(denied
            ? "Location permission was removed: the activity is paused."
            : "Precise location was turned off: the activity is paused. Turn Precise Location on to continue.")
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        for location in locations {
            if recorder.currentState == .preparing {
                recorder.gpsReady(elapsedRealtimeMs: nowElapsedRealtimeMs(), timeMs: nowTimeMs())
            }
            // Always forward the fix: ActivityRecorder.onSample stores it in every non-terminal state (F5)
            // and only feeds live metrics while RECORDING — gating the call itself here would silently lose
            // every fix that arrives while PAUSED instead of just excluding it from metrics.
            guard let sample = location.toLocationSample() else {
                // A fix the sample model rejects (NaN, out-of-range): counted, never a crash (C2).
                recorder.recordDroppedInput(reason: .invalidSample)
                continue
            }
            recorder.onSample(sample: sample)
        }
    }
}

private extension CLLocation {
    /// Monotonic time of the fix: "now" on the sleep-inclusive clock minus how old the fix already is (TDD-0001
    /// §5.3). CoreLocation only gives a wall-clock `timestamp` per fix, not a monotonic one like Android's
    /// `elapsedRealtimeNanos`, so this is the closest equivalent.
    /// Returns nil for fixes the sample model cannot represent (out-of-range lat/lon): counted as
    // INVALID_SAMPLE, never allowed to crash the app (Kotlin `require` on LocationSample would otherwise
    // terminate the process when it reaches Swift).
    func toLocationSample() -> LocationSample? {
        let lat = self.coordinate.latitude
        let lon = self.coordinate.longitude
        guard abs(lat) <= 90, abs(lon) <= 180 else { return nil }
        let ageS = max(0, Date().timeIntervalSince(self.timestamp))
        let elapsedRealtimeMs = nowElapsedRealtimeMs() - Int64(ageS * 1000)
        let timeMs = Int64(self.timestamp.timeIntervalSince1970 * 1000)
        return LocationSample(
            timeMs: timeMs,
            elapsedRealtimeMs: elapsedRealtimeMs,
            latitude: lat,
            longitude: lon,
            // Never trust a negative/absent accuracy as "perfect": treat it as very poor instead.
            horizontalAccuracyM: self.horizontalAccuracy >= 0 ? self.horizontalAccuracy : 9_999.0,
            altitudeM: self.verticalAccuracy >= 0 ? KotlinDouble(double: self.altitude) : nil,
            speedMps: self.speed >= 0 ? KotlinDouble(double: self.speed) : nil,
            bearingDeg: self.course >= 0 ? KotlinDouble(double: self.course) : nil
        )
    }
}
