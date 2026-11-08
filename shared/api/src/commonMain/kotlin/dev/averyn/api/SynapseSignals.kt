package dev.averyn.api

import kotlinx.serialization.Serializable

/**
 * What a Synapse band recorded during an activity, as the server shows it (ADR-0030, level L1 and L1R). The contract of
 * `GET /v1/activities/{id}/synapse` and of the signal window next to it.
 *
 * Everything here is **imported data**: the band computed it, Averyn did not, and it is shown as "via Synapse" with the
 * producer's own labels, never as an Averyn metric (ADR-0014). A value the band marked "not available" is `null`,
 * never zero, and a stretch with no data is a [SynapseGapDto], never a line drawn across it.
 */
@Serializable
data class SynapseSummaryDto(
    val clientActivityId: String,
    /** Provenance of every number below. */
    val source: String = "synapse-device",
    /** The version of the transformation Averyn applied (decimation, units, gap detection), not the band's own. */
    val seriesVersion: String = "synapse-series-v1",
    /** The derived values at about 1 Hz; null when the user did not consent to `signal_quality` or nothing was stored. */
    val features: SynapseFeaturesDto? = null,
    /** The raw signals; null without the `raw_signals` consent or when nothing was stored. */
    val raw: SynapseRawDto? = null,
)

/** The feature frames of the activity, as parallel arrays on the phone's reception clock. */
@Serializable
data class SynapseFeaturesDto(
    val frames: Int,
    /** Frames the link lost, from the 16-bit counter. */
    val lostFrames: Int,
    /** Frames of a version this server does not read: counted, never interpreted. */
    val unknownVersionFrames: Int,
    val statusRecords: Int,
    /** Milliseconds since the recording began, at the phone, for each frame. */
    val elapsedMs: List<Long>,
    val heartRateBpm: List<Int?>,
    /** 0.0 to 1.0. */
    val ppgQuality: List<Double?>,
    val motionArtifact: List<Double?>,
    val motionLevel: List<Double?>,
    val batteryPercent: List<Int?>,
    /** `baseline`, `stress`, `artifact` or `unknown`; all null without the `stress_monitoring` consent. */
    val stress: List<String?>,
    /** Beat-to-beat interval in ms; all null without the `rr_recording` consent. */
    val rrLastMs: List<Double?>,
    /** The band's own RMSSD and SDNN; all null without the `hrv_analysis` consent. */
    val rmssdMs: List<Int?>,
    val sdnnMs: List<Int?>,
    val gaps: List<SynapseGapDto>,
)

/** A stretch with no data, and why. */
@Serializable
data class SynapseGapDto(
    /** Where it starts, milliseconds since the recording began. */
    val tMs: Long,
    val durMs: Long,
    /** `lost_frames`, `device_dropped` or `restart`. */
    val reason: String,
    /** Frames or samples, depending on the reason. */
    val count: Int,
)

@Serializable
data class SynapseChannelDto(
    val name: String,
    /** The unit of the displayed values, from the contract. */
    val unit: String,
)

@Serializable
data class SynapseRawDto(
    val durationMs: Long,
    val streams: List<SynapseRawStreamDto>,
    /** Frames refused, by reason (`unknown_version`, `truncated`, ...). */
    val invalidFrames: Map<String, Int>,
)

@Serializable
data class SynapseRawStreamDto(
    val stream: String,
    val rateHz: Int,
    val channels: List<SynapseChannelDto>,
    val frames: Int,
    val values: Long,
    val lostFrames: Int,
    /** Samples the band says it discarded itself. */
    val deviceDropped: Int,
    val restarts: Int,
    val outOfOrder: Int,
    val startMs: Long,
    val endMs: Long,
    val gaps: List<SynapseGapDto>,
)

/**
 * A window of one raw stream, ready to draw: for each contiguous [SynapseSegmentDto] the minimum and maximum of every
 * bucket, so a 500 Hz signal can be shown whole and zoomed to a single beat without sending every sample.
 */
@Serializable
data class SynapseWindowDto(
    val stream: String,
    val fromMs: Long,
    val toMs: Long,
    val channels: List<SynapseChannelDto>,
    val segments: List<SynapseSegmentDto>,
    val gaps: List<SynapseGapDto>,
)

/** A stretch of the stream with no hole in it. Between two segments there is a gap: do not join them. */
@Serializable
data class SynapseSegmentDto(
    /** Milliseconds since the recording began, at the start of the first bucket. */
    val t0Ms: Double,
    /** The duration of one bucket. */
    val bucketMs: Double,
    /** Samples per bucket: 1 is the signal itself, then 10, 100, ... */
    val bucketSamples: Int,
    /** `[channel][bucket]`, in the channel's unit. */
    val min: List<List<Double>>,
    val max: List<List<Double>>,
)
