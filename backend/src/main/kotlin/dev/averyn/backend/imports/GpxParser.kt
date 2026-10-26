package dev.averyn.backend.imports

import java.io.InputStream
import java.time.OffsetDateTime
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

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
    fun parse(input: InputStream): ImportParseResult {
        val segments = mutableListOf<List<ImportedPoint>>()
        val ignored = mutableMapOf<String, Int>()
        var current: MutableList<ImportedPoint>? = null
        var inTrack = false
        var inExtensions = false
        var trackCount = 0
        var pointCount = 0
        var point: MutablePoint? = null
        var capture: Capture? = null
        val text = StringBuilder()
        // The depth of the element being read and the depth of the track point it belongs to: only the direct
        // children of a point carry its time and its elevation, an extension with a `time` of its own does not.
        var depth = 0
        var pointDepth = -1

        val reader =
            try {
                secureReader(input)
            } catch (e: XMLStreamException) {
                return ImportParseResult.Invalid("xml_error", "the file is not readable XML")
            }

        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> {
                        val elementDepth = depth
                        depth++
                        when (reader.localName) {
                            "trk" -> {
                                trackCount++
                                if (trackCount > maxTracks) {
                                    return ImportParseResult.Invalid("too_many_tracks", "the file has too many tracks")
                                }
                                inTrack = true
                            }
                            "trkseg" -> {
                                if (!inTrack) {
                                    return ImportParseResult.Invalid(
                                        "trkseg_outside_track",
                                        "a track segment is not inside a track",
                                    )
                                }
                                if (current != null) {
                                    return ImportParseResult.Invalid(
                                        "nested_trkseg",
                                        "a track segment is nested in another one",
                                    )
                                }
                                current = mutableListOf()
                            }
                            "trkpt" -> {
                                if (current == null) {
                                    return ImportParseResult.Invalid(
                                        "trkpt_outside_segment",
                                        "a track point is not inside a track segment",
                                    )
                                }
                                val latitude = reader.getAttributeValue(null, "lat")?.toDoubleOrNull()
                                val longitude = reader.getAttributeValue(null, "lon")?.toDoubleOrNull()
                                if (latitude == null || longitude == null) {
                                    return ImportParseResult.Invalid(
                                        "invalid_coordinate",
                                        "a track point has no usable coordinates",
                                    )
                                }
                                if (!latitude.isFinite() || latitude !in -90.0..90.0) {
                                    return ImportParseResult.Invalid(
                                        "invalid_coordinate",
                                        "a track point is not on the globe",
                                    )
                                }
                                if (!longitude.isFinite() || longitude !in -180.0..180.0) {
                                    return ImportParseResult.Invalid(
                                        "invalid_coordinate",
                                        "a track point is not on the globe",
                                    )
                                }
                                point = MutablePoint(latitude, longitude)
                                pointDepth = elementDepth
                            }
                            "ele" -> if (point != null && elementDepth == pointDepth + 1) capture = Capture.ELEVATION
                            "time" -> if (point != null && elementDepth == pointDepth + 1) capture = Capture.TIME
                            "extensions" -> inExtensions = true
                            else -> if (inExtensions) ignored.merge(reader.localName, 1, Int::plus)
                        }
                    }
                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (capture != null) text.append(reader.text)
                    }
                    XMLStreamConstants.END_ELEMENT -> {
                        val elementDepth = depth - 1
                        depth--
                        when (reader.localName) {
                            "trkpt" -> {
                                val currentPoint =
                                    point
                                        ?: return ImportParseResult.Invalid(
                                            "invalid_point",
                                            "a track point could not be read",
                                        )
                                point = null
                                pointDepth = -1
                                capture = null
                                val timeMs =
                                    parseTime(currentPoint.timeText)
                                        ?: return ImportParseResult.Invalid(
                                            "invalid_time",
                                            "a track point has no usable time (ISO-8601 with a timezone offset)",
                                        )
                                val elevation =
                                    currentPoint.elevationText?.let { text ->
                                        val value = text.toDoubleOrNull()
                                        if (value == null || !value.isFinite()) {
                                            return ImportParseResult.Invalid(
                                                "invalid_elevation",
                                                "a track point has an unreadable elevation",
                                            )
                                        }
                                        value
                                    }
                                pointCount++
                                if (pointCount > maxPoints) {
                                    return ImportParseResult.Invalid(
                                        "too_many_points",
                                        "the file has too many track points",
                                    )
                                }
                                current?.add(
                                    ImportedPoint(timeMs, currentPoint.latitude, currentPoint.longitude, elevation),
                                )
                            }
                            "trkseg" -> {
                                val segment = current
                                current = null
                                if (segment != null && segment.isNotEmpty()) {
                                    if (segments.size >= maxSegments) {
                                        return ImportParseResult.Invalid(
                                            "too_many_segments",
                                            "the file has too many track segments",
                                        )
                                    }
                                    val previous = segments.lastOrNull()?.lastOrNull()
                                    val first = segment.first()
                                    if (previous != null && first.timeMs < previous.timeMs) {
                                        return ImportParseResult.Invalid(
                                            "non_monotonic_segments",
                                            "a track segment starts before the previous one ends",
                                        )
                                    }
                                    segments += segment.toList()
                                }
                            }
                            "trk" -> inTrack = false
                            "ele" -> {
                                if (point != null && elementDepth == pointDepth + 1) {
                                    point?.elevationText = text.toString().trim().ifEmpty { null }
                                }
                                capture = null
                                text.setLength(0)
                            }
                            "time" -> {
                                if (point != null && elementDepth == pointDepth + 1) {
                                    point?.timeText = text.toString().trim().ifEmpty { null }
                                }
                                capture = null
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
            return ImportParseResult.Invalid("xml_error", "the file is not readable XML")
        } finally {
            runCatching { reader.close() }
        }

        if (segments.isEmpty()) {
            return ImportParseResult.Invalid("no_track", "the file has no track points")
        }
        return ImportParseResult.Parsed(ImportedTrack(segments, ignored))
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
