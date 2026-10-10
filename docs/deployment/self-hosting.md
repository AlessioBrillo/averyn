# Self-hosting

Status: single node. Two modes from the same files: the plain-HTTP **development stack** (default) and the **TLS stack** for an instance others reach ([below](#tls-public-instance), [ADR-0018](../adr/0018-tls-edge-and-public-issuer.md), [TDD-0005](../design/TDD-0005-self-host-hardening.md)).

## Quickstart (single node)

Requirements: Docker with Compose v2.

```sh
cd infrastructure/compose
sh init-env.sh                  # writes .env and the object store's keys with random secrets (once)
docker compose --env-file .env up -d --build
curl http://localhost:8080/health   # {"status":"ok"}
curl http://localhost:8080/ready    # {"status":"ready"} once the DB is migrated
```

Services: `db` (PostgreSQL 18 + PostGIS), `storage` (SeaweedFS, S3 API), `idp` (Zitadel, [ADR-0011](../adr/0011-identity-oidc.md)), `backend` (Ktor), `web` (Caddy, see below). Services start in dependency order and wait for each other's health checks. Flyway migrations run on backend startup. The backend stores each uploaded activity's raw file in `storage` (bucket `averyn`, created on first start) and a summary in PostgreSQL ([TDD-0002](../design/TDD-0002-activity-sync.md)); the S3 credentials are `S3_ACCESS_KEY` / `S3_SECRET_KEY` and must match the object store's identity file, `S3_CONFIG_FILE` (`init-env.sh` generates both; without it the development defaults in `seaweedfs-s3.json` are used). Two one-shot jobs run on every start: `idp-perms` (volume ownership) and `idp-init` (creates the Averyn project and its two public OIDC apps, mobile and web, in the IdP, idempotently).

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
- **Basemap:** `AVERYN_MAP_STYLE_URL` is a MapLibre style URL. Unset, the app uses a demo world style that is fine to try things out. Whoever serves the tiles learns which areas your users look at (the track itself is drawn in the browser and is never sent to them), so a real instance should point this at a tile service it trusts, or its own. Set **`AVERYN_MAP_ORIGINS`** with it: the origins the style, tiles, glyphs and sprites come from (space-separated); the web app's Content-Security-Policy blocks every other host, and the map then stays blank (the browser console names the blocked host).
- **Security headers:** a strict Content-Security-Policy (scripts from the app's own origin only), `frame-ancestors 'none'`, `Referrer-Policy: no-referrer` ([TDD-0005](../design/TDD-0005-self-host-hardening.md)); in the TLS stack also HSTS.
- The container writes no access log, because request paths contain activity ids.

## TLS (public instance)

The overlay `docker-compose.tls.yml` puts Caddy in front of everything: it is the only service publishing ports (80, redirected, and 443), serves the web app and `/v1` on `AVERYN_DOMAIN` and the identity provider on `IDP_HOST`, and obtains certificates automatically. **Decide the two names before the first start:** the issuer becomes `https://IDP_HOST` and is fixed from then on; moving an existing plain-HTTP instance to TLS, or to other names, is not supported (ADR-0018).

1. Two DNS names pointing at the machine, e.g. `averyn.example.org` and `auth.averyn.example.org`; ports 80 and 443 reachable from the internet (certificate issuance).
2. In `.env`: `AVERYN_DOMAIN=averyn.example.org`, `IDP_HOST=auth.averyn.example.org` (leave `IDP_PORT` as is: it is now internal only).
3. Start with both files:

   ```sh
   docker compose -f docker-compose.yml -f docker-compose.tls.yml --env-file .env up -d --build
   ```

4. Web app: `https://AVERYN_DOMAIN`. Console: `https://IDP_HOST/ui/console`. Apps: server URL `https://AVERYN_DOMAIN`.

The backend reaches the identity provider inside the network (`AVERYN_OIDC_INTERNAL_URL`) and still checks that it publishes the issuer `https://IDP_HOST`. The OIDC apps are registered without development mode, so only `https` redirects are accepted for the web app.

**Trying it locally:** `AVERYN_DOMAIN=averyn.localhost` and `IDP_HOST=auth.averyn.localhost` work without DNS; Caddy then issues certificates from its own local CA. Export its root with `docker compose ... cp web:/data/caddy/pki/authorities/local/root.crt .` and trust it in the browser (or the phone) you test with.

## Syncing from a phone

1. In `.env` set `IDP_HOST=<host-lan-ip>.nip.io` (Android emulator: `10.0.2.2.nip.io`) and `AVERYN_BIND=0.0.0.0`, then `docker compose down -v && docker compose up -d --build` (the IdP stores its host at first start).
2. In the console (`http://<IDP_HOST>:8081/ui/console`, see above) create a user.
3. In the app enter the server URL `http://<host-lan-ip>:8080` (the `BACKEND_PORT`), tap **Sign in**, log in as that user in the browser sheet, and finish an activity (or tap **Sync now**): the status line shows `READY` once the server has it.

Debug builds allow plain HTTP for this; release builds need the TLS stack (server URL `https://AVERYN_DOMAIN`, no port).

## Security notes

- Development stack: the IdP is bound to `127.0.0.1` unless you set `AVERYN_BIND`, and speaks plain HTTP: never expose it; use the TLS stack instead.
- The S3 credentials in `seaweedfs-s3.json` are **development defaults**; `init-env.sh` replaces them with generated ones. Storage is not published to the host by default.
- The backend is bound to `127.0.0.1` (host port `BACKEND_PORT`, default 8080; `AVERYN_BIND=0.0.0.0` also exposes the unauthenticated `/metrics`, so only use it on a trusted LAN). In the TLS stack it publishes no port at all and Caddy does not proxy `/metrics`.
- Keep `.env` out of version control.

## Deleting data and backups

Users can delete an activity, delete their account's data and download an export from the web app ([ADR-0017](../adr/0017-data-deletion-and-retention.md)). Deleting the account removes Averyn's data only: close the person's identity in your identity provider separately (in the bundled Zitadel: Users, select the user, Delete). Deletion is immediate in the database and object store, but **your backups still hold the data**: make them expire within a retention period you choose and state to your users (we suggest at most 35 days). After restoring a backup, delete again whatever users deleted since it was taken.

## Backups and upgrades

Runbooks: [back up](../runbooks/backup-database.md), [restore](../runbooks/restore-database.md), [upgrade](../runbooks/upgrade-self-hosted.md). Back up before every upgrade: migrations only go forward.

## Not yet available (tracked)

Automated backups · secret rotation · troubleshooting.
