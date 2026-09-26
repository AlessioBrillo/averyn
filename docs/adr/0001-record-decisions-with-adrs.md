# ADR-0001: Record decisions with ADRs

- **Status:** accepted
- **Date:** 2026-09-26
- **Deciders:** @AlessioBrillo

## Context and problem

The project is built by one person now and must stay understandable to future contributors and to the author in a year. The reference report lists ~20 open decisions ([§24](../product/reference-report-v0.1.md)) and asks for a decision log ([§23.C](../product/reference-report-v0.1.md)).

## Options considered

1. **Wiki / chat history** — cheap, but not versioned with code and easily lost.
2. **ADRs in the repo** — versioned, reviewed in PRs, greppable.

## Decision

Use MADR-style ADRs in `docs/adr/`, immutable once accepted, superseded rather than edited.

## Consequences

- Good: decisions have rationale and a "revisit when" trigger.
- Cost: a short document per significant decision.

## Revisit when

The number of ADRs makes the index unmanageable (adopt tags or a generated index).
