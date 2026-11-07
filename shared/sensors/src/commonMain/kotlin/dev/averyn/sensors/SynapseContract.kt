package dev.averyn.sensors

// The GATT identifiers of a Synapse band, contract 2.2 (`contracts/gatt_v2.json` and `gatt_v1.json` of the device
// repository; vendored and pinned by checksum in the tests, ADR-0030). They are constants and not parsed at run
// time: a test compares each one with the vendored contract, so a change of the contract fails the build.

/** The consumer service: the v2 feature, status, stream-metadata and control characteristics live on it. */
const val SYNAPSE_CONSUMER_SERVICE_UUID = "f9e08ffd-3d34-441f-97a6-1bf0958123f1"

/** Notifies the feature frames (27 bytes, 1 Hz by default). */
const val SYNAPSE_FEATURE_CHARACTERISTIC_UUID = "23443aaf-382c-4475-baf8-ea3c59ba1a82"

/** Notifies the status record (40 bytes): tier, health, MTU, uptime and the band's own loss counters. */
const val SYNAPSE_STATUS_CHARACTERISTIC_UUID = "fea798da-b114-47c9-8ec4-265bb358e741"

/** Read once after connecting: how each raw stream is laid out (rate, channels, unit). */
const val SYNAPSE_STREAM_METADATA_CHARACTERISTIC_UUID = "07458f36-0a8a-4ad0-8513-41279bb5d981"

/** Written to start and stop the raw streams, to set the feature rate and the band's wall clock. */
const val SYNAPSE_CONTROL_POINT_CHARACTERISTIC_UUID = "bc560345-f1e9-4bbf-98a7-e545c88c02a1"

/** The research service: one characteristic per raw stream. Averyn subscribes only under `raw_signals` (ADR-0030). */
const val SYNAPSE_RESEARCH_SERVICE_UUID = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c70"

/** Characteristic UUIDs of the raw streams, by stream. */
val SYNAPSE_RESEARCH_CHARACTERISTIC_UUIDS: Map<SynapseRawStream, String> =
    mapOf(
        SynapseRawStream.ECG to "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c71",
        SynapseRawStream.PPG to "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c72",
        SynapseRawStream.ACC to "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c73",
        SynapseRawStream.GYRO to "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c74",
        SynapseRawStream.MAG to "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c75",
        SynapseRawStream.POWER to "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c79",
    )

/** The ATT MTU a v2 feature notification needs: 27 bytes plus the 3-byte ATT header. */
const val SYNAPSE_FEATURE_MIN_MTU = 30

/** The ATT MTU a status notification needs: 40 bytes plus the 3-byte ATT header. */
const val SYNAPSE_STATUS_MIN_MTU = 43

/** The MTU to ask for on connecting: a raw frame is at most 244 bytes, plus the 3-byte ATT header. */
const val SYNAPSE_REQUESTED_MTU = 247
