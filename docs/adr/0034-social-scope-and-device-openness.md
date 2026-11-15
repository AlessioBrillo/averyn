# ADR-0034: Social scope additions, and features follow the signal, never the brand

- **Status:** accepted
- **Date:** 2026-11-15
- **Deciders:** @AlessioBrillo

## Context and problem

[ADR-0031](0031-social-and-community.md) fixed the order of the social slices. Two things were left out: some features a full community needs (photos, search, gear and goals, live safety tracking, "trained together", a heatmap), and the rule for features that need the Synapse band. The owner's position, restated on 2026-11-15: no paywall of any kind; a feature may be tied to the band only when it needs a signal that no other supported source gives accurately enough to offer it to everyone; and any other device must be connectable, with nobody blocked ([ADR-0010](0010-ecosystem-boundary.md), [ADR-0014](0014-sensor-integration-contract.md), [ADR-0032](0032-revenue-model.md)).

## Options considered

1. **Leave these for later, one by one.** Keeps the ADR count down, but the rules (EXIF, live location, heatmap thresholds) are the part that is hard to reverse.
2. **One ADR with a rule per addition, and the rule for sensor-bound features** (chosen).
3. **Tie live tracking to the band.** Rejected: it makes a safety feature depend on a purchase ([vision.md](../product/vision.md)) and the band a dependency of a core feature (ADR-0014).

## Decision

**Option 2.** All additions come after MVP-L, in the MVP-4 order of the [roadmap](../product/roadmap.md), each with its own TDD and its device-matrix row. The rules of ADR-0031 (private by default, visibility checked on the server on every read, zones applied to every non-owner surface, health data never social without its own consent) apply to all of them.

- **Title and description.** User text; it follows the activity's visibility and is part of the export.
- **Photos.** The server strips EXIF and GPS before storing. They count toward the anti-abuse limit. The DPIA currently says photos are not processed: it is updated before the slice opens.
- **Athlete search.** By display name or club. No suggestion from location or contacts. A private profile is found only if the user allows it.
- **Gear and goals.** Private to the owner. Distance per item and weekly or yearly goals come from the existing analysis.
- **Live safety tracking.** Opt-in per activity, for a chosen number of hours or until the recording ends, to contacts the user picks, with a link that can be revoked and that expires. Privacy zones apply. Available to everyone with a phone. With any heart-rate sensor it adds the live heart rate (separate consent, [ADR-0013](0013-health-data-special-category.md)) and an alert when the signal stops. The band's own signals are an extra only where no other source gives them.
- **Trained together.** Links activities close in time and place, and only when each person can see the other's profile; either can unlink.
- **Global heatmap.** Public activities only; private zones removed before aggregation; a cell shows only with at least *k* distinct athletes (k set in the TDD, never below 5); opt-out per account; no health data.
- **Sensor-bound features.** A feature may require a given sensor only if it needs a signal no other supported source provides with enough accuracy; the threshold is written in that metric's `docs/metrics/*.md`. It opens to any device that meets it. It is never a paywall and never tied to a brand name.
- **Other devices.** They connect through open formats and profiles only: reading from HealthKit and Health Connect, standard Bluetooth profiles (running speed and cadence, cycling speed and cadence, power), TCX import and the heart-rate, cadence and power fields of GPX and FIT. Vendor cloud APIs and vendor names in code stay out (brand neutrality; each would need its own terms and agreement). Order: after MVP-L, with the platform reading already on the roadmap.

## Consequences

- Good: every device can join and no feature is bought; the safety feature is not hostage to hardware.
- Cost: photos, live location and the heatmap each add a DPIA risk row and moderation or abuse work for one developer. The heatmap and live tracking are last in the order for that reason.
- Follow-ups: [roadmap](../product/roadmap.md), [business model](../product/business-model.md), [vision](../product/vision.md), [Synapse integration](../integrations/synapse.md), [TDD-0018](../design/TDD-0018-social-graph.md) and [data classification](../privacy/data-classification.md) updated; the DPIA is extended by the slice that first needs it.

## Revisit when

A sensor-bound feature turns out to be possible from another source (it opens), or moderation of photos costs more than one person can carry (photos wait).
