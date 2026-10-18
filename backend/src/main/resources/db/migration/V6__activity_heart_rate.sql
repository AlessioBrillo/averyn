-- Heart-rate metric (hr-v1, docs/metrics/heart-rate.md): computed server-side from the `HR ` records of
-- the raw file, same as distance/duration. hr_source names the sensor that produced the values.
ALTER TABLE activities
    ADD COLUMN avg_hr_bpm integer,
    ADD COLUMN max_hr_bpm integer,
    ADD COLUMN hr_source text;

UPDATE activities
SET avg_hr_bpm = 0, max_hr_bpm = 0, hr_source = 'none'
WHERE avg_hr_bpm IS NULL;

ALTER TABLE activities ALTER COLUMN avg_hr_bpm SET NOT NULL;
ALTER TABLE activities ALTER COLUMN max_hr_bpm SET NOT NULL;
ALTER TABLE activities ALTER COLUMN hr_source SET NOT NULL;
