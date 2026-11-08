package dev.averyn.sensors

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The parsers against the golden vectors of the device repository, which come from its SDK encoder
 * (`scripts/make_consumer_vectors.py`), not from this code. A vector that cannot be decoded as it says is a
 * disagreement between the two repositories, and the build fails until someone decides which side is wrong.
 */
class SynapseVectorsTest {
    private fun text(name: String): String =
        checkNotNull(
            javaClass.getResourceAsStream("/synapse/$name"),
        ) { "missing $name" }.readBytes().toString(Charsets.UTF_8)

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun JsonObject.wire(key: String): Int? = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int

    private val consumer = Json.parseToJsonElement(text("consumer_v2_vectors.json")).jsonObject
    private val raw = Json.parseToJsonElement(text("raw_frame_vectors_v1.json")).jsonObject

    private fun feature(name: String): Pair<JsonObject, SynapseFeatureFrame> {
        val case =
            consumer.getValue("feature").jsonArray.map { it.jsonObject }.first {
                it.getValue("name").jsonPrimitive.content ==
                    name
            }
        val result = parseSynapseFeatureFrame(hex(case.getValue("hex").jsonPrimitive.content))
        return case.getValue("values").jsonObject to assertIs<SynapseFrameResult.Frame>(result).frame
    }

    @Test
    fun everyFeatureVectorDecodesAsItsEncoderInputSays() {
        for (case in consumer.getValue("feature").jsonArray.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val values = case.getValue("values").jsonObject
            val bytes = hex(case.getValue("hex").jsonPrimitive.content)
            assertEquals(SYNAPSE_FEATURE_FRAME_BYTES, bytes.size, name)
            val f = assertIs<SynapseFrameResult.Frame>(parseSynapseFeatureFrame(bytes), name).frame

            assertEquals(values.wire("sequence"), f.sequence, "$name sequence")
            assertEquals(values.getValue("t_dev_ms").jsonPrimitive.long, f.deviceMillis, "$name t_dev_ms")
            assertEquals(values.wire("sensor_health"), f.sensorHealth, "$name health")
            // A value on the wire that is out of its physical range is null, not clipped: 0 bpm is no heart rate.
            assertEquals(values.wire("heart_rate_bpm")?.takeIf { it in 1..300 }, f.heartRateBpm, "$name hr")
            assertEquals(values.wire("rr_count"), f.rrCount, "$name rr_count")
            assertEquals(values.wire("rmssd_ms"), f.rmssdMs, "$name rmssd")
            assertEquals(values.wire("sdnn_ms"), f.sdnnMs, "$name sdnn")
            assertEquals(values.wire("sport_type"), f.sport, "$name sport")
            assertEquals(values.wire("battery_pct"), f.batteryPercent, "$name battery")
            assertEquals(values.wire("temperature_c100")?.let { it / 100.0 }, f.temperatureCelsius, "$name temperature")
            // A scale of 1/254: 255 is the sentinel, so 254 is the full scale. Never /255.
            assertEquals(values.wire("ppg_sqi")?.let { it / 254.0 }, f.ppgQuality, "$name sqi")
            assertEquals(values.wire("ecg_quality")?.let { it / 254.0 }, f.ecgQuality, "$name ecg quality")
            assertEquals(values.wire("motion_level")?.let { it / 254.0 }, f.motionLevel, "$name motion")
            assertEquals(
                values.wire("motion_artifact")?.let { it / 254.0 },
                f.motionArtifactProbability,
                "$name artifact",
            )
        }
    }

    @Test
    fun aRestingFrameHasTheValuesTheBandSent() {
        val (_, f) = feature("typical_resting")
        assertEquals(62, f.heartRateBpm)
        assertEquals(991 * 1000.0 / 1024.0, checkNotNull(f.rrLastMs), 1e-9) // the wire carries 1/1024 s
        assertEquals(1, f.rrCount)
        assertEquals(SynapseStress.BASELINE, f.stress)
        assertEquals(87, f.batteryPercent)
        assertEquals(33.45, checkNotNull(f.temperatureCelsius), 1e-9)
        assertEquals(48, f.rmssdMs)
        assertEquals(61, f.sdnnMs)
        assertEquals(3_600_000L, f.deviceMillis)
        assertTrue(f.transmitting)
        assertNull(f.charging, "no power monitor: charging is not 'false', it is not known")
    }

