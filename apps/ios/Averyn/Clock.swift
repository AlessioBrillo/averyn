import Foundation

/// Monotonic milliseconds since boot **including the time the device spends asleep**.
///
/// `ProcessInfo.systemUptime` stops while the device sleeps, so with the screen locked for hours every
/// duration measured with it came out too short and every implied speed too high (TDD-0006 §5.2).
/// `CLOCK_MONOTONIC_RAW` keeps counting through sleep and restarts at boot, which is the same shape as
/// Android's `elapsedRealtime` that the shared recorder expects.
func nowElapsedRealtimeMs() -> Int64 {
    Int64(clock_gettime_nsec_np(CLOCK_MONOTONIC_RAW) / 1_000_000)
}

/// Wall-clock UTC epoch milliseconds, for display and for measuring an interruption; never for ordering.
func nowTimeMs() -> Int64 {
    Int64(Date().timeIntervalSince1970 * 1000)
}
