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
| S15 | Heart rate vs reference strap | Record ≥ 10 min with a BLE HR strap; compare `avgHrBpm`/`maxHrBpm` in the exported diagnostics with the strap's own app | HR present, within 5 bpm of the strap reference, no dropouts > 30 s |
| S16 | Consent revocation mid-activity | Grant `rr_recording`, record with RR, revoke consent in Account, continue recording | RR sidecar stops immediately; re-upload of the sidecar gets `410`; the activity file itself is intact |
| S17 | Analysis of a recorded activity | Record ≥ 10 km (or the longest the route allows) with the app; open the activity on the web | The splits add up to the distance of the summary (±0.1 %); the times of the splits add up to the moving time (±1 s); the elevation is within 15 % of the reference of the route; the charts are drawn |
| S18 | Analysis of an imported file | Import a GPX of another app with more than one `<trkseg>` and with an `<ele>`; open the activity on the web | The pause is not crossed by the track; the profile is drawn; the elevation is not zero when the file has one; the last split is marked partial |
| S19 | Analysis of the heart rate | Record ≥ 30 min with a BLE HR strap, pause 5 min in the middle, open the activity on the web | The coverage of the heart rate is the one of the recording time, the pause excluded (≥ 95 %); the chart breaks where the strap was silent; the dropouts are the ones of the strap |
| S20 | Analysis, export and deletion | Export the account, delete one activity, open another one | The export holds `analysis/<id>.json` with the splits of the activity; the deleted activity has no analysis left; another user gets `404` |
| S21 | Best efforts of a recorded activity | Record ≥ 10 km with the app; open the activity on the web | The best 1 km is the fastest kilometre of the track: its time is between the time of the fastest split and the time of the average split (±2 s); the effort never crosses a pause |
| S22 | Records of the account | Record twice, open the Records page, delete the activity that holds the record | The record is the fastest of the two; deleting it promotes the other one; the page says the source and the quality of the record |
| S23 | History on the phone ([TDD-0011](../design/TDD-0011-mobile-read-path.md)) | Sign in on the phone with an account that has ≥ 3 activities (one recorded on another device, one imported GPX); open the history | The list shows the activities of the account with distance, time and pace; the numbers match the web (±0.1 % distance, ±1 s moving time); the imported one is marked as imported; opening an activity shows its splits and its two curves; no map |
| S24 | Offline copy | Open 3 activities online, then airplane mode, force-quit and relaunch | The list shows the activities with an "offline copy" note and the time of the copy; the 3 activities open with their splits and curves; an activity that was never opened says it is not available offline; after the network is back the refresh clears the note |
| S25 | The numbers of an activity not yet uploaded | Airplane mode, record ≥ 10 min (Android with a HR strap), stop, force-quit and relaunch | The row of the activity shows distance, elapsed time, pace and the average/max HR, without a connection; the same numbers appear in the diagnostics (±1 bpm); after the upload the numbers of the server are the same |
| S26 | An activity deleted on the server | Upload an activity, delete it on the web, refresh the history on the phone | The activity disappears from the list; it is still in "On this device" with its numbers and its exports; deleting it there removes its files; the server is not touched by the local deletion |
| S27 | A version that does not fit | Point the app at a server with `AVERYN_MIN_CLIENT_VERSION` above the version of the app (or an `apiVersion` of the future) | The app warns on the history and on the account screen and keeps working: the list and the detail open; the warning says which side is behind; an app and a server of the same version show nothing |
| S28 | The analysis is being computed | Open an activity whose analysis the server is computing (a long activity, or a server that answers `503` with `Retry-After`) | The detail shows the numbers of the activity and "the analysis is being computed"; the app reads it again after the time the server asked and shows the curves; no new request loop, no crash |
| S32 | Profile on the phone ([TDD-0012](../design/TDD-0012-profile-zones-phone-map.md)) | Sign in; in the account section choose imperial and a maximum heart rate of 187; save; force-quit; airplane mode; relaunch and open the history | The list, the detail and the splits write distances in miles, paces per mile and altitudes in feet, also offline; the web shows the same profile; a maximum of 99 is refused on the screen and nothing is saved |
| S33 | Heart-rate zones | After S19 (≥ 30 min with a strap) open the activity on the phone and on the web | Both show the same five times (±1 s) and they add up to the time with a heart rate; changing the maximum on the web and refreshing the phone moves the time between the zones; with no maximum the screen says where to set it |
| S34 | The map on the phone ([TDD-0012](../design/TDD-0012-profile-zones-phone-map.md)) | Open an activity with a pause online; then airplane mode, force-quit, relaunch and open it again; then open one never opened offline | Online the track is on the map of the server's style, fitted to the screen, with a break at the pause and no line across it; offline the same line is drawn on a plain background with a note that the base map is not available; the never-opened activity says it is not available offline; the screen still scrolls when a finger starts on the map |
| S35 | Import of a FIT file from a watch ([TDD-0013](../design/TDD-0013-fit-import.md)) | Export an activity with a heart-rate strap from a sports watch as FIT; import it on the web; compare with the watch's own app | The distance is within 2 % of the watch, the elapsed and moving times within 5 s, the pauses of the watch are pauses, the average and maximum heart rate within 2 bpm; the import report lists what was not imported; importing the same file again opens the same activity |
| S36 | Import of an exported archive ([TDD-0013](../design/TDD-0013-fit-import.md)) | Request the data export of a sports platform you used, import the ZIP in parts of at most 100 files | Every activity file is a line of the report; the ones the platform wrote as FIT or GPX are imported (also gzipped), the rest is counted as not read; the totals of distance match the platform's within 2 %; importing the same ZIP again stores nothing |
| S37 | Account deletion on the phone ([TDD-0004](../design/TDD-0004-data-deletion-and-export.md)) | Sign in with a test account that has ≥ 2 uploaded activities; in the account section choose Delete my account, type the word, confirm; then open the web with the same sign-in | The app signs out and says what stays; the offline copy is gone; the recordings made on the phone are still listed under "On this device"; the web shows no activities and the account is refused (`403`); a wrong word does not delete anything |

