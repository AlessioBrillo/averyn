# Device test matrix

Status: ready for the first campaign — protocol below; results are filled in as devices are tested during MVP-0. Emulators are **not** valid evidence for background, battery or recovery behavior ([TDD-0001 §6](../design/TDD-0001-tracking-engine.md)). Builds under test come from the tester tracks ([ADR-0019](../adr/0019-crash-reporting-and-tester-builds.md)); every result row names the build.

## Dimensions ([report §6.4](../product/reference-report-v0.1.md))

OS version · device model/vendor · battery state · signal quality · app state (foreground / background / locked) · connectivity · duration · sport · sensors present · ambient temperature (when relevant).

## MVP-0 exit criteria and how each is measured

| Criterion ([roadmap](../product/roadmap.md)) | Measured by |
|---|---|
| ≥ 3 h outdoor on ≥ 2 Android and ≥ 2 iOS devices, screen locked | S2 + S11 rows per device; `elapsedMs` ≥ 3 h in the diagnostics, device locked the whole time |
| Force-kill and reboot recovery lose ≤ 1 sample interval | S5/S6: the last stored sample is within one interval (1 s) of the kill instant — see "Recovery loss" below |
| GPX export validates | S1 row: `xmllint --noout --schema gpx-1.1.xsd activity.gpx` passes |
| Diagnostics report shows quality | `quality.grade`, flag counts and `accuracyWithin20mPercent` present in the exported JSON |
| Results logged here | one row per run in Results |

## Scenarios (each run records pass/fail + notes)

Setup common to all: fully charged unless the scenario says otherwise, location permission "Always"/precise granted, Android battery optimisation set to unrestricted for the app (record the setting), same outdoor route for comparable runs, sport RUN or RIDE, notes taken with wall-clock times.

| ID | Scenario | Steps | Pass when |
|---|---|---|---|
| S1 | Foreground recording, screen on | Record ≥ 10 min walking, finish, export GPX and diagnostics | Distance within 2% of a reference (track length or second device); GPX validates; grade ≥ FAIR |
| S2 | Background, screen locked, ≥ 1 h | Start, lock the screen, put the phone in a pocket, 1 h | No gap > 10 s in `intervalDistribution`; `longestGapMs` < 10 s outside known signal loss |
| S3 | Pause / resume (manual) | 3 pauses of ≥ 1 min | `pausedMs` equals the sum within 2 s; no samples counted while paused; export has one segment per recording interval |
| S4 | Intermittent GPS (tunnel, urban canyon) | Walk through ≥ 2 min of bad signal | Gap and `POOR_ACCURACY` counted in the report; no crash; track continues |
| S5 | Force-kill during recording → recovery | Swipe the app away (Android also: `adb shell am kill dev.averyn.app`); note the instant; wait 30 s | Recording resumes by itself (R1) or is offered on next launch (iOS swipe-kill is expected to be the latter); `recoveryGaps[0].lastSampleBeforeMs` within 1 s of the kill instant; numbers continuous |
| S6 | Reboot during recording → recovery | Reboot the phone, then open/tap the notification | Recovery works; no `NON_MONOTONIC_TIME` burst; `rebootDetected: true`; distance keeps growing after the reboot |
| S7 | Permission revoked / downgraded mid-activity | Switch to "While using"/"approximate" or revoke in Settings | Activity paused with reason `PERMISSION_*`, banner shown, never silently stopped (F9) |
| S8 | Low battery / battery saver on | Run at ≤ 15% and with saver mode | Recording continues or fails visibly; battery drain/h in the row |
| S9 | Network lost / airplane mode | Airplane mode for the whole activity | No effect on recording; sync is `PENDING` until reconnect |
| S10 | App update during an activity | Install a newer tester build over the running one | Activity interrupted and recovered; no data from before the update lost |
| S11 | Long activity (≥ 3 h) | One continuous outdoor session, screen locked | Completes; `elapsedMs` ≥ 3 h; memory/battery sane; GPX valid and complete |
| S12 | Sync ([TDD-0002](../design/TDD-0002-activity-sync.md)): finish offline, reconnect | Needs the backend reachable over TLS from the phone | Reaches `READY`; one row server-side; same distance as the phone |
| S13 | Sync: kill the app mid-upload | Throttle the network, kill during upload, relaunch | Retries; no duplicate row; one object |
| S14 | Sync: expired / revoked sign-in | Revoke the session at the IdP | `FAILED_RETRYABLE (AUTH)`; signing in again uploads it |

### Recovery loss (S5/S6)

"Loses ≤ 1 sample interval" is a durability claim: every fix the app received before the kill is on disk. It cannot be read from the phone alone, so the tester records the **kill instant** (wall clock, to the second: `adb shell date +%T` before `am kill`, or a stopwatch app for a swipe). In the diagnostics, `recoveryGaps[n].lastSampleBeforeMs` is the wall time of the last stored sample. Pass: `kill instant − lastSampleBeforeMs ≤ 1 s` (one 1 s interval; +1 s tolerance for clock reading). `gapMs` (time without GPS until recording resumed) is reported separately as resume latency; it is not part of the criterion.

### GPX validation

Download `gpx-1.1.xsd` once (`http://www.topografix.com/GPX/1/1/gpx.xsd`, also kept at `tests/gps-fixtures/schema/` after the tracking fixes of [TDD-0006](../design/TDD-0006-field-readiness.md)), then:

```sh
xmllint --noout --schema gpx-1.1.xsd activity.gpx
```

## Filling a row

After each run, use the history list in the app: export **diagnostics** (JSON, `diagnostics-v2`, [TDD-0006 §5.5](../design/TDD-0006-field-readiness.md)), **GPX** and, for S5/S6/S10, the **raw file**. Diagnostics hold everything **Samples lost** and **Battery Δ/h** need (`droppedInputs`, `recordLosses`, `batteryDrainPercentPerHour`) and the quality grade for **Notes**. Keep the exported files with the run in a private folder, not in this repository (test artifacts, not fixtures; raw files contain coordinates).

## Results

| Date | Device | OS | Build (version / commit) | Scenario | Duration | Battery Δ/h | Samples lost | Recovery lastSampleBefore Δ / gap | GPX valid | Grade | Result | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| | | | | | | | | | | | | |

## Devices

| Device | OS | Owner | Battery optimisation setting | Notes |
|---|---|---|---|---|
| _to be filled_ | | | | Prioritize: 1 Pixel, 1 Samsung, 1 Xiaomi/OnePlus (aggressive battery managers), ≥ 1 recent iPhone, 1 older iPhone |
