-- Tombstone for RR sidecars removed on consent revocation (ADR-0021). An upload of an RR sidecar for a
-- deleted_rr activity gets 410; the activity file itself is untouched.
CREATE TABLE deleted_rr (
    owner_id           uuid NOT NULL REFERENCES users (id),
    client_activity_id uuid NOT NULL,
    deleted_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, client_activity_id)
);

CREATE INDEX deleted_rr_owner_idx ON deleted_rr (owner_id);
