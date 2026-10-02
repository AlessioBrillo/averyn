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
    val diagnostics: DiagnosticsReport,
)

/**
 * What [ActivityRecorder.recover] found for one interrupted activity (TDD-0001 F4).
 *
 * A `sealed class`, not a `sealed interface`: Kotlin/Native's Objective-C/Swift export turns a sealed
 * interface into a Swift `protocol`, whose nested implementations aren't reachable as `Foo.Bar` from Swift
 * (a `sealed class` exports as a real base class with genuinely nested subclasses instead).
 */
sealed class RecoveryResult {
    /** Was RECORDING or PAUSED: the recorder is live again, ready for the user to resume/pause/stop it. */
    data class Resumed(
        val activityId: String,
        val sport: Sport,
        val snapshot: LiveSnapshot,
        /** Corrupt/truncated lines skipped during replay (F5: counted, never silently dropped). */
        val droppedRecordCount: Int,
    ) : RecoveryResult()

    /** Was STOPPING (killed mid-finalize): finalizing is a pure computation, so it's just re-run now. */
    data class Completed(
        val activity: CompletedActivity,
        /** Corrupt/truncated lines skipped during replay (F5: counted, never silently dropped). */
        val droppedRecordCount: Int,
    ) : RecoveryResult()
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

    /** Native adapter hook: returns the device's current battery percent (0–100), or null if unknown. */
    var batteryPercent: (() -> Int?)? = null

    private var state: ActivityState = ActivityState.IDLE
    private var activityId: String? = null
    private var sport: Sport? = null
    private var startedAtMs: Long = 0
    private var qualityAssessor = QualityAssessor()
    private var qualityAccumulator = QualityReportAccumulator()
    private var metrics = ActivityMetrics()

    // Diagnostics counters (F8/diagnostics-v1): reset per activity in start()/recover() so a reused recorder
    // doesn't carry a previous activity's numbers into the next one's report.
    private var totalSamples = 0
    private var recordingSamples = 0
    private val recordLosses = mutableMapOf<RecordLossReason, Int>()
    private var recoveries = 0
    private var batteryStartPercent: Int? = null
    private var batteryEndPercent: Int? = null
    private var batteryReadingCount = 0

    private fun resetDiagnosticsCounters() {
        totalSamples = 0
        recordingSamples = 0
        recordLosses.clear()
        recoveries = 0
        batteryStartPercent = null
        batteryEndPercent = null
        batteryReadingCount = 0
    }

    private fun trackBattery(percent: Int?) {
        if (percent == null) return
        if (batteryStartPercent == null) batteryStartPercent = percent
        batteryEndPercent = percent
        batteryReadingCount++
    }

    val currentState: ActivityState get() = state

    /**
     * The elapsed/wall-clock time of the last thing that actually happened to this activity — a sample, a
     * pause/resume, or (after [recover]) the last pre-crash record. Unlike "now", this never jumps forward
     * across an app-relaunch gap: use it (not the caller's own clock) to finalize a recovered activity that
     * the user chooses to finish without resuming live tracking.
     */
    var lastKnownElapsedRealtimeMs: Long = 0
        private set
    var lastKnownTimeMs: Long = 0
        private set

    private fun trackLastKnown(
        elapsedRealtimeMs: Long,
        timeMs: Long,
    ) {
        lastKnownElapsedRealtimeMs = elapsedRealtimeMs
        lastKnownTimeMs = timeMs
    }

    /** Current numbers, callable anytime (not just from [listener]) — e.g. right after [start] or [recover]. */
    fun snapshot(): LiveSnapshot = LiveSnapshot(state, metrics.snapshot(), qualityAccumulator.build())

    fun start(
        activityId: String,
        sport: Sport,
        elapsedRealtimeMs: Long,
        timeMs: Long,
    ) {
        // One recorder serves many activities (the apps keep one for the process lifetime): a finished one
        // starts over, and each activity gets its own quality/metrics accumulators.
        if (state.isTerminal) state = ActivityState.IDLE
        state = state.transitionTo(ActivityState.PREPARING)
        this.activityId = activityId
        this.sport = sport
        this.startedAtMs = timeMs
        qualityAssessor = QualityAssessor()
        qualityAccumulator = QualityReportAccumulator()
        metrics = ActivityMetrics()
        resetDiagnosticsCounters()
        trackLastKnown(elapsedRealtimeMs, timeMs)
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
        trackLastKnown(elapsedRealtimeMs, timeMs)
        logEvent(elapsedRealtimeMs, timeMs, ActivityState.RECORDING, Reason.GPS_READY)
        emit()
    }

    /**
     * Every raw sample, in every non-terminal state (PREPARING/RECORDING/PAUSED/STOPPING) — stored durably
     * before use (F3), never dropped (F5). The native adapter should call this unconditionally for every
     * fix it receives; gating the call itself on `currentState == RECORDING` would silently lose samples
     * that arrive while PAUSED instead of just excluding them from live metrics, which is what this does.
     */
    fun onSample(sample: LocationSample) {
        val id = activityId ?: return
        if (state.isTerminal) return // a stray late callback after stop()/fail(): nothing to append to anymore
        store.appendSample(id, sample)
        totalSamples++
        trackLastKnown(sample.elapsedRealtimeMs, sample.timeMs)
        if (state == ActivityState.RECORDING) {
            recordingSamples++
            ingestSample(sample, qualityAssessor, qualityAccumulator, metrics)
        }
        emit()
    }

