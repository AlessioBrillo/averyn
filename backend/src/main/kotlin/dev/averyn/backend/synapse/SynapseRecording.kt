package dev.averyn.backend.synapse

import dev.averyn.api.SynapseChannelDto
import dev.averyn.api.SynapseGapDto
import dev.averyn.api.SynapseRawDto
import dev.averyn.api.SynapseRawStreamDto
import dev.averyn.api.SynapseSegmentDto
import dev.averyn.api.SynapseWindowDto
import dev.averyn.sensors.SYNAPSE_STREAMS
import dev.averyn.sensors.SynapseRawOutcome
import dev.averyn.sensors.SynapseRawSession
import dev.averyn.sensors.SynapseRawStream
import dev.averyn.sensors.SynapseStreamInfo
import dev.averyn.sensors.readSynapseSidecar
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * A raw sidecar read once, ready to be windowed (ADR-0030, level L1R).
 *
 * The file stays verbatim in the object store; this is a derived view of it. Samples are grouped in **segments**: a
 * stretch of one stream with no hole in it. A lost frame, samples the band says it dropped, and a restart of the band
 * each end a segment and start the next one, anchored on the phone's clock at the first frame after the hole: the line
 * is never drawn across a gap, and the gap is reported with its reason. The band's own clock only places samples
 * *within* a frame; it never overrides the phone's (ADR-0030).
 *
 * ponytail: the anchor is the phone's reception time of the segment's first frame minus the frame's own duration, so
 * the BLE latency (tens of ms) is inside the anchor, and the clock drift between phone and band is not modelled.
 * Measure both on a real band before trusting a time axis finer than that.
 */
