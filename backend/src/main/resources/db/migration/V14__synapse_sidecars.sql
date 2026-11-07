-- What a Synapse band sent during an activity, stored as received (ADR-0030). One object per (activity, kind), next to
-- the activity and not inside it: 'features' are the consumer records at about 1 Hz (consent: signal_quality), 'raw'
-- are the research frames (consent: raw_signals). The row points at the object; deleting the activity removes both.
CREATE TABLE activity_synapse_sidecars (
    activity_id uuid NOT NULL REFERENCES activities (id) ON DELETE CASCADE,
    kind        text NOT NULL CHECK (kind IN ('features', 'raw')),
    owner_id    uuid NOT NULL REFERENCES users (id),
    object_key  text NOT NULL,
    sha256      char(64) NOT NULL,
    size_bytes  bigint NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (activity_id, kind)
);

CREATE INDEX activity_synapse_sidecars_owner_idx ON activity_synapse_sidecars (owner_id, kind);

-- Tombstone for sidecars removed on consent revocation (ADR-0021), as for the RR sidecar: an upload for a deleted
-- (owner, client activity, kind) gets 410, and the activity itself is untouched.
CREATE TABLE deleted_synapse (
    owner_id           uuid NOT NULL REFERENCES users (id),
    client_activity_id uuid NOT NULL,
    kind               text NOT NULL CHECK (kind IN ('features', 'raw')),
    deleted_at         timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (owner_id, client_activity_id, kind)
);
