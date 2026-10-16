-- Synapse health-data import (day-1 lab path, ADR-0010 / ADR-0014).
--
-- Averyn is the system of record; Synapse is a stateless inference worker or
-- a file source. All rows here are owner-scoped and carry first-class
-- provenance (source + external algorithm_version) so Synapse-computed values
-- are never laundered into Averyn's own metric types (ADR-0014).
--
-- Deletion/export: extend UserData.kt (deleteAccount + export manifest) to
-- cover these tables when new owner-scoped data lands (ADR-0017).

-- One row per physical device bound to an owner. Serial (BLE 0x2A25) is the
-- binding key at day 1; no cloud provisioning yet.
CREATE TABLE devices (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id      uuid NOT NULL REFERENCES users (id),
    kind          text NOT NULL CHECK (kind IN ('synapse-band', 'synapse-headband')),
    vendor_serial text NOT NULL,                 -- e.g. 'SYN-123456' from BLE DIS
    name          text NOT NULL DEFAULT 'Synapse Device',
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (owner_id, vendor_serial)
);
CREATE INDEX devices_owner_idx ON devices (owner_id);

-- Imported per-sample health data from Synapse (or any future vendor).
-- `activity_id` is NULL when the sample is not tied to a recorded activity
-- (e.g. overnight HRV, stress samples outside an activity).
CREATE TABLE imported_health_samples (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id         uuid NOT NULL REFERENCES users (id),
    activity_id      uuid REFERENCES activities (id),
    device_id        uuid REFERENCES devices (id),
    kind             text NOT NULL,               -- 'hrv' | 'stress' | 'sleep' | 'sqi' | 'temperature'
    recorded_at      timestamptz NOT NULL,
    value_json       jsonb NOT NULL,              -- e.g. {rmssd_ms, sdnn_ms, stress_level, sqi}
    source           text NOT NULL,               -- 'synapse-worker' | 'fit-file' | 'ble-live'
    algorithm_version text NOT NULL,              -- external version Averyn stores but does not own
    created_at       timestamptz NOT NULL DEFAULT now(),
);
CREATE INDEX imported_health_samples_owner_idx ON imported_health_samples (owner_id, recorded_at DESC);
CREATE INDEX imported_health_samples_owner_kind_idx ON imported_health_samples (owner_id, kind, recorded_at DESC);
CREATE INDEX imported_health_samples_device_idx ON imported_health_samples (device_id);