### Recovery loss (S5/S6)

"Loses ≤ 1 sample interval" is a durability claim: every fix the app received before the kill is on disk. It cannot be read from the phone alone, so the tester records the **kill instant** (wall clock, to the second: `adb shell date +%T` before `am kill`, or a stopwatch app for a swipe). In the diagnostics, `recoveryGaps[n].lastSampleBeforeMs` is the wall time of the last stored sample. Pass: `kill instant − lastSampleBeforeMs ≤ 1 s` (one 1 s interval; +1 s tolerance for clock reading). `gapMs` (time without GPS until recording resumed) is reported separately as resume latency; it is not part of the criterion.

### GPX validation

Download `gpx-1.1.xsd` once (`http://www.topografix.com/GPX/1/1/gpx.xsd`, also kept at `tests/gps-fixtures/schema/` after the tracking fixes of [TDD-0006](../design/TDD-0006-field-readiness.md)), then:

```sh
xmllint --noout --schema gpx-1.1.xsd activity.gpx
```

## Filling a row

After each run, use the history list in the app: export **diagnostics** (JSON, `diagnostics-v3`, [TDD-0006 §5.5](../design/TDD-0006-field-readiness.md)), **GPX** and, for S5/S6/S10, the **raw file**. Diagnostics hold everything **Samples lost** and **Battery Δ/h** need (`droppedInputs`, `recordLosses`, `batteryDrainPercentPerHour`) and the quality grade for **Notes**. Keep the exported files with the run in a private folder, not in this repository (test artifacts, not fixtures; raw files contain coordinates).

For the analysis rows (S17–S20) the evidence is the web page: a screenshot of the splits and of the two charts, the numbers of the activity and of the analysis next to each other, and the `analysis/<id>.json` of the export.

## Results

| Date | Device | OS | Build (version / commit) | Scenario | Duration | Battery Δ/h | Samples lost | Recovery lastSampleBefore Δ / gap | GPX valid | Grade | Result | Notes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| | | | | | | | | | | | | |

## Devices

| Device | OS | Owner | Battery optimisation setting | Notes |
|---|---|---|---|---|
| _to be filled_ | | | | Prioritize: 1 Pixel, 1 Samsung, 1 Xiaomi/OnePlus (aggressive battery managers), ≥ 1 recent iPhone, 1 older iPhone |
