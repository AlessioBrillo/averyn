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

Services: `db` (PostgreSQL 18 + PostGIS), `storage` (SeaweedFS, S3 API), `idp` (Zitadel, [ADR-0011](../adr/0011-identity-oidc.md)), `backend` (Ktor), `web` (Caddy, see below). Flyway migrations run on backend startup. The backend stores each uploaded activity's raw file in `storage` (bucket `averyn`, created on first start) and a summary in PostgreSQL ([TDD-0002](../design/TDD-0002-activity-sync.md)); the S3 credentials are `S3_ACCESS_KEY` / `S3_SECRET_KEY` and must match `seaweedfs-s3.json`. Two one-shot jobs run on every start: `idp-perms` (volume ownership) and `idp-init` (creates the Averyn project and its two public OIDC apps, mobile and web, in the IdP, idempotently).

## Identity provider

The issuer is `http://IDP_HOST:IDP_PORT` (`.env`). The `iss` claim of every token is exactly that URL, so the **phone/browser and the backend must reach the IdP under the same host and port**. Compose gives the backend that name through a network alias.

| Where you sign in from | `IDP_HOST` | Also set |
|---|---|---|
| Browser on the same machine | `idp.localhost` (default; resolves to loopback by itself) | none |
| Physical phone on the LAN | `<host-lan-ip>.nip.io` | `AVERYN_BIND=0.0.0.0` |
| Android emulator | `10.0.2.2.nip.io` | `AVERYN_BIND=0.0.0.0` |

The console is at `http://IDP_HOST:IDP_PORT/ui/console`, login name `admin@zitadel.<IDP_HOST>` with the password from `.env`. Users are created there (self-registration is off). Changing `IDP_HOST` after the first start requires `docker compose down -v`, because the IdP stores its external domain at first start. To use your own OIDC provider instead, drop `idp`/`idp-init` and set `AVERYN_OIDC_ISSUER`, `AVERYN_OIDC_AUDIENCE`, `AVERYN_OIDC_CLIENT_ID` and `AVERYN_OIDC_WEB_CLIENT_ID` on the backend.

## Web app

The `web` service serves the web app at `http://localhost:8082` (`WEB_PORT`) and forwards `/v1/*` to the backend, so the browser talks to one origin. Sign in with a user created in the IdP console; the list shows synced activities and each opens with its track and basic metrics ([TDD-0003](../design/TDD-0003-web-activity-view.md)).

- **`WEB_ORIGIN`** is the exact URL you open the web app at (default `http://localhost:${WEB_PORT}`). `idp-init` registers the browser app's redirect with it **the first time the stack starts**; if you later change it, sign-in fails with a redirect mismatch. Fix it in the console (Averyn project, app `averyn-web`) or recreate the IdP volume.
- **Basemap:** `AVERYN_MAP_STYLE_URL` is a MapLibre style URL. Unset, the app uses a demo world style that is fine to try things out. Whoever serves the tiles learns which areas your users look at (the track itself is drawn in the browser and is never sent to them), so a real instance should point this at a tile service it trusts, or its own.
- The container writes no access log, because request paths contain activity ids.

## Syncing from a phone

1. In `.env` set `IDP_HOST=<host-lan-ip>.nip.io` (Android emulator: `10.0.2.2.nip.io`) and `AVERYN_BIND=0.0.0.0`, then `docker compose down -v && docker compose up -d --build` (the IdP stores its host at first start).
2. In the console (`http://<IDP_HOST>:8081/ui/console`, see above) create a user.
3. In the app enter the server URL `http://<host-lan-ip>:8080` (the `BACKEND_PORT`), tap **Sign in**, log in as that user in the browser sheet, and finish an activity (or tap **Sync now**): the status line shows `READY` once the server has it.

Debug Android builds and the current iOS builds allow plain HTTP for this; put the stack behind HTTPS before using it beyond a trusted network.

## Security notes

- The IdP is bound to `127.0.0.1` unless you set `AVERYN_BIND`, and speaks plain HTTP in this dev stack: do not expose it publicly without a TLS-terminating reverse proxy (a TLS example is still to be written).
- The S3 credentials in `seaweedfs-s3.json` are **development defaults**: change them before exposing the stack. Storage is not published to the host by default.
- The backend is bound to `127.0.0.1` (host port `BACKEND_PORT`, default 8080; `AVERYN_BIND=0.0.0.0` also exposes the unauthenticated `/metrics`, so only use it on a trusted LAN). Put a TLS-terminating reverse proxy in front for public access, and do not proxy `/metrics`.
- Keep `.env` out of version control.

## Not yet available (tracked)

Backup/restore scripts and runbooks · upgrade guide · reverse-proxy/TLS example · troubleshooting.
