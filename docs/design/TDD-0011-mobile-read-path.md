# TDD-0011: The server on the phone — reading, offline copy and local summary

- **Status:** implemented
- **Version:** 0.1
- **Author:** @AlessioBrillo
- **Date:** 2026-10-22
- **Related:** ADR-0015 (local store), ADR-0020/0021 (HR channel and consent), ADR-0025 (copy of the server on
  the device), TDD-0002 (sync), TDD-0009 (analysis)

## 1. Summary

Until TDD-0010 the phone records and uploads but does not read: the history of an account is only visible on the
web. This increment closes the loop — the phone reads the list of activities and the detail of each one — and
makes reading possible without a connection, with two pieces of derived data that live on the device:

- the **copy of the server** (cache): the summaries of the list and the last details opened, bounded by volume
  and by count (ADR-0025);
- the **local summary** (`<id>.summary.json`): the numbers of an activity that has just finished, written next to
  the activity file, so a run not yet synced shows distance and pace even offline.

No new endpoint: the server already exposes `GET /v1/activities`, `/{id}`, `/{id}/track` and
`/{id}/analysis`. The only contract change is additive: `apiVersion` and `minClientVersion` in
`/v1/client-config`, which produce a non-blocking warning when app and server do not match.

## 2. Goals and non-goals

### Goals

1. The phone shows the list of the account's activities (those on the server and those not yet uploaded) and the
   detail of each one (numbers, splits, elevation curve, heart-rate curve).
2. Offline, the list and the details already opened stay readable, and the screen says they are a copy.
3. An activity finished on the device shows its numbers before (and without) sync.
4. An activity deleted on the server disappears from the list and stays in "on this device" until the user
   removes it.
5. An analysis the server is still computing is a state ("being computed"), not an error.
6. An app and a server that do not match produce a warning, never a block.

### Non-goals

- The map: no basemap and no MapLibre Native in this increment. The track is still copied (it is the server's
  GeoJSON), ready for the next slice.
- Personal records, the profile, units and maximum HR.
- The HR sensor on iOS (the iOS app had no BLE adapter at the time of this increment).
- Deleting an activity **on the server** from the phone: mobile is read-only on the server.
- Analysis computed locally for an activity not yet synced: the local detail shows the summary, the splits
  arrive with the sync.

## 3. Functional requirements

| # | Requirement | How it is met |
|---|---|---|
| F1 | List of the account's activities | `GET /v1/activities?limit=50`, opaque cursor, `nextCursor` |
| F2 | Detail of an activity | `GET /v1/activities/{id}` (+ `/{id}/track`, `/{id}/analysis`) |
| F3 | List offline | copy of the summaries (`summaries.json`), with the time of the copy |
| F4 | Detail offline | copy of the last details opened (`details/<id>.json`) |
| F5 | Numbers of a local activity | local summary `<id>.summary.json` |
| F6 | Activity deleted on the server | disappears from the list, stays in "on this device" |
| F7 | Analysis being computed | `503` + `Retry-After` → "being computed" state, read again after the wait |
| F8 | Incompatible versions | `apiVersion`/`minClientVersion` in `/v1/client-config` → non-blocking banner |
| F9 | Local deletion | removes the raw file, the summary, the sync state and the HR sidecar |

## 4. Non-functional requirements

- **Privacy.** The copy holds coordinates and HR series: it is in the same class as the source data
  (`docs/privacy/data-classification.md`). No token in the copy, no raw file, no "Health" HR data (RR
  intervals). The copy is excluded from the backup, is wiped at the end of each session, and the user can delete
  it by hand (ADR-0025).
- **Consistency.** The server is the authority. The copy is always best effort: it can be older than the server,
  and every screen says when what it shows is a copy.
- **Performance.** All I/O of the copy and of the local store runs off the main thread (`Dispatchers.Default`);
  no operation proportional to the length of an activity runs in the UI loop.
- **Battery.** No background polling: the copy is refreshed when the user opens the screen.
- **Self-hosting.** No database schema change; one more, optional environment variable
  (`AVERYN_MIN_CLIENT_VERSION`).

## 5. Design

### 5.1 The modules

```text
backend ─────────────► shared:api   (ActivitySummary, ActivityPage, Track, ClientConfig, Api)
   │
   └─────────────────► shared:analysis (ActivityAnalysis, already shared)

Android/iOS apps ───► shared:client (AverynClient, ActivityCache, HistoryRepository)
                        ├──► shared:api
                        ├──► shared:analysis
                        ├──► shared:tracking  (ActivityStore, LocalActivitySummary)
                        └──► shared:sync      (SyncStatusStore)
```

`shared:api` is pure: only DTOs and constants, no Ktor. The backend depends on `shared:api` and not on
`shared:client`: the contract lives in one place, and the Ktor client lives only where it is needed.

