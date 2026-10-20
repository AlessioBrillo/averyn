package dev.averyn.backend.activities

import dev.averyn.analysis.ActivityAnalysis
import dev.averyn.analysis.analyzeActivity
import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Sport
import dev.averyn.metrics.ActivitySnapshot
import dev.averyn.sensors.HeartRateSample
import dev.averyn.tracking.ActivityStore
import dev.averyn.tracking.replay

/**
 * What the replay collects for the analysis: the accepted samples of every continuous recording interval (a pause
 * is a boundary, never a straight line across it) and the heart-rate samples recorded while recording.
 */
class AnalysisInput {
    private val segments = mutableListOf<MutableList<LocationSample>>()
    private val heartRate = mutableListOf<HeartRateSample>()

    /** The accepted samples of every recording interval, in order. */
    val recordedSegments: List<List<LocationSample>> get() = segments.map { it.toList() }

    /** A new recording interval starts: what follows belongs to it. */
    fun onRecordingStart() {
        segments.add(mutableListOf())
    }

    /** An accepted sample of the current interval. */
    fun onSample(sample: LocationSample) {
        if (segments.isEmpty()) onRecordingStart()
        segments.last().add(sample)
    }

    /** A heart-rate sample: only the ones of the recording state are part of the metric. */
    fun onHeartRate(
        sample: HeartRateSample,
        state: ActivityState,
    ) {
        if (state == ActivityState.RECORDING) heartRate += sample
    }

    /** The analysis of what was collected. */
    fun build(
        sport: Sport,
        totals: ActivitySnapshot,
    ): ActivityAnalysis = analyzeActivity(sport, segments.map { it.toList() }, heartRate, totals)
}

/**
 * The analysis of a stored activity: the shared replay produces the samples, the shared analyzer the numbers
 * (TDD-0009). The device and the server therefore never disagree about an activity.
 */
object ActivityAnalyzer {
    /**
     * The analysis of the activity in the store. It throws when the file cannot be replayed: the caller decides
     * what to do with an activity it cannot read.
     */
    fun analyze(
        store: ActivityStore,
        activityId: String,
    ): ActivityAnalysis {
        val input = AnalysisInput()
        val replayed =
            replay(
                store,
                activityId,
                onMeteredSample = { sample -> input.onSample(sample) },
                onRecordingStart = { input.onRecordingStart() },
                onHeartRate = { sample, state -> input.onHeartRate(sample, state) },
            )
        return input.build(replayed.metadata.sport, replayed.metrics.snapshot())
    }
}
