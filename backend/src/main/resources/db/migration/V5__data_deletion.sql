-- Data deletion (ADR-0017, TDD-0004). A deleted account keeps only its pseudonymous (issuer, subject) row, flagged, so
-- the same identity cannot start a new empty account while its devices still hold data; calls then answer 403.
ALTER TABLE users ADD COLUMN deleted_at timestamptz;

-- Remembers that an activity id was deleted, so a device retry gets 410 instead of bringing the activity back.
-- Holds no activity data: only the ids and when.
CREATE TABLE deleted_activities (
    owner_id           uuid NOT NULL REFERENCES users (id),
    client_activity_id uuid NOT NULL,
    deleted_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, client_activity_id)
);