### 5.2 The merge of the list

The list is **driven by the server**: a row is an activity of the server. Activities that exist only on the
device and that the server does not have yet (not uploaded, queued, or refused) are added. An activity that both
the server and the device have is **one row**, with the server's numbers.

A local `READY` activity that the server no longer has (it was deleted there) **does not appear in the list**: it
stays in "on this device", where the user can delete it. A `410` from the server (activity already deleted)
has the same effect.

```text
server ──► [ activities of the server ] ──┐
                                          ├──► merge ──► list
local ───► [ only here, not yet ]       ──┘
```

### 5.3 The copy on the device (ADR-0025)

```text
<cache>/manifest.json          schema version and the server it belongs to
<cache>/summaries.json         the summaries loaded, the cursor, when they were read
<cache>/details/<id>.json      the detail opened: summary, track, analysis
<cache>/details/index.json     when each was last opened, how many bytes it takes
```

- Atomic writes (temporary file + rename): a kill leaves the previous file, never half a JSON.
- Limits: 10 details, 50 MB, 500 summaries. The oldest one leaves to make room.
- The manifest reads the server: a copy from another server is never shown (and is deleted).
- A `404` on a detail deletes the copied detail and the summary from the list.
- The track is copied but **not drawn**: the map arrives with the next slice.

### 5.4 The local summary

`<id>.summary.json`, next to `<id>.jsonl`. It is not an activity: it is not uploaded, not exported, and holds no
coordinates — only the numbers the list shows.

It is written in `ActivityRecorder.buildCompletedActivity()`, which covers the normal stop, the recovery of an
activity left in `STOPPING`, and the closing of older activities. The numbers come from the same tools as the
server (`ActivitySnapshot`, `QualityReport`, `HeartRateMetrics` with `hr-v2`), so the local summary and the
server's cannot diverge. Activities recorded by an earlier build get it from a recomputation at first launch
(`backfillLocalSummaries`).

### 5.5 The versions

`/v1/client-config` exposes `apiVersion` (the version of the contract the server speaks) and `minClientVersion`
(the minimum app version the instance supports, optional). The app compares the numbers with its own version
and shows a banner: the server is older, the server is newer, the app is below the minimum. The contract is
additive within `apiVersion = 1`, so an old client keeps working.

### 5.6 The states of the analysis

`GET /v1/activities/{id}/analysis` answers `503` with `Retry-After: 5` while the server is computing. For the
client that is a state, not an error: the detail shows "being computed" and reads again after the indicated wait
(at most three times, then a "Retry" button). The other temporary answers (429, 502, 504, network) are retried by
the client, always waiting as long as the server asks.

## 6. Test strategy

| Level | What it covers |
|---|---|
| `shared:api` | decoding of the contract, unknown fields ignored, comparison of versions |
| `shared:client` | cache (limits, LRU, schema, other server, broken file), client (401/404/429/503, `Retry-After`), merge of the list, 404, analysis being computed |
| `shared:tracking` | the local summary: write, pause, recovery, delete, recomputation, backfill |
| `backend` | `/v1/client-config` exposes the versions; an invalid minimum is ignored |
| device matrix | S23–S28 (see `docs/testing/device-matrix.md`) |

## 7. Rollout and migration

- No migration: the database does not change and the format of the local files does not change (the summary is
  a new file, next to the others).
- The copy of the server is new and empty: it fills at the first launch of the screen.
- The local summary is missing for activities recorded before: the recomputation at first launch (in the
  background) writes it; if that has not happened yet, the activity shows the numbers as soon as the server has
  them.
- The version banner appears only if the versions do not match: a server and an app of the same version show
  nothing.

## 8. Risks and mitigations

| Risk | Mitigation |
|---|---|
| A copy of data the device did not have | ADR-0025, wipe at the end of each session, exclusion from the backup, manual deletion |
| Local numbers that diverge from the server's | same algorithms (`hr-v2`, `distance-v1`…), versions in the summary, the server always wins, the local summary is labelled |
| A copy that does not match the server | the server is the authority, `404` removes, the screen shows the timestamp |
| The volume of the copy | 10 details, 50 MB, 500 summaries, LRU |
| App and server that do not match | non-blocking warning, additive contract |
| Work piling up in `shared:client` | separate modules (`shared:api` pure, `shared:client` Ktor), no dependency on the backend |

## 9. Open questions

- The copied track is not drawn yet: the map (MapLibre Native) is the next slice and will use the copy this slice
  leaves on the device (planned in the [launch checklist](../product/mvp-launch.md)).
- Analysis computed locally for unsynced activities is not in this increment: to be considered when the cost of
  a replay on the phone is measured on a real device.

---

Editorial note, 2026-10-25: translated from Italian to English; no change of content.
