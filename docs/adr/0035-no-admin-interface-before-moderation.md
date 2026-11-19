# ADR-0035: No admin interface before moderation (MVP-4)

- **Status:** accepted
- **Date:** 2026-11-19
- **Deciders:** @AlessioBrillo
- **Supersedes in part:** [ADR-0007](0007-web-stack.md) (the "admin inside the web app" decision only)

## Context and problem

[ADR-0007](0007-web-stack.md) chose a React + TypeScript + Vite SPA and said
that an admin area would be a route of the web app, guarded by role. The web app
was then built without one, and the architecture overview states plainly that
there is **no admin or moderation interface**: the operator works with runbooks
and the database ([overview](../architecture/overview.md)). The README still
claimed that `apps/web` "includes the admin area", which is false and misleads
anyone evaluating the project.

There is also no moderation feature before the social milestone
([ADR-0031](0031-social-and-community.md), [ADR-0034](0034-social-scope-and-device-openness.md)):
there are no public profiles, feeds, comments or reports to moderate in the
launch product. Building an admin console now would be code without a user, a
security surface without a policy, and a second source of truth next to the
database.

## Options considered

1. **Build the admin area now** — matches the old ADR, but adds a privileged
   interface, authentication rules and audit requirements before there is any
   content to moderate. High cost, no launch value.
2. **No admin interface until MVP-4; the operator uses runbooks and SQL** —
   honest about the current system, keeps the launch surface small, and defers
   the design until the moderation rules exist.
3. **A separate admin app** — rejected for the same reason as in ADR-0007: it
   doubles tooling for one maintainer before there is a use case.

## Decision

There is **no admin or moderation interface** in MVP-L. The operator uses the
runbooks (`docs/runbooks/`) and the database. The README, the web app and the
architecture overview say so.

When MVP-4 starts, the moderation interface is designed in its own ADR and
design doc, with its roles, audit log and data-access policy. It may still be a
route of the web app (the stack decision of ADR-0007 stands), but that is a new
decision, not an inherited one.

## Consequences

- Good: the public description matches the code; no privileged UI, no new
  security surface, no unused admin code before launch.
- Cost: operator actions during the pre-alpha and the launch are manual and
  require shell/database access; this is acceptable for a single-node,
  one-operator service and is documented in the runbooks.
- Follow-up: the first moderation ADR must include the audit trail and the
  access policy that a moderation interface needs.

## Revisit when

MVP-4 (social and community) starts, or a second operator needs to act on user
data without shell access. The trigger is the first moderation workflow that
cannot be performed safely with a runbook.
