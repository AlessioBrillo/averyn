-- One row per uploaded activity (TDD-0002). The raw JSONL file is the system of record (ADR-0016) and lives in
-- object storage; everything here is derived from it and can be recomputed when an algorithm version changes.
CREATE TABLE activities (
    id                        uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id                  uuid NOT NULL REFERENCES users (id),
    client_activity_id        uuid NOT NULL,
    sport                     text NOT NULL,
    started_at                timestamptz NOT NULL,
    final_state               text NOT NULL CHECK (final_state IN ('COMPLETED', 'FAILED')),
    raw_object_key            text NOT NULL,
    raw_sha256                char(64) NOT NULL,
    raw_size_bytes            bigint NOT NULL,
    distance_m                double precision NOT NULL,
    elapsed_ms                bigint NOT NULL,
    moving_ms                 bigint NOT NULL,
    paused_ms                 bigint NOT NULL,
    quality_grade             text NOT NULL,
    -- Stored lines that could not be read (TDD-0001 F5: counted, never silently dropped).
    dropped_records           integer NOT NULL DEFAULT 0,
    metrics_algorithm_version text NOT NULL,
    quality_algorithm_version text NOT NULL,
    -- Samples that counted towards the distance, in order; NULL when there are fewer than two.
    track                     geometry(LineString, 4326),
    visibility                text NOT NULL DEFAULT 'private' CHECK (visibility IN ('private', 'followers', 'public', 'link')),
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now(),
    UNIQUE (owner_id, client_activity_id)
);

CREATE INDEX activities_owner_started_idx ON activities (owner_id, started_at DESC);
CREATE INDEX activities_track_gix ON activities USING GIST (track);
