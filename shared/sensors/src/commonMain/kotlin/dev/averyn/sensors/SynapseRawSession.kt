package dev.averyn.sensors

/** An event on a stream's sequence, with the sequence number it was seen at. */
data class SynapseSequenceEvent(
    val sequence: Int,
    /** `gap`, `restart` or `out_of_order`. */
    val kind: String,
)

/** What one stream of a recording amounts to: the frames, the values and everything that went wrong. */
class SynapseStreamSummary(
    val stream: SynapseRawStream,
) {
    var frames: Int = 0
        internal set
    var values: Long = 0
        internal set

    /** Samples the band says it discarded itself (the sum of the frames' `dropped` byte). */
    var deviceDropped: Int = 0
        internal set
    var wraps: Int = 0
        internal set
    var lostFrames: Int = 0
        internal set
    var outOfOrder: Int = 0
        internal set
    var restarts: Int = 0
        internal set

    /** The band's clock at the first sample of each accepted frame, extended past the 32-bit wrap, in microseconds. */
    val timelineUs: MutableList<Long> = mutableListOf()
    val events: MutableList<SynapseSequenceEvent> = mutableListOf()
}

/** What the session made of one raw frame. */
sealed class SynapseRawOutcome {
    /**
     * A frame that adds samples. [extendedUs] is the band's clock at its first sample, past the wrap; [missed] frames
     * were lost just before it; a [restart] means the band rebooted (its clock restarted: do not join it to the old run).
     */
    class Accepted(
        val header: SynapseRawHeader,
        val values: IntArray,
        val extendedUs: Long,
        val missed: Int,
        val restart: Boolean,
    ) : SynapseRawOutcome()

    /** A repeat or a late frame: counted, adds nothing. */
    data class OutOfOrder(
        val header: SynapseRawHeader,
    ) : SynapseRawOutcome()

    data class Invalid(
        val reason: SynapseRawInvalid,
    ) : SynapseRawOutcome()
}

/**
 * Reads the raw frames of one recording in the order the phone received them and keeps the account the contract
 * asks for (`raw_frame_v1.json`, ADR-0030): per stream the frames, the values, what the link lost, what the band
 * says it dropped, restarts and late frames, and the band's clock extended past its 32-bit wrap; and the frames
 * refused, by reason. Loss is data: nothing here is thrown away without being counted.
 *
 * Pure and deterministic. The device repository publishes the same account for its fixtures
 * (the `expected.json` files of `contracts/fixtures`), computed by a reference implementation of the same rules; the tests
 * replay those and compare.
 */
class SynapseRawSession {
    private val trackers = mutableMapOf<SynapseRawStream, SynapseSequenceTracker>()
    private val epochs = mutableMapOf<SynapseRawStream, Long>()
    private val summaries = linkedMapOf<SynapseRawStream, SynapseStreamSummary>()
    private val invalid = linkedMapOf<SynapseRawInvalid, Int>()

    /** Frames refused, by reason. */
    val invalidFrames: Map<SynapseRawInvalid, Int> get() = invalid

    /** Streams seen, in the order their first valid frame arrived. */
    val streams: Map<SynapseRawStream, SynapseStreamSummary> get() = summaries

    fun onFrame(data: ByteArray): SynapseRawOutcome {
        val decoded = decodeSynapseRawFrame(data)
        if (decoded is SynapseRawResult.Invalid) {
            invalid[decoded.reason] = (invalid[decoded.reason] ?: 0) + 1
            return SynapseRawOutcome.Invalid(decoded.reason)
        }
        decoded as SynapseRawResult.Frame
        val header = decoded.header
        val tracker = trackers.getOrPut(header.stream) { SynapseSequenceTracker() }
        val summary = summaries.getOrPut(header.stream) { SynapseStreamSummary(header.stream) }
        var missed = 0
        var restart = false
        when (val verdict = tracker.classify(header.sequence)) {
            SynapseSequenceVerdict.OutOfOrder -> {
                summary.events += SynapseSequenceEvent(header.sequence, "out_of_order")
                summary.outOfOrder = tracker.outOfOrderFrames
                return SynapseRawOutcome.OutOfOrder(header)
            }
            SynapseSequenceVerdict.Restart -> {
                restart = true
                epochs[header.stream] = 0L // a rebooted band restarts its clock too
                summary.events += SynapseSequenceEvent(header.sequence, "restart")
            }
            is SynapseSequenceVerdict.InOrder -> {
                missed = verdict.missed
                if (missed > 0) summary.events += SynapseSequenceEvent(header.sequence, "gap")
            }
        }
        if (header.timestampWrapped) {
            epochs[header.stream] = (epochs[header.stream] ?: 0L) + 1
            summary.wraps++
        }
        val extended = ((epochs[header.stream] ?: 0L) shl 32) or header.timestampUs
        summary.frames++
        summary.values += header.valueCount
        summary.deviceDropped += header.dropped
        summary.timelineUs += extended
        summary.lostFrames = tracker.lostFrames
        summary.outOfOrder = tracker.outOfOrderFrames
        summary.restarts = tracker.restarts
        return SynapseRawOutcome.Accepted(header, decoded.values, extended, missed, restart)
    }
}
