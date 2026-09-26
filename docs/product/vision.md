# Vision

Averyn (codename) is an open-source, self-hostable sports platform for iOS, Android and Web with broad functional parity with an existing platform, without a paywall on core features. It is not a UI clone: it is a modular system whose users can run the managed service or keep their own data on their own infrastructure.

## Priorities (in order)

1. Extremely reliable GPS and tracking
2. Broad feature parity with an existing platform
3. Advanced sports analysis
4. Offline maps and navigation
5. Social and community
6. Smartwatch and sensor support

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

The product is built around its own data model and processing pipeline. Integrations (an existing platform, HealthKit, Health Connect, …) live in a separate layer and must respect the terms of service of each platform; the system must remain fully functional if every external integration disappears. No scraping, no reliance on unguaranteed APIs.

Source: [reference report v0.1](reference-report-v0.1.md) §1–3.
