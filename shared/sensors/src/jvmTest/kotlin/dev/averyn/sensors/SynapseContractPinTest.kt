package dev.averyn.sensors

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The contract files of the device repository (`SYNAPSE-24` master at `e1f1dc9`, contract 2.2), vendored here and
 * pinned by checksum so that a change of the contract is a decision and not an accident (ADR-0030). The constants
 * of this module are compared with the vendored JSON: a UUID, a size or a unit that drifts fails the build.
 *
 * To re-pin: copy the files from the device repository (LF line endings, which its `.gitattributes` enforces),
 * update the hashes below and make the parser changes in the same pull request, paired with the device repository.
 */
class SynapseContractPinTest {
    private val pinned =
        mapOf(
            // Retired, byte-identical: kept only because the research service UUIDs live in it, not in v2.
            "gatt_v1.json" to "0c6b9e38fccbf5510ea82741ed5b40c2b2338ce08fe47a7e5655f75a6b0688a9",
            "gatt_v2.json" to "dfda3fa59c865a17be94d557a81f022171ef5c8b7c619d22b32341a393e8c342",
            "raw_frame_v1.json" to "1f1db14d6a3d916ef44c25e216d53bd6d4148b43a58699b37d480a3422f83553",
            "consumer_v2_vectors.json" to "f7f63046825a854d680bef4610dabcd99bd06086cc9e9c5506fe11befc5e2c1c",
            "raw_frame_vectors_v1.json" to "fc3b66f2701912ef7760e16fad5fd1dbdc182aedbf91df34b36d7b7488edc73a",
            "band_scenarios_v1.json" to "a5a600f134c6bcf540d0cd18e92482d93d22bdb5af2d9c38036985398a25b14f",
            "session_60s.expected.json" to "a6d333ee250dbb4acec0dd27316609cbc346c0a7cb3dcc36ddfc0c8c7a6dc118",
            "session_60s.synapse.bin" to "e3abdd4471eaef9b0da0689ff1b82ea259214e4e58ff05e7e416808030e3f9c8",
            "session_60s.synapse-raw.bin" to "7fe291e0c7596fb49a074996ae8f3013bab0037ce908f600a07ec1e91b30b3b4",
        )

    private fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/synapse/$name")) { "missing $name" }.readBytes()

    private val gatt: JsonObject = Json.parseToJsonElement(bytes("gatt_v2.json").toString(Charsets.UTF_8)).jsonObject

