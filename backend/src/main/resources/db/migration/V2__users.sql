-- Averyn is an OIDC relying party (ADR-0011): a user is whatever the IdP says (issuer, subject); no credentials here.
CREATE TABLE users (
    id           uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    oidc_issuer  text NOT NULL,
    oidc_subject text NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (oidc_issuer, oidc_subject)
);
