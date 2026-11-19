# Backend

Ktor modular monolith (one Gradle module, a package per domain) with Flyway
migrations on PostgreSQL/PostGIS and S3-compatible object storage. The design is
in [TDD-0002](../docs/design/TDD-0002-activity-sync.md) and
[TDD-0005](../docs/design/TDD-0005-self-host-hardening.md); the API contract
is [docs/api/openapi.yaml](../docs/api/openapi.yaml).

## Requirements

- JDK 21.
- Docker for the integration tests (Testcontainers starts PostgreSQL/PostGIS and
  SeaweedFS).

## Test

```sh
./gradlew :backend:test
```

The tests use the same replay and analysis code as the devices
(`shared/tracking`, `shared/analysis`), so a red backend test can point at a
shared-module regression.

## Run

The supported way is the Compose stack:

```sh
cd infrastructure/compose
sh init-env.sh
docker compose --env-file .env up -d --build
curl http://localhost:8080/ready
```

For a local process, the backend reads the `DATABASE_*`, `AVERYN_S3_*` and
`AVERYN_OIDC_*` environment variables; see
[docs/deployment/self-hosting.md](../docs/deployment/self-hosting.md).

## Database

Flyway migrations are in `src/main/resources/db/migration` and run at startup.
Migrations only go forward: back up before an upgrade
([runbook](../docs/runbooks/upgrade-self-hosted.md)).
