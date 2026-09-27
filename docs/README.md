# Documentation system

Docs live in the repo, are reviewed like code, and are checked in CI (markdownlint + link check).

| Type | Where | Purpose | Lifecycle |
|---|---|---|---|
| **Product** | [`product/`](product/) | Vision, roadmap, original reference report, business model | Edited freely by maintainers |
| **Strategy** | [`strategy/`](strategy/README.md) | Frozen ecosystem-strategy input (Italian, unedited) plus its technical review | Input is frozen; the review is edited freely |
| **Integrations** | [`integrations/`](integrations/synapse.md) | Contracts required from a third-party device/vendor to integrate | Kept current with what Averyn actually requires |
| **ADR** (Architecture Decision Record) | [`adr/`](adr/README.md) | One hard-to-reverse decision: problem, options, decision, consequences | `proposed` → `accepted` → (`superseded` \| `deprecated`). **Immutable once accepted**; supersede with a new ADR |
| **Design doc** (TDD) | [`design/`](design/template.md) | How a non-trivial feature/system will work, before it is built | `draft` → `in-review` → `approved` → `implemented`. Updated to reflect reality after shipping |
| **Metric spec** | [`metrics/`](metrics/template.md) | The 11-field definition of one metric, versioned | New version = new section; old versions are never edited |
| **Architecture** | [`architecture/`](architecture/overview.md) | C4 diagrams and system overview | Kept current with `main` |
| **Privacy** | [`privacy/`](privacy/data-classification.md) | Data classification, retention, threat model | Reviewed on any change to collected data |
| **Testing** | [`testing/`](testing/device-matrix.md) | Device matrix, test strategy | Updated as devices are tested |
| **API** | [`api/`](api/openapi.yaml) | OpenAPI contract and versioning policy | Contract-first: change the spec in the same PR as the code |
| **Deployment** | [`deployment/`](deployment/self-hosting.md) | Self-hosting guide, upgrade notes | Must be valid for every release |
| **Runbooks** | [`runbooks/`](runbooks/README.md) | Step-by-step operational procedures | Written when an operation is first performed |

## Rules

- **Where does it go?** A choice between alternatives with lasting consequences → ADR. A description of how something will be built → design doc. How a number is computed → metric spec.
- **One source of truth.** Link, don't copy. The original [reference report](product/reference-report-v0.1.md) and the [Synapse strategy input](strategy/README.md) are frozen inputs; their content is superseded by the docs above as they are written.
- **Status header.** Every ADR, design doc and metric spec starts with a status line so readers know whether to trust it.
- **Language:** English. **Diagrams:** Mermaid (renders on GitHub, diffable).
- **Numbering:** ADRs `NNNN-slug.md`, design docs `TDD-NNNN-slug.md`. Numbers are never reused.
