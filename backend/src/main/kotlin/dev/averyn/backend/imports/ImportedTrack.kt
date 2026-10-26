package dev.averyn.backend.imports

import dev.averyn.domain.Sport

/**
 * Limits of one import (TDD-0008 §5.2). A 10-hour activity at 1 Hz is about 36 000 points; the limits leave
 * room for denser traces and stop a file that would cost the server minutes of CPU and memory.
 */
const val MAX_IMPORT_POINTS = 200_000

/** One `<trkseg>` per pause; a file with more segments than this is not a track. */
const val MAX_IMPORT_SEGMENTS = 10_000

const val MAX_IMPORT_TRACKS = 1_000

/** One point of an imported track. */
data class ImportedPoint(
    /** Wall-clock UTC epoch millis, parsed from the `<time>` element. */
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    /** Metres, from the optional `<ele>` element. */
    val altitudeM: Double?,
)

/** One heart-rate reading of an imported file. */
data class ImportedHeartRate(
    val timeMs: Long,
    val bpm: Int,
)

/**
 * The track of an imported file: its points grouped in segments (one per `<trkseg>`, or per timer interval of a
 * FIT file), plus the extensions that were found and not imported. A segment is a continuous recording interval: the boundary between two of them
 * is a pause, not a straight line through the gap.
 */
data class ImportedTrack(
    val segments: List<List<ImportedPoint>>,
    val ignoredExtensions: Map<String, Int>,
    /** The heart rate the file carries, in time order (a FIT file does; a GPX file here does not). */
    val heartRates: List<ImportedHeartRate> = emptyList(),
    /** The sport the file declares, when it does and it is one Averyn records (a FIT file does). */
    val sport: Sport? = null,
) {
    val pointCount: Int = segments.sumOf { it.size }
    val startedAtMs: Long = segments.first().first().timeMs
    val lastTimeMs: Long = segments.last().last().timeMs
}

/** What the parser found, or why the file cannot be imported. */
sealed interface ImportParseResult {
    data class Parsed(
        val track: ImportedTrack,
    ) : ImportParseResult

    /**
     * The file is not a track this importer can read. [code] is stable (the web app maps it to a message),
     * [message] never carries a coordinate: an import error must not leak the user's track into a log.
     */
    data class Invalid(
        val code: String,
        val message: String,
    ) : ImportParseResult
}
