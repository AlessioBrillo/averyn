package dev.averyn.tracking

/** How long the phone waits for a first fix before it says so (TDD-0006 §5.7). */
const val PREPARING_NOTICE_MS = 3 * 60_000L

/**
 * True when an activity has been PREPARING (waiting for its first GPS fix) for [thresholdMs] or more.
 * A notice for the screen, never a reason to give up: the user can cancel, and a late fix still starts the activity.
 * [startedMs] is on the same clock as [nowMs]; 0 means "unknown" and never triggers.
 */
fun preparingTooLong(
    nowMs: Long,
    startedMs: Long,
    thresholdMs: Long = PREPARING_NOTICE_MS,
): Boolean = startedMs > 0 && nowMs - startedMs >= thresholdMs
