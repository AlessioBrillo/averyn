# ADR-0011: Identity — Averyn as an OIDC relying party, no shared service-account tokens

- **Status:** accepted (boundary rule 2026-09-27; bundled IdP chosen by the MVP-1 spike on 2026-10-02, see below)
- **Date:** 2026-09-27
- **Deciders:** @AlessioBrillo

## Context and problem

[ADR-0010](0010-ecosystem-boundary.md) makes Averyn the only system of record for identity. The original Synapse Cloud proposal ([`docs/strategy/03-synapse-cloud-adr.md`](../strategy/03-synapse-cloud-adr.md)) authenticated its `SynapseCloudClient` with one static **service-account** JWT and passed `user_id` as a query parameter on every metrics call ([`docs/strategy/review-2026-09-27.md`](../strategy/review-2026-09-27.md) #15) — a confused-deputy design: anyone holding that one token can read any user's health data by changing a parameter. Averyn also needs to pick, or defer, an actual identity provider for self-host Compose.

## Options considered

1. **Averyn implements its own login/session system**, and optionally exposes an OIDC provider endpoint for the inference worker to validate against. Fewer moving parts to self-host, but Averyn ends up owning password storage, MFA, passkeys — security-critical code better delegated to a maintained IdP.
2. **Averyn is an OIDC relying party only**; a bundled default IdP ships in Compose for zero-config self-hosting, and any self-hoster can point Averyn at their own IdP instead (Keycloak, Zitadel, Authentik, an existing corporate IdP for club/team deployments). Passkeys/MFA come from the IdP, not from Averyn's own code.
3. **Authentik specifically, mandatory** — as the strategy input names it (though its actual sample config in file 03 is a Keycloak realm export, not Authentik's config format — a copy-paste that doesn't run against either product as written).

## Decision

**Option 2** for the boundary: Averyn's backend is an OIDC **relying party**, never an identity provider of its own and never the holder of a single shared secret that stands in for all users. Every call that reads or writes user data — including a job dispatched to the Synapse inference worker — carries a token scoped to one user's consent grant, not a service account with blanket access. The worker validates job authenticity against Averyn (e.g. a short-lived, job-scoped credential Averyn mints per dispatch), never a user token.

### Bundled IdP: Zitadel (spike, 2026-10-02)

Each candidate was run in Docker Compose on a 12-CPU / 8 GB Windows machine against PostgreSQL 17, idle after start-up, with the criteria this ADR set: modest RAM, one container, a declarative setup a CI job can run without clicks, and a boring upgrade path.

| | Zitadel v4.19.4 | Authentik 2026.8.3 | Keycloak 26.8.0 (`start-dev`) |
|---|---|---|---|
| Memory at idle (IdP only) | ~80 MiB | ~750 MiB (server 450 + worker 300) | ~745 MiB |
| IdP containers | 1 | 2 (server + worker) | 1 |
| Ready after start | ~10 s | ~195 s | ~100 s |
| Unattended OIDC client setup | management API with a machine-user PAT (two calls); the **client id is generated**, not chosen | blueprint YAML, fixed client id | realm JSON import, fixed client id |
| Licence | AGPL-3.0 | MIT (core) | Apache-2.0 |
| Own database | creates its own database on an existing PostgreSQL server | needs PostgreSQL | needs PostgreSQL |

**Decision: Zitadel.** It is roughly an order of magnitude lighter than the other two, which is what decides a "self-host in 30 minutes on a small server" promise (vision principle 7), and it covers PKCE, refresh tokens and passkeys. Its cost is the generated client id: a one-shot `idp-init` job in the Compose stack creates the project and the public native app idempotently and writes the ids to a volume the backend reads. A self-hoster who brings their own IdP skips that job and sets `AVERYN_OIDC_ISSUER`, `AVERYN_OIDC_AUDIENCE` and `AVERYN_OIDC_CLIENT_ID` directly; Averyn depends only on standard OIDC, never on Zitadel specifics.

Operational constraint found by the spike: the `iss` claim is the exact external URL, and Zitadel selects its instance from the request `Host` header. The backend and the phone must therefore reach the IdP under the same host and port; in Compose a network alias gives the backend that name (see [self-hosting](../deployment/self-hosting.md)).

Alternatives stay valid for deployments with an existing IdP (Keycloak, Authentik, a club's own): Averyn is a relying party.

## Consequences

- Good: closes the IDOR-shaped hole in the original design before any code depending on it exists.
- Good: self-hosters keep the option to federate into an existing IdP (e.g. a club's own OIDC), satisfying vision principle 7 (self-hosting as a real mode).
- Cost: one more container in the default Compose stack for people who don't want to bring their own IdP, about 80 MiB at idle with Zitadel.
- Cost: the generated client id needs the `idp-init` job; the issuer host must be identical for phone and backend.
- Follow-up: passkey/MFA timing is still open (tracked in [`docs/product/roadmap.md`](../product/roadmap.md)); Zitadel supports both.

## Revisit when

A self-hoster's federation need doesn't fit the relying-party model above, or the bundled IdP's licence, footprint or upgrade path stops meeting the criteria above (then a new ADR supersedes the product choice, not the boundary rule).
