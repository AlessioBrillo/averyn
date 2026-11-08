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

/**
 * What the launch found (TDD-0006 §5.1): the newest interrupted activity and what to do with it, how many older
 * ones were finished on the way (their sync is due), and [failure] when the stored files could not be read.
 * [failure] is the exception's class name, never its message (a path or a file's content has no place in a log).
 */
class LaunchRecovery(
    val decision: ResumeDecision?,
    val finishedOlder: Int,
    val failure: String?,
)

/**
 * The one launch routine of both phones: decide on the newest interrupted activity and finish the older ones.
 *
 * Reads whole stored files, so call it off the main thread. It never throws: a file that cannot be read (a full
 * or failing disk, a corrupt first line) would otherwise end the process from Swift, where Kotlin exceptions are
 * not catchable. The activity files stay on disk; the failure is reported so the caller can say so.
 */
fun ActivityStore.planLaunchRecovery(
    nowElapsedRealtimeMs: Long,
    nowTimeMs: Long,
    hasPrecisePermission: Boolean,
): LaunchRecovery =
    try {
        val decision = resumeDecision(nowTimeMs, hasPrecisePermission)
        val finished =
            if (decision ==
                null
            ) {
                0
            } else {
                finishOlderInterrupted(decision.metadata.activityId, nowElapsedRealtimeMs, nowTimeMs)
            }
        LaunchRecovery(decision, finished, null)
    } catch (e: Exception) {
        LaunchRecovery(null, 0, e::class.simpleName ?: "Exception")
    }
