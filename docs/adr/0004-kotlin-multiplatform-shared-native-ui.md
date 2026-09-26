# ADR-0004: Kotlin Multiplatform shared logic; native UI and native tracking

- **Status:** accepted
- **Date:** 2026-09-26
- **Deciders:** @AlessioBrillo

## Context and problem

GPS tracking, background execution and sensors are the highest-risk area and are OS-specific ([report §5.1, §7, §8](../product/reference-report-v0.1.md)). Domain rules, metrics, validation and sync must behave identically on iOS, Android and the backend.

## Options considered

1. **Fully native, duplicated logic** — best OS fidelity, but every metric/rule is implemented and tested twice.
2. **Flutter / React Native** — one UI codebase, but background location and sensor work still need native modules and fight the framework lifecycle.
3. **KMP for domain + native UI + native tracking adapters** — shared, testable logic; full control where the OS matters.

## Decision

Option 3. `shared/` (Kotlin Multiplatform: android, iOS, jvm targets) holds the sample model, the activity state machine, the tracking pipeline (validation, quality assessment) and metrics. Native code owns location services, lifecycle, permissions, background execution, BLE, HealthKit and Health Connect, behind interfaces declared in `shared/tracking` (e.g. `LocationSource`).

The `jvm` target lets the **backend reuse the same metrics and validation code**, so device-side and server-side results agree by construction.

## Consequences

- Good: one implementation and one test suite for the rules; native reliability for tracking.
- Cost: KMP/Kotlin-Native interop learning curve; iOS builds require macOS; two UI toolkits.
- The tracking PoC (MVP-0) is the validation of this decision; failure criteria are listed in [TDD-0001](../design/TDD-0001-tracking-engine.md).

## Revisit when

The PoC shows KMP interop is a blocker for the tracking pipeline on iOS, or Kotlin/Native memory/perf issues affect battery.
