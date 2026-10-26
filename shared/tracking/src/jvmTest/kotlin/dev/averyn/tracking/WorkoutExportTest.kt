package dev.averyn.tracking

import dev.averyn.domain.LocationSample
import dev.averyn.domain.Sport
import dev.averyn.sensors.HeartRateSample
import kotlinx.io.files.Path
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The workout a health platform is given (TDD-0014): what is in it and what is deliberately not. */
class WorkoutExportTest {
    private lateinit var dir: java.nio.file.Path
    private lateinit var store: ActivityStore

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("averyn-workout-test")
        store = ActivityStore(Path(dir.toString()))
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private val step = 100.0 / 111_195.08
    private val wall = 1_700_000_000_000L

    private fun fix(
        elapsedMs: Long,
        steps: Int,
        accuracyM: Double = 5.0,
        altitudeM: Double? = 120.0,
    ) = LocationSample(wall + elapsedMs, elapsedMs, steps * step, 9.0, accuracyM, altitudeM = altitudeM)

    private fun hr(
        elapsedMs: Long,
        bpm: Int,
    ) = HeartRateSample(wall + elapsedMs, elapsedMs, bpm)

    @Test
    fun aFinishedActivityIsAWorkoutWithItsRouteItsHeartRateItsPauseAndItsDistance() {
        val recorder = ActivityRecorder(store)
        recorder.start("w1", Sport.RIDE, 0, wall)
        recorder.gpsReady(0, wall)
        recorder.onSample(fix(0, 0))
        recorder.onHeartRate(hr(1_000, 140))
        recorder.onSample(fix(10_000, 1))
        recorder.pause(12_000, wall + 12_000)
        recorder.onHeartRate(hr(13_000, 90)) // taken while the user was paused
        recorder.resume(20_000, wall + 20_000)
        recorder.onSample(fix(21_000, 2))
        recorder.onHeartRate(hr(22_000, 150))
        recorder.stop(30_000, wall + 30_000)

        val workout = assertNotNull(store.workoutExport("w1"))

        assertEquals(Sport.RIDE, workout.sport)
        assertEquals(wall, workout.startMs)
        assertEquals(wall + 30_000, workout.endMs)
        assertEquals(3, workout.route.size)
        assertEquals(120.0, workout.route.first().altitudeM)
        assertEquals(listOf(140, 150), workout.heartRate.map { it.bpm }) // the 90 of the pause is not in it
        assertEquals(1, workout.pauses.size)
        assertEquals(wall + 12_000, workout.pauses.single().startMs)
        assertEquals(wall + 20_000, workout.pauses.single().endMs)
        // The leg across the pause is not distance: 100 m before it, 0 m after it.
        assertEquals(100.0, workout.distanceM, 3.0)
    }

    @Test
    fun aSampleTheQualityRulesExcludeFromTheDistanceIsNotInTheRoute() {
        val recorder = ActivityRecorder(store)
        recorder.start("w2", Sport.RUN, 0, wall)
        recorder.gpsReady(0, wall)
        recorder.onSample(fix(0, 0))
        recorder.onSample(fix(10_000, 1))
        recorder.onSample(fix(10_000, 1)) // the same fix delivered twice: a duplicate, not a second place
        recorder.stop(15_000, wall + 15_000)

        val workout = assertNotNull(store.workoutExport("w2"))

        assertEquals(2, workout.route.size)
        assertEquals(listOf(wall, wall + 10_000), workout.route.map { it.timeMs })
    }

    @Test
    fun anActivityThatIsNotFinishedIsNotAWorkout() {
        val recorder = ActivityRecorder(store)
        recorder.start("w3", Sport.RUN, 0, wall)
        recorder.gpsReady(0, wall)
        recorder.onSample(fix(0, 0))
        recorder.onSample(fix(10_000, 1))

        assertNull(store.workoutExport("w3"))
    }

    @Test
    fun anActivityWithoutARouteIsNotAWorkoutAndAnUnknownIdIsNull() {
        val recorder = ActivityRecorder(store)
        recorder.start("w4", Sport.RUN, 0, wall)
        recorder.gpsReady(0, wall)
        recorder.stop(5_000, wall + 5_000)

        assertNull(store.workoutExport("w4"))
        assertNull(store.workoutExport("nothing"))
    }

    @Test
    fun anActivityWithoutAHeartRateHasAnEmptyList() {
        val recorder = ActivityRecorder(store)
        recorder.start("w5", Sport.WALK, 0, wall)
        recorder.gpsReady(0, wall)
        recorder.onSample(fix(0, 0))
        recorder.onSample(fix(10_000, 1))
        recorder.stop(15_000, wall + 15_000)

        assertTrue(assertNotNull(store.workoutExport("w5")).heartRate.isEmpty())
    }
}
