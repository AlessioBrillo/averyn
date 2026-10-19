package dev.averyn.backend.imports

import java.io.InputStream
import java.time.OffsetDateTime
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/**
 * Limits of one import (TDD-0008 §5.2). A 10-hour activity at 1 Hz is about 36 000 points; the limits leave
 * room for denser traces and stop a file that would cost the server minutes of CPU and memory.
 */
const val MAX_IMPORT_POINTS = 200_000

/** One `<trkseg>` per pause; a file with more segments than this is not a track. */
const val MAX_IMPORT_SEGMENTS = 10_000

const val MAX_IMPORT_TRACKS = 1_000

/** One point of an imported track. */
data class GpxPoint(
    /** Wall-clock UTC epoch millis, parsed from the `<time>` element. */
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    /** Metres, from the optional `<ele>` element. */
    val altitudeM: Double?,
)

/**
 * The track of an imported file: its points grouped in segments (one per `<trkseg>`), plus the extensions that
 * were found and not imported. A segment is a continuous recording interval: the boundary between two of them
 * is a pause, not a straight line through the gap.
 */
data class GpxTrack(
    val segments: List<List<GpxPoint>>,
    val ignoredExtensions: Map<String, Int>,
) {
    val pointCount: Int = segments.sumOf { it.size }
    val startedAtMs: Long = segments.first().first().timeMs
    val lastTimeMs: Long = segments.last().last().timeMs
}

/** What the parser found, or why the file cannot be imported. */
sealed interface GpxParseResult {
    data class Parsed(
        val track: GpxTrack,
    ) : GpxParseResult

    /**
     * The file is not a track this importer can read. [code] is stable (the web app maps it to a message),
     * [message] never carries a coordinate: an import error must not leak the user's track into a log.
     */
    data class Invalid(
        val code: String,
        val message: String,
    ) : GpxParseResult
}

/**
 * Reads a GPX 1.0/1.1 file into points and segments (TDD-0008 §5.2).
 *
 * The parser is strict about the things it cannot invent: a point without a time, a coordinate out of range or
 * a time without a timezone is an error, not something to guess. It is forgiving about the things it can keep:
 * an unknown extension is counted and ignored, the original file is stored untouched.
 */