    fun pause(
        elapsedRealtimeMs: Long,
        timeMs: Long,
        reason: Reason = Reason.USER,
    ) {
        state = state.transitionTo(ActivityState.PAUSED)
        metrics.onPause(elapsedRealtimeMs)
        trackLastKnown(elapsedRealtimeMs, timeMs)
        logEvent(elapsedRealtimeMs, timeMs, ActivityState.PAUSED, reason)
        emit()
    }

    fun resume(
        elapsedRealtimeMs: Long,
        timeMs: Long,
    ) {
        state = state.transitionTo(ActivityState.RECORDING)
        metrics.onResume(elapsedRealtimeMs)
        trackLastKnown(elapsedRealtimeMs, timeMs)
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
        trackLastKnown(elapsedRealtimeMs, timeMs)
        state = state.transitionTo(ActivityState.COMPLETED)
        logEvent(elapsedRealtimeMs, timeMs, ActivityState.COMPLETED, Reason.USER)
        val completed = buildCompletedActivity()
        emit()
        return completed
    }

    fun fail(
        elapsedRealtimeMs: Long,
        timeMs: Long,
        reason: Reason,
    ) {
        state = state.transitionTo(ActivityState.FAILED)
        trackLastKnown(elapsedRealtimeMs, timeMs)
        logEvent(elapsedRealtimeMs, timeMs, ActivityState.FAILED, reason)
        emit()
        // ponytail: no DiagnosticsReport for a FAILED activity (there's no CompletedActivity to attach it to)
        // even though all its counters exist here. A fail-time report is only needed once the history/detail
        // view (post-MVP-0) wants to show diagnostics for a failed run; add a buildDiagnostics() call here then.
    }

    private fun logEvent(
        elapsedRealtimeMs: Long,
        timeMs: Long,
        newState: ActivityState,
        reason: Reason,
    ) {
        val id = activityId ?: return
        val battery = batteryPercent?.invoke()
        trackBattery(battery)
        store.appendEvent(id, StateEvent(elapsedRealtimeMs, timeMs, newState, reason, battery))
    }

    private fun emit() {
        listener?.invoke(snapshot())
    }

    private fun buildCompletedActivity(): CompletedActivity =
        CompletedActivity(activityId!!, sport!!, metrics.snapshot(), qualityAccumulator.build(), buildDiagnostics())

    private fun buildDiagnostics(): DiagnosticsReport {
        val snap = metrics.snapshot()
        val hours = snap.elapsedMs / 3_600_000.0
        val drainPerHour =
            if (batteryReadingCount < 2 || hours <= 0.0) {
                null
            } else {
                (batteryStartPercent!! - batteryEndPercent!!) / hours
            }
        return DiagnosticsReport(
            formatVersion = DIAGNOSTICS_FORMAT_VERSION,
            activityId = activityId!!,
            sport = sport!!,
            startedAtMs = startedAtMs,
            totalSamples = totalSamples,
            recordingSamples = recordingSamples,
            recordLosses = recordLosses.toMap(),
            recoveries = recoveries,
            distanceM = snap.distanceM,
            elapsedMs = snap.elapsedMs,
            movingMs = snap.movingMs,
            pausedMs = snap.pausedMs,
            quality = qualityAccumulator.build(),
            batteryStartPercent = batteryStartPercent,
            batteryEndPercent = batteryEndPercent,
            batteryDrainPercentPerHour = drainPerHour,
        )
    }

    /**
     * Rebuilds this recorder from a stored activity (F4) with [replay] (streaming, bounded memory), after a
     * kill/crash/reboot, and logs a RECOVERED event at [nowElapsedRealtimeMs]. Call on a fresh
     * [ActivityRecorder]; [store]`.interrupted()` finds which activity ids need this.
     */
    fun recover(
        activityId: String,
        nowElapsedRealtimeMs: Long,
        nowTimeMs: Long,
    ): RecoveryResult {
        val replayed = replay(store, activityId)
        val metadata = replayed.metadata
        this.activityId = activityId
        this.sport = metadata.sport
        this.startedAtMs = metadata.startedAtMs
        metrics = replayed.metrics
        qualityAssessor = replayed.assessor
        qualityAccumulator = replayed.quality
        resetDiagnosticsCounters()
        totalSamples = replayed.totalSamples
        recordingSamples = replayed.recordingSamples
        recordLosses.putAll(replayed.recordLosses)
        recoveries = replayed.recoveries
        batteryStartPercent = replayed.batteryStartPercent
        batteryEndPercent = replayed.batteryEndPercent
        batteryReadingCount = replayed.batteryReadingCount
        trackLastKnown(replayed.lastKnownElapsedRealtimeMs, replayed.lastKnownTimeMs)
        val lastState = replayed.lastState
        val droppedRecordCount = replayed.recordLosses.values.sum()
        state = lastState
        recoveries++ // this recovery itself; past ones were counted from the replayed RECOVERED events
        logEvent(nowElapsedRealtimeMs, nowTimeMs, lastState, Reason.RECOVERED)

        return if (lastState == ActivityState.STOPPING) {
            state = state.transitionTo(ActivityState.COMPLETED)
            logEvent(nowElapsedRealtimeMs, nowTimeMs, ActivityState.COMPLETED, Reason.RECOVERED)
            // Finalize at the last known activity time, not "now" (relaunch can happen long after the kill).
            metrics.finish(lastKnownElapsedRealtimeMs)
            RecoveryResult.Completed(
                buildCompletedActivity(),
                droppedRecordCount,
            )
        } else {
            RecoveryResult.Resumed(
                activityId,
                metadata.sport,
                LiveSnapshot(state, metrics.snapshot(), qualityAccumulator.build()),
                droppedRecordCount,
            )
        }
    }
}
