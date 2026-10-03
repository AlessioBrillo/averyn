# Device test matrix

Status: template — populated as devices are tested during MVP-0. Emulators are **not** valid evidence for background, battery or recovery behavior ([TDD-0001 §6](../design/TDD-0001-tracking-engine.md)).

## Dimensions ([report §6.4](../product/reference-report-v0.1.md))

OS version · device model/vendor · battery state · signal quality · app state (foreground / background / locked) · connectivity · duration · sport · sensors present · ambient temperature (when relevant).

## Scenarios (each run records pass/fail + notes)

| ID | Scenario |
|---|---|
| S1 | Foreground recording, screen on |
| S2 | Background, screen locked, ≥ 1 h |
| S3 | Pause / resume (manual) |
| S4 | Intermittent GPS (tunnel, urban canyon) |
| S5 | Force-kill during recording → recovery |
| S6 | Device reboot during recording → recovery |
| S7 | Permission revoked / downgraded mid-activity |
| S8 | Low battery / battery saver on |
| S9 | Network lost / airplane mode |
| S10 | App update during an activity |
| S11 | Long activity (≥ 3 h) |
| S12 | Sync ([TDD-0002](../design/TDD-0002-activity-sync.md)): finish an activity offline, reconnect, it reaches `READY` and one row exists server-side |
| S13 | Sync: kill the app mid-upload, relaunch: it retries and no duplicate is created |
| S14 | Sync: expired / revoked sign-in shows `FAILED_RETRYABLE (AUTH)`, signing in again uploads it |

## Filling a row

After each run, use the "Export diagnostics" action in the app (Android: share-sheet JSON; iOS: `ShareLink`)
to get the activity's `diagnostics-v1` report ([TDD-0001 §5.4](../design/TDD-0001-tracking-engine.md)). It
has everything **Samples lost** and **Battery Δ/h** below need directly: `recordLosses`,
`batteryDrainPercentPerHour`, and the quality grade/accuracy for **Notes**. Keep the exported JSON files
with the run (not committed to this repo — they're test artifacts, not fixtures).

## Results

| Date | Device | OS | Scenario | Duration | Battery Δ/h | Samples lost | Result | Notes |
|---|---|---|---|---|---|---|---|---|
| | | | | | | | | |

## Devices

| Device | OS | Owner | Notes |
|---|---|---|---|
| _to be filled_ | | | Prioritize: 1 Pixel, 1 Samsung, 1 Xiaomi/OnePlus (aggressive battery managers), ≥ 1 recent iPhone, 1 older iPhone |
