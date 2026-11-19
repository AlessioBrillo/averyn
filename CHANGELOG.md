# Changelog

All notable changes to this project are documented in this file. The format is
based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the
project aims to follow [Semantic Versioning](https://semver.org/).

There are no tagged releases yet. The first tag will be `v0.1.0` at MVP-L
([ADR-0028](docs/adr/0028-consumer-launch-target.md)). Until then, self-host from
a commit on `main`; the upgrade runbook uses commit ids.

## [Unreleased]

### Added

- `NOTICE` with the copyright notice and the explicit AGPL-3.0-or-later grant.
- `tools/ci/ci-local.sh` (and the Windows shim) so the Woodpecker gate can be run
  from a clone, without a server.
- Link check (lychee), OpenAPI lint (Spectral) and secret scan in the Woodpecker
  gate.
- `docs/metrics/README.md` and a three-state metric lifecycle (`draft` →
  `implemented` → `stable`).
- Module READMEs for `apps/android`, `apps/web`, `backend` and `shared/`.
- A public security policy with safe harbor and a realistic scope.

### Changed

- The README now states what is in code, what is verified on a device and what is
  still missing; it no longer claims an admin area or that only device evidence
  is missing.
- The self-hosting quickstart is the canonical `init-env.sh` path; the README
  points to it instead of duplicating a different one.
- Woodpecker is the only declared CI gate; the GitHub workflows are inert
  (`.disabled`), disabled in the repository settings too, and marked deprecated.
- Issue templates and CONTRIBUTING use the labels and milestones that actually
  exist.
- Frozen inputs (reference report and strategy files) start with a banner that
  says they are not a source of truth.
- `CLAUDE.md` is sanitized for public consumption; owner-only operations moved
  out of the repository.

### Fixed

- The license metadata: `README`, `CONTRIBUTING` and `NOTICE` now agree on
  AGPL-3.0-or-later.
- The OpenAPI document declares its license, servers, tags and contact, and the
  two equivalent `/v1/activities/{...}` path items are merged; Spectral stays an
  error-level gate.
- The contradiction between the roadmap, the GitHub milestones and the MVP-L due
  date.
- The default Compose stack no longer starts with static object-store
  credentials: `init-env.sh` is required and the stack fails closed without it.
