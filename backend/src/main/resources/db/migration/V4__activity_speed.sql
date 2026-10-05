-- Average speed and pace are stored with the row they were computed with (metrics_algorithm_version names speed-v1),
-- so a later speed-v2 can never silently change what an old row displays (TDD-0003 §5.2).
ALTER TABLE activities
    ADD COLUMN average_speed_mps double precision,
    ADD COLUMN pace_sec_per_km   double precision;

-- Backfill with the speed-v1 formula as it is today; frozen here, never edited (docs/metrics/speed-and-pace.md).
UPDATE activities
SET average_speed_mps = CASE WHEN moving_ms > 0 THEN distance_m / (moving_ms / 1000.0) ELSE 0 END;

UPDATE activities
SET pace_sec_per_km = 1000.0 / average_speed_mps
WHERE average_speed_mps > 0;

ALTER TABLE activities ALTER COLUMN average_speed_mps SET NOT NULL;
