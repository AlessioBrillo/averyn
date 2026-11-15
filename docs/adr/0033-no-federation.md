# ADR-0033: The social graph lives inside one instance; no federation for now

- **Status:** accepted
- **Date:** 2026-11-15
- **Deciders:** @AlessioBrillo

## Context and problem

Each deployment has its own identity provider ([ADR-0011](0011-identity-oidc.md)) and its own data. Self-hosters would be islands next to the managed cloud. A federation protocol would connect them but spreads the moderation and the deletion across other people's servers.

## Options considered

1. **One graph per instance** (chosen). The managed cloud is the community; a self-hosted instance is a private or local one.
2. **Federation (ActivityPub or similar).** Coherent with open source; every deletion, block and privacy zone has to be honoured by servers we do not run. Too heavy for one developer.
3. **A bridge:** a self-hosted instance publishes chosen activities to the managed cloud. Smaller than 2, but it sends private location data to a second controller.

## Decision

**Option 1.** Social features exist per instance. Federation or a bridge is a future ADR, only if the privacy and moderation duties are solved in it; the data model uses opaque ids for users and groups so that a later link is not blocked.

## Consequences

- Good: one system of record, one moderation scope, deletion that is real ([ADR-0017](0017-data-deletion-and-retention.md)).
- Cost: a self-hoster does not reach the managed community (the export lets them move: vision principle 1).

## Revisit when

A real number of self-hosted instances ask to link, and the deletion and block semantics across servers are written first.
