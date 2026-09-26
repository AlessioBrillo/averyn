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

## Results

| Date | Device | OS | Scenario | Duration | Battery Δ/h | Samples lost | Result | Notes |
|---|---|---|---|---|---|---|---|---|
| | | | | | | | | |

## Devices

| Device | OS | Owner | Notes |
|---|---|---|---|
| _to be filled_ | | | Prioritize: 1 Pixel, 1 Samsung, 1 Xiaomi/OnePlus (aggressive battery managers), ≥ 1 recent iPhone, 1 older iPhone |
