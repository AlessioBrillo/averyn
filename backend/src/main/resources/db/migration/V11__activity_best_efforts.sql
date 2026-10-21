-- The best efforts of an activity (TDD-0010, ADR-0024): the fastest window of each target distance of the
-- sport. Derived data: the raw JSON Lines file stays the system of record (ADR-0016), everything here is
-- recomputed from it when the algorithm changes.
CREATE TABLE activity_best_efforts (
    activity_id       uuid NOT NULL REFERENCES activities (id) ON DELETE CASCADE,
    -- The distance of the effort: one of the targets of the sport (best-efforts-v1).
    distance_m        double precision NOT NULL,
    -- The moving time of the effort: what a record is made of.
    duration_ms       bigint NOT NULL,
    -- The wall time of the effort, the stops inside it included.
    elapsed_ms        bigint NOT NULL,
    -- Where the effort is, on the cumulative distance of the activity.
    start_distance_m  double precision NOT NULL,
    end_distance_m    double precision NOT NULL,
    -- When the effort is, from the first sample of the activity.
    start_elapsed_ms  bigint NOT NULL,
    end_elapsed_ms    bigint NOT NULL,
    avg_speed_mps     double precision NOT NULL,
    pace_sec_per_km   double precision,
    PRIMARY KEY (activity_id, distance_m)
);

-- The version of the algorithm that produced the stored best efforts: a change of it is a new version, never
-- an edit. The rows that have no version are the ones stored before the best efforts existed: they are
-- recomputed the first time the activity is read.
ALTER TABLE activity_analyses
    ADD COLUMN best_efforts_version text NOT NULL DEFAULT 'none';

-- The version of the shape of the analysis document, not of one of its series: the name said otherwise.
ALTER TABLE activity_analyses
    RENAME COLUMN series_schema_version TO analysis_schema_version;

-- The records of an account are the best efforts of its activities: the lookup is by owner and sport.
CREATE INDEX activities_owner_sport_idx ON activities (owner_id, sport);