    @Test
    fun aFrameWithNothingAvailableIsAllNull() {
        val (_, f) = feature("all_not_available")
        assertNull(f.heartRateBpm)
        assertNull(f.rrLastMs)
        assertNull(f.rrCount)
        assertNull(f.ppgQuality)
        assertNull(f.ecgQuality)
        assertNull(f.motionLevel)
        assertNull(f.motionArtifactProbability)
        assertNull(f.stress)
        assertNull(f.batteryPercent)
        assertNull(f.temperatureCelsius)
        assertNull(f.rmssdMs)
        assertNull(f.sdnnMs)
        assertNull(f.sport)
    }

    @Test
    fun anUnknownStressClassIsNotANullClass() {
        val (_, f) = feature("stress_class_unknown_is_not_na")
        // the model ran and has no class; a model that did not run is null, not UNKNOWN
        assertEquals(SynapseStress.UNKNOWN, f.stress)
    }

    @Test
    fun chargingIsReadOnlyWhenThePowerMonitorExists() {
        val (_, f) = feature("charging_with_power_sensor")
        assertEquals(true, f.charging)
        assertEquals(100, f.batteryPercent)
    }

    @Test
    fun theExtremesStayWithinTheirRanges() {
        val (_, f) = feature("extremes")
        assertEquals(65535, f.sequence)
        assertEquals(4_294_967_295L, f.deviceMillis)
        assertEquals(300, f.heartRateBpm)
        assertNull(f.rrLastMs, "65534/1024 s is not a beat interval")
        assertEquals(254, f.rrCount)
        assertEquals(1.0, checkNotNull(f.ppgQuality), 0.0)
        assertEquals(SynapseStress.ARTIFACT, f.stress)
        assertEquals(4, f.sport)
        assertEquals(327.66, checkNotNull(f.temperatureCelsius), 1e-9)
        assertTrue(f.leadOff)
        assertTrue(f.clockWrapped)
        assertEquals(true, f.charging)
    }

    @Test
    fun aNegativeTemperatureKeepsItsSign() {
        val (_, f) = feature("negative_temperature")
        assertEquals(-12.34, checkNotNull(f.temperatureCelsius), 1e-9)
        assertNull(f.heartRateBpm, "0 bpm is not a heart rate")
    }

    @Test
    fun aShortOrForeignFrameIsReportedAndNeverRead() {
        val good =
            hex(
                consumer
                    .getValue("feature")
                    .jsonArray
                    .first()
                    .jsonObject
                    .getValue("hex")
                    .jsonPrimitive.content,
            )
        assertEquals(SynapseFrameResult.Truncated(0), parseSynapseFeatureFrame(ByteArray(0)))
        assertEquals(SynapseFrameResult.Truncated(26), parseSynapseFeatureFrame(good.copyOf(26)))
        assertEquals(SynapseFrameResult.UnknownVersion(3), parseSynapseFeatureFrame(good.also { it[0] = 3 }))
        // A longer frame of the same version is read for the fields it knows.
        val longer =
            hex(
                consumer
                    .getValue("feature")
                    .jsonArray
                    .first()
                    .jsonObject
                    .getValue("hex")
                    .jsonPrimitive.content,
            ) +
                byteArrayOf(9, 9)
        assertIs<SynapseFrameResult.Frame>(parseSynapseFeatureFrame(longer))
    }

