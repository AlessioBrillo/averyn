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

    /**
     * A value the sensor sent that the sample model rejects (a bpm above 300, an RR that rounds to 0 ms): the
     * value is left out of the [Complete] next to it and reported here, so the caller counts it instead of
     * crashing or dropping it silently.
     */
    data class Invalid(
        val reason: String,
    ) : ParseResult()
}
