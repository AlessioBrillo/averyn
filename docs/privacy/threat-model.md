# Threat model (STRIDE, MVP-1 sync)

Status: v0.2, scope = account identity, activity sync ([TDD-0002](../design/TDD-0002-activity-sync.md)), web app and self-host edge ([TDD-0005](../design/TDD-0005-self-host-hardening.md)). Extend when new data flows are added. Classes refer to [data classification](data-classification.md).

## Assets

Raw activity files and routes (**Restricted**), access tokens and refresh tokens (**Confidential**), the user ↔ IdP-subject mapping (**Confidential**).

## Trust boundaries

Device / browser ↔ edge proxy (internet, TLS, [ADR-0018](../adr/0018-tls-edge-and-public-issuer.md)), edge ↔ backend and IdP (internal network), backend ↔ IdP (JWKS, discovery), backend ↔ object storage and PostgreSQL (internal network), `/metrics` (internal network only, [ADR-0009](../adr/0009-observability-baseline.md)).

## Threats and mitigations

| | Threat | Mitigation | Test |
|---|---|---|---|
| **S**poofing | Forged or replayed bearer token | JWT verified against the issuer's JWKS: `iss`, `aud`, `exp` checked; short access-token lifetime; HTTPS only in release builds | auth tests: expired, wrong issuer, wrong audience, none |
| **S** | Confused deputy via one shared service token | No service accounts for user data; every call carries a token for one user ([ADR-0011](../adr/0011-identity-oidc.md)) | — (design rule) |
| **T**ampering | Modified body on retry | SHA-256 per `(owner, clientActivityId)`; a different hash → `409`, the first upload is never overwritten | ingest test: modified body → 409 |
| **R**epudiation | A deleted activity reappears from a device retry | Data-free tombstone; the upload answers `410` | deletion test: re-PUT → 410 |
| **R** | A user disputes an upload | Request ids in logs ([ADR-0009](../adr/0009-observability-baseline.md)); row `created_at` and raw hash | — |
| **I**nformation disclosure | **IDOR**: reading another user's activity by id | Ownership checked on every read; non-owner gets `404`, not `403` | ingest test: user B → 404 |
| **I** | **IDOR on deletion**: deleting another user's activity by id | Deletion is scoped to the owner; a non-owner gets `404` and nothing changes | deletion test: user B → 404, row intact |
| **I** | Data kept after the user asked to delete it | Hard delete of row and raw object; account deletion removes the whole `raw/{ownerId}/` prefix ([ADR-0017](../adr/0017-data-deletion-and-retention.md)); backups expire per operator policy | deletion tests: object absent |
| **I** | Coordinates or tokens in logs | Log ids, counts and hashes only; reviewed in PRs | log grep in end-to-end verification |
| **I** | Tokens read from a lost or backed-up phone | Android: session encrypted with an AES-GCM key held in the Android Keystore, `allowBackup=false`. iOS: Keychain `AfterFirstUnlockThisDeviceOnly` (no iCloud sync, no restore onto another device). Sign-out deletes it | manual: device matrix S14 |
| **I** | Credentials or tokens sent in clear on the network | TLS overlay: only the edge publishes ports, 80 → 443, HSTS, issuer `https://` ([ADR-0018](../adr/0018-tls-edge-and-public-issuer.md)). Release builds of both apps are HTTPS-only; cleartext is allowed in Debug builds only, for the plain-HTTP development stack | CI TLS Compose job; CI check of the iOS Release Info.plist |
| **I** | Injected script in the web app reads the session token from `sessionStorage` | Content-Security-Policy `script-src 'self'`, `connect-src` limited to the API, the IdP and the map hosts, `frame-ancestors 'none'` ([TDD-0005 §5.3](../design/TDD-0005-self-host-hardening.md)) | CI header check; browser run without violations |
| **I** | Raw objects exposed through the storage endpoint | S3 not published to the host in Compose; backend is the only client | compose review |
| **D**enial of service | Oversized or endless body | 32 MB limit enforced while streaming (`413`, also without `Content-Length`); idle read timeout of 60 s on the server | ingest tests; checked on a real connection |
| **D** | Many concurrent uploads exhaust threads / temp disk | At most 8 uploads processed at once, the rest get `503` + `Retry-After`; JWKS lookups fail fast while the IdP is down, so a down IdP cannot tie up request threads | ingest test (slots), auth test (IdP unreachable → 401) |
| **D** | Request capacity exhausted by one user | Per-user token bucket on every authenticated `/v1` route, `429` + `Retry-After` (TDD-0005 §5.4) | backend test: limit + 1 → 429, other user 200 |
| **D** | Storage filled by one user | Per-user quota on raw files, checked in the ingest transaction; `507`, nothing stored (TDD-0005 §5.5) | integration test: over quota → 507, no row, no object |
| **E**levation of privilege | Token for another audience accepted | `aud` verified; client id and audience configured explicitly | auth test: wrong audience |

## Open items

Backend-for-frontend session (TDD-0005 Q1); encryption at rest (TDD-0005 Q2); legal retention and a deletion audit trail for a hosted service; DPIA before a public hosted service.
