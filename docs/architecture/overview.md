# Architecture overview

Status: v0.1 — target architecture; only the pieces marked ✅ exist in the scaffold.

## System context

```mermaid
flowchart LR
  athlete([Athlete])
  admin([Admin / moderator])
  subgraph averyn["Averyn"]
    mobile[Mobile apps<br/>iOS · Android]
    web[Web app]
    api[Backend API]
  end
  sensors[/BLE sensors · watches/]
  health[/HealthKit · Health Connect/]
  ext[/External platforms<br/>via integration layer/]
  athlete --> mobile & web
  admin --> web
  sensors --> mobile
  health <--> mobile
  mobile <-->|sync| api
  web --> api
  api <--> ext
```

## Containers

```mermaid
flowchart TB
  subgraph device["Device"]
    ui[Native UI<br/>SwiftUI · Compose] --> sh[shared/ KMP ✅<br/>domain · tracking · metrics]
    nat[Native adapters<br/>location · background · BLE] --> sh
    sh --> local[(Local store)]
  end
  subgraph server["Server (single deployable)"]
    ktor[Ktor modular monolith ✅]
    jobs[Job worker]
  end
  pg[(PostgreSQL + PostGIS ✅)]
  s3[(S3-compatible storage ✅)]
  spa[Web SPA ✅]
  local -.sync queue.-> ktor
  spa --> ktor
  ktor --> pg & s3
  jobs --> pg & s3
  sh -. same code on jvm target .-> ktor
```

## Key properties

- **Separation:** acquisition → raw storage → normalization → analysis → sync → presentation → integrations ([report §26](../product/reference-report-v0.1.md)). Raw data is immutable; derived data is versioned.
- **Shared rules:** metrics and validation are one Kotlin implementation used by both device and server ([ADR-0004](../adr/0004-kotlin-multiplatform-shared-native-ui.md)).
- **One deployable server** for both self-hosted and managed cloud ([ADR-0005](../adr/0005-ktor-modular-monolith.md)).
- **Sync** is offline-first, idempotent and resumable (design doc to be written at MVP-1 start; requirements in [report §9](../product/reference-report-v0.1.md)).

## Related

[ADRs](../adr/README.md) · [TDD-0001 Tracking Engine](../design/TDD-0001-tracking-engine.md) · [API](../api/openapi.yaml)
