# Security policy

## Reporting a vulnerability

Use GitHub's **private vulnerability reporting** ("Security" tab → "Report a vulnerability") on this repository. Do not open public issues or PRs for security problems.

Include: affected component and version/commit, reproduction steps, impact, and any suggested fix.

## What to expect

- Acknowledgement within 5 working days.
- An assessment and a remediation plan within 30 days for confirmed issues.
- Credit in the release notes if you want it.

## Scope

In scope: backend API, authentication/session handling, authorization (IDOR), file upload/import parsers (GPX/FIT/TCX), sync protocol, mobile apps, web app, default self-hosting configuration.

Location data reveals home, workplace and routines; a bug that leaks a private activity, a privacy zone or a start/end point is treated as **high severity**.

## Supported versions

Pre-alpha: only `main` is supported.