class SynapseRecording private constructor(
    private val streams: Map<SynapseRawStream, StreamData>,
    val invalidFrames: Map<String, Int>,
    val durationMs: Long,
) {
    /** One contiguous stretch of one stream. Values are wire integers, channel-minor. */
    private class Segment(
        val startMs: Double,
        val channels: Int,
        val rateHz: Int,
    ) {
        val values = IntList()
        val samples: Int get() = values.size / channels
        val endMs: Double get() = startMs + samples * 1000.0 / rateHz
        private val levels = mutableListOf<Level>()

        /** Level `k` summarises `10^k` samples per bucket; level 0 is the signal. Built when first asked for. */
        @Synchronized
        fun level(k: Int): Level {
            while (levels.size <= k) {
                levels += if (levels.isEmpty()) Level.of(values, channels) else levels.last().coarser()
            }
            return levels[k]
        }
    }

    /** Per-channel minimum and maximum of every bucket, in wire integers. */
    private class Level(
        val min: Array<IntArray>,
        val max: Array<IntArray>,
    ) {
        val buckets: Int get() = min[0].size

        fun coarser(): Level {
            val n = (buckets + 9) / 10
            val lo = Array(min.size) { IntArray(n) }
            val hi = Array(min.size) { IntArray(n) }
            for (c in min.indices) {
                for (b in 0 until n) {
                    var l = Int.MAX_VALUE
                    var h = Int.MIN_VALUE
                    for (i in b * 10 until min(buckets, b * 10 + 10)) {
                        l = min(l, min[c][i])
                        h = max(h, max[c][i])
                    }
                    lo[c][b] = l
                    hi[c][b] = h
                }
            }
            return Level(lo, hi)
        }

        companion object {
            fun of(
                values: IntList,
                channels: Int,
            ): Level {
                val n = values.size / channels
                val a = Array(channels) { c -> IntArray(n) { values[it * channels + c] } }
                return Level(a, a)
            }
        }
    }

    private class StreamData(
        val info: SynapseStreamInfo,
        val segments: MutableList<Segment> = mutableListOf(),
        val gaps: MutableList<SynapseGapDto> = mutableListOf(),
        var frames: Int = 0,
        var values: Long = 0,
        var lostFrames: Int = 0,
        var deviceDropped: Int = 0,
        var restarts: Int = 0,
        var outOfOrder: Int = 0,
    ) {
        fun startMs(): Long = segments.firstOrNull()?.let { it.startMs.toLong() } ?: 0L

        fun endMs(): Long = segments.lastOrNull()?.let { it.endMs.toLong() } ?: 0L
    }

    /** What was recorded, stream by stream. */
    fun summary(): SynapseRawDto =
        SynapseRawDto(
            durationMs = durationMs,
            streams =
                streams.values.map { s ->
                    SynapseRawStreamDto(
                        stream = s.info.stream.wireName,
                        rateHz = s.info.rateHz,
                        channels = channelsOf(s.info),
                        frames = s.frames,
                        values = s.values,
                        lostFrames = s.lostFrames,
                        deviceDropped = s.deviceDropped,
                        restarts = s.restarts,
                        outOfOrder = s.outOfOrder,
                        startMs = s.startMs(),
                        endMs = s.endMs(),
                        gaps = s.gaps,
                    )
                },
            invalidFrames = invalidFrames,
        )

    /**
     * The part of [stream] between [fromMs] and [toMs] (milliseconds since the recording began), at the finest level
     * that keeps each segment under [maxPoints] buckets. Values are in the contract's display unit. Null for a stream
     * that was not recorded.
     */
    fun window(
        stream: SynapseRawStream,
        fromMs: Long,
        toMs: Long,
        maxPoints: Int,
    ): SynapseWindowDto? {
        val data = streams[stream] ?: return null
        val info = data.info
        val scale = info.displayPerWire
        val segments =
            data.segments.mapNotNull { seg ->
                val from = max(fromMs.toDouble(), seg.startMs)
                val to = min(toMs.toDouble(), seg.endMs)
                if (to <= from) return@mapNotNull null
                val first = ((from - seg.startMs) * seg.rateHz / 1000.0).toInt().coerceIn(0, seg.samples - 1)
                val last = ceil((to - seg.startMs) * seg.rateHz / 1000.0).toInt().coerceIn(first + 1, seg.samples)
                var k = 0
                var size = 1
                while (ceil((last - first).toDouble() / size) > maxPoints) {
                    k++
                    size *= 10
                }
                val level = seg.level(k)
                val b0 = first / size
                val b1 = min(level.buckets, ceil(last.toDouble() / size).toInt())
                SynapseSegmentDto(
                    t0Ms = seg.startMs + b0 * size * 1000.0 / seg.rateHz,
                    bucketMs = size * 1000.0 / seg.rateHz,
                    bucketSamples = size,
                    min = List(seg.channels) { c -> (b0 until b1).map { level.min[c][it] * scale } },
                    max = List(seg.channels) { c -> (b0 until b1).map { level.max[c][it] * scale } },
                )
            }
        return SynapseWindowDto(
            stream = stream.wireName,
            fromMs = fromMs,
            toMs = toMs,
            channels = channelsOf(info),
            segments = segments,
            gaps = data.gaps.filter { it.tMs + it.durMs >= fromMs && it.tMs <= toMs },
        )
    }

    companion object {
        private fun channelsOf(info: SynapseStreamInfo) =
            info.channelNames.indices.map { SynapseChannelDto(info.channelNames[it], info.channelUnits[it]) }

        /**
         * Reads a raw sidecar. Never throws on a damaged file: a cut tail is ignored, a refused frame is counted in
         * [invalidFrames], and a stream the contract does not describe for display (TEMP, SYNC) is counted but not shown.
         */
        fun read(sidecar: ByteArray): SynapseRecording {
            val file = readSynapseSidecar(sidecar)
            val session = SynapseRawSession()
            val data = linkedMapOf<SynapseRawStream, StreamData>()
            var lastMs = 0L
            for (record in file.records) {
                val receivedMs = record.receivedNanos / 1_000_000
                lastMs = max(lastMs, receivedMs)
                val outcome = session.onFrame(record.frame)
                if (outcome !is SynapseRawOutcome.Accepted) continue
                val header = outcome.header
                val info = SYNAPSE_STREAMS[header.stream] ?: continue
                val s = data.getOrPut(header.stream) { StreamData(info) }
                val samples = header.valueCount / info.channels
                val frameMs = samples * 1000.0 / info.rateHz
                val startMs = receivedMs - frameMs
                val current = s.segments.lastOrNull()
                val reason =
                    when {
                        current == null -> null
                        outcome.restart -> "restart"
                        outcome.missed > 0 -> "lost_frames"
                        header.dropped > 0 -> "device_dropped"
                        else -> null
                    }
                if (current == null || reason != null) {
                    if (current != null) {
                        val count =
                            when (reason) {
                                "lost_frames" -> outcome.missed
                                "device_dropped" -> header.dropped
                                else -> 1
                            }
                        val from = current.endMs
                        val length = max(0L, (startMs - from).toLong())
                        s.gaps += SynapseGapDto(from.toLong(), length, reason!!, count)
                    }
                    s.segments += Segment(startMs, info.channels, info.rateHz)
                }
                val seg = s.segments.last()
                for (v in outcome.values) seg.values.add(v)
                s.frames++
                s.values += header.valueCount
                s.deviceDropped += header.dropped
            }
            for ((stream, summary) in session.streams) {
                val s = data[stream] ?: continue
                s.lostFrames = summary.lostFrames
                s.restarts = summary.restarts
                s.outOfOrder = summary.outOfOrder
            }
            return SynapseRecording(data, session.invalidFrames.mapKeys { it.key.wireName }, lastMs)
        }
    }
}

/** A growable list of ints without boxing: a 500 Hz stream is millions of them. */
internal class IntList {
    private var data = IntArray(1024)
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }

    operator fun get(i: Int): Int = data[i]
}
