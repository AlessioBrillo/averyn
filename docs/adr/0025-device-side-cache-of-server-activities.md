# ADR-0025: The server's copy on the device — a cache with an end, not a mirror

- **Status:** accepted
- **Date:** 2026-10-22
- **Deciders:** @AlessioBrillo

## Context and problem

TDD-0011 closes the loop: the phone reads the list of the account's activities and the detail of each one. But a
phone is a device that goes on trains, up mountains and into metro tunnels: without a connection the list does
not open, and recording — the one thing the product really does — becomes disconnected from everything else.

The server already has the data, behind a simple contract (`GET /v1/activities`, `/{id}`, `/{id}/track`,
`/{id}/analysis`). The question is whether the device should keep a copy, and what kind of copy.

Copies have a cost that must be stated now, because it is the real point:

1. **The track is Restricted.** The coordinates of a run are personal data, not a number. A copy on the phone
   lengthens the time those data exist outside the server's control.
2. **Phones get lost, stolen, lent.** A device in the wrong hands shows the whole history of an account.
3. **Backups.** An automatic phone backup that carries the copy away also carries the coordinates, outside the
   perimeter the user thinks they chose.

On the other hand, the phone **already** keeps activity data: the raw file on the phone holds the GPS samples of
every activity (ADR-0015), and the HR sidecar holds the heart-rate samples (ADR-0020/0021). The server's copy
does not introduce a new class of data on the device: it introduces a **new provenance** (another activity, or
an activity from another device), and therefore a copy the phone did not earn by itself.

Finally, the local numbers (`<id>.summary.json`) are a different thing: they are derived from the activity the
phone recorded, not a copy of another activity. There is no choice: either they are computed and saved, or the
row of an activity not yet synced stays without numbers.

## Options considered

1. **No copy.** The phone reads everything from the server. The simplest and the most private, and the most
   useless: without a network there is no history, and half the activities (those not yet uploaded) are not
   there anyway.
2. **A full mirror of the account.** The server sends everything to the device, and the phone becomes a second
   system of record. A lot of data, a lot of work, two sources of truth that diverge (an activity deleted on the
   server stays on the phone forever, for example) and the largest possible privacy surface.
3. **A copy with an end, limited to what the user has opened.** The device keeps the summaries of the list and
   the last details opened, with a limit on count and volume, and the copy ends with the session.
4. **Numbers only, no track.** The copy is limited to summaries: the list reads offline, the detail does not.
   Less data, but the detail is half the value.

## Decision

We choose **Option 3**: a copy with an end.

- **What is copied.** The summaries of the list (`ActivitySummary`) and, for the activities the user opens, the
  detail: summary, track (GeoJSON) and analysis. Nothing else: no raw file, no token, no RR interval ("Health"
  data, ADR-0013).
- **How much.** 10 details, 50 MB, 500 summaries. The least recent one leaves to make room. The copy is best
  effort: the operating system may remove it, and that is fine — it is a copy, not the system of record.
- **When it ends.** At the end of the session (sign-out, or a refresh token the provider refuses) and when the
  server changes. The manifest of the copy remembers which server it belongs to: the copy of another server is
  never shown.
- **The backup.** The copy is excluded from the device backup (`isExcludedFromBackup` on iOS,
  `allowBackup=false` on Android). The phone does not carry the server's copy to Apple's or Google's cloud.
- **The user can delete it.** The account screen shows how much it takes and deletes it with a tap.
- **The server stays the authority.** The copy is dated and says so: every screen that shows a copy says since
  when. A `404` deletes the copied detail; an activity deleted on the server disappears from the list, and its
  files stay on the device until the user removes them.
- **The local summary is another thing.** The numbers of an activity recorded by the phone are derived from what
  the phone measured: they are not a copy, they are a computation, and do not have the privacy implications of a
  copy of another activity. They stay next to the activity file, with the same life.

## Consequences

- Good: the history reads without a network, and freshly recorded activities show their numbers at once.
- Good: the surface of new data is limited and declared: tracks and HR series of what the user opened, nothing
  else.
- Good: the copy cannot become a second source of truth: it is dated, bounded and deletable.
- Bad / costs: the device keeps a copy of data the user did not create there (an activity imported from a GPX, an
  activity recorded on another phone). The threat model says so (ADR-0025 → threat model).
- Bad / costs: the copy is another place where the format can evolve. For that it has a `schemaVersion`: a copy
  of another version is thrown away and rebuilt.
- Bad / costs: without a network the list can show activities the server has already deleted, until the next
  refresh. It is the price of a copy, and it is declared on screen.
- Follow-ups: the copied track is not drawn yet (the map is the next slice); when the map arrives, the copy
  serves it as it is.

## Revisit when

- The real volume of the copy exceeds the limit (50 MB) on a real account: then the limit changes, or the copy
  becomes selective (for example summaries only).
- The copy is run by a background service (for a push notification, or a widget): then its life is no longer tied
  to the session and the decision must be made again from scratch.
- A "Health" datum (the RR intervals) comes into play: the copy cannot hold them without a consent and a
  dedicated review (ADR-0013, ADR-0021).

---

Editorial note, 2026-10-25: translated from Italian to English, typos fixed; no change of the decision.
