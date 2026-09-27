package dev.averyn.tracking

import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Reason
import dev.averyn.domain.Sport
import dev.averyn.domain.StateEvent
import dev.averyn.metrics.ActivityMetrics
import dev.averyn.metrics.ActivitySnapshot

data class LiveSnapshot(
    val state: ActivityState,
    val metrics: ActivitySnapshot,
    val quality: QualityReport,
)

data class CompletedActivity(
    val activityId: String,
    val sport: Sport,
    val metrics: ActivitySnapshot,
    val quality: QualityReport,
)

/** What [ActivityRecorder.recover] found for one interrupted activity (TDD-0001 F4). */
sealed interface RecoveryResult {
    /** Was RECORDING or PAUSED: the recorder is live again, ready for the user to resume/pause/stop it. */
    data class Resumed(
        val activityId: String,
        val sport: Sport,
        val snapshot: LiveSnapshot,
    ) : RecoveryResult

    /** Was STOPPING (killed mid-finalize): finalizing is a pure computation, so it's just re-run now. */
    data class Completed(
        val activity: CompletedActivity,
    ) : RecoveryResult
}

/**
 * Owns the [ActivityState] machine (TDD-0001 §5.2) and the durable record of one activity at a time,
 * combining [ActivityStore] (durability, F3), [QualityAssessor]/[QualityReportAccumulator] and
 * [ActivityMetrics] (live numbers). Every method takes the caller's own `elapsedRealtimeMs`/`timeMs` —
 * the recorder never reads a clock itself, so it stays pure Kotlin and deterministic for tests (TDD-0001 §4).
 */
