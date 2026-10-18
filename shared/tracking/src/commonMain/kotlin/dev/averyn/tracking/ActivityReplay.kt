package dev.averyn.tracking

import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Reason
import dev.averyn.metrics.ActivityMetrics
import dev.averyn.sensors.HR_ALGORITHM_VERSION
import dev.averyn.sensors.HeartRateSample

/**
 * What [replay] rebuilt from one stored activity. The accumulators are the live objects, so a recorder can
 * adopt them and carry on (`ActivityRecorder.recover`), while a reader that only wants numbers (the backend,
 * TDD-0002 §5.4) just takes [metrics]`.snapshot()` and [quality]`.build()`.
 */
class ReplayResult internal constructor(
    val metadata: ActivityMetadata,
    /** State after the last stored EVENT; [ActivityState.IDLE] if the file has none. */
    val lastState: ActivityState,
    val metrics: ActivityMetrics,
    val quality: QualityReportAccumulator,
    internal val assessor: QualityAssessor,
    val totalSamples: Int,
    val recordingSamples: Int,
    /** Lines that couldn't be read, by reason (F5: counted, never silently dropped). */
    val recordLosses: Map<RecordLossReason, Int>,
    /** RECOVERED events already stored, i.e. how many times this activity was recovered before. */
    val recoveries: Int,
    val batteryStartPercent: Int?,
    val batteryEndPercent: Int?,
    val batteryReadingCount: Int,
    /**
     * Time of the last thing that happened to the activity: a sample or a state event (a same-state
     * `RECOVERED` marker is not one). On the continuous clock: raw values plus [clockOffsetMs].
     */
    val lastKnownElapsedRealtimeMs: Long,
    val lastKnownTimeMs: Long,
    /** Cumulative offset in force after the last stored event (TDD-0006 §5.2); 0 for a file never rebooted. */
    val clockOffsetMs: Long,
    internal val recoveryGaps: RecoveryGapTracker,
    val timeline: List<TimelineEvent>,
    /** True when at least one `HR ` record was found (diagnostics stamps `hr-v1`). */
    val hasHeartRate: Boolean = false,
)

/**
 * Feeds one RECORDING-state sample through quality assessment and metrics. The one place this happens, shared
 * by the live path (`ActivityRecorder.onSample`) and [replay] so they can never silently diverge. Returns
 * whether the sample counted towards the metrics (it isn't DUPLICATE / NON_MONOTONIC_TIME / JUMP).
 */
internal fun ingestSample(
    sample: LocationSample,
    assessor: QualityAssessor,
    quality: QualityReportAccumulator,
    metrics: ActivityMetrics,
): Boolean {
    val flags = assessor.assess(sample)
    quality.add(sample, flags)
    val metered = flags.none { it.excludesFromDistance }
    if (metered) metrics.onSample(sample)
    return metered
}

/**
 * Rebuilds an activity's state by streaming its stored records (bounded memory). **Read-only**: it never
 * writes to [store], unlike `ActivityRecorder.recover`, which logs a RECOVERED event on top of this.
 *
 * The metrics of an activity that ended (COMPLETED, or FAILED, which keeps its data) come back finished at its
 * last event: an open pause is closed there, so the paused/moving split adds up. COMPLETED matches exactly what
 * `ActivityRecorder.stop` left. An unfinished one (RECORDING/PAUSED/STOPPING) is returned as it stood;
 * finishing it is the caller's call.
 * [onMeteredSample] sees every RECORDING-state sample that counted towards the metrics, in order.
 *
 * @throws IllegalStateException if [activityId] has no readable metadata line.
 */
