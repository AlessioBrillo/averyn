# Documentation system

Docs live in the repo, are reviewed like code, and are checked in CI (markdownlint + link check).

| Type | Where | Purpose | Lifecycle |
|---|---|---|---|
| **Product** | [`product/`](product/) | Vision, roadmap, [launch checklist](product/mvp-launch.md), original reference report, business model | Edited freely by maintainers |
| **Strategy** | [`strategy/`](strategy/README.md) | Frozen ecosystem-strategy input (Italian, unedited) plus its technical review | Input is frozen; the review is edited freely |
| **Integrations** | [`integrations/`](integrations/synapse.md) | Contracts required from a third-party device/vendor to integrate | Kept current with what Averyn actually requires |
| **ADR** (Architecture Decision Record) | [`adr/`](adr/README.md) | One hard-to-reverse decision: problem, options, decision, consequences | `proposed` → `accepted` → (`superseded` \| `deprecated`). **Immutable once accepted**; supersede with a new ADR |
| **Design doc** (TDD) | [`design/`](design/template.md) | How a non-trivial feature/system will work, before it is built | `draft` → `in-review` → `approved` → `implemented` (code and tests merged) → `verified` (the rows of the [device matrix](testing/device-matrix.md) that cover it are green). Updated to reflect reality after shipping |
| **Metric spec** | [`metrics/`](metrics/template.md) | The 11-field definition of one metric, versioned | New version = new section; old versions are never edited |
| **Architecture** | [`architecture/`](architecture/overview.md) | C4 diagrams and system overview | Kept current with `main` |
| **Privacy** | [`privacy/`](privacy/data-classification.md) | Data classification, [threat model](privacy/threat-model.md), retention | Reviewed on any change to collected data |
| **Testing** | [`testing/`](testing/device-matrix.md) | Device matrix, test strategy | Updated as devices are tested |
| **API** | [`api/`](api/openapi.yaml) | OpenAPI contract and versioning policy | Contract-first: change the spec in the same PR as the code |
| **Deployment** | [`deployment/`](deployment/self-hosting.md) | Self-hosting guide, upgrade notes | Must be valid for every release |
| **Runbooks** | [`runbooks/`](runbooks/README.md) | Step-by-step operational procedures | Written when an operation is first performed |

## Design docs

| # | Title | Status |
|---|---|---|
| [0001](design/TDD-0001-tracking-engine.md) | Tracking engine | implemented, not verified |
| [0002](design/TDD-0002-activity-sync.md) | Activity sync | implemented, not verified |
| [0003](design/TDD-0003-web-activity-view.md) | Web activity view | implemented, not verified |
| [0004](design/TDD-0004-data-deletion-and-export.md) | Data deletion and export | implemented, not verified |
| [0005](design/TDD-0005-self-host-hardening.md) | Self-host hardening | implemented, not verified |
| [0006](design/TDD-0006-field-readiness.md) | Field readiness | implemented, not verified |
| [0007](design/TDD-0007-sensor-channels.md) | Sensor channels: BLE heart rate | implemented, not verified |
| [0008](design/TDD-0008-gpx-import.md) | GPX import | implemented, not verified |
| [0009](design/TDD-0009-activity-analysis.md) | Activity analysis | implemented, not verified |
| [0010](design/TDD-0010-best-efforts.md) | Best efforts and personal records | implemented, not verified |
| [0011](design/TDD-0011-mobile-read-path.md) | The server on the phone: reading, offline copy, local summary | implemented, not verified |
| [0012](design/TDD-0012-profile-zones-phone-map.md) | Profile, heart-rate zones and the map on the phone | implemented, not verified |
| [0013](design/TDD-0013-fit-import.md) | FIT import and archive import | implemented, not verified |
| [0014](design/TDD-0014-health-platforms.md) | Saving an activity to HealthKit and Health Connect | implemented, not verified |

Nothing is `verified` yet: the device matrix has no filled row ([ADR-0028](adr/0028-consumer-launch-target.md)). The status line inside each design doc keeps saying `implemented` until its rows are green.

## Rules

- **Where does it go?** A choice between alternatives with lasting consequences → ADR. A description of how something will be built → design doc. How a number is computed → metric spec.
- **One source of truth.** Link, don't copy. The original [reference report](product/reference-report-v0.1.md) and the [Synapse strategy input](strategy/README.md) are frozen inputs; their content is superseded by the docs above as they are written.
- **Status header.** Every ADR, design doc and metric spec starts with a status line so readers know whether to trust it.
- **No spec without code.** A design doc or a metric spec lands with its code and tests in the same increment; otherwise it stays `draft` and is not on the roadmap.
- **Language:** English, in docs, code and commit messages. **Diagrams:** Mermaid (renders on GitHub, diffable).
- **Numbering:** ADRs `NNNN-slug.md`, design docs `TDD-NNNN-slug.md`. Numbers are never reused.