    @Test
    fun everyStatusVectorDecodesAsItsEncoderInputSays() {
        for (case in consumer.getValue("status").jsonArray.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val values = case.getValue("values").jsonObject
            val bytes = hex(case.getValue("hex").jsonPrimitive.content)
            assertEquals(SYNAPSE_STATUS_BYTES, bytes.size)
            val s = assertIs<SynapseStatusResult.Status>(parseSynapseStatus(bytes), name).status
            assertEquals(values.wire("tier"), s.tier, name)
            assertEquals(values.wire("sensor_health"), s.sensorHealth, name)
            assertEquals(values.wire("mtu"), s.mtu, name)
            assertEquals((values.wire("uptime_s") ?: 0).toLong(), s.uptimeSeconds, name)
            assertEquals(values.wire("epoch_wraps") ?: 0, s.epochWraps, name)
            assertEquals((values["ring_overflow"]?.jsonPrimitive?.long ?: 0L), s.ringOverflow, name)
            assertEquals((values["stale_samples"]?.jsonPrimitive?.long ?: 0L), s.staleSamples, name)
            assertEquals((values["dropped_frames"]?.jsonPrimitive?.long ?: 0L), s.droppedFrames, name)
            assertEquals((values["read_errors"]?.jsonPrimitive?.long ?: 0L), s.readErrors, name)
            assertEquals((values["fw_build"]?.jsonPrimitive?.long ?: 0L), s.firmwareBuild, name)
            assertEquals((values["ppg_skipped"]?.jsonPrimitive?.long ?: 0L), s.ppgSkipped, name)
            assertEquals((values["power_dropped"]?.jsonPrimitive?.long ?: 0L), s.powerDropped, name)
            assertEquals(((values.wire("flags") ?: 0) and 0x04) != 0, s.transmitting, name)
        }
        assertEquals(SynapseStatusResult.Truncated(39), parseSynapseStatus(ByteArray(39) { if (it == 0) 1 else 0 }))
        assertEquals(SynapseStatusResult.UnknownVersion(2), parseSynapseStatus(ByteArray(40) { if (it == 0) 2 else 0 }))
    }

    @Test
    fun everyValidRawVectorDecodesAsItsEncoderInputSays() {
        for (case in raw.getValue("valid").jsonArray.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val expected = case.getValue("expected").jsonObject
            val frame =
                assertIs<SynapseRawResult.Frame>(
                    decodeSynapseRawFrame(hex(case.getValue("hex").jsonPrimitive.content)),
                    name,
                )
            val h = frame.header
            assertEquals(expected.getValue("stream").jsonPrimitive.content, h.stream.wireName, name)
            assertEquals(expected.getValue("sequence").jsonPrimitive.int, h.sequence, name)
            assertEquals(expected.getValue("timestamp_us").jsonPrimitive.long, h.timestampUs, name)
            assertEquals(expected.getValue("ts_epoch_wrapped").jsonPrimitive.boolean, h.timestampWrapped, name)
            assertEquals(expected.getValue("sample_format").jsonPrimitive.content, h.format.name.lowercase(), name)
            assertEquals(expected.getValue("dropped").jsonPrimitive.int, h.dropped, name)
            assertEquals(
                expected.getValue("values").jsonArray.map { it.jsonPrimitive.int },
                frame.values.toList(),
                name,
            )
        }
    }

    @Test
    fun everyInvalidRawVectorIsRefusedForItsReason() {
        for (case in raw.getValue("invalid").jsonArray.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val result = decodeSynapseRawFrame(hex(case.getValue("hex").jsonPrimitive.content))
            assertEquals(
                case.getValue("reason").jsonPrimitive.content,
                assertIs<SynapseRawResult.Invalid>(result, name).reason.wireName,
                name,
            )
        }
    }

    @Test
    fun aSetTimeWriteIsLittleEndianMilliseconds() {
        val bytes = SynapseControl.setTime(1_700_000_000_123L)
        assertEquals(9, bytes.size)
        assertEquals(4, bytes[0].toInt())
        var back = 0L
        for (i in 0 until 8) back = back or ((bytes[1 + i].toLong() and 0xFF) shl (8 * i))
        assertEquals(1_700_000_000_123L, back)
        assertEquals(listOf<Byte>(3, 5), SynapseControl.setFeatureRate(5).toList())
        assertEquals(listOf<Byte>(1), SynapseControl.start().toList())
        assertEquals(listOf<Byte>(2), SynapseControl.stop().toList())
    }

    @Test
    fun aControlWriteOutsideItsRangeIsRefusedHere() {
        for (bad in listOf(0, 6)) {
            val failed = runCatching { SynapseControl.setFeatureRate(bad) }.isFailure
            assertTrue(failed, "$bad Hz")
        }
        assertTrue(runCatching { SynapseControl.setTime(SYNAPSE_SET_TIME_MIN_EPOCH_MS - 1) }.isFailure)
    }
}
