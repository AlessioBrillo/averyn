# TDD-0014: Saving an activity to HealthKit and Health Connect

- **Status:** implemented
- **Version:** 0.1
- **Author:** @AlessioBrillo
- **Date:** 2026-10-26
- **Related:** [TDD-0006](TDD-0006-field-readiness.md), [TDD-0007](TDD-0007-sensor-channels.md), [ADR-0013](../adr/0013-health-data-special-category.md), [ADR-0014](../adr/0014-sensor-integration-contract.md), [ADR-0022](../adr/0022-imported-activity-provenance.md), [DPIA](../privacy/dpia.md), [launch checklist](../product/mvp-launch.md), issue #70

## 1. Summary

A user can save a finished activity, recorded on their phone, to the health platform of the phone (Health on iOS, Health Connect on Android) with one tap. The workout, its route, the heart rate that was recorded and the distance are written. Averyn **only writes**: it reads nothing from the platform and asks for no permission to read.

## 2. Goals and non-goals

**Goals:** a manual, opt-in "save to Health / Health Connect" for an activity that is on this phone; one description of the workout for both platforms; saving twice does not duplicate; the permissions asked are exactly the ones written.

**Non-goals (v1):** reading anything from the platforms (steps, sleep, a workout of another app) — an import from them has a duplicate problem that needs its own design ([ADR-0022](../adr/0022-imported-activity-provenance.md)); writing automatically at the end of every recording (a setting for later: a permission prompt at the end of a run is the wrong moment); activities that are not on this phone (an imported or other-device activity has no per-point times on the phone); pauses (a Health Connect session has none; the route simply has no points while paused); calories and cadence (Averyn does not measure them).

## 3. Functional requirements

| ID | Requirement |
|---|---|
| H1 | The list "On this device" has a button to save a finished activity; the button is not shown for an activity that has no summary yet. |
| H2 | What is written is the activity as Averyn counts it: the route is the accepted samples of the recording time (the same rule as the `ACCEPTED_ONLY` GPX), the heart rate is the readings of the recording time (one taken during a pause is not part of the workout), the distance is the one the metrics computed. |
| H3 | Saving the same activity again does not make a copy: Health Connect records have a client id made from the activity id; a HealthKit workout has the activity id as its external id and is looked up first. |
| H4 | The permissions asked are write-only: workout, route, heart rate, distance. A refusal is reported in words and nothing is written. |
| H5 | If the platform is not there (no Health Connect on the phone, or one that needs an update; Health not available on some iPads), the button says why. |
| H6 | The platform asks why the app wants its permissions, and gets an answer (Android: a rationale screen; iOS: the usage description). |

## 4. Non-functional requirements

- **Privacy:** the workout leaves Averyn for another app on the same phone because the user asked. It is Restricted data written by the user's request; Averyn keeps nothing of it and never reads it back. The platforms' own sharing is the user's choice there.
- **No new data in the cloud:** nothing here touches the server.
- **Store declarations:** Google Play requires a Health Connect permissions declaration and a rationale; Apple reviews the HealthKit entitlement and the usage text. Both are part of the store listing work, not of the code ([launch checklist](../product/mvp-launch.md)).

## 5. Design

### 5.1 One description of the workout

`shared/tracking` has `ActivityStore.workoutExport(activityId)` (`WorkoutExport`): the sport, the start and end, the distance, the route points (time, position, altitude, accuracy), the heart-rate readings and the pauses. It returns null for an activity that is not finished or has no route. Both phones map it to their platform; the rule of what belongs in a workout is written, and tested, once.

### 5.2 Android: Health Connect

`androidx.health.connect:connect-client` 1.1.0. `HealthConnectExporter.toRecords` maps the workout to an `ExerciseSessionRecord` (with an `ExerciseRoute`), a `HeartRateRecord` and a `DistanceRecord`; it is a pure function with unit tests, because Health Connect rejects what is slightly wrong (a route with equal or decreasing times, a point outside the session, a session that does not last), and the mapping drops such points instead of failing the whole write. The sport maps to running, biking, walking or hiking. Permissions: `WRITE_EXERCISE`, `WRITE_EXERCISE_ROUTE`, `WRITE_HEART_RATE`, `WRITE_DISTANCE`. The manifest declares the rationale screen and its Android 14 alias, and that the Health Connect package is visible (before Android 14 it is a separate app).

### 5.3 iOS: HealthKit

`HealthKitExporter` uses an `HKWorkoutBuilder` (the workout, the distance and heart-rate samples, the external id as metadata) and an `HKWorkoutRouteBuilder` for the route. The entitlement is `com.apple.developer.healthkit` with no read access; the only usage text is `NSHealthUpdateUsageDescription`. It runs off the main thread, like the other file work of the app.

## 6. Test strategy

| Level | What it covers |
|---|---|
| `WorkoutExportTest` (shared) | a finished activity with a pause: route, heart rate without the pause reading, the pause, the distance; a sample the quality rules exclude is not in the route; an unfinished or route-less activity and an unknown id are null; no heart rate is an empty list |
| `HealthConnectExporterTest` (Android) | the records of a workout; the sport to the exercise type; client ids; a route with strictly increasing times inside the session; no heart rate or distance means no such record; a session that does not last is not written; a reading outside the session is left out |
| iOS | the iOS build only; it is Swift against system frameworks |
| device matrix | S38 (Health Connect) and S39 (Health) on real devices: the workout is in the platform with the right route and numbers, saving twice does not duplicate, a refusal writes nothing |

## 7. Rollout and migration

No server change, no migration. The Android permissions are new in the manifest; the iOS entitlement is new in the project (the signing profile of the tester builds must carry HealthKit, [ADR-0019](../adr/0019-crash-reporting-and-tester-builds.md)).

## 8. Risks and mitigations

| Risk | Mitigation |
|---|---|
| The platform rejects a record the mapping got wrong | The mapping is pure and tested against the constructors' own validations; a failed write says so and changes nothing |
| A duplicate in Health | Client ids on Android; an external-id lookup first on iOS |
| An app that asks for health permissions is reviewed more strictly | Write-only, minimal, explained; the declarations are part of the store work |
| The Swift code uses system frameworks that only a device exercises | Row S39; the iOS job compiles it |

## 9. Open questions

| # | Question | Status |
|---|---|---|
| Q1 | Save automatically at the end of a recording, as a setting? | Open, after the first runs on real devices |
| Q2 | Read from the platforms (to import a history)? | Out of MVP-L: needs a duplicate design |

## 10. What is implemented

The shared workout, the Health Connect export with its rationale, the HealthKit export, the buttons on both phones. **Not verified:** nothing was run on a device (rows S38 and S39).
