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
| **I** | Tokens read from a lost or backed-up phone | Android: session encrypted with an AES-GCM key held in the Android Keystore, `allowBackup=false`. iOS: Keychain `AfterFirstUnlockThisDeviceOnly` (no iCloud sync, no restore onto another device). Sign-out deletes it | manual: device matrix S14 |
| **I** | Credentials or tokens sent in clear on the network | Release Android builds are HTTPS-only. The dev stack is plain HTTP: debug-only on Android, `NSAllowsArbitraryLoads` on iOS PoC builds (internal only); TLS example and removal before any store distribution | none |
| **I** | Raw objects exposed through the storage endpoint | S3 not published to the host in Compose; backend is the only client | compose review |
| **D**enial of service | Oversized or endless body | 32 MB limit enforced while streaming (`413`, also without `Content-Length`); idle read timeout of 60 s on the server | ingest tests; checked on a real connection |
| **D** | Many concurrent uploads exhaust threads / temp disk | At most 8 uploads processed at once, the rest get `503` + `Retry-After`; JWKS lookups fail fast while the IdP is down, so a down IdP cannot tie up request threads | ingest test (slots), auth test (IdP unreachable → 401) |
| **D** | Storage or capacity filled by one user | Per-user quota and rate limit: **not in v1**, required before any public instance | none |
| **E**levation of privilege | Token for another audience accepted | `aud` verified; client id and audience configured explicitly | auth test: wrong audience |

## Open items

Per-user quota and rate limiting (before any public instance); retention and deletion schedule ([data classification](data-classification.md)); DPIA before a public hosted service.
