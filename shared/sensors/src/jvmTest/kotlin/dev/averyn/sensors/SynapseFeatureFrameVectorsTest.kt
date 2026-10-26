package dev.averyn.sensors

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Synapse feature frame against the golden vectors of the contract (ADR-0026, docs/integrations/synapse.md).
 *
 * The vectors were generated from the packed layout of the device's struct with Python's `struct`, independently of
 * this parser; the contract file is a vendored copy of the device repository's `contracts/gatt_v1.json`, pinned by
 * its checksum so that a change of the contract is a decision and not an accident.
 */
class SynapseFeatureFrameVectorsTest {
    /** The checksum of the vendored `gatt_v1.json` (LF line endings). Update it only with a new pin. */
    private val pinnedContractSha256 = "0c6b9e38fccbf5510ea82741ed5b40c2b2338ce08fe47a7e5655f75a6b0688a9"

    private fun resource(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/synapse/$name")) { "missing $name" }
            .readBytes()
            .toString(Charsets.UTF_8)

    private fun hex(text: String): ByteArray =
        ByteArray(text.length / 2) {
            text.substring(2 * it, 2 * it + 2).toInt(16).toByte()
        }

    private val vectors =
        Json
            .parseToJsonElement(resource("feature_frame_v1_vectors.json"))
            .jsonObject
            .getValue("vectors")
            .jsonArray

    @Test
    fun theVendoredContractIsTheOnePinned() {
        val text = resource("gatt_v1.json").replace("\r\n", "\n")
        val sha256 =
            MessageDigest
                .getInstance(
                    "SHA-256",
                ).digest(text.toByteArray())
                .joinToString("") { "%02x".format(it) }

        assertEquals(pinnedContractSha256, sha256, "the contract changed: re-pin it on purpose (ADR-0026)")
    }

    @Test
    fun theUuidsInTheCodeAreTheOnesOfTheContract() {
        val contract = Json.parseToJsonElement(resource("gatt_v1.json")).jsonObject
        val feature =
            contract
                .getValue("services")
                .jsonObject
                .getValue("feature_stream")
                .jsonObject

        assertEquals("1.0", contract.getValue("version").jsonPrimitive.content)
        assertEquals(
            SYNAPSE_FEATURE_SERVICE_UUID,
            feature
                .getValue("uuid")
                .jsonPrimitive.content
                .lowercase(),
        )
        assertEquals(
            SYNAPSE_FEATURE_CHARACTERISTIC_UUID,
            feature
                .getValue("characteristics")
                .jsonObject
                .getValue("feature")
                .jsonPrimitive.content
                .lowercase(),
        )
    }

    @Test
    fun everyVectorIsParsedAsTheContractSays() {
        assertTrue(vectors.size >= 8, "the vectors file lost vectors")
        for (entry in vectors) {
            val vector = entry.jsonObject
            val name = vector.getValue("name").jsonPrimitive.content
            val expected = vector.getValue("expected").jsonObject
            val result = parseSynapseFeatureFrame(hex(vector.getValue("hex").jsonPrimitive.content))
            when (vector.getValue("result").jsonPrimitive.content) {
                "frame" -> assertFrame(name, expected, assertIs<SynapseFrameResult.Frame>(result, name).frame)
                "unknownVersion" ->
                    assertEquals(
                        expected.getValue("version").jsonPrimitive.int,
                        assertIs<SynapseFrameResult.UnknownVersion>(result, name).version,
                        name,
                    )
                "truncated" ->
                    assertEquals(
                        expected.getValue("size").jsonPrimitive.int,
                        assertIs<SynapseFrameResult.Truncated>(result, name).size,
                        name,
                    )
                else -> error("unknown result kind in $name")
            }
        }
    }

    private fun assertFrame(
        name: String,
        expected: JsonObject,
        frame: SynapseFeatureFrame,
    ) {
        fun int(field: String) = expected.getValue(field).let { if (it is JsonNull) null else it.jsonPrimitive.int }

        fun real(field: String) = expected.getValue(field).let { if (it is JsonNull) null else it.jsonPrimitive.double }

        fun checkReal(
            field: String,
            actual: Double?,
        ) {
            val want = real(field)
            if (want == null) {
                assertNull(actual, "$name: $field")
            } else {
                assertEquals(want, assertNotNull(actual, "$name: $field"), 1e-9, "$name: $field")
            }
        }
        assertEquals(int("sequence"), frame.sequence, "$name: sequence")
        assertEquals(int("heartRateBpm"), frame.heartRateBpm, "$name: heartRateBpm")
        assertEquals(int("rrIntervalMs"), frame.rrIntervalMs, "$name: rrIntervalMs")
        checkReal("ppgQuality", frame.ppgQuality)
        checkReal("motionArtifactProbability", frame.motionArtifactProbability)
        checkReal("ecgQuality", frame.ecgQuality)
        assertEquals(expected.getValue("stress").jsonPrimitive.contentOrNull, frame.stress?.name, "$name: stress")
        checkReal("motionLevel", frame.motionLevel)
        assertEquals(int("batteryPercent"), frame.batteryPercent, "$name: batteryPercent")
        assertEquals(expected.getValue("charging").jsonPrimitive.booleanOrNull, frame.charging, "$name: charging")
        assertEquals(int("sport"), frame.sport, "$name: sport")
        assertEquals(int("cadenceRpm"), frame.cadenceRpm, "$name: cadenceRpm")
        checkReal("temperatureCelsius", frame.temperatureCelsius)
        assertEquals(int("rmssdMs"), frame.rmssdMs, "$name: rmssdMs")
        assertEquals(int("sdnnMs"), frame.sdnnMs, "$name: sdnnMs")
    }
}
