# Security policy

## Reporting a vulnerability

Use GitHub's **private vulnerability reporting** on this repository: **Security** tab → **Report a vulnerability**. It is the only reporting channel today: do not open a public issue or a pull request for a security problem.

Include the affected component and commit, the reproduction steps, the impact, and any suggested fix. If a proof of concept contains personal data, redact it before attaching it.

## What to expect

- Acknowledgement within 5 working days (best effort: this is a one-maintainer project).
- An assessment and a remediation plan within 30 days for confirmed issues.
- Credit in the security advisory and the CHANGELOG if you want it. There are no tagged releases yet.

A dedicated `security@` mailbox and a PGP key will be published before the managed service opens. Until then, GitHub's private reporting keeps the report private in transit and at rest.

## Scope

In scope: the backend API, authentication and session handling, authorization (including IDOR), the GPX and FIT importers (TCX is not implemented), the sync protocol, the Android and iOS apps, the web app, and the default self-hosting configuration.

Out of scope: findings that require a compromised device or host, social engineering, denial of service without a reproducible proof of concept, and issues in third-party services or dependencies that are already reported upstream.

Location data reveals home, workplace and routines. A bug that leaks a private activity, a privacy zone or a start/end point is treated as **high severity**. The same applies to heart-rate and RR data, which are health-adjacent ([ADR-0013](docs/adr/0013-health-data-special-category.md)).

## Safe harbor

We will not pursue legal action or ask a platform to remove research carried out in good faith under this policy. Keep the report private until a fix is available, do not access other users' data beyond the minimum needed to demonstrate the issue, and do not degrade the service.

## Supported versions

Pre-alpha: only `main` is supported. There are no tagged releases yet; a report should name the commit it was tested against.
