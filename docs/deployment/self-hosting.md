# Self-hosting

Status: dev stack only. Production-grade self-hosting (TLS, backups, upgrades) is part of MVP-1 and tracked in GitHub issues.

## Quickstart (single node)

Requirements: Docker with Compose v2.

```sh
cd infrastructure/compose
cp .env.example .env            # change every value marked CHANGE
docker compose --env-file .env up -d --build
curl http://localhost:8080/health   # {"status":"ok"}
curl http://localhost:8080/ready    # {"status":"ready"} once the DB is migrated
```

Services: `db` (PostgreSQL 18 + PostGIS), `storage` (SeaweedFS, S3 API), `idp` (Zitadel, [ADR-0011](../adr/0011-identity-oidc.md)), `backend` (Ktor). Flyway migrations run on backend startup. Two one-shot jobs run on every start: `idp-perms` (volume ownership) and `idp-init` (creates the Averyn project and its public OIDC app in the IdP, idempotently).

## Identity provider

The issuer is `http://IDP_HOST:IDP_PORT` (`.env`). The `iss` claim of every token is exactly that URL, so the **phone/browser and the backend must reach the IdP under the same host and port**. Compose gives the backend that name through a network alias.

| Where you sign in from | `IDP_HOST` | Also set |
|---|---|---|
| Browser on the same machine | `idp.localhost` (default; resolves to loopback by itself) | none |
| Physical phone on the LAN | `<host-lan-ip>.nip.io` | `AVERYN_BIND=0.0.0.0` |
| Android emulator | `10.0.2.2.nip.io` | `AVERYN_BIND=0.0.0.0` |

The console is at `http://IDP_HOST:IDP_PORT/ui/console`, login name `admin@zitadel.<IDP_HOST>` with the password from `.env`. Users are created there (self-registration is off). Changing `IDP_HOST` after the first start requires `docker compose down -v`, because the IdP stores its external domain at first start. To use your own OIDC provider instead, drop `idp`/`idp-init` and set `AVERYN_OIDC_ISSUER`, `AVERYN_OIDC_AUDIENCE` and `AVERYN_OIDC_CLIENT_ID` on the backend.

## Security notes

- The IdP is bound to `127.0.0.1` unless you set `AVERYN_BIND`, and speaks plain HTTP in this dev stack: do not expose it publicly without a TLS-terminating reverse proxy (a TLS example is still to be written).
- The S3 credentials in `seaweedfs-s3.json` are **development defaults**: change them before exposing the stack. Storage is not published to the host by default.
- The backend is bound to `127.0.0.1` (host port `BACKEND_PORT`, default 8080; `AVERYN_BIND=0.0.0.0` also exposes the unauthenticated `/metrics`, so only use it on a trusted LAN). Put a TLS-terminating reverse proxy in front for public access, and do not proxy `/metrics`.
- Keep `.env` out of version control.

## Not yet available (tracked)

Backup/restore scripts and runbooks · upgrade guide · reverse-proxy/TLS example · troubleshooting · web app serving.
