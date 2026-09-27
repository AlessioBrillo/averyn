package dev.averyn.domain

import kotlinx.serialization.Serializable

/** Why an [ActivityState] transition happened. See TDD-0001 §5.2, F9. */
enum class Reason {
    /** The user tapped start/pause/resume/stop. */
    USER,

    /** The location provider produced its first fix after `start`. */
    GPS_READY,

    /** Location permission was revoked or downgraded mid-activity (F9). */
    PERMISSION_REVOKED,

    /** The platform location provider (GPS) was disabled mid-activity. */
    PROVIDER_DISABLED,

    /** The activity was found interrupted on launch and recovered (F4). */
    RECOVERED,

    /** An unrecoverable error moved the activity to FAILED. */
    ERROR,
}

/**
 * One state-machine transition, durably logged alongside samples so recovery and the moving/paused
 * time split (TDD-0001 §5.6) can replay exactly what happened and why.
 */
@Serializable
data class StateEvent(
    /** Monotonic millis since boot. Used for ordering and durations, same as [LocationSample.elapsedRealtimeMs]. */
    val elapsedRealtimeMs: Long,
    /** Wall-clock UTC epoch millis, for display only. */
    val timeMs: Long,
    val state: ActivityState,
    val reason: Reason,
)
