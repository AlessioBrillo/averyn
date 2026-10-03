# Threat model (STRIDE, MVP-1 sync)

Status: v0.1, scope = account identity and activity sync ([TDD-0002](../design/TDD-0002-activity-sync.md)). Extend when new data flows are added. Classes refer to [data classification](data-classification.md).

## Assets

Raw activity files and routes (**Restricted**), access tokens and refresh tokens (**Confidential**), the user ↔ IdP-subject mapping (**Confidential**).

## Trust boundaries

Device ↔ backend (internet, TLS), backend ↔ IdP (JWKS, discovery), backend ↔ object storage and PostgreSQL (internal network), `/metrics` (internal network only, [ADR-0009](../adr/0009-observability-baseline.md)).

## Threats and mitigations

| | Threat | Mitigation | Test |
|---|---|---|---|
| **S**poofing | Forged or replayed bearer token | JWT verified against the issuer's JWKS: `iss`, `aud`, `exp` checked; short access-token lifetime; HTTPS only in release builds | auth tests: expired, wrong issuer, wrong audience, none |
| **S** | Confused deputy via one shared service token | No service accounts for user data; every call carries a token for one user ([ADR-0011](../adr/0011-identity-oidc.md)) | — (design rule) |
| **T**ampering | Modified body on retry | SHA-256 per `(owner, clientActivityId)`; a different hash → `409`, the first upload is never overwritten | ingest test: modified body → 409 |
| **R**epudiation | A user disputes an upload | Request ids in logs ([ADR-0009](../adr/0009-observability-baseline.md)); row `created_at` and raw hash | — |
| **I**nformation disclosure | **IDOR**: reading another user's activity by id | Ownership checked on every read; non-owner gets `404`, not `403` | ingest test: user B → 404 |
| **I** | Coordinates or tokens in logs | Log ids, counts and hashes only; reviewed in PRs | log grep in end-to-end verification |
| **I** | Raw objects exposed through the storage endpoint | S3 not published to the host in Compose; backend is the only client | compose review |
| **D**enial of service | Oversized or endless body | 32 MB limit enforced while streaming (`413`); request timeouts | ingest test: over limit → 413 |
| **D** | Storage filled by one user | Per-user quota — **not in v1**; tracked as a risk for any public instance | — |
| **E**levation of privilege | Token for another audience accepted | `aud` verified; client id and audience configured explicitly | auth test: wrong audience |

## Open items

Per-user quota and rate limiting (before any public instance); retention and deletion schedule ([data classification](data-classification.md)); DPIA before a public hosted service.
