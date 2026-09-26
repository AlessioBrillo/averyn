# Self-hosting

Status: dev stack only. Production-grade self-hosting (TLS, backups, upgrades) is part of MVP-1 and tracked in GitHub issues.

## Quickstart (single node)

Requirements: Docker with Compose v2.

```sh
cd infrastructure/compose
cp .env.example .env            # set POSTGRES_PASSWORD
docker compose --env-file .env up -d --build
curl http://localhost:8080/health   # {"status":"ok"}
curl http://localhost:8080/ready    # {"status":"ready"} once the DB is migrated
```

Services: `db` (PostgreSQL 17 + PostGIS), `storage` (SeaweedFS, S3 API), `backend` (Ktor). Flyway migrations run on backend startup.

## Security notes

- The S3 credentials in `seaweedfs-s3.json` are **development defaults**: change them before exposing the stack. Storage is not published to the host by default.
- The backend is bound to `127.0.0.1`. Put a TLS-terminating reverse proxy in front for public access, and do not proxy `/metrics`.
- Keep `.env` out of version control.

## Not yet available (tracked)

Backup/restore scripts and runbooks · upgrade guide · reverse-proxy/TLS example · troubleshooting · web app serving.
