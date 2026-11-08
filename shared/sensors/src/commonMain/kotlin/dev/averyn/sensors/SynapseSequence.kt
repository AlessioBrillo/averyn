package dev.averyn.sensors

/** How one frame stands against the previous ones of its stream (contract `raw_frame_v1.json`, "sequence"). */
sealed class SynapseSequenceVerdict {
    /** The next frame, or the first one; [missed] frames never arrived just before it (0 when none). */
    data class InOrder(
        val missed: Int,
    ) : SynapseSequenceVerdict()

    /** A repeat, or a late frame: counted apart, adds nothing, and does not move the counter back. */
    data object OutOfOrder : SynapseSequenceVerdict()

    /** The counter fell by more than the out-of-order window: the band rebooted. Its clock restarts too. */
    data object Restart : SynapseSequenceVerdict()
}

/**
 * Counts the frames the link lost, from the 16-bit counter (ADR-0026: a gap is counted with its reason, never
 * silently dropped). A frame whose counter is just behind the last one (a repeat, or an old one) is counted apart
 * and does not move the counter back. A counter far behind is a restart of the band: the stream starts again from
 * there, and the frames of the old run are not counted as lost.
 *
 * Since contract 2.2 the band advances a stream's sequence only when its stack accepted the frame, so a hole is a
 * frame that was lost after the stack took it; samples the band discarded itself travel in `dropped`.
 */
class SynapseSequenceTracker {
    private companion object {
        /** A step back of up to this many frames is a repeat or a late frame; a longer one is a restart. */
        const val OUT_OF_ORDER_WINDOW = 64
    }

    private var last: Int? = null

    /** Frames that never arrived, in total. */
    var lostFrames: Int = 0
        private set

    /** Frames that arrived with a counter at or behind the last one. */
    var outOfOrderFrames: Int = 0
        private set

    /** How many times the counter restarted (the band rebooted or reconnected). */
    var restarts: Int = 0
        private set

    /** Notes the sequence of a frame and says how it stands. */
    fun classify(sequence: Int): SynapseSequenceVerdict {
        val previous = last
        last = sequence
        if (previous == null) return SynapseSequenceVerdict.InOrder(0)
        val missed = (sequence - previous - 1) and 0xFFFF
        // A distance over half the counter is a step backwards (or a repeat), not 60 000 lost frames.
        if (missed >= 0x8000) {
            // ponytail: a fixed window; a band with a different frame rate would need it from the contract.
            if (((previous - sequence) and 0xFFFF) > OUT_OF_ORDER_WINDOW) {
                restarts++
                return SynapseSequenceVerdict.Restart
            }
            outOfOrderFrames++
            last = previous
            return SynapseSequenceVerdict.OutOfOrder
        }
        lostFrames += missed
        return SynapseSequenceVerdict.InOrder(missed)
    }

    /** Notes the sequence of a frame; returns how many frames were missing just before it. */
    fun onFrame(sequence: Int): Int = (classify(sequence) as? SynapseSequenceVerdict.InOrder)?.missed ?: 0
}
