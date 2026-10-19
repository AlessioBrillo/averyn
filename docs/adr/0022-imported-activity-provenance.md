# ADR-0022: Imported activities — the original file is kept next to the canonical one

- **Status:** accepted
- **Date:** 2026-10-19
- **Deciders:** @AlessioBrillo

## Context and problem

MVP-2 adds import: a user brings an activity recorded elsewhere (a GPX file from another app or device) and wants it in Averyn, with the same numbers, on the same web page, as an activity recorded with Averyn itself. ADR-0016 makes the uploaded file the system of record: the server stores it byte-for-byte and derives everything else from it. ADR-0015 defines the on-device format (JSON Lines, one file per activity) and ADR-0016 makes the server replay that same format to compute the metrics, so a device and the server never disagree.

An import breaks three things ADR-0016/0015 assume:

1. **The file the user has is not the format the server replays.** A GPX file is XML with its own structure (`<trk>`, `<trkseg>`, `<trkpt>`, `<time>`, `<ele>`), while the server's system of record is the JSON Lines activity file. Something has to translate, and something has to be kept: the translation, the original, or both.
2. **A GPX track carries no accuracy.** The quality metric is built on the platform-reported accuracy radius (`docs/metrics/gps-quality.md`). A file that carries no accuracy cannot be graded: it is neither good nor bad, it is unmeasured. Inventing a number (a sentinel like 9999) would put a fake measurement into the data and into every derived number.
3. **The same file can be imported twice.** The sync path is idempotent per `(owner, clientActivityId)`; an import has no client-chosen id, so the identity of an import is the file itself.

## Options considered

1. **Server-side canonicalization, original + canonical kept.** The server parses the GPX, writes the canonical JSON Lines file, stores it as the raw activity, and stores the uploaded file next to it. The replay and the metrics are the same as for a recorded activity.
2. **Store the original only, canonicalize on the fly.** One file per activity, but every read of an activity parses XML and every metric change re-parses it; the metrics could then differ between two reads of the same file.
3. **Canonicalize on the device, upload the canonical file.** The device would need a GPX parser, and the original would be lost on the device.
4. **Keep the original only, and give the imported activity its own summary columns.** No replay, no shared code, a second set of metric paths to maintain and to version.

## Decision

**Option 1**, with these rules:

- **Both files are kept.** The uploaded file is stored byte-for-byte (`raw/{owner}/{clientActivityId}/source/{sha256}.gpx`); the canonical activity file (`raw/{owner}/{clientActivityId}/{sha256}.jsonl`) is the system of record, exactly as for a recorded activity. The original is what the user gave us; the canonical is what the metrics come from. A better parser later rebuilds the canonical file from the original, without asking the user to upload it again.
- **The identity of an import is the file.** The SHA-256 of the uploaded file is unique per owner. The same file is imported once: the second import answers with the activity that exists. The tombstone of a deleted activity is keyed by its client activity id and only stops a *device retry*; it does not blacklist the file, so a file deleted on purpose can be imported again.
- **No accuracy is invented.** `horizontalAccuracyM` becomes nullable. An imported activity's quality grade is `UNKNOWN`, not `POOR` and not `GOOD`: the criterion could not be evaluated. The other quality signals (flags, longest gap, interval distribution) are still computed. This is a change of the quality algorithm, so it is `quality-v2`.
- **A `<trkseg>` is a pause.** The segments of a GPX file are the continuous recording intervals; the boundary between two of them is a pause, not a straight line through the gap. The canonical file records it as such, and the stored track is a `MultiLineString` (one line per interval) so the map draws the same shape.
- **The sport is explicit.** A GPX `<type>` is optional free text; the API asks for the sport instead of guessing it.
- **The import is server-side and web-only for now.** The parser is in the backend, the file is uploaded as it is, and the web app only uploads and shows what the server computed. The API is the contract: a mobile client can upload the same file later, without a second parser.

## Consequences

- Good: an imported activity behaves like a recorded one everywhere else — the same replay, the same metrics, the same export, the same deletion.
- Good: the user's file is never lost, and a better parser (or a new format, e.g. FIT) can be applied to files already imported.
- Cost: two objects per imported activity, and the quota counts both.
- Cost: the import path is a new way in: an XML parser on user input. It is hardened (no DTD, no external entities, size and point limits) and covered by the threat model.
- Follow-up: heart rate and other extensions in the GPX file are **not** imported in this version; they are counted and reported as ignored, and the original is kept so they can be imported later without asking the user to upload the file again.

## Revisit when

A second import format (FIT, TCX) needs the same treatment, or a parser improvement makes it worth rebuilding the canonical files of the already-imported activities.
