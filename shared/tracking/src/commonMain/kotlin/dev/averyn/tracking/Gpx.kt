package dev.averyn.tracking

import dev.averyn.domain.ActivityState
import dev.averyn.domain.LocationSample
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString

private const val GPX_HEADER =
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<gpx version=\"1.1\" creator=\"Averyn\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n"
private const val GPX_FOOTER = "</gpx>\n"

enum class GpxContent { RAW, ACCEPTED_ONLY }

/**
 * Streams a completed/recovered activity to GPX 1.1 (TDD-0001 F7), reading [store] rather than holding
 * the activity in memory. One `<trkseg>` per RECORDING interval — a pause closes the current segment, which
 * is how GPX consumers expect a pause to look. Samples recorded while PAUSED are never exported (they aren't
 * part of the track); [content] then further chooses between every RECORDING-time sample (`RAW`, unfiltered
 * by quality) or only ones not excluded from distance (`ACCEPTED_ONLY`, docs/metrics/distance.md).
 *
 * Returns the number of corrupt/truncated store lines skipped (F5: counted, never silently dropped) — a
 * non-zero result means the export is missing some points and the caller should surface that, not ignore it.
 */
fun writeGpx(
    store: ActivityStore,
    activityId: String,
    sink: Sink,
    content: GpxContent = GpxContent.RAW,
): Int {
    val metadata = store.metadataOf(activityId) ?: error("no stored activity: $activityId")
    sink.writeString(GPX_HEADER)
    sink.writeString("  <trk>\n    <name>${xmlEscape("${metadata.sport} ${metadata.activityId}")}</name>\n")

    val assessor = QualityAssessor()
    var inSegment = false
    var recording = false
    var droppedRecordCount = 0

    fun closeSegmentIfOpen() {
        if (inSegment) sink.writeString("    </trkseg>\n")
        inSegment = false
    }

    store.forEachRecord(activityId, onLoss = { droppedRecordCount++ }) { record ->
        when (record) {
            is ActivityRecord.Event -> {
                recording = record.event.state == ActivityState.RECORDING
                if (!recording) closeSegmentIfOpen()
            }
            is ActivityRecord.Sample -> {
                if (!recording) return@forEachRecord
                val flags = assessor.assess(record.sample)
                val include = content == GpxContent.RAW || flags.none { it.excludesFromDistance }
                if (include) {
                    if (!inSegment) {
                        sink.writeString("    <trkseg>\n")
                        inSegment = true
                    }
                    writeTrkpt(sink, record.sample)
                }
            }
        }
    }
    closeSegmentIfOpen()
    sink.writeString("  </trk>\n")
    sink.writeString(GPX_FOOTER)
    return droppedRecordCount
}

private fun writeTrkpt(
    sink: Sink,
    sample: LocationSample,
) {
    sink.writeString("      <trkpt lat=\"${sample.latitude}\" lon=\"${sample.longitude}\">\n")
    sample.altitudeM?.let { sink.writeString("        <ele>$it</ele>\n") }
    sink.writeString("        <time>${isoInstant(sample.timeMs)}</time>\n")
    sink.writeString("      </trkpt>\n")
}

private fun xmlEscape(text: String): String =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

// No kotlinx-datetime dependency for one formatter: epoch millis -> "yyyy-MM-ddTHH:mm:ssZ" (UTC, GPX's <time>),
// using Howard Hinnant's well-known civil-calendar-from-days algorithm (proleptic Gregorian, valid for any date).
private fun isoInstant(epochMs: Long): String {
    val epochSec = floorDiv(epochMs, 1000L)
    val days = floorDiv(epochSec, 86_400L)
    val secOfDay = floorMod(epochSec, 86_400L)
    val (year, month, day) = civilFromDays(days)
    val hour = secOfDay / 3600
    val minute = (secOfDay % 3600) / 60
    val second = secOfDay % 60
    return "${pad4(year)}-${pad2(month)}-${pad2(day)}T${pad2(hour)}:${pad2(minute)}:${pad2(second)}Z"
}

private fun floorDiv(
    x: Long,
    y: Long,
): Long {
    val q = x / y
    return if (x % y != 0L && (x < 0) != (y < 0)) q - 1 else q
}

private fun floorMod(
    x: Long,
    y: Long,
): Long = x - floorDiv(x, y) * y

private fun civilFromDays(z0: Long): Triple<Long, Long, Long> {
    val z = z0 + 719_468L
    val era = floorDiv(z, 146_097L)
    val doe = z - era * 146_097L // [0, 146096]
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365 // [0, 399]
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100) // [0, 365]
    val mp = (5 * doy + 2) / 153 // [0, 11]
    val day = doy - (153 * mp + 2) / 5 + 1 // [1, 31]
    val month = if (mp < 10) mp + 3 else mp - 9 // [1, 12]
    val year = if (month <= 2) y + 1 else y
    return Triple(year, month, day)
}

private fun pad2(value: Long): String = if (value < 10) "0$value" else "$value"

private fun pad4(value: Long): String =
    when {
        value < 10 -> "000$value"
        value < 100 -> "00$value"
        value < 1000 -> "0$value"
        else -> "$value"
    }

/**
 * Convenience for native adapters (Swift/iOS) that don't otherwise need kotlinx-io types on their side —
 * an extension function on [ActivityStore] so Swift reaches it as `store.exportGpxToFile(...)` (a top-level
 * function isn't reliably callable from Swift, but an instance/extension method is). Returns the same
 * dropped-record count as [writeGpx].
 */
fun ActivityStore.exportGpxToFile(
    activityId: String,
    filePath: String,
    content: GpxContent = GpxContent.RAW,
): Int {
    var droppedRecordCount = 0
    SystemFileSystem.sink(Path(filePath)).buffered().use { sink ->
        droppedRecordCount = writeGpx(this, activityId, sink, content)
    }
    return droppedRecordCount
}
