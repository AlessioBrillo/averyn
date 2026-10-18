package dev.averyn.sensors

/**
 * Result of parsing one `0x2A37` Heart Rate Measurement notification. A truncated notification reports
 * `Incomplete` with how many more bytes are expected; the caller retries with more data, never fabricating
 * a value (ADR-0014: vendor frames parsed into plain data before crossing into `shared/`).
 */
sealed class ParseResult {
    data class Complete(
        val heartRate: HeartRateSample?,
        val rrIntervals: List<RrInterval>,
        val energyExpended: Int?,
        val consumedBytes: Int,
    ) : ParseResult()

    data class Incomplete(
        val neededBytes: Int,
    ) : ParseResult()
}
