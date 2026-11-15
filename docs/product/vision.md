# Vision

Averyn (codename) is an open-source, self-hostable sports platform for iOS, Android and Web that people actually enjoy using: free, private by default, and good to look at, without a paywall on core features. It is not a UI clone: it is a modular system whose users can run the managed service or keep their own data on their own infrastructure.

## Priorities (in order)

1. Extremely reliable GPS and tracking, proved on real devices
2. A great experience in three moments: while recording, the summary right after, the history and progress
3. Privacy that the user can see: no account needed to record, private zones, one-tap export and deletion
4. Everything free, with a managed cloud that costs the user nothing
5. Sensors that just work: any standard heart-rate strap, and the Synapse band as a companion
6. Social and community, free and private by default, right after the launch ([ADR-0031](../adr/0031-social-and-community.md)): profiles, follow, feed, groups, segments, challenges, then sections for more kinds of athlete
7. Advanced analysis, then offline maps and navigation

A feature is added when it serves one of the three moments, the privacy promise or the community ([ADR-0028](../adr/0028-consumer-launch-target.md), [ADR-0031](../adr/0031-social-and-community.md)). The users' data is never the product: no profiling, no advertising built on it ([ADR-0032](../adr/0032-revenue-model.md)).

## Principles

1. User data is always exportable.
2. Core functions are never artificially paywalled.
3. Tracking works without a connection.
4. Data quality is explicit and measurable.
5. Metrics are versioned and reproducible.
6. Offline-first wherever it makes sense.
7. Self-hosting is a real mode, not a theoretical add-on.
8. Privacy is designed in from the start.
9. Extensible without prematurely becoming microservices.
10. Sustainable for a single developer.

## Never locked behind a paywall

Access to personal data · export · basic tracking · fundamental metrics · security features · the ability to migrate to a self-hosted install.

## Independence from third parties

The product is built around its own data model and processing pipeline. Integrations (third-party fitness platforms, HealthKit, Health Connect, …) live in a separate layer and must respect the terms of service of each platform; the system must remain fully functional if every external integration disappears. No scraping, no reliance on unguaranteed APIs.

Source: [reference report v0.1](reference-report-v0.1.md) §1–3.
