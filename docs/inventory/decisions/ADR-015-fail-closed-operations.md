# ADR-015 — Fail-closed operations, reconciliation, and recoverability

## Status

Accepted — 2026-09-07. Supersedes ADR-005.

## Context

An operational shortcut that alters stock without authoritative proof destroys no-oversell and audit guarantees. Recovery must be rehearsable rather than depend on manual database repair.

## Decision

Inventory fails closed when primary state or atomic audit/outbox persistence is unavailable. Broker/Search/cache outage degrades delivery/read freshness only. Reconciliation detects but never automatically corrects business state; correction uses audited commands and Order exceptions. Audit/evidence retain at least seven years. Recovery restores one authoritative point, verifies buckets, ledger, identities, outbox, and access controls, then rebuilds derived systems before writes reopen. RPO=0 and RTO <= 60 minutes require drill evidence.

## Consequences

No direct production database write path exists for correction or recovery. Platform and Inventory jointly reopen writes after verified recovery. See [operations.md](../operations.md).

## Verification

Monthly restore integrity and quarterly failover, replay, access-control, and discrepancy exercises prove operational readiness.
