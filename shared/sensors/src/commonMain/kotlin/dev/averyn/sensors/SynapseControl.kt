package dev.averyn.sensors

/** Before this the band's clock would be wrong by years: `set_time` refuses it (2020-01-01T00:00:00Z in ms). */
const val SYNAPSE_SET_TIME_MIN_EPOCH_MS = 1_577_836_800_000L

/** The fastest feature rate the band accepts, in Hz. */
const val SYNAPSE_FEATURE_RATE_MAX_HZ = 5

/** Error codes of a refused control write, as the band reports them (ATT application error `0x80 + code`). */
object SynapseControlError {
    const val BAD_OPCODE = 1
    const val BAD_LENGTH = 2
    const val BAD_ARGUMENT = 3
}

/**
 * The writes to the control point (contract 2.2). `start` and `stop` gate the raw research streams; the feature and
 * status characteristics keep flowing and report the state. A new connection starts running, so a phone that wants
 * the raw streams only while a signal screen is open sends `stop` right after connecting and `start` when it opens.
 */
object SynapseControl {
    fun start(): ByteArray = byteArrayOf(1)

    fun stop(): ByteArray = byteArrayOf(2)

    /** Sets how often the feature frame is notified, 1 to [SYNAPSE_FEATURE_RATE_MAX_HZ] Hz. */
    fun setFeatureRate(hz: Int): ByteArray {
        require(
            hz in 1..SYNAPSE_FEATURE_RATE_MAX_HZ,
        ) { "feature rate $hz Hz is outside 1..$SYNAPSE_FEATURE_RATE_MAX_HZ" }
        return byteArrayOf(3, hz.toByte())
    }

    /**
     * Gives the band its wall clock (Unix epoch in **milliseconds**, little endian): it has none of its own, and the
     * night window and the files it writes need it. Send it on every connection.
     */
    fun setTime(epochMillis: Long): ByteArray {
        require(epochMillis >= SYNAPSE_SET_TIME_MIN_EPOCH_MS) { "set_time: $epochMillis ms is before 2020" }
        return ByteArray(9) { i -> if (i == 0) 4 else ((epochMillis ushr (8 * (i - 1))) and 0xFF).toByte() }
    }
}
