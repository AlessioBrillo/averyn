package dev.averyn.campaign

import dev.averyn.backend.imports.ImportedHeartRate
import dev.averyn.domain.LocationSample
import dev.averyn.domain.Sport
import dev.averyn.sensors.HeartRateSample
import dev.averyn.tracking.ActivityRecorder
import dev.averyn.tracking.ActivityStore
import dev.averyn.tracking.DeviceInfo
import dev.averyn.tracking.toJson
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EvaluateTest {
    private val device =
        DeviceInfo("Pixel 8", "Android 15", "0.1.0", "Google", "abc1234", "PRECISE_ALWAYS", false, true)

    /** An exported diagnostics file of a 12-minute run with a strap that is silent for [silentMs] in the middle. */
    private fun export(
        bpm: Int,
        silentMs: Long = 0,
    ): String {
        val dir = Files.createTempDirectory("campaign-test").toFile()
        try {
            val recorder = ActivityRecorder(ActivityStore(Path(dir.path)))
            recorder.start("a1", Sport.RUN, 0, 0)
            recorder.gpsReady(0, 0)
            recorder.onSample(LocationSample(0, 0, 45.0, 9.0, 5.0))
            var t = 0L
            while (t <= 720_000) {
                val silent = silentMs > 0 && t in 300_000 until 300_000 + silentMs
                if (!silent) recorder.onHeartRate(HeartRateSample(t, t, bpm))
                t += 1_000
            }
            recorder.onSample(LocationSample(720_000, 720_000, 45.01, 9.0, 5.0))
            return recorder.stop(720_000, 720_000).diagnostics.toJson(device)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun strap(
        bpm: Int,
        peak: Int = bpm,
    ) = List(721) { ImportedHeartRate(it * 1_000L, if (it == 360) peak else bpm) }

    @Test
    fun theExportOfTheAppIsReadBack() {
        val (report, d) = parseExport(export(bpm = 140))

        assertEquals("diagnostics-v5", report.formatVersion)
        assertEquals(140, report.heartRate?.avgBpm)
        assertEquals("Pixel 8", d.model)
    }

    @Test
    fun s15PassesWhenTheStrapAgreesWithinFiveBpmAndNoDropoutIsOver30Seconds() {
        val (report, d) = parseExport(export(bpm = 140))
        val ref = hrReference(strap(bpm = 143), 0, 720_000)
        val result = evaluate("S15", Inputs(report, d, hrReference = ref))

        assertEquals(Verdict.PASS, result.verdict, result.checks.joinToString { "${it.name}=${it.ok}" })
    }

    @Test
    fun s15FailsOnADisagreementAndOnALongDropout() {
        val (report, d) = parseExport(export(bpm = 140))
        assertEquals(
            Verdict.FAIL,
            evaluate("S15", Inputs(report, d, hrReference = hrReference(strap(bpm = 150), 0, 720_000))).verdict,
        )

        val (gappy, d2) = parseExport(export(bpm = 140, silentMs = 45_000))
        val dropout = evaluate("S15", Inputs(gappy, d2, hrReference = hrReference(strap(bpm = 140), 0, 720_000)))
        assertEquals(Verdict.FAIL, dropout.verdict)
        assertTrue(
            dropout.checks
                .single { it.ok == false }
                .name
                .contains("dropout"),
        )
    }

    @Test
    fun withoutTheStrapFileTheRowNeedsAPersonNotAGuess() {
        val (report, d) = parseExport(export(bpm = 140))

        assertEquals(Verdict.MANUAL, evaluate("S15", Inputs(report, d)).verdict)
    }

    @Test
    fun aRowWithoutAMachineCheckIsManual() {
        val (report, d) = parseExport(export(bpm = 140))

        assertEquals(Verdict.MANUAL, evaluate("S47", Inputs(report, d)).verdict)
    }

    @Test
    fun theRowRendersForTheResultsTable() {
        val (report, d) = parseExport(export(bpm = 140))
        val input = Inputs(report, d, hrReference = hrReference(strap(bpm = 141), 0, 720_000))
        val text = render(evaluate("S15", input), input, "2026-11-09")

        assertTrue(text.startsWith("| 2026-11-09 | Pixel 8 | Android 15 | 0.1.0 / abc1234 | S15 | 12 min |"), text)
        assertTrue(text.contains("| PASS |"), text)
    }

    @Test
    fun aGpxIsCheckedAgainstTheSchemaAndABrokenOneIsNotAnError() {
        val xsd = File("../../tests/gps-fixtures/schema/gpx-1.1.xsd")
        assertTrue(xsd.isFile, "run from tools/campaign: ${xsd.absolutePath}")
        val good = File.createTempFile("good", ".gpx")
        val bad = File.createTempFile("bad", ".gpx")
        try {
            good.writeText(
                """<?xml version="1.0"?><gpx version="1.1" creator="t" xmlns="http://www.topografix.com/GPX/1/1">""" +
                    """<trk><trkseg><trkpt lat="45.0" lon="9.0"><time>2026-11-09T10:00:00Z</time></trkpt></trkseg></trk></gpx>""",
            )
            bad.writeText("<gpx><not-closed>")

            assertTrue(validGpx(good, xsd))
            assertEquals(false, validGpx(bad, xsd))
        } finally {
            good.delete()
            bad.delete()
        }
    }
}
