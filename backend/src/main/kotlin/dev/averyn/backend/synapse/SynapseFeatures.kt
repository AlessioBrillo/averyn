package dev.averyn.backend.synapse

import dev.averyn.api.SynapseFeaturesDto
import dev.averyn.api.SynapseGapDto
import dev.averyn.sensors.SYNAPSE_STATUS_BYTES
import dev.averyn.sensors.SynapseFrameResult
import dev.averyn.sensors.SynapseSequenceTracker
import dev.averyn.sensors.SynapseSequenceVerdict
import dev.averyn.sensors.parseSynapseFeatureFrame
import dev.averyn.sensors.readSynapseSidecar
import kotlin.math.max

/** Which of the consents beyond `signal_quality` are in force when the series is read (fail closed: no consent, no field). */
data class FeatureConsent(
    val stress: Boolean,
    val rr: Boolean,
    val bandHrv: Boolean,
)

/**
 * The feature sidecar as parallel arrays on the phone's reception clock (ADR-0030, level L1). The sidecar is
 * stored as received; this is a derived view. A field the consents do not cover is `null` all the way down,
 * whatever the stored bytes say (they were masked when the consent was revoked, and this masks again so that a
 * file written before the revocation can never leak through a read).
 */
fun buildFeatures(
    sidecar: ByteArray,
    consent: FeatureConsent,
): SynapseFeaturesDto {
    val file = readSynapseSidecar(sidecar)
    val tracker = SynapseSequenceTracker()
    val elapsed = ArrayList<Long>()
    val hr = ArrayList<Int?>()
    val sqi = ArrayList<Double?>()
    val artifact = ArrayList<Double?>()
    val motion = ArrayList<Double?>()
    val battery = ArrayList<Int?>()
    val stress = ArrayList<String?>()
    val rr = ArrayList<Double?>()
    val rmssd = ArrayList<Int?>()
    val sdnn = ArrayList<Int?>()
    val gaps = ArrayList<SynapseGapDto>()
    var unknown = 0
    var statuses = 0
    for (record in file.records) {
        if (record.frame.size == SYNAPSE_STATUS_BYTES) {
            statuses++
            continue
        }
        val parsed = parseSynapseFeatureFrame(record.frame)
        if (parsed is SynapseFrameResult.UnknownVersion) {
            unknown++
            continue
        }
        val f = (parsed as? SynapseFrameResult.Frame)?.frame ?: continue
        val ms = record.receivedNanos / 1_000_000
        when (val verdict = tracker.classify(f.sequence)) {
            SynapseSequenceVerdict.OutOfOrder -> continue // a repeat or a late frame adds no point
            SynapseSequenceVerdict.Restart -> {
                val last = elapsed.lastOrNull() ?: ms
                gaps += SynapseGapDto(last, max(0L, ms - last), "restart", 1)
            }
            is SynapseSequenceVerdict.InOrder ->
                if (verdict.missed > 0) {
                    val last = elapsed.lastOrNull() ?: ms
                    gaps += SynapseGapDto(last, max(0L, ms - last), "lost_frames", verdict.missed)
                }
        }
        elapsed += ms
        hr += f.heartRateBpm
        sqi += f.ppgQuality
        artifact += f.motionArtifactProbability
        motion += f.motionLevel
        battery += f.batteryPercent
        stress += if (consent.stress) f.stress?.name?.lowercase() else null
        rr += if (consent.rr) f.rrLastMs else null
        rmssd += if (consent.bandHrv) f.rmssdMs else null
        sdnn += if (consent.bandHrv) f.sdnnMs else null
    }
    return SynapseFeaturesDto(
        frames = elapsed.size,
        lostFrames = tracker.lostFrames,
        unknownVersionFrames = unknown,
        statusRecords = statuses,
        elapsedMs = elapsed,
        heartRateBpm = hr,
        ppgQuality = sqi,
        motionArtifact = artifact,
        motionLevel = motion,
        batteryPercent = battery,
        stress = stress,
        rrLastMs = rr,
        rmssdMs = rmssd,
        sdnnMs = sdnn,
        gaps = gaps,
    )
}
