# Strategy input (frozen)

These six files are the original Synapse × Averyn ecosystem strategy, written before the technical review below. Like [`product/reference-report-v0.1.md`](../product/reference-report-v0.1.md), they are a **frozen input**, kept in their original language (Italian) and substantively unedited — the only change made after the fact is redacting named third-party brands to generic descriptions, for brand neutrality on a public repo (no other content was altered). They are superseded wherever an ADR, `docs/integrations/synapse.md`, `docs/product/business-model.md` or the roadmap says otherwise.

Read [`review-2026-09-27.md`](review-2026-09-27.md) first — it lists every correction, and which document now supersedes which claim.

| File | Original topic |
|---|---|
| [00-visione-strategica-completa.md](00-visione-strategica-completa.md) | Business vision, flywheel, 24-month roadmap |
| [01-synapse24-restructure-plan.md](01-synapse24-restructure-plan.md) | SYNAPSE-24 repo restructuring plan |
| [02-averyn-synapse-integration.md](02-averyn-synapse-integration.md) | Averyn-side integration sketch (BLE adapter, importer, device manager) |
| [03-synapse-cloud-adr.md](03-synapse-cloud-adr.md) | Original Synapse Cloud architecture sketch |
| [04-synapse-device-sdk-spec.md](04-synapse-device-sdk-spec.md) | Device SDK sketch (Python/C/Rust) |
| [05-master-index.md](05-master-index.md) | Index and quick-start per role |

## What actually supersedes this input

- Ecosystem boundary, identity, sensor contract → [ADR-0010](../adr/0010-ecosystem-boundary.md), [ADR-0011](../adr/0011-identity-oidc.md), [ADR-0014](../adr/0014-sensor-integration-contract.md)
- What Averyn requires from any Synapse device/cloud → [`docs/integrations/synapse.md`](../integrations/synapse.md)
- Economics → [`docs/product/business-model.md`](../product/business-model.md)
- Sequencing → [`docs/product/roadmap.md`](../product/roadmap.md)
- Health-data handling → [ADR-0013](../adr/0013-health-data-special-category.md), [`docs/privacy/data-classification.md`](../privacy/data-classification.md)
- CLA / store distribution → [ADR-0012](../adr/0012-cla-and-store-distribution.md)
