# Contributing

## Ground rules

1. **Nothing lands on `main` without a PR and green CI.** `main` is protected, history is linear (squash merge).
2. **Conventional Commits** for PR titles (`feat(tracking): …`, `fix(backend): …`, `docs: …`, `chore: …`). CI enforces this. Scopes: `tracking`, `metrics`, `domain`, `backend`, `android`, `ios`, `web`, `infra`, `docs`.
3. **Decisions are written down.** Anything that is hard to reverse (framework, storage format, protocol, license, public API shape) needs an ADR in `docs/adr/` in the same PR or before it.
4. **Non-trivial features start with a design doc** in `docs/design/` (copy `template.md`). Trivial changes don't.
5. **Tests ship with logic.** Tracking, metrics and sync code without tests will not be merged. GPS behavior is tested against fixtures in `tests/gps-fixtures/`.
6. **Metrics are versioned.** Changing an algorithm's output means a new `algorithm_version` and an update to its doc in `docs/metrics/`; never silently change an existing version.
7. **Privacy impact is part of review.** Location data is sensitive (see `docs/privacy/data-classification.md`). New data collected, stored or logged must be justified in the PR.
8. **No secrets in the repo.** `.env.example` only. CI runs gitleaks.

## Workflow

```sh
git switch -c feat/tracking-state-machine
# work, commit small
./gradlew check            # JVM/KMP: tests + ktlint
(cd apps/web && npm run check)   # lint + typecheck + test
git push -u origin HEAD    # open a PR using the template
```

Branch names: `feat/…`, `fix/…`, `docs/…`, `chore/…`. Track work in GitHub issues with `area/*`, `type/*`, `priority/*` labels and an MVP milestone.

## Code style

- Kotlin: ktlint (official style), enforced by `./gradlew check`.
- TypeScript: oxlint + `tsc` with `strict`; no separate formatter yet (add one when needed).
- Swift: SwiftLint is added when iOS code grows beyond the stub (tracked in the roadmap).
- Formatting is defined in `.editorconfig`; let your editor apply it.

## Architecture guardrails

- `shared/domain` and `shared/metrics` are pure Kotlin with **no platform dependencies**.
- Platform specifics (location, background execution, BLE, HealthKit, Health Connect) live in the app modules behind interfaces defined in `shared/tracking`.
- Backend modules are packages under `backend/src/main/kotlin/…`; a domain must not reach into another domain's internals.
- Raw location samples are **never overwritten**; derived data is stored separately and versioned.

## Licensing of contributions

The project is AGPL-3.0-or-later. A CLA (or DCO) decision is pending in [ADR-0002](docs/adr/0002-license-agplv3.md); until it is settled, external code contributions are not accepted.

## Reporting security issues

See [SECURITY.md](SECURITY.md). Do not open public issues for vulnerabilities.
