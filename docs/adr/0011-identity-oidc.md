# ADR-0011: Identity — Averyn as an OIDC relying party, no shared service-account tokens

- **Status:** proposed (IdP product choice is a spike at MVP-1 start; the boundary rule below is decided now because it gates ADR-0010)
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

The specific bundled default IdP for Compose (Authentik vs. Zitadel vs. Keycloak) is **left open**, decided by a short spike at the start of MVP-1 once Averyn's actual auth requirements (roles, session lifetime, self-host resource budget) are concrete — this status stays `proposed` until then. Whatever is picked must be self-host-friendly (modest RAM, one container, boring upgrade path) and must not require the specific config shape the strategy input assumed.

## Consequences

- Good: closes the IDOR-shaped hole in the original design before any code depending on it exists.
- Good: self-hosters keep the option to federate into an existing IdP (e.g. a club's own OIDC), satisfying vision principle 7 (self-hosting as a real mode).
- Cost: one more container in the default Compose stack for people who don't want to bring their own IdP; mitigated by picking something with a genuinely light footprint at the MVP-1 spike, not defaulting to Keycloak's ~2 GB baseline.
- Follow-up: the MVP-1 spike also decides passkey/MFA timing (tracked as an open decision in [`docs/product/roadmap.md`](../product/roadmap.md)).

## Revisit when

The MVP-1 auth spike completes (promote to `accepted` with the chosen IdP named), or a self-hoster's federation need doesn't fit the relying-party model above.
