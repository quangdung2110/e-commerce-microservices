# ADR-011 — Relational authoritative state with transactional outbox

## Status

Accepted — 2026-09-07. Supersedes ADR-001, ADR-002, ADR-003, and ADR-004.

## Context

No oversell, complete auditability, and operational simplicity outrank hot-path optimization. The prior Redis-centered decisions create multiple persistence concerns and an unclear recovery boundary.

## Decision

Inventory has one relational primary database as the sole write authority. Every accepted command atomically commits current authoritative state, immutable stock movement/audit, command result, successful operation identity, and transactional outbox records. Cache, Redis, broker ordering, and Search have no correctness authority. Outbox delivery is at-least-once and consumers deduplicate.

## Consequences

Commands fail closed if the atomic transaction cannot commit. Recovery restores state, identities, audit/movement, and outbox at the same point. Event sourcing and distributed locks are not part of Release 1. See [data.md](../data.md) and [architecture.md](../architecture.md).

## Verification

Concurrency, duplicate-command/event, broker-outage, restore, and ledger-reconciliation tests prove the decision.