class ActivityRecorder(
    private val store: ActivityStore,
) {
    var listener: ((LiveSnapshot) -> Unit)? = null

    private var state: ActivityState = ActivityState.IDLE
    private var activityId: String? = null
    private var sport: Sport? = null
    private val qualityAssessor = QualityAssessor()
    private val qualityAccumulator = QualityReportAccumulator()
    private val metrics = ActivityMetrics()

    val currentState: ActivityState get() = state

    /** Current numbers, callable anytime (not just from [listener]) — e.g. right after [start] or [recover]. */
    fun snapshot(): LiveSnapshot = LiveSnapshot(state, metrics.snapshot(), qualityAccumulator.build())

    fun start(
        activityId: String,
        sport: Sport,
        elapsedRealtimeMs: Long,
        timeMs: Long,
    ) {
        state = state.transitionTo(ActivityState.PREPARING)
        this.activityId = activityId
        this.sport = sport
        store.create(ActivityMetadata(activityId, sport, timeMs))
        logEvent(elapsedRealtimeMs, timeMs, ActivityState.PREPARING, Reason.USER)
        emit()
    }

    /** The native adapter calls this on the first fix after [start], or [fail] if none arrives. */
    fun gpsReady(
        elapsedRealtimeMs: Long,
        timeMs: Long,
    ) {
        state = state.transitionTo(ActivityState.RECORDING)
        logEvent(elapsedRealtimeMs, timeMs, ActivityState.RECORDING, Reason.GPS_READY)
        emit()
    }

    /** Every raw sample, in every non-terminal state; stored durably before use (F3), never dropped (F5). */
    fun onSample(sample: LocationSample) {
        val id = activityId ?: return
        store.appendSample(id, sample)
        if (state != ActivityState.RECORDING) return // stored for the record, but not fed into live metrics
        val flags = qualityAssessor.assess(sample)
        qualityAccumulator.add(sample, flags)
        if (flags.none { it.excludesFromDistance }) metrics.onSample(sample)
        emit()
    }

    fun pause(
        elapsedRealtimeMs: Long,
        timeMs: Long,
        reason: Reason = Reason.USER,
    ) {
        state = state.transitionTo(ActivityState.PAUSED)
        metrics.onPause(elapsedRealtimeMs)
        logEvent(elapsedRealtimeMs, timeMs, ActivityState.PAUSED, reason)
        emit()
    }

    fun resume(
        elapsedRealtimeMs: Long,
        timeMs: Long,
    ) {
        state = state.transitionTo(ActivityState.RECORDING)
        metrics.onResume(elapsedRealtimeMs)
        logEvent(elapsedRealtimeMs, timeMs, ActivityState.RECORDING, Reason.USER)
        emit()
    }

    /** Finalization (STOPPING → COMPLETED) is synchronous in MVP-0: no upload/processing step to wait for. */
    fun stop(
        elapsedRealtimeMs: Long,
        timeMs: Long,
    ): CompletedActivity {
        state = state.transitionTo(ActivityState.STOPPING)
        logEvent(elapsedRealtimeMs, timeMs, ActivityState.STOPPING, Reason.USER)
        metrics.finish(elapsedRealtimeMs)
        state = state.transitionTo(ActivityState.COMPLETED)
        logEvent(elapsedRealtimeMs, timeMs, ActivityState.COMPLETED, Reason.USER)
        val completed = CompletedActivity(activityId!!, sport!!, metrics.snapshot(), qualityAccumulator.build())
        emit()
        return completed
    }

    fun fail(
        elapsedRealtimeMs: Long,
        timeMs: Long,
        reason: Reason,
    ) {
        state = state.transitionTo(ActivityState.FAILED)
        logEvent(elapsedRealtimeMs, timeMs, ActivityState.FAILED, reason)
        emit()
    }

    private fun logEvent(
        elapsedRealtimeMs: Long,
        timeMs: Long,
        newState: ActivityState,
        reason: Reason,
    ) {
        val id = activityId ?: return
        store.appendEvent(id, StateEvent(elapsedRealtimeMs, timeMs, newState, reason))
    }

    private fun emit() {
        listener?.invoke(snapshot())
    }

    /**
     * Replays a stored activity's records from disk (streaming, bounded memory) to rebuild this recorder's
     * live state after a kill/crash/reboot (F4), and logs a RECOVERED event at [nowElapsedRealtimeMs]. Call
     * on a fresh [ActivityRecorder]; [store]`.interrupted()` finds which activity ids need this.
     */
    fun recover(
        activityId: String,
        nowElapsedRealtimeMs: Long,
        nowTimeMs: Long,
    ): RecoveryResult {
        val metadata =
            store.metadataOf(activityId)
                ?: error("recover() called for an activity with no stored metadata: $activityId")
        this.activityId = activityId
        this.sport = metadata.sport
        var lastState = ActivityState.IDLE
        var lastEventElapsedRealtimeMs = 0L
        store.forEachRecord(activityId) { record ->
            when (record) {
                is ActivityRecord.Sample -> {
                    if (lastState == ActivityState.RECORDING) {
                        val flags = qualityAssessor.assess(record.sample)
                        qualityAccumulator.add(record.sample, flags)
                        if (flags.none { it.excludesFromDistance }) metrics.onSample(record.sample)
                    }
                }
                is ActivityRecord.Event -> {
                    when (record.event.state) {
                        ActivityState.PAUSED -> metrics.onPause(record.event.elapsedRealtimeMs)
                        ActivityState.RECORDING ->
                            if (lastState ==
                                ActivityState.PAUSED
                            ) {
                                metrics.onResume(record.event.elapsedRealtimeMs)
                            }
                        else -> {}
                    }
                    lastState = record.event.state
                    lastEventElapsedRealtimeMs = record.event.elapsedRealtimeMs
                }
            }
        }
        state = lastState
        logEvent(nowElapsedRealtimeMs, nowTimeMs, lastState, Reason.RECOVERED)

        return if (lastState == ActivityState.STOPPING) {
            state = state.transitionTo(ActivityState.COMPLETED)
            logEvent(nowElapsedRealtimeMs, nowTimeMs, ActivityState.COMPLETED, Reason.RECOVERED)
            // Finalize at the last known activity time, not "now" (relaunch can happen long after the kill).
            metrics.finish(lastEventElapsedRealtimeMs)
            RecoveryResult.Completed(
                CompletedActivity(activityId, metadata.sport, metrics.snapshot(), qualityAccumulator.build()),
            )
        } else {
            RecoveryResult.Resumed(
                activityId,
                metadata.sport,
                LiveSnapshot(state, metrics.snapshot(), qualityAccumulator.build()),
            )
        }
    }
}
