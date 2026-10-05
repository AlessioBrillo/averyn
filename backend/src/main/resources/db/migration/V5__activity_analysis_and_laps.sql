CREATE TABLE activity_laps (
    id UUID PRIMARY KEY,
    activity_id UUID NOT NULL REFERENCES activities(id),
    lap_number INT NOT NULL,
    distance_m DOUBLE PRECISION NOT NULL,
    duration_ms BIGINT NOT NULL
);
