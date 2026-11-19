# API contract

[`openapi.yaml`](openapi.yaml) is the contract of the `/v1` API. It is written
by hand and reviewed with the code: an endpoint change is a change to this file
in the same PR.

## Versioning

- Additive changes stay in `/v1`.
- Breaking changes go to a new URL prefix (`/v2`).
- `info.version` is the version of the contract document, not of the server.
  The server publishes the contract version it speaks as `apiVersion` in
  `/v1/client-config`, and clients warn when they do not understand it.

## Authentication

Everything under `/v1` except `/v1/client-config` requires a bearer token from
the OIDC issuer named in `/v1/client-config` ([ADR-0011](../adr/0011-identity-oidc.md)).
`/health`, `/ready` and `/metrics` are operational endpoints; `/metrics` is
unauthenticated and must stay on the internal network ([ADR-0009](../adr/0009-observability-baseline.md)).

## Lint

The Woodpecker gate runs Spectral with [`.spectral.yaml`](../../.spectral.yaml)
and fails on errors. Locally:

```sh
npx --yes @stoplight/spectral-cli@6.17.1 lint docs/api/openapi.yaml \
  --ruleset .spectral.yaml --fail-severity error
```

The pipeline also parses the YAML, because a colon in a description is enough to
make the file unreadable.

## Servers

The `servers` entries are examples. A self-hosted instance serves the API on its
own origin; the web app reaches it through the same origin (`/v1` is proxied by
Caddy in the Compose stack).
