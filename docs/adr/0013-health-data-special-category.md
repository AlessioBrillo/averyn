# ADR-0013: Health-adjacent metrics are special-category data

- **Status:** accepted
- **Date:** 2026-09-27
- **Deciders:** @AlessioBrillo

## Context and problem

[`data-classification.md`](../privacy/data-classification.md) already treats heart rate and other health metrics as **Restricted**. Bringing in HRV, ECG, stress-triage and sleep-stage data (whether from Synapse or any other sensor, per [ADR-0014](0014-sensor-integration-contract.md)'s vendor-neutral stance) raises the bar further: under GDPR Art. 9, data concerning health is a **special category**, requiring an explicit legal basis (not just "legitimate interest") and, before any hosted/managed processing of it, a Data Protection Impact Assessment. The strategy input processes this data in a second, less-reviewed system (the original Synapse Cloud) with no DPIA mentioned at all ([`docs/strategy/review-2026-09-27.md`](../strategy/review-2026-09-27.md) #20).

## Options considered

1. **Treat it like existing Restricted data** (encrypted, access-checked, exportable, deletable) and nothing more. Simpler, but doesn't meet Art. 9's explicit-consent bar or produce the DPIA a regulator would expect before a managed health-adjacent service goes live.
2. **A new data class with per-scope consent and a mandatory DPIA gate before managed hosting.** More process, but matches what the data actually is, and keeps the "wellness, not medical" framing honest and auditable.

## Decision

**Option 2.** Add a **Health (special category)** class to `data-classification.md`, covering: HRV, raw ECG/PPG, sleep stage, stress classification, and any other biosignal-derived metric, from any sensor vendor. Rules:

- Processing requires a **specific, revocable, per-scope consent record** (e.g. "HRV analysis", "sleep staging" as separate grants — the strategy input's own `SynapseConsent.scopes` shape is a reasonable starting point, minus the parts that assumed a second backend).
- Revoking a scope stops processing new data under it immediately; already-computed results are deleted or kept only if the user separately opts to keep them.
- A **DPIA is a release gate**: it must be written and reviewed before any health-adjacent processing (Synapse's or any other sensor's) is enabled in the managed cloud offering. Self-hosters make their own compliance decisions but get the same consent-scope mechanism.
- Marketing/UI language stays strictly **wellness framing** ("readiness estimate", not "diagnosis"), to stay outside EU MDR 2017/745's scope — this is a constraint on product copy and claims, not something code enforces alone, and needs real legal review before any public release, same as the license (ADR-0002).

## Consequences

- Good: consent-per-scope is exactly the hook [ADR-0014](0014-sensor-integration-contract.md) needs to gate whether a sensor adapter is even allowed to run for a user.
- Cost: a DPIA is real work, not a checkbox; it blocks turning on managed hosting for health-adjacent features until done.
- Follow-up: `data-classification.md`'s "To be written" section already lists a threat model and DPIA at MVP-1 start — this ADR makes the DPIA a hard gate specifically for health-adjacent processing, not just a nice-to-have.

## Revisit when

Before the first managed-cloud release that processes any health-adjacent metric, or if legal review of the wellness/medical-device boundary changes what can be claimed in the UI.