    @Test
    fun theVendoredFilesAreTheOnesPinned() {
        for ((name, sha) in pinned) {
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes(name)).joinToString("") { "%02x".format(it) }
            assertEquals(sha, actual, "$name changed: re-pin it on purpose (ADR-0030), with the parser change")
        }
    }

    @Test
    fun theVendoredContractIsVersion22() {
        assertEquals("2.2", gatt.getValue("version").jsonPrimitive.content)
    }

    @Test
    fun theUuidsAreTheContractsOwn() {
        assertEquals(gatt.getValue("service").jsonPrimitive.content, SYNAPSE_CONSUMER_SERVICE_UUID)
        val chars = gatt.getValue("characteristics").jsonObject
        assertEquals(chars.getValue("feature").jsonPrimitive.content, SYNAPSE_FEATURE_CHARACTERISTIC_UUID)
        assertEquals(chars.getValue("status").jsonPrimitive.content, SYNAPSE_STATUS_CHARACTERISTIC_UUID)
        assertEquals(
            chars.getValue("stream_metadata").jsonPrimitive.content,
            SYNAPSE_STREAM_METADATA_CHARACTERISTIC_UUID,
        )
        assertEquals(chars.getValue("control_point").jsonPrimitive.content, SYNAPSE_CONTROL_POINT_CHARACTERISTIC_UUID)
    }

    @Test
    fun theResearchUuidsAreTheContractsOwn() {
        val research =
            Json
                .parseToJsonElement(
                    bytes("gatt_v1.json").toString(Charsets.UTF_8),
                ).jsonObject
                .getValue("services")
                .jsonObject
                .getValue("research")
                .jsonObject
        assertEquals(
            research
                .getValue("uuid")
                .jsonPrimitive.content
                .lowercase(),
            SYNAPSE_RESEARCH_SERVICE_UUID,
        )
        val chars = research.getValue("characteristics").jsonObject
        val byName =
            mapOf(
                "ECG" to SynapseRawStream.ECG,
                "PPG" to SynapseRawStream.PPG,
                "ACC" to SynapseRawStream.ACC,
                "GYRO" to SynapseRawStream.GYRO,
                "MAG" to SynapseRawStream.MAG,
                "POWER" to SynapseRawStream.POWER,
            )
        for ((name, stream) in byName) {
            assertEquals(
                chars
                    .getValue(name)
                    .jsonPrimitive.content
                    .lowercase(),
                SYNAPSE_RESEARCH_CHARACTERISTIC_UUIDS.getValue(stream),
                "$name characteristic",
            )
        }
        assertEquals(byName.size, SYNAPSE_RESEARCH_CHARACTERISTIC_UUIDS.size)
    }

    @Test
    fun theSizesAndTheMtuFloorsFollowFromTheContract() {
        assertEquals(
            gatt
                .getValue("feature")
                .jsonObject
                .getValue("bytes")
                .jsonPrimitive.int,
            SYNAPSE_FEATURE_FRAME_BYTES,
        )
        assertEquals(
            gatt
                .getValue("status")
                .jsonObject
                .getValue("bytes")
                .jsonPrimitive.int,
            SYNAPSE_STATUS_BYTES,
        )
        assertEquals(SYNAPSE_FEATURE_FRAME_BYTES + 3, SYNAPSE_FEATURE_MIN_MTU)
        assertEquals(SYNAPSE_STATUS_BYTES + 3, SYNAPSE_STATUS_MIN_MTU)
        assertEquals(
            gatt
                .getValue("feature")
                .jsonObject
                .getValue("version")
                .jsonPrimitive.int,
            SYNAPSE_FEATURE_FRAME_VERSION,
        )
        assertEquals(
            gatt
                .getValue("status")
                .jsonObject
                .getValue("version")
                .jsonPrimitive.int,
            SYNAPSE_STATUS_VERSION,
        )
        assertEquals(
            gatt
                .getValue("feature")
                .jsonObject
                .getValue("rate_max_hz")
                .jsonPrimitive.int,
            SYNAPSE_FEATURE_RATE_MAX_HZ,
        )
    }

    @Test
    fun theFeatureFieldsSitWhereTheParserReadsThem() {
        val offsets =
            gatt
                .getValue("feature")
                .jsonObject
                .getValue("fields")
                .jsonArray
                .associate {
                    it.jsonObject
                        .getValue("name")
                        .jsonPrimitive.content to
                        it.jsonObject
                            .getValue("offset")
                            .jsonPrimitive.int
                }
        // The offsets used in parseSynapseFeatureFrame, by name.
        val used =
            mapOf(
                "version" to 0,
                "flags" to 1,
                "sequence" to 2,
                "t_dev_ms" to 4,
                "heart_rate_bpm" to 8,
                "rr_last" to 10,
                "rr_count" to 12,
                "ppg_sqi" to 13,
                "ecg_quality" to 14,
                "motion_level" to 15,
                "stress_level" to 16,
                "battery_pct" to 17,
                "temperature_c100" to 18,
                "rmssd_ms" to 20,
                "sdnn_ms" to 22,
                "sport_type" to 24,
                "sensor_health" to 25,
                "motion_artifact" to 26,
            )
        assertEquals(used, offsets)
    }

    @Test
    fun theStatusFieldsSitWhereTheParserReadsThem() {
        val offsets =
            gatt
                .getValue("status")
                .jsonObject
                .getValue("fields")
                .jsonArray
                .associate {
                    it.jsonObject
                        .getValue("name")
                        .jsonPrimitive.content to
                        it.jsonObject
                            .getValue("offset")
                            .jsonPrimitive.int
                }
        val used =
            mapOf(
                "version" to 0,
                "tier" to 1,
                "sensor_health" to 2,
                "flags" to 3,
                "mtu" to 4,
                "uptime_s" to 6,
                "epoch_wraps" to 10,
                "ring_overflow" to 12,
                "stale_samples" to 16,
                "dropped_frames" to 20,
                "read_errors" to 24,
                "fw_build" to 28,
                "ppg_skipped" to 32,
                "power_dropped" to 36,
            )
        assertEquals(used, offsets)
    }

    @Test
    fun theFlagsAndHealthBitsAreTheOnesTheParserTests() {
        val flags = gatt.getValue("flags").jsonObject.mapValues { it.value.jsonPrimitive.int }
        assertEquals(mapOf("charging" to 0, "lead_off" to 1, "transmitting" to 2, "clock_wrapped" to 3), flags)
        val health = gatt.getValue("sensor_health_bits").jsonObject.mapValues { it.value.jsonPrimitive.int }
        assertEquals(
            mapOf(
                "ecg" to SynapseHealth.ECG,
                "ppg" to SynapseHealth.PPG,
                "imu" to SynapseHealth.IMU,
                "temp" to SynapseHealth.TEMP,
                "power" to SynapseHealth.POWER,
            ).mapValues { Integer.numberOfTrailingZeros(it.value) },
            health,
        )
    }

    @Test
    fun theStreamTableIsTheContractsStreamTable() {
        val streams =
            gatt
                .getValue("stream_metadata")
                .jsonObject
                .getValue("streams")
                .jsonArray
        // TEMP and SYNC carry nothing for a user and are not in the contract's table either.
        assertEquals(streams.size, SYNAPSE_STREAMS.size)
        for (entry in streams) {
            val o = entry.jsonObject
            val stream = checkNotNull(SynapseRawStream.fromId(o.getValue("stream_id").jsonPrimitive.int))
            val info = checkNotNull(SYNAPSE_STREAMS[stream]) { "no table row for $stream" }
            assertEquals(o.getValue("name").jsonPrimitive.content, stream.wireName)
            assertEquals(o.getValue("rate_hz").jsonPrimitive.int, info.rateHz, "$stream rate")
            assertEquals(o.getValue("channels").jsonPrimitive.int, info.channels, "$stream channels")
            assertEquals(o.getValue("channel_names").jsonArray.map { it.jsonPrimitive.content }, info.channelNames)
            assertEquals(o.getValue("channel_units").jsonArray.map { it.jsonPrimitive.content }, info.channelUnits)
            assertEquals(o.getValue("display_unit").jsonPrimitive.content, info.displayUnit)
            assertEquals(
                o.getValue("display_per_wire").jsonPrimitive.double,
                info.displayPerWire,
                1e-12,
                "$stream scale",
            )
            assertEquals(o.getValue("format").jsonPrimitive.int, formatOf(stream).code, "$stream format")
        }
    }

    /** The format a stream travels in, from the contract's table; the parser reads the format from each header. */
    private fun formatOf(stream: SynapseRawStream): SynapseRawFormat =
        when (stream) {
            SynapseRawStream.PPG, SynapseRawStream.POWER -> SynapseRawFormat.S32
            else -> SynapseRawFormat.S16
        }

    @Test
    fun theControlPointIsTheContractsControlPoint() {
        val control = gatt.getValue("control_point").jsonObject
        val opcodes = control.getValue("opcodes").jsonObject.mapValues { it.value.jsonPrimitive.int }
        assertEquals(mapOf("start" to 1, "stop" to 2, "set_feature_rate" to 3, "set_time" to 4), opcodes)
        assertEquals(opcodes.getValue("start"), SynapseControl.start()[0].toInt())
        assertEquals(opcodes.getValue("stop"), SynapseControl.stop()[0].toInt())
        assertEquals(opcodes.getValue("set_feature_rate"), SynapseControl.setFeatureRate(1)[0].toInt())
        assertEquals(opcodes.getValue("set_time"), SynapseControl.setTime(SYNAPSE_SET_TIME_MIN_EPOCH_MS)[0].toInt())
        assertEquals(
            control
                .getValue("set_time_min_epoch_ms")
                .jsonPrimitive.content
                .toLong(),
            SYNAPSE_SET_TIME_MIN_EPOCH_MS,
        )
        val errors = control.getValue("errors").jsonObject.mapValues { it.value.jsonPrimitive.int }
        assertEquals(
            mapOf(
                "bad_opcode" to SynapseControlError.BAD_OPCODE,
                "bad_length" to SynapseControlError.BAD_LENGTH,
                "bad_argument" to SynapseControlError.BAD_ARGUMENT,
            ),
            errors,
        )
    }
}
