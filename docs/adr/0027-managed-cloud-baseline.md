# ADR-0027: Managed cloud baseline — one EU host, the self-host stack, off-site backups, self-hosted map tiles

- **Status:** proposed
- **Date:** 2026-10-25
- **Deciders:** @AlessioBrillo

## Context and problem

The launch target is a public managed service ([roadmap](../product/roadmap.md#mvp-l-launch)) run by one developer. Principle 9 of the [vision](../product/vision.md) says the system must not become microservices early; principle 10 says it must be sustainable for one person. The self-host stack already exists (Compose, TLS edge, health checks, runbooks). The roadmap listed three decisions as open: the cloud provider, the map tile source, and what "managed" adds on top of the self-host stack.

## Options considered

1. **The self-host stack on one EU virtual machine**, with off-site object backups and an external uptime check. Same artefact as self-hosters run, so every fix serves both.
2. **A managed container platform or Kubernetes.** More elastic, much more to learn and operate alone, and diverges from what self-hosters run.
3. **A hosted third-party map tile service.** No operating cost, but a per-request quota owned by someone else and a user-location leak to a third party.

## Decision

- **Option 1 for hosting.** One virtual machine in an EU region running the existing Compose stack with the TLS overlay. A second region holds only the encrypted off-site backups. The provider is chosen by the owner against: EU legal entity and data location, a data processing agreement, snapshots, and price; it is recorded here when chosen.
- **Backups.** The runbooks ([backup](../runbooks/backup-database.md), [restore](../runbooks/restore-database.md)) run on a schedule; a restore drill into a clean host is a launch criterion, and its log is kept.
- **Operations.** An external uptime check and alert on the health endpoints, a status page, and an incident runbook ([incident.md](../runbooks/incident.md)).
- **Sign-up.** Open registration in the identity provider with email verification through an SMTP provider chosen by the owner; the per-user limits and storage quota ([TDD-0005](../design/TDD-0005-self-host-hardening.md)) are on.
- **Map tiles.** A static vector basemap in the PMTiles format, served from the object store, rendered by MapLibre on web and phones through `ClientConfig.mapStyleUrl`. No third-party tile requests, so no quota and no location leak. Offline packs are MVP-3.
- **Scale-out is not decided here.** A single host carries the launch; the first measured bottleneck decides the next step (documented as a `ponytail:` limit where it is already known).

## Consequences

- Good: one artefact, one runbook set, for managed and self-hosted; the cost is a few hundred euros a year.
- Good: no dependency on a map vendor.
- Cost: a single host is a single point of failure; the restore drill and the uptime check are what bound the risk, and the status page is the honest answer to an outage.
- Cost: the basemap must be produced and updated by us.

## Revisit when

Sustained load or an availability target the single host cannot meet, or the first external user whose data-location requirement is not met by one EU region.
