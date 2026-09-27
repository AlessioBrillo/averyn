package dev.averyn.tracking

import dev.averyn.domain.LocationSample
import dev.averyn.metrics.ActivityMetrics
import dev.averyn.metrics.ActivitySnapshot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.w3c.dom.Element
import java.io.File
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals

@Serializable
private data class ExpectedDistance(
    val value: Double,
    @SerialName("tolerance_m") val toleranceM: Double,
)

@Serializable
private data class Expected(
    @SerialName("distance_m") val distanceM: ExpectedDistance,
    @SerialName("duration_s") val durationS: Long,
    @SerialName("moving_time_s") val movingTimeS: Long,
    @SerialName("quality_flags") val qualityFlags: List<String>,
)

@Serializable
private data class ExpectedFile(
    val description: String,
    val expected: Expected,
)

/**
 * Runs every `tests/gps-fixtures/<name>/track.gpx` against its `expected.json` (TDD-0001 §6). Each fixture is
 * one `<trkseg>` (a single continuous RECORDING interval; no pause/resume fixtures yet — see the fixtures
 * README) fed through [QualityAssessor]/[QualityReportAccumulator]/[ActivityMetrics] in file order, with
 * `elapsedRealtimeMs` derived from each `<trkpt>`'s `<time>` relative to the first point's — GPX has no
 * separate monotonic clock, so a fixture that goes backward in `<time>` (`non-monotonic`) is exactly a
 * sample whose derived `elapsedRealtimeMs` doesn't increase, matching real device behavior.
 */
class FixtureConformanceTest {
    private data class GpxPoint(
        val lat: Double,
        val lon: Double,
        val altitudeM: Double?,
        val epochMs: Long,
    )

    private fun fixturesDir(): File {
        val path =
            System.getProperty("averyn.fixturesDir")
                ?: error("system property averyn.fixturesDir is not set (see shared/tracking/build.gradle.kts)")
        return File(path)
    }

    private fun parseGpx(file: File): List<GpxPoint> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val trkpts = doc.getElementsByTagName("trkpt")
        return (0 until trkpts.length).map { i ->
            val el = trkpts.item(i) as Element
            val lat = el.getAttribute("lat").toDouble()
            val lon = el.getAttribute("lon").toDouble()
            val eleText = el.getElementsByTagName("ele").let { if (it.length > 0) it.item(0).textContent else null }
            val timeText = el.getElementsByTagName("time").item(0).textContent
            GpxPoint(lat, lon, eleText?.toDouble(), Instant.parse(timeText).toEpochMilli())
        }
    }

    private fun runFixture(dir: File): Pair<ActivitySnapshot, QualityReport> {
        val points = parseGpx(File(dir, "track.gpx"))
        val firstEpochMs = points.first().epochMs
        val assessor = QualityAssessor()
        val accumulator = QualityReportAccumulator()
        val metrics = ActivityMetrics()
        points.forEach { p ->
            val sample = LocationSample(p.epochMs, p.epochMs - firstEpochMs, p.lat, p.lon, 5.0, p.altitudeM)
            val flags = assessor.assess(sample)
            accumulator.add(sample, flags)
            if (flags.none { it.excludesFromDistance }) metrics.onSample(sample)
        }
        return metrics.snapshot() to accumulator.build()
    }

    @Test
    fun everyFixtureMatchesItsExpectedJson() {
        val json = Json { ignoreUnknownKeys = true }
        val fixtureDirs = fixturesDir().listFiles { f -> f.isDirectory && File(f, "expected.json").exists() }.orEmpty()
        check(fixtureDirs.isNotEmpty()) { "no fixtures found under ${fixturesDir()}" }

        for (dir in fixtureDirs.sortedBy { it.name }) {
            val expectedFile = json.decodeFromString(ExpectedFile.serializer(), File(dir, "expected.json").readText())
            val expected = expectedFile.expected
            val (snapshot, report) = runFixture(dir)

            assertEquals(
                expected.distanceM.value,
                snapshot.distanceM,
                expected.distanceM.toleranceM,
                "${dir.name}: distance",
            )
            assertEquals(expected.durationS, snapshot.elapsedMs / 1000, "${dir.name}: duration")
            assertEquals(expected.movingTimeS, snapshot.movingMs / 1000, "${dir.name}: moving time")

            val actualFlags =
                report.flagCounts
                    .filterValues { it > 0 }
                    .keys
                    .map { it.name }
                    .toSet()
            assertEquals(expected.qualityFlags.toSet(), actualFlags, "${dir.name}: quality flags")
        }
    }
}
