package dev.averyn.tracking

import dev.averyn.domain.ActivityState

/**
 * An interruption older than this is not resumed silently: whoever opens the app days later should decide
 * whether those days belong to the activity (TDD-0006 §5.1). Within it, resuming is what a runner who just
 * lost the app to the OS wants.
 */
const val AUTO_RESUME_MAX_GAP_MS = 30L * 60 * 1000

enum class ResumeAction { RESUME_NOW, ASK_USER }

/** The newest interrupted activity and what to do with it. */
data class ResumeDecision(
    val metadata: ActivityMetadata,
    val state: ActivityState,
    /** Wall-clock millis since the last sample or event; negative if the system clock was moved back. */
    val gapMs: Long,
    val action: ResumeAction,
)

/** Wall-clock time since the activity last recorded something, and whether precise location is available. */
internal fun resumeAction(
    gapMs: Long,
    hasPrecisePermission: Boolean,
): ResumeAction =
    if (hasPrecisePermission && gapMs <= AUTO_RESUME_MAX_GAP_MS) ResumeAction.RESUME_NOW else ResumeAction.ASK_USER

/**
 * What to do with the interrupted activity that was active most recently, or null when none is interrupted.
 * One policy for both platforms (TDD-0006 §5.1): a kill during STOPPING is always finalized (no GPS involved);
 * anything else resumes by itself only when recent and [hasPrecisePermission], otherwise the user is asked.
 * Extension functions on [ActivityStore] so Swift reaches them as `store.resumeDecision(...)`.
 */
fun ActivityStore.resumeDecision(
    nowTimeMs: Long,
    hasPrecisePermission: Boolean,
): ResumeDecision? {
    val newest =
        interrupted()
            .map { metadata ->
                val replayed = replay(this, metadata.activityId)
                Triple(metadata, replayed.lastState, nowTimeMs - replayed.lastKnownTimeMs)
            }.minByOrNull { it.third } ?: return null
    val (metadata, state, gapMs) = newest
    val action =
        if (state == ActivityState.STOPPING) ResumeAction.RESUME_NOW else resumeAction(gapMs, hasPrecisePermission)
    return ResumeDecision(metadata, state, gapMs, action)
}

/**
 * Finishes every interrupted activity except [keepId] at its last known time, so none stays orphaned in the
 * store while the app deals with the newest one (each is recovered by its own throwaway recorder; the app's
 * recorder serves one activity at a time). Returns how many were finished: their sync is now due.
 */
fun ActivityStore.finishOlderInterrupted(
    keepId: String?,
    nowElapsedRealtimeMs: Long,
    nowTimeMs: Long,
): Int {
    var finished = 0
    interrupted().filter { it.activityId != keepId }.forEach { metadata ->
        val recorder = ActivityRecorder(this)
        when (recorder.recover(metadata.activityId, nowElapsedRealtimeMs, nowTimeMs)) {
            is RecoveryResult.Resumed ->
                recorder.stop(recorder.lastKnownElapsedRealtimeMs, recorder.lastKnownTimeMs)
            is RecoveryResult.Completed -> {}
        }
        finished++
    }
    return finished
}