class GpxParser(
    private val maxPoints: Int = MAX_IMPORT_POINTS,
    private val maxSegments: Int = MAX_IMPORT_SEGMENTS,
    private val maxTracks: Int = MAX_IMPORT_TRACKS,
) {
    fun parse(input: InputStream): GpxParseResult {
        val segments = mutableListOf<List<GpxPoint>>()
        val ignored = mutableMapOf<String, Int>()
        var current: MutableList<GpxPoint>? = null
        var inTrack = false
        var inExtensions = false
        var trackCount = 0
        var pointCount = 0
        var point: MutablePoint? = null
        var capture: Capture? = null
        val text = StringBuilder()

        val reader =
            try {
                secureReader(input)
            } catch (e: XMLStreamException) {
                return GpxParseResult.Invalid("xml_error", "the file is not readable XML")
            }

        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        when (reader.localName) {
                            "trk" -> {
                                trackCount++
                                if (trackCount > maxTracks) {
                                    return GpxParseResult.Invalid("too_many_tracks", "the file has too many tracks")
                                }
                                inTrack = true
                            }
                            "trkseg" -> {
                                if (!inTrack) {
                                    return GpxParseResult.Invalid(
                                        "trkseg_outside_track",
                                        "a track segment is not inside a track",
                                    )
                                }
                                if (current != null) {
                                    return GpxParseResult.Invalid(
                                        "nested_trkseg",
                                        "a track segment is nested in another one",
                                    )
                                }
                                current = mutableListOf()
                            }
                            "trkpt" -> {
                                if (current == null) {
                                    return GpxParseResult.Invalid(
                                        "trkpt_outside_segment",
                                        "a track point is not inside a track segment",
                                    )
                                }
                                val latitude = reader.getAttributeValue(null, "lat")?.toDoubleOrNull()
                                val longitude = reader.getAttributeValue(null, "lon")?.toDoubleOrNull()
                                if (latitude == null || longitude == null) {
                                    return GpxParseResult.Invalid(
                                        "invalid_coordinate",
                                        "a track point has no usable coordinates",
                                    )
                                }
                                if (!latitude.isFinite() || latitude !in -90.0..90.0) {
                                    return GpxParseResult.Invalid(
                                        "invalid_coordinate",
                                        "a track point is not on the globe",
                                    )
                                }
                                if (!longitude.isFinite() || longitude !in -180.0..180.0) {
                                    return GpxParseResult.Invalid(
                                        "invalid_coordinate",
                                        "a track point is not on the globe",
                                    )
                                }
                                point = MutablePoint(latitude, longitude)
                            }
                            "ele" -> if (point != null) capture = Capture.ELEVATION
                            "time" -> if (point != null) capture = Capture.TIME
                            "extensions" -> inExtensions = true
                            else -> if (inExtensions) ignored.merge(reader.localName, 1, Int::plus)
                        }
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (capture != null) text.append(reader.text)
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        when (reader.localName) {
                            "trkpt" -> {
                                val currentPoint =
                                    point
                                        ?: return GpxParseResult.Invalid(
                                            "invalid_point",
                                            "a track point could not be read",
                                        )
                                point = null
                                capture = null
                                val timeMs =
                                    parseTime(currentPoint.timeText)
                                        ?: return GpxParseResult.Invalid(
                                            "invalid_time",
                                            "a track point has no usable time (ISO-8601 with a timezone offset)",
                                        )
                                val elevation =
                                    currentPoint.elevationText?.let { text ->
                                        val value = text.toDoubleOrNull()
                                        if (value == null || !value.isFinite()) {
                                            return GpxParseResult.Invalid(
                                                "invalid_elevation",
                                                "a track point has an unreadable elevation",
                                            )
                                        }
                                        value
                                    }
                                pointCount++
                                if (pointCount > maxPoints) {
                                    return GpxParseResult.Invalid(
                                        "too_many_points",
                                        "the file has too many track points",
                                    )
                                }
                                current?.add(GpxPoint(timeMs, currentPoint.latitude, currentPoint.longitude, elevation))
                            }
                            "trkseg" -> {
                                val segment = current
                                current = null
                                if (segment != null && segment.isNotEmpty()) {
                                    if (segments.size >= maxSegments) {
                                        return GpxParseResult.Invalid(
                                            "too_many_segments",
                                            "the file has too many track segments",
                                        )
                                    }
                                    val previous = segments.lastOrNull()?.lastOrNull()
                                    val first = segment.first()
                                    if (previous != null && first.timeMs < previous.timeMs) {
                                        return GpxParseResult.Invalid(
                                            "non_monotonic_segments",
                                            "a track segment starts before the previous one ends",
                                        )
                                    }
                                    segments += segment.toList()
                                }
                            }
                            "trk" -> inTrack = false
                            "ele" -> {
                                capture = null
                                point?.elevationText = text.toString().trim().ifEmpty { null }
                                text.setLength(0)
                            }
                            "time" -> {
                                capture = null
                                point?.timeText = text.toString().trim().ifEmpty { null }
                                text.setLength(0)
                            }
                            "extensions" -> inExtensions = false
                            else -> text.setLength(0)
                        }
                    }
                }
            }
        } catch (e: XMLStreamException) {
            // A DOCTYPE, an unparsable entity or a truncated file: never the reason, never the content.
            return GpxParseResult.Invalid("xml_error", "the file is not readable XML")
        } finally {
            runCatching { reader.close() }
        }

        if (segments.isEmpty()) {
            return GpxParseResult.Invalid("no_track", "the file has no track points")
        }
        return GpxParseResult.Parsed(GpxTrack(segments, ignored))
    }

    private enum class Capture { ELEVATION, TIME }

    private class MutablePoint(
        val latitude: Double,
        val longitude: Double,
    ) {
        var elevationText: String? = null
        var timeText: String? = null
    }

    /**
     * A stream reader that cannot fetch anything from outside the file: no DTD, no external entities. A GPX file
     * is user input, and an XML parser that resolves entities would read the server's own files.
     */
    private fun secureReader(input: InputStream): XMLStreamReader {
        val factory = XMLInputFactory.newFactory()
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setProperty(XMLInputFactory.IS_COALESCING, true)
        runCatching { factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
        runCatching { factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
        return factory.createXMLStreamReader(input)
    }

    /** ISO-8601 with a timezone offset; a local time without one is ambiguous and is not guessed. */
    private fun parseTime(text: String?): Long? =
        if (text.isNullOrEmpty()) {
            null
        } else {
            runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        }
}
