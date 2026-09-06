# ADR-014 — Contract-first facts and Search recovery feed

## Status

Accepted — 2026-09-07. Supersedes ADR-007 and the integration parts of ADR-002/003.

## Context

Order needs durable Inventory facts for reconciliation. Search needs a recoverable stock projection but must never become a checkout dependency.

## Decision

Commands use authenticated caller identity, idempotency key, correlation, and causation IDs. Public facts use stable event ID, aggregate revision, and export sequence and are delivered at least once. Search receives only stock availability/warehouse availability through a snapshot session with fixed watermark plus ordered change feed. Replay retention is at least 30 days; a lagging consumer bootstraps a new snapshot after `410 REPLAY_WINDOW_EXPIRED`.

## Consequences

Consumers own inbox/dedupe and DLQ. No global ordering is promised. Breaking schemas use a new version and 90-day parallel support after all registered consumers migrate. See [interfaces.md](../interfaces.md).

## Verification

Contract compatibility, duplicate/reorder, snapshot/change-feed, and replay-window tests prove the contract.
