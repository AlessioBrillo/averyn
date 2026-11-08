package dev.averyn.sensors

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The synthetic band sessions of the device repository, replayed through the session reader and compared with the
 * answer key it publishes. The key is computed there by a reference implementation of the same rules
 * (`scripts/make_band_fixtures.py`, `make_band_sidecars.py`); nothing here is a measurement.
 *
 * What this proves: this reader draws the same conclusions as the contract's reference about losses, restarts,
 * late frames, the 32-bit clock wrap and refused frames. What it does not: that a real band behaves like these
 * sessions. There is no band yet.
 */
class SynapseFixturesTest {
    private fun bytes(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/synapse/$name")) { "missing $name" }.readBytes()

    private fun json(name: String): JsonObject =
        Json.parseToJsonElement(bytes(name).toString(Charsets.UTF_8)).jsonObject

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** The account of a session, in the shape the answer key uses. */
    private fun assertMatchesKey(
        name: String,
        session: SynapseRawSession,
        key: JsonObject,
    ) {
        val streams = key.getValue("streams").jsonObject
        assertEquals(
            streams.keys,
            session.streams.keys
                .map { it.wireName }
                .toSet(),
            "$name: streams seen",
        )
        for ((stream, expected) in streams) {
            val e = expected.jsonObject
            val s = checkNotNull(session.streams.values.firstOrNull { it.stream.wireName == stream })
            assertEquals(e.getValue("frames").jsonPrimitive.int, s.frames, "$name/$stream frames")
            assertEquals(e.getValue("values").jsonPrimitive.long, s.values, "$name/$stream values")
            assertEquals(
                e.getValue("device_dropped").jsonPrimitive.int,
                s.deviceDropped,
                "$name/$stream device_dropped",
            )
            assertEquals(e.getValue("wraps").jsonPrimitive.int, s.wraps, "$name/$stream wraps")
            assertEquals(e.getValue("lost_frames").jsonPrimitive.int, s.lostFrames, "$name/$stream lost")
            assertEquals(e.getValue("out_of_order").jsonPrimitive.int, s.outOfOrder, "$name/$stream out_of_order")
            assertEquals(e.getValue("restarts").jsonPrimitive.int, s.restarts, "$name/$stream restarts")
            assertEquals(
                e.getValue("timeline_us").jsonArray.map { it.jsonPrimitive.long },
                s.timelineUs,
                "$name/$stream: the band's clock past the 32-bit wrap",
            )
            assertEquals(
                e.getValue("events").jsonArray.map {
                    it.jsonObject
                        .getValue("sequence")
                        .jsonPrimitive.int to
                        it.jsonObject
                            .getValue("event")
                            .jsonPrimitive.content
                },
                s.events.map { it.sequence to it.kind },
                "$name/$stream events",
            )
        }
        assertEquals(
            key.getValue("invalid_frames").jsonObject.mapValues { it.value.jsonPrimitive.int },
            session.invalidFrames.mapKeys { it.key.wireName },
            "$name: refused frames, by reason",
        )
    }

    @Test
    fun everyRawScenarioDrawsTheSameConclusionsAsTheReference() {
        val scenarios = json("band_scenarios_v1.json").getValue("raw").jsonArray.map { it.jsonObject }
        assertEquals(7, scenarios.size) // clean, lost, restart, wrap, device-reported loss, invalid, repeat and late
        for (scenario in scenarios) {
            val name = scenario.getValue("name").jsonPrimitive.content
            val session = SynapseRawSession()
            for (frame in scenario.getValue("frames").jsonArray) {
                session.onFrame(
                    hex(
                        frame.jsonObject
                            .getValue("hex")
                            .jsonPrimitive.content,
                    ),
                )
            }
            assertMatchesKey(name, session, scenario.getValue("expected").jsonObject)
        }
    }

    @Test
    fun theL1ScenarioCountsWhatTheReferenceCounts() {
        val l1 = json("band_scenarios_v1.json").getValue("l1").jsonObject
        val expected = l1.getValue("expected").jsonObject
        val tracker = SynapseSequenceTracker()
        var unknown = 0
        var accepted = 0
        for (entry in l1.getValue("frames").jsonArray.map { it.jsonObject }) {
            val frame = hex(entry.getValue("hex").jsonPrimitive.content)
            when (val parsed = parseSynapseFeatureFrame(frame)) {
                is SynapseFrameResult.UnknownVersion -> unknown++
                is SynapseFrameResult.Frame -> {
                    accepted++
                    tracker.classify(parsed.frame.sequence)
                    val values = entry.getValue("values").jsonObject
                    // Warm-up frames carry sentinels: nothing but the heart rate is known yet.
                    val expectedSqi = values["ppg_sqi"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int
                    assertEquals(expectedSqi?.let { it / 254.0 }, parsed.frame.ppgQuality)
                }
                is SynapseFrameResult.Truncated -> error("a frame of the scenario is cut")
            }
        }
        assertEquals(expected.getValue("lost_frames").jsonPrimitive.int, tracker.lostFrames)
        assertEquals(expected.getValue("restarts").jsonPrimitive.int, tracker.restarts)
        assertEquals(expected.getValue("out_of_order").jsonPrimitive.int, tracker.outOfOrderFrames)
        assertEquals(expected.getValue("unknown_version_frames").jsonPrimitive.int, unknown)
        assertEquals(expected.getValue("accepted_frames").jsonPrimitive.int, accepted)
    }

    @Test
    fun theRawSidecarOfAMinuteReplaysToTheAnswerKey() {
        val sidecar = readSynapseSidecar(bytes("session_60s.synapse-raw.bin"))
        val key = json("session_60s.expected.json").getValue("raw").jsonObject
        assertEquals(0, sidecar.trailingBytes, "the file ends on a whole record")
        assertEquals(key.getValue("records").jsonPrimitive.int, sidecar.records.size)
        assertTrue(
            sidecar.records.zipWithNext().all { (a, b) ->
                a.receivedNanos <= b.receivedNanos
            },
            "reception order",
        )

        val session = SynapseRawSession()
        for (record in sidecar.records) session.onFrame(record.frame)
        assertMatchesKey("session_60s", session, key)
    }

    @Test
    fun theConsumerSidecarOfAMinuteReplaysToTheAnswerKey() {
        val sidecar = readSynapseSidecar(bytes("session_60s.synapse.bin"))
        val key = json("session_60s.expected.json").getValue("l1").jsonObject
        assertEquals(0, sidecar.trailingBytes)
        assertEquals(key.getValue("records").jsonPrimitive.int, sidecar.records.size)

        val tracker = SynapseSequenceTracker()
        var features = 0
        var statuses = 0
        var unknown = 0
        for (record in sidecar.records) {
            when (record.frame.size) {
                SYNAPSE_STATUS_BYTES -> {
                    statuses++
                    assertIs<SynapseStatusResult.Status>(parseSynapseStatus(record.frame))
                }
                else -> {
                    when (val parsed = parseSynapseFeatureFrame(record.frame)) {
                        is SynapseFrameResult.Frame -> {
                            features++
                            tracker.classify(parsed.frame.sequence)
                        }
                        is SynapseFrameResult.UnknownVersion -> unknown++
                        is SynapseFrameResult.Truncated -> error("a feature record is cut")
                    }
                }
            }
        }
        assertEquals(key.getValue("feature_frames").jsonPrimitive.int, features)
        assertEquals(key.getValue("status_records").jsonPrimitive.int, statuses)
        assertEquals(key.getValue("unknown_version_frames").jsonPrimitive.int, unknown)
        // The unknown-version frame was skipped but used a sequence number, so the reader sees a hole there too.
        assertEquals(key.getValue("lost_frames").jsonPrimitive.int, tracker.lostFrames)
        assertEquals(key.getValue("restarts").jsonPrimitive.int, tracker.restarts)
        assertEquals(key.getValue("out_of_order").jsonPrimitive.int, tracker.outOfOrderFrames)
    }
}
