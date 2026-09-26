# ADR-0002: License — AGPL-3.0-or-later

- **Status:** accepted (CLA/DCO sub-decision pending)
- **Date:** 2026-09-26
- **Deciders:** @AlessioBrillo

## Context and problem

The project is open-source with an optional managed cloud and first-class self-hosting ([report §3, §17](../product/reference-report-v0.1.md)). The license must prevent a third party from running a closed fork of the server as a competing SaaS without sharing changes.

## Options considered

1. **AGPL-3.0** — network-copyleft; modifications to a hosted server must be published. Some companies avoid AGPL.
2. **Apache-2.0** — permissive, patent grant; allows proprietary SaaS forks.
3. **MIT** — permissive, no patent grant; allows proprietary forks.

## Decision

**AGPL-3.0-or-later** for all code in this repository.

## Consequences

- Good: protects the managed-cloud business model and the self-hosting community.
- Cost: dependency licenses must be compatible (no GPL-incompatible or proprietary SDKs in the core); app-store distribution of AGPL code needs care with store terms.
- **Pending:** to keep the option of dual-licensing (e.g. a commercial license for organizations), external contributors would have to sign a **CLA**, or contributions must be accepted under a DCO only. Until decided, **external code contributions are not accepted** ([CONTRIBUTING](../../CONTRIBUTING.md)).
- Follow-up: legal review before the first public release (report §17); add a dependency-license check to CI.
- Map data (OSM/ODbL) and DEM licenses are separate concerns, handled in the maps ADR.

## Revisit when

A large contributor or organization requires a different license, or before the first public release.
