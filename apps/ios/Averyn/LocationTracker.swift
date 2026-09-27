import CoreLocation
import Foundation
import Shared

/// Thin native adapter (TDD-0001 §5.1): converts CoreLocation callbacks into `LocationSample`s and drives
/// the shared `ActivityRecorder`. All tracking logic — state machine, quality, metrics, storage — lives in
/// `shared/`; this class only talks to `CLLocationManager`.
final class LocationTracker: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    private let recorder: ActivityRecorder

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

    func start() {
        manager.requestAlwaysAuthorization()
        manager.startUpdatingLocation()
    }

    func stop() {
        manager.stopUpdatingLocation()
    }

    // F9: a permission change mid-activity is surfaced, not a silent stop. No auto-resume on re-grant —
    // the user resumes explicitly, which is less surprising than the app deciding on its own. A denial
    // while still PREPARING (no fix yet) has no "resume" to offer, so it fails outright instead of
    // pausing — otherwise the activity would be stuck showing "Waiting for GPS…" forever.
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .denied, .restricted:
            switch recorder.currentState {
            case .recording:
                recorder.pause(elapsedRealtimeMs: nowElapsedRealtimeMs(), timeMs: nowTimeMs(), reason: .permissionRevoked)
            case .preparing:
                recorder.fail(elapsedRealtimeMs: nowElapsedRealtimeMs(), timeMs: nowTimeMs(), reason: .permissionRevoked)
            default:
                break
            }
        default:
            break
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        for location in locations {
            if recorder.currentState == .preparing {
                recorder.gpsReady(elapsedRealtimeMs: nowElapsedRealtimeMs(), timeMs: nowTimeMs())
            }
            // Always forward the fix: ActivityRecorder.onSample stores it in every non-terminal state (F5)
            // and only feeds live metrics while RECORDING — gating the call itself here would silently lose
            // every fix that arrives while PAUSED instead of just excluding it from metrics.
            recorder.onSample(sample: location.toLocationSample())
        }
    }
}

private func nowElapsedRealtimeMs() -> Int64 {
    Int64(ProcessInfo.processInfo.systemUptime * 1000)
}

private func nowTimeMs() -> Int64 {
    Int64(Date().timeIntervalSince1970 * 1000)
}

private extension CLLocation {
    /// Monotonic time from `ProcessInfo.systemUptime`, offset by how old this fix already is (TDD-0001
    /// §5.3): CoreLocation only gives a wall-clock `timestamp` per fix, not a monotonic one like Android's
    /// `elapsedRealtimeNanos`, so this is the closest equivalent.
    func toLocationSample() -> LocationSample {
        let ageS = Date().timeIntervalSince(self.timestamp)
        let elapsedRealtimeMs = Int64((ProcessInfo.processInfo.systemUptime - ageS) * 1000)
        let timeMs = Int64(self.timestamp.timeIntervalSince1970 * 1000)
        return LocationSample(
            timeMs: timeMs,
            elapsedRealtimeMs: elapsedRealtimeMs,
            latitude: self.coordinate.latitude,
            longitude: self.coordinate.longitude,
            // Never trust a negative/absent accuracy as "perfect": treat it as very poor instead.
            horizontalAccuracyM: self.horizontalAccuracy >= 0 ? self.horizontalAccuracy : 9_999.0,
            altitudeM: self.verticalAccuracy >= 0 ? KotlinDouble(double: self.altitude) : nil,
            speedMps: self.speed >= 0 ? KotlinDouble(double: self.speed) : nil,
            bearingDeg: self.course >= 0 ? KotlinDouble(double: self.course) : nil
        )
    }
}
