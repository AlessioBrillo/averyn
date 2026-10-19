package dev.averyn.backend.imports

import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Reason
import dev.averyn.domain.Sport
import dev.averyn.domain.StateEvent
import dev.averyn.tracking.ActivityMetadata
import dev.averyn.tracking.ActivityRecord
import dev.averyn.tracking.IMPORT_FORMAT_VERSION

/**
 * Turns a parsed track into the records a device would have written for it (TDD-0008 §5.3).
 *
 * The canonical file is the same format the recorder writes, so the same replay computes the same metrics: a
 * `<trkseg>` boundary becomes a pause, the samples carry no accuracy (a GPX track has none) and the whole
 * activity is `COMPLETED`, because an imported file is a finished recording by definition.
 */
object GpxCanonicalizer {
    /** The metadata of the imported activity: it starts at its first point and is format version 3. */
    fun metadata(
        activityId: String,
        sport: Sport,
        track: GpxTrack,
    ): ActivityMetadata =
        ActivityMetadata(
            activityId = activityId,
            sport = sport,
            startedAtMs = track.startedAtMs,
            formatVersion = IMPORT_FORMAT_VERSION,
        )

    /** Every record of the canonical file, in order. */
    fun records(track: GpxTrack): List<ActivityRecord> {
        val startedAtMs = track.startedAtMs
        val records = mutableListOf<ActivityRecord>()
        var previousSegmentEndMs: Long? = null

        for (segment in track.segments) {
            val first = segment.first()
            if (previousSegmentEndMs == null) {
                // The start of an activity: preparing, then recording, then the first point.
                records += event(startedAtMs, startedAtMs, ActivityState.PREPARING)
                records += event(startedAtMs, startedAtMs, ActivityState.RECORDING)
            } else {
                // The boundary between two segments is a pause: the gap is time the user was not recording, so
                // it is not a straight line through the track and it is not distance.
                records += event(previousSegmentEndMs - startedAtMs, previousSegmentEndMs, ActivityState.PAUSED)
                records += event(first.timeMs - startedAtMs, first.timeMs, ActivityState.RECORDING)
            }
            for (point in segment) {
                records +=
                    ActivityRecord.Sample(
                        LocationSample(
                            timeMs = point.timeMs,
                            elapsedRealtimeMs = point.timeMs - startedAtMs,
                            latitude = point.latitude,
                            longitude = point.longitude,
                            // A GPX track carries no accuracy: the sample says "not measured" instead of a number.
                            horizontalAccuracyM = null,
                            altitudeM = point.altitudeM,
                        ),
                    )
            }
            previousSegmentEndMs = segment.last().timeMs
        }

        val lastTimeMs = track.lastTimeMs
        records += event(lastTimeMs - startedAtMs, lastTimeMs, ActivityState.STOPPING)
        records += event(lastTimeMs - startedAtMs, lastTimeMs, ActivityState.COMPLETED)
        return records
    }

    private fun event(
        elapsedRealtimeMs: Long,
        timeMs: Long,
        state: ActivityState,
    ): ActivityRecord.Event =
        ActivityRecord.Event(
            StateEvent(
                elapsedRealtimeMs = elapsedRealtimeMs,
                timeMs = timeMs,
                state = state,
                reason = Reason.IMPORTED,
            ),
        )
}
