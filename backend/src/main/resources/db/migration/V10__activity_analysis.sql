-- Activity analysis (TDD-0009, ADR-0023): the derived numbers of an activity — the automatic splits, the
-- elevation and the display series. The raw JSON Lines file stays the system of record (ADR-0016): everything
-- here is derived from it and is recomputed when an algorithm version changes.
ALTER TABLE activities
    ADD COLUMN stationary_ms bigint NOT NULL DEFAULT 0,
    ADD COLUMN hr_algorithm_version text NOT NULL DEFAULT 'hr-v1';

-- The rows that exist were computed with the time the activity was recording, the pauses excluded: what was
-- left of it is the stationary time.
UPDATE activities SET stationary_ms = GREATEST(elapsed_ms - moving_ms - paused_ms, 0);

-- One row per activity: the summary of the analysis. The series is a bounded, decimated drawing (the numbers
-- to trust are the splits and the totals), so it is one JSONB document and not a row per sample.
CREATE TABLE activity_analyses (
    activity_id                uuid PRIMARY KEY REFERENCES activities (id) ON DELETE CASCADE,
    -- Which algorithm produced which number: a change of any of them is a new version, never an edit.
    splits_version             text NOT NULL,
    elevation_version          text NOT NULL,
    series_version             text NOT NULL,
    series_schema_version      integer NOT NULL,
    split_length_m             double precision NOT NULL,
    elevation_source           text,
    elevation_gain_m           double precision,
    elevation_loss_m           double precision,
    min_altitude_m             double precision,
    max_altitude_m             double precision,
    avg_altitude_m             double precision,
    elevation_coverage_percent double precision,
    hr_algorithm_version       text,
    hr_min_bpm                 integer,
    hr_coverage_percent        double precision,
    hr_dropouts                integer,
    hr_artifacts               integer,
    series                     jsonb NOT NULL,
    computed_at                timestamptz NOT NULL DEFAULT now()
);

-- The automatic splits: one row each, so a later increment can query them (best efforts, laps) without
-- reading the JSON of the analysis.
CREATE TABLE activity_splits (
    activity_id      uuid NOT NULL REFERENCES activities (id) ON DELETE CASCADE,
    idx              integer NOT NULL,
    start_distance_m double precision NOT NULL,
    end_distance_m   double precision NOT NULL,
    elapsed_ms       bigint NOT NULL,
    moving_ms        bigint NOT NULL,
    avg_speed_mps    double precision NOT NULL,
    elevation_gain_m double precision,
    elevation_loss_m double precision,
    avg_hr_bpm       integer,
    partial          boolean NOT NULL DEFAULT false,
    PRIMARY KEY (activity_id, idx),
    CHECK (end_distance_m >= start_distance_m)
);
