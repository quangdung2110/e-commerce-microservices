# ADR-013 — Immutable Refund Scope and quarantine-first returns

## Status

Accepted — 2026-09-07. Supersedes ADR-010.

## Context

Return receipt must preserve physical truth and must not make potentially defective stock saleable. Multiple refund attempts and incomplete packages need an unambiguous owner.

## Decision

Order registers an immutable, whole-order Refund Scope before any return receipt. A per-order lock permits at most one active scope. ADMIN accepts exact scoped quantities into quarantine and records one final `PASS`/`FAIL` inspection per receipt quantity at the receiving warehouse. Only `PASS` returns stock to sellable. Inventory emits one all-items-inspection-completed fact when all expected quantity is final inspected; only Order closes the scope or closes an incomplete return.

## Consequences

No receipt occurs without scope, no return is saleable on receipt, and failed stock remains quarantined until evidenced disposition/reversal. Inventory does not decide payout. See [domain.md](../domain.md) and [workflows.md](../workflows.md).

## Verification

Tests cover duplicate/wrong/over receipt, inspection idempotency, multi-package counters, completion exactly once, and close guards.
