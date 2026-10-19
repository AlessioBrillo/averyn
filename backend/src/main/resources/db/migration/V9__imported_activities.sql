-- Import provenance (TDD-0008, ADR-0022). An activity recorded by a device has source 'device'; an imported
-- one keeps the file it came from next to the canonical JSON Lines file, so the original bytes are never lost
-- and a better parser can rebuild the canonical file without asking the user to upload it again.
ALTER TABLE activities
    ADD COLUMN source text NOT NULL DEFAULT 'device'
        CHECK (source IN ('device', 'gpx', 'fit', 'tcx')),
    ADD COLUMN source_object_key text,
    ADD COLUMN source_sha256 char(64),
    ADD COLUMN source_size_bytes bigint,
    ADD COLUMN import_version text,
    ADD COLUMN imported_at timestamptz;

-- One file per owner: the same bytes are imported once. A deleted activity leaves no row behind, so the same
-- file can be imported again on purpose (ADR-0022): the tombstone of a deleted activity is keyed by its
-- client activity id and only stops a device retry, it does not blacklist the file.
CREATE UNIQUE INDEX activities_owner_source_sha_uidx
    ON activities (owner_id, source_sha256)
    WHERE source_sha256 IS NOT NULL;

-- The stored track keeps the pauses: one line per continuous recording interval instead of one line straight
-- across them. A GPX <trkseg> is such an interval, so an imported file shows the same shape as a recorded one.
ALTER TABLE activities
    ALTER COLUMN track TYPE geometry(MultiLineString, 4326) USING ST_Multi(track);

-- The heart-rate sidecar is tracked too, so a consent revocation can delete exactly the files it covers and an
-- export can include them (ADR-0021).
ALTER TABLE activities
    ADD COLUMN rr_object_key text,
    ADD COLUMN rr_sha256 char(64),
    ADD COLUMN rr_size_bytes bigint;
