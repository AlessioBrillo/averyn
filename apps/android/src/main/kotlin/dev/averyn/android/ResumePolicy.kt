package dev.averyn.android

import dev.averyn.domain.ActivityState
import dev.averyn.tracking.ActivityMetadata
import dev.averyn.tracking.ActivityRecorder
import dev.averyn.tracking.ActivityStore
import dev.averyn.tracking.RecoveryResult
import dev.averyn.tracking.replay

/**
 * An interruption older than this is not resumed silently: whoever opens the app days later should decide
 * whether those days belong to the activity (TDD-0006 §5.1). Within it, resuming is what a runner who just
 * lost the app to the OS wants.
 */
internal const val AUTO_RESUME_MAX_GAP_MS = 30 * 60 * 1000L

internal enum class ResumeAction { RESUME_NOW, ASK_USER }

/** Wall-clock time since the activity last recorded something, and whether precise location is granted. */
internal fun resumeAction(
    gapMs: Long,
    hasPrecisePermission: Boolean,
): ResumeAction =
    if (hasPrecisePermission && gapMs <= AUTO_RESUME_MAX_GAP_MS) ResumeAction.RESUME_NOW else ResumeAction.ASK_USER

internal data class Interrupted(
    val metadata: ActivityMetadata,
    val state: ActivityState,
    /** Wall-clock millis since the last sample or event; negative if the system clock was moved back. */
    val gapMs: Long,
)

/** The interrupted activity that was active most recently, or null. */
internal fun latestInterrupted(
    store: ActivityStore,
    nowTimeMs: Long,
): Interrupted? =
    store
        .interrupted()
        .map { metadata ->
            val replayed = replay(store, metadata.activityId)
            Interrupted(metadata, replayed.lastState, nowTimeMs - replayed.lastKnownTimeMs)
        }.minByOrNull { it.gapMs }

/**
 * Finishes every interrupted activity except [keepId] at its last known time, so none stays orphaned in the
 * store while the app deals with the newest one (each is recovered by its own throwaway recorder; the app's
 * recorder serves one activity at a time). Returns how many were finished: their sync is now due.
 */
internal fun finishOlderInterrupted(
    store: ActivityStore,
    keepId: String?,
    nowElapsedRealtimeMs: Long,
    nowTimeMs: Long,
): Int {
    var finished = 0
    store.interrupted().filter { it.activityId != keepId }.forEach { metadata ->
        val recorder = ActivityRecorder(store)
        when (recorder.recover(metadata.activityId, nowElapsedRealtimeMs, nowTimeMs)) {
            is RecoveryResult.Resumed ->
                recorder.stop(recorder.lastKnownElapsedRealtimeMs, recorder.lastKnownTimeMs)
            is RecoveryResult.Completed -> {}
        }
        finished++
    }
    return finished
}
