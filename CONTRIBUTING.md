# Contributing

## Ground rules

1. **Nothing lands on `main` without a PR and a green Woodpecker gate.** `main` is protected (PR required, linear history). The Woodpecker server is not connected to the repository yet: until it is, run `tools/ci/ci-local.sh` before opening the PR, and the check will be made required when the server reports it.
2. **Conventional Commits** for PR titles (`feat(tracking): …`, `fix(backend): …`, `docs: …`, `chore: …`). Scopes: `tracking`, `metrics`, `domain`, `backend`, `android`, `ios`, `web`, `sync`, `import`, `records`, `infra`, `docs`, `deps`, `ci`.
3. **Decisions are written down.** Anything that is hard to reverse (framework, storage format, protocol, license, public API shape) needs an ADR in `docs/adr/` in the same PR or before it.
4. **Non-trivial features start with a design doc** in `docs/design/` (copy `template.md`). Trivial changes don't.
5. **Tests ship with logic.** Tracking, metrics and sync code without tests will not be merged. GPS behavior is tested against fixtures in `tests/gps-fixtures/`.
6. **Metrics are versioned.** Changing an algorithm's output means a new `algorithm_version` and an update to its doc in `docs/metrics/`; never silently change an existing version.
7. **Privacy impact is part of review.** Location data is sensitive (see `docs/privacy/data-classification.md`). New data collected, stored or logged must be justified in the PR.
8. **No secrets in the repo.** `.env.example` only. The Woodpecker gate runs gitleaks.

## Workflow

```sh
git switch -c feat/tracking-state-machine
# work, commit small
./gradlew check                  # JVM/KMP: tests + ktlint
(cd apps/web && npm run check)   # lint + typecheck + test
tools/ci/ci-local.sh             # the full gate, same commands as Woodpecker (Docker)
git push -u origin HEAD          # open a PR using the template
```

Branch names: `feat/…`, `fix/…`, `docs/…`, `chore/…`. Track work in GitHub issues with `area/*` labels and the active milestone (`MVP-L` until the launch). Type is expressed with GitHub's default labels (`bug`, `enhancement`, `documentation`); there is no separate `priority/*` taxonomy.

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
- Third-party sensor integrations (any vendor, not just Synapse) follow [ADR-0014](docs/adr/0014-sensor-integration-contract.md): standard GATT profiles first, vendor extensions stay in native adapters, cloud/vendor-computed scores are stored as imported data, never as a `shared/metrics` type.

## Licensing of contributions

The project is AGPL-3.0-or-later (see [NOTICE](NOTICE) for the copyright and the
"or later" grant). External contributions require a signed [CLA](CLA.md); the
signing flow is being finalized, so **contact the maintainer before opening a
non-trivial PR** and the signature will be recorded manually. See
[ADR-0012](docs/adr/0012-cla-and-store-distribution.md) for why a CLA rather than
a DCO. A CLA draft is not legal advice and will be reviewed before it is relied
on.

## CI gate

Woodpecker is the only gate (`.woodpecker/ci.yml`): JVM, backend, Android, web,
docs, link check, OpenAPI lint and secret scan. Run the same pipeline locally
with `tools/ci/ci-local.sh` (Docker required); until the server is connected,
that local run is the gate. iOS is not covered by CI: build it
on macOS following [apps/ios/README.md](apps/ios/README.md) and say so in the PR.
A Swift change that has not been compiled stays a draft.

## Reporting security issues

See [SECURITY.md](SECURITY.md). Do not open public issues for vulnerabilities.

## Public-repo hygiene

This is a public repository; nothing here should expose a contributor's real email or read as a comparison against a named competitor.

- **Commit identity:** set `git config user.email <id>+<username>@users.noreply.github.com` (GitHub's noreply address, from Settings → Emails) before your first commit. Enable the guard rail: `git config core.hooksPath .githooks` — `.githooks/pre-push` rejects a push containing a non-noreply author/committer email or `Co-authored-by` trailer.
- **No competitor names or disparagement.** Describe the market generically ("established sports-tracking platforms"). Interoperability format/API names (GPX, FIT, TCX, HealthKit, Health Connect, BLE GATT profiles) are fine where technically required.
- **No personal traces.** Never commit real GPS recordings, home/work coordinates, or other personal data — see `tests/gps-fixtures/README.md` for the anonymization rule.