fun replay(
    store: ActivityStore,
    activityId: String,
    onMeteredSample: (LocationSample) -> Unit = {},
    onHeartRate: (HeartRateSample) -> Unit = {},
): ReplayResult {
    val metadata =
        store.metadataOf(activityId)
            ?: error("replay() called for an activity with no stored metadata: $activityId")
    val assessor = QualityAssessor()
    val quality = QualityReportAccumulator()
    val metrics = ActivityMetrics()
    val losses = mutableMapOf<RecordLossReason, Int>()
    var lastState = ActivityState.IDLE
    var totalSamples = 0
    var recordingSamples = 0
    var recoveries = 0
    var batteryStart: Int? = null
    var batteryEnd: Int? = null
    var batteryReadings = 0
    var lastElapsedMs = 0L
    var lastTimeMs = 0L
    var offsetMs = 0L
    var hasHeartRate = false
    val gaps = RecoveryGapTracker()
    val timeline = mutableListOf<TimelineEvent>()

    store.forEachRecord(
        activityId,
        onLoss = { reason -> losses[reason] = (losses[reason] ?: 0) + 1 },
    ) { record ->
        when (record) {
            is ActivityRecord.Sample -> {
                // The raw sample stays as stored; only what is fed to the metrics is moved onto the
                // continuous clock (TDD-0006 §5.2).
                val sample = record.sample.onClock(offsetMs)
                totalSamples++
                gaps.onSample(sample.timeMs)
                lastElapsedMs = sample.elapsedRealtimeMs
                lastTimeMs = sample.timeMs
                if (lastState == ActivityState.RECORDING) {
                    recordingSamples++
                    if (ingestSample(sample, assessor, quality, metrics)) onMeteredSample(sample)
                }
            }
            is ActivityRecord.Event -> {
                val event = record.event
                val recovered = event.reason == Reason.RECOVERED
                val previousOffsetMs = offsetMs
                if (recovered) offsetMs = event.clockOffsetMs
                // A recovery also finalizes a killed STOPPING as COMPLETED(RECOVERED): one recovery, two events.
                if (recovered && event.state != ActivityState.COMPLETED) {
                    recoveries++
                    gaps.onRecovered(event.timeMs, rebootDetected = offsetMs != previousOffsetMs)
                }
                val elapsedMs = event.elapsedRealtimeMs + offsetMs
                event.batteryPercent?.let { percent ->
                    if (batteryStart == null) batteryStart = percent
                    batteryEnd = percent
                    batteryReadings++
                }
                // A RECOVERED event that repeats the state is a marker: the activity did not change state,
                // so it neither opens a pause nor moves "the last thing that happened" to the relaunch time.
                val marker = recovered && event.state == lastState
                if (!marker) {
                    when (event.state) {
                        ActivityState.PAUSED -> metrics.onPause(elapsedMs)
                        ActivityState.RECORDING -> if (lastState == ActivityState.PAUSED) metrics.onResume(elapsedMs)
                        else -> {}
                    }
                    lastElapsedMs = elapsedMs
                    lastTimeMs = event.timeMs
                }
                lastState = event.state
                timeline += TimelineEvent(elapsedMs, event.timeMs, event.state, event.reason)
            }
            is ActivityRecord.HeartRate -> {
                // Heart-rate records don't affect GPS state/metrics; they're reported to the caller
                // (backend computes hr-v1 from them, same as GPS samples from onMeteredSample).
                onHeartRate(record.sample)
                hasHeartRate = true
            }
        }
    }
    if (lastState == ActivityState.COMPLETED || lastState == ActivityState.FAILED) metrics.finish(lastElapsedMs)

    return ReplayResult(
        metadata = metadata,
        lastState = lastState,
        metrics = metrics,
        quality = quality,
        assessor = assessor,
        totalSamples = totalSamples,
        recordingSamples = recordingSamples,
        recordLosses = losses.toMap(),
        recoveries = recoveries,
        batteryStartPercent = batteryStart,
        batteryEndPercent = batteryEnd,
        batteryReadingCount = batteryReadings,
        lastKnownElapsedRealtimeMs = lastElapsedMs,
        lastKnownTimeMs = lastTimeMs,
        clockOffsetMs = offsetMs,
        recoveryGaps = gaps,
        timeline = timeline.toList(),
        hasHeartRate = hasHeartRate,
    )
}

/**
 * The diagnostics report of a stored activity, rebuilt by [replay] (what a history screen exports for an
 * activity that is no longer the one just recorded). Null when the file has no readable metadata. Inputs the
 * recorder dropped before storing (`droppedInputs`) are not in the file, so that map is empty here.
 */
fun ActivityStore.diagnosticsOf(activityId: String): DiagnosticsReport? {
    metadataOf(activityId) ?: return null
    val replayed = replay(this, activityId)
    return diagnosticsReport(
        activityId = activityId,
        sport = replayed.metadata.sport,
        startedAtMs = replayed.metadata.startedAtMs,
        totalSamples = replayed.totalSamples,
        recordingSamples = replayed.recordingSamples,
        recordLosses = replayed.recordLosses,
        recoveries = replayed.recoveries,
        metrics = replayed.metrics.snapshot(),
        quality = replayed.quality.build(),
        batteryStartPercent = replayed.batteryStartPercent,
        batteryEndPercent = replayed.batteryEndPercent,
        batteryReadingCount = replayed.batteryReadingCount,
        droppedInputs = emptyMap(),
        recoveryGaps = replayed.recoveryGaps.snapshot(),
        events = replayed.timeline,
        endedAtMs = replayed.lastKnownTimeMs,
        heartRate = if (replayed.hasHeartRate) HR_ALGORITHM_VERSION else null,
    )
}

/** [this] with its monotonic time moved onto the continuous clock; the same instance when nothing to add. */
internal fun LocationSample.onClock(offsetMs: Long): LocationSample =
    if (offsetMs == 0L) this else copy(elapsedRealtimeMs = elapsedRealtimeMs + offsetMs)
