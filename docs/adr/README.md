# Architecture Decision Records

Format: [MADR](https://adr.github.io/madr/)-style, see [template](template.md). Process: copy the template to `NNNN-slug.md` with status `proposed`, open a PR, set to `accepted` on merge. Accepted ADRs are immutable; to change a decision write a new ADR that supersedes it.

| # | Title | Status |
|---|---|---|
| [0001](0001-record-decisions-with-adrs.md) | Record decisions with ADRs | accepted |
| [0002](0002-license-agplv3.md) | License: AGPL-3.0-or-later | accepted |
| [0003](0003-monorepo-and-build-tooling.md) | Monorepo with Gradle and npm | accepted |
| [0004](0004-kotlin-multiplatform-shared-native-ui.md) | KMP shared logic, native UI and native tracking | accepted |
| [0005](0005-ktor-modular-monolith.md) | Backend: Ktor modular monolith | accepted |
| [0006](0006-postgres-postgis-s3-storage.md) | PostgreSQL/PostGIS and S3-compatible object storage | accepted |
| [0007](0007-web-stack.md) | Web: React + TypeScript + Vite, admin inside the web app | accepted |
| [0008](0008-project-codename.md) | Project name: "Averyn" is a codename | proposed |
| [0009](0009-observability-baseline.md) | Observability baseline | accepted |
| [0010](0010-ecosystem-boundary.md) | Ecosystem boundary: Averyn is the platform, Synapse is an optional integration | accepted |
| [0011](0011-identity-oidc.md) | Identity: Averyn as an OIDC relying party, no shared service-account tokens | accepted |
| [0012](0012-cla-and-store-distribution.md) | Contributor License Agreement, resolving App Store distribution | accepted |
| [0013](0013-health-data-special-category.md) | Health-adjacent metrics are special-category data | accepted |
| [0014](0014-sensor-integration-contract.md) | Sensor integration contract: standards first, vendor extensions native-only | accepted |
| [0015](0015-local-activity-store.md) | Local activity store: append-only file, not a database | accepted |
| [0016](0016-server-raw-activity-storage.md) | Server raw activity storage: the JSONL file is the system of record | accepted |

Open decisions still without an ADR are tracked in [product/roadmap.md](../product/roadmap.md#open-decisions).
