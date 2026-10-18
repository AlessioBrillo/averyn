-- Consent for health-adjacent sensor data (ADR-0021). One row per (owner, scope); the row is the audit
-- record of grant/revoke and is never deleted on revocation (only revoked_at is set). The data covered by
-- a revoked scope is deleted separately (RR sidecar objects + deleted_rr tombstone).
CREATE TABLE consents (
    owner_id     uuid NOT NULL REFERENCES users (id),
    scope        text NOT NULL,
    granted_at   timestamptz NOT NULL DEFAULT now(),
    revoked_at   timestamptz,
    PRIMARY KEY (owner_id, scope)
);

-- Index for the device-side "what consents does this user have?" query (one row per user in practice).
CREATE INDEX consents_owner_scope_idx ON consents (owner_id, scope);
