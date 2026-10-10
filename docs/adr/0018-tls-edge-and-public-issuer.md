# ADR-0018: TLS at one edge proxy, a fixed public issuer, internal OIDC discovery

- **Status:** proposed
- **Date:** 2026-10-10
- **Deciders:** @AlessioBrillo

## Context and problem

The Compose stack is plain HTTP end to end: Zitadel runs with `--tlsMode disabled`, both OIDC apps are registered in `devMode`, and the issuer is `http://${IDP_HOST}:${IDP_PORT}`. A Compose network alias makes that same hostname reach the IdP container, so the backend, the browser and the phone all see one issuer, which must match the `iss` claim exactly ([ADR-0011](0011-identity-oidc.md)). Any instance reachable by others needs TLS ([threat model](../privacy/threat-model.md): credentials in clear). Once TLS is terminated in front of the IdP, the issuer becomes an `https://` URL, and the backend inside the network can no longer reach it through the alias: plain HTTP would hit the wrong port, and the edge's certificate would not be trusted by the JVM for local CAs. The issuer is also baked into every user's identity (`users.issuer` + `subject`), so changing it later is expensive.

## Options considered

1. **One edge proxy (Caddy, already shipped for the web app), two hostnames.** `AVERYN_DOMAIN` serves the SPA and `/v1`; `IDP_HOST` (e.g. `auth.` + the same domain) proxies the IdP. Automatic certificates. The backend reads discovery and keys from the IdP's internal address and checks the published issuer as today.
2. **One hostname, IdP under path prefixes.** One certificate, but the IdP serves many prefixes (`/oauth`, `/oidc`, `/ui`, gRPC service paths) that change between releases; routing breaks on upgrades.
3. **TLS documented only.** No stack change, operators bring their own proxy. The "self-host from a clean machine" exit criterion stays unverified.
4. **Backend trusts the edge's local CA** and keeps reaching the IdP through the public name. No backend change, but a CA import in the backend image that only works for the local CA, and an extra hop through the proxy for every key lookup.

## Decision

**Option 1.**

- Caddy is the only service publishing ports in TLS mode (80 → redirect, 443). The IdP runs with `--tlsMode external` and `ExternalSecure=true`, `ExternalPort=443`, reached by the edge over h2c.
- The issuer is `https://${IDP_HOST}` and is **fixed at the first start**. Moving an existing instance from HTTP to TLS, or to another domain, is not supported by the stack; it needs an IdP domain change and every user keeps the old `issuer` in Averyn. It gets its own runbook when someone needs it.
- New optional backend setting `AVERYN_OIDC_INTERNAL_URL`. When set, discovery and the key set are read from that URL with `X-Forwarded-Host` set to the issuer's host, and the `jwks_uri` prefix is rewritten to the internal URL. The discovery document's `issuer` must still equal the configured issuer exactly. When unset, nothing changes (any external IdP keeps working).
- TLS is an **overlay** (`docker-compose.tls.yml`) on the plain-HTTP development stack, which stays the default for local work and phones on the LAN.
- `*.localhost` names get certificates from Caddy's local CA; any other name gets a public ACME certificate.

## Consequences

- Good: one service to expose and harden; the same image and config in CI and in production; the backend still verifies the issuer the clients see.
- Good: the HTTP stack used for development is unchanged.
- Cost: two DNS names and ports 80/443 for an operator; phones and browsers on a local test domain must trust Caddy's local CA.
- Cost: the issuer cannot be changed in place; choosing the domain is a first-start decision (documented in the self-hosting guide).
- Cost: `X-Forwarded-Host` is how the bundled IdP picks its instance (verified on Zitadel v4.19.4, 2026-10-10); another IdP behind the internal URL must accept it or be configured with its public URL instead.

## Revisit when

An operator needs to migrate an existing HTTP instance to TLS or rename the domain; more than one backend replica or a managed load balancer replaces the Compose edge; or the IdP's instance selection changes on upgrade.
