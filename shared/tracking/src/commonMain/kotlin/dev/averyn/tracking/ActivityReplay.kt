package dev.averyn.tracking

import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Reason
import dev.averyn.metrics.ActivityMetrics

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
    /** Time of the last thing that happened to the activity: a sample or a state event. */
    val lastKnownElapsedRealtimeMs: Long,
    val lastKnownTimeMs: Long,
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
 * A COMPLETED activity's metrics come back finished, exactly as `ActivityRecorder.stop` left them. An
 * unfinished one (RECORDING/PAUSED/STOPPING/FAILED) is returned as it stood; finishing it is the caller's call.
 * [onMeteredSample] sees every RECORDING-state sample that counted towards the metrics, in order.
 *
 * @throws IllegalStateException if [activityId] has no readable metadata line.
 */
fun replay(
    store: ActivityStore,
    activityId: String,
    onMeteredSample: (LocationSample) -> Unit = {},
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

    store.forEachRecord(
        activityId,
        onLoss = { reason -> losses[reason] = (losses[reason] ?: 0) + 1 },
    ) { record ->
        when (record) {
            is ActivityRecord.Sample -> {
                totalSamples++
                lastElapsedMs = record.sample.elapsedRealtimeMs
                lastTimeMs = record.sample.timeMs
                if (lastState == ActivityState.RECORDING) {
                    recordingSamples++
                    if (ingestSample(record.sample, assessor, quality, metrics)) onMeteredSample(record.sample)
                }
            }
            is ActivityRecord.Event -> {
                val event = record.event
                if (event.reason == Reason.RECOVERED) recoveries++
                event.batteryPercent?.let { percent ->
                    if (batteryStart == null) batteryStart = percent
                    batteryEnd = percent
                    batteryReadings++
                }
                when (event.state) {
                    ActivityState.PAUSED -> metrics.onPause(event.elapsedRealtimeMs)
                    ActivityState.RECORDING ->
                        if (lastState == ActivityState.PAUSED) metrics.onResume(event.elapsedRealtimeMs)
                    else -> {}
                }
                lastState = event.state
                lastElapsedMs = event.elapsedRealtimeMs
                lastTimeMs = event.timeMs
            }
        }
    }
    if (lastState == ActivityState.COMPLETED) metrics.finish(lastElapsedMs)

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
    )
}
