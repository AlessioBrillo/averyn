package dev.averyn.backend.imports

import dev.averyn.domain.ActivityState
import dev.averyn.tracking.ActivityRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The canonical file of an imported track (TDD-0008, TDD-0013): the order of its records. */
class TrackCanonicalizerTest {
    private val start = 1_792_999_200_000L

    private fun point(second: Int) = ImportedPoint(start + second * 1000L, 45.0 + second * 0.0001, 9.0, null)

    /** A short label of each record, in order, to read the whole file at a glance. */
    private fun labels(records: List<ActivityRecord>): List<String> =
        records.map {
            when (it) {
                is ActivityRecord.Sample -> "S${(it.sample.timeMs - start) / 1000}"
                is ActivityRecord.HeartRate -> "H${(it.sample.timeMs - start) / 1000}"
                is ActivityRecord.Event ->
                    it.event.state.name
                        .take(4)
            }
        }

    @Test
    fun theHeartRateIsWrittenBetweenTheSamplesAndAReadingInAPauseIsWrittenInThePause() {
        val track =
            ImportedTrack(
                segments = listOf(listOf(point(0), point(1), point(2)), listOf(point(10), point(11))),
                ignoredExtensions = emptyMap(),
                heartRates =
                    listOf(
                        ImportedHeartRate(start, 140),
                        ImportedHeartRate(start + 2_000, 142),
                        // The strap kept reading while the timer was stopped.
                        ImportedHeartRate(start + 5_000, 90),
                        ImportedHeartRate(start + 11_000, 150),
                    ),
            )

        val written = labels(TrackCanonicalizer.records(track))

        assertEquals(
            listOf(
                "PREP",
                "RECO",
                "S0",
                "H0",
                "S1",
                "S2",
                "H2",
                "PAUS",
                "H5",
                "RECO",
                "S10",
                "S11",
                "H11",
                "STOP",
                "COMP",
            ),
            written,
        )
    }

    @Test
    fun aTrackWithoutHeartRateWritesNoHeartRateRecords() {
        val track = ImportedTrack(listOf(listOf(point(0), point(1))), emptyMap())

        assertTrue(TrackCanonicalizer.records(track).none { it is ActivityRecord.HeartRate })
    }

    @Test
    fun theElapsedTimeOfAReadingIsCountedFromTheStartOfTheActivity() {
        val track =
            ImportedTrack(
                listOf(listOf(point(0), point(3))),
                emptyMap(),
                heartRates = listOf(ImportedHeartRate(start + 3_000, 150)),
            )

        val reading =
            TrackCanonicalizer
                .records(track)
                .filterIsInstance<ActivityRecord.HeartRate>()
                .single()
                .sample

        assertEquals(3_000L, reading.elapsedRealtimeMs)
        assertEquals(150, reading.bpm)
        assertEquals(
            ActivityState.COMPLETED,
            (TrackCanonicalizer.records(track).last() as ActivityRecord.Event).event.state,
        )
    }
}
