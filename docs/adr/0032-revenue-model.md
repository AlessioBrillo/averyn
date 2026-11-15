# ADR-0032: The users' data is never the product; the band is sold in the open, and limits exist only against abuse

- **Status:** accepted
- **Date:** 2026-11-15
- **Deciders:** @AlessioBrillo

## Context and problem

Averyn is meant to give a user base to the Synapse band. One way to read that goal is to profile users from their activity and health data and aim advertising at them very precisely. That was considered and is rejected here, on the record, because:

- It contradicts what the project already promises: [dpia.md](../privacy/dpia.md) says "we do not sell data and there is no profiling for advertising", and [ADR-0010](0010-ecosystem-boundary.md) says the vendor never receives a user identifier.
- Heart rate, RR, HRV and stress are special-category data ([ADR-0013](0013-health-data-special-category.md)). Advertising built on them needs an explicit consent that cannot be a condition of the service; the EU Digital Services Act (art. 26) also forbids advertising based on profiling with special categories of data, and minors may not be profiled at all.
- A hidden purpose in a product sold as private is a deception of its users, and a store listing's privacy label would have to state it.

At the same time [business-model.md](../product/business-model.md) holds a per-account quota that conflicts with "free and unlimited".

## Options considered

1. **Profiled advertising.** Rejected, as above.
2. **Contextual sponsors, opt-in.** Possible later; not needed now.
3. **Transparent sale of the band inside the app, no use of user data** (chosen).
4. **No promotion at all.** Cleanest, but wastes the app as the place where a sensor owner and a sensor meet.

## Decision

**Option 3.**

- Averyn shows the band where a user would look for a sensor (the sensors page, the pairing, the first-run flow), as a product, labelled as such, **never chosen from the user's data**: the same message for everyone, with no tracking pixel, no advertising id, no third-party analytics SDK.
- No data is sold, shared with a vendor or used to profile; the Synapse side receives nothing it does not already receive through [ADR-0026](0026-averyn-synapse-link.md).
- **Limits.** No feature is limited by plan. The per-account quota of the managed cloud becomes an **anti-abuse limit**: high, published, never lowered for one account, and checked for what it costs. This amends the "Money" paragraph of ADR-0028; the order of reaction in the business model (donations first) stays.
- Contextual sponsors (for example a challenge sponsored by a brand, chosen by the user, never based on health or location) are a possible later ADR, not a default.

## Consequences

- Good: the DPIA statement stays true and the store labels can say "no data used to track you".
- Cost: no advertising income; the cloud depends on the band margin and on donations, which is only a partial answer ([business-model.md](../product/business-model.md)).
- Follow-ups: [business-model.md](../product/business-model.md) updated; the policy and the store labels (issue #74) carry the statement.

## Revisit when

The cloud cost outgrows the margin and the donations, or a sponsor model is proposed: that is a new ADR with a DPIA update, never a quiet change.
