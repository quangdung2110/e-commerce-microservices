# Inventory Service — Operations and Resilience

> **Status:** Target design accepted for Release 1.
>
> **Safety rule:** Preserve correct stock state before accepting a convenient request.

## 1. Failure modes

| Condition | Behavior | Owner and recovery |
|---|---|---|
| Authoritative primary unavailable or recovery unverified | Fail closed for mutation; Preview returns `503 INVENTORY_UNAVAILABLE`; no cache fallback/pending order. | Platform + Inventory recover/fail over, validate all gates, then jointly reopen writes. |
| State/audit/command/outbox cannot commit atomically | Abort entire command as retryable technical failure. | Inventory fixes datastore health; caller retries same ID. |
| Lock timeout/deadlock/serialization conflict | Retry internally at most twice, then `503 BUSY`. | Inventory + Platform investigate contention; never classify as out of stock. |
| Broker/relay unavailable after commit | Durable outbox accumulates; commands continue while capacity is safe. | Platform + Inventory restore relay and publish original records. |
| Search readiness absent/stale/unavailable | Authoritative commands continue; affected per-SKU availability remains pending and is not published/exported until readiness exists. | Search registers after its Product document exists; Inventory emits the current snapshot, then Search resumes change feed/replay. |
| Availability read model has no clean state for a SKU | Batch-read returns `UNKNOWN` for that SKU; authoritative commands continue. | Inventory repairs/recomputes the affected state; PDP BFF does not replay or use a stale fallback. |
| Availability Read API unavailable | BFF receives `503` and degrades affected PDP SKU to `UNKNOWN`; authoritative commands continue. | Inventory + Platform restore read endpoint/capacity; Storefront observes dependency outcome. |
| Order/Payment workflow delayed | Leave `RESERVED` unchanged; issue orphan alert only when older than 60 minutes. | Order explicitly commits/releases; Inventory never auto-releases. |
| Warehouse outage/inactive | Exclude from new allocation only. | ADMIN verifies/reconfigures; existing commitments use Order exception flow if needed. |
| Count discrepancy | Do not modify protected buckets silently. | ADMIN + Order use explicit exception, release/correction workflow. |
| Consumer failure / replay window expired | Producer fact remains durable; consumer can lag. | Order uses its inbox/DLQ; Search does not ACK retryable/readiness violations and starts a new per-SKU snapshot after `410`. |

Automatic fail-closed protection requires no approval. A manual global stop is requested by the Inventory incident commander and executed by Platform/SRE. Inventory and Platform jointly authorize write reopening; Order, Search, and warehouse ADMIN cannot override the control.

## 2. Service objectives and alerts

| SLI | Release 1 target | Measurement |
|---|---:|---|
| Reserve latency | p95 <= 200 ms, p99 <= 500 ms | Durable terminal result at Inventory boundary. |
| Preview latency | p95 <= 300 ms, p99 <= 750 ms | Requests while primary is available. |
| Infrastructure failure rate | <= 0.1% per 30 days | Excludes valid domain `REJECT_ALL`. |
| Search freshness | <= 60 seconds | Inventory availability projection/export time to Search's applied revision for ready SKUs. |
| PDP availability read latency/freshness | TBD before PDP rollout | Agreed Inventory/Storefront budget for a clean `SkuAvailabilityState` batch result; `UNKNOWN` is not counted as a successful known state. |
| Committed-data loss | RPO = 0 | Proven by failover/restore drill. |
| Authoritative recovery | RTO <= 60 minutes | Incident declaration to verified write reopening. |
| Integrity | 0 violations | Bucket/ledger mismatch is SEV-1, not an error-budget item. |

Metrics use bounded labels only (command/event type, result class, caller surface, deployment version, owner). Correlation, command, order, SKU, and warehouse IDs belong in structured logs/traces/audit, not high-cardinality metric labels.

| Trigger | Route | Immediate response |
|---|---|---|
| Bucket invariant/ledger mismatch, duplicate final inspection, impossible negative balance | SEV-1: Inventory + Platform | Freeze affected writes, preserve evidence, incident recovery; never mask with adjustment. |
| Primary unavailable, atomicity uncertain, backup/restore failure, RPO proof lost | SEV-1: Platform + Inventory + Security | Fail closed and recover authoritative source. |
| Oldest unpublished outbox > 30 s / > 60 s | Warning / page: Platform + Inventory | Restore relay; notify Order/Search at page threshold. |
| Search freshness > 30 s / > 60 s, or readiness-suppressed facts grow | Warning / page: Inventory + Search | Repair readiness/projector, replay or snapshot bootstrap. |
| Availability Read API latency/error or BFF `UNKNOWN` rate breaches its agreed budget | Warning / page: Inventory + Storefront | Repair read endpoint/projector/cache; BFF continues to degrade rather than replaying data. |
| Any `RESERVED` root > 60 min | Operational page: Order | Order decides explicit outcome; no Inventory cleanup. |
| `503 BUSY` > 1% of mutation attempts for 5 min, or latency breach for 10 min | High: Inventory + Platform | Reduce contention/capacity pressure without bypassing validation. |
| Audit failure, privileged anomaly, direct-write attempt | Security page | Deny/contain, preserve evidence, investigate. |

Refund-scope/quarantine age is dashboarded but has no automatic closure threshold: Order owns return-window policy and Operations owns inspection cadence.

## 3. Reconciliation and correction

| Cadence | Check | Result on mismatch |
|---|---|---|
| Every accepted mutation | Bucket/guard/identity/outbox/audit transaction checks. | Abort before commit. |
| Hourly | Full current StockPosition bucket/non-negative scan. | SEV-1 and affected-write freeze. |
| Daily | Replay movement ledger from last verified checkpoint and compare current state. | SEV-1; preserve state/ledger snapshot. |
| Every 5 minutes | Identify `RESERVED` roots older than 60 minutes. | Alert Order; do not mutate. |
| Daily | Order compares its active orders/refunds to Inventory query results. | Idempotent command or Order exception; no DB write. |
| Continuous | Recompute sampled `SkuAvailabilityState`, monitor dirty/fan-out backlog, BFF clean-state rate, readiness-suppressed facts, and Search applied sequence versus export watermark. | Repair projector/read model; Search then replay/bootstrap if its export copy needs recovery. |
| Pre/post recovery | Bucket scan, ledger replay, identity, outbox/export, and consumer checks. | Writes stay closed until all pass. |

Reconciliation detects only. Correction requires ADMIN reason/evidence and normal command audit. For `RESERVED` discrepancy, Order first releases the whole root with `STOCK_DISCREPANCY`; for committed/dispatched stock it decides a cancellation/post-dispatch exception. A systemic ledger/state mismatch requires incident recovery, never compensating adjustment.

## 4. Outbox, consumer recovery, and backpressure

- State, audit/movement, command outcome, and outbox record commit together. Relay publishes at least once from bounded durable batches with exponential backoff/jitter and a five-minute maximum interval.
- Public/export events are retained at least 30 days; an undelivered record stays beyond ordinary cleanup. If retention/capacity becomes unsafe, stop accepting new mutations before dropping evidence.
- Order owns an inbox/dedupe record and DLQ. DLQ preserves the original event, attempt/error history, and replay goes through the normal inbox path. Operators never synthesize a replacement fact.
- Search relies on per-SKU `availability_revision`, does not use a DLQ or Inventory inbox, and never upserts a missing Product document. It uses a 24-hour per-SKU snapshot session plus ordered change feed; after `410 REPLAY_WINDOW_EXPIRED` it must bootstrap availability again in its existing Product index.

## 5. Security and audit

Service calls use authenticated workload identity and encrypted transport. Interactive ADMIN requires MFA. Primary data, backups, audit, and outbox are encrypted at rest. No direct-write credential exists for operational correction; time-bounded, individually attributable break-glass access is read-only and reviewed.

Audit retains actor/effective role, command and operation identity, reason/evidence reference, before/after values, timestamp, correlation/causation IDs, and result for at least seven years. Events/logs omit credentials, raw evidence contents, and unnecessary customer PII. Compliance/Legal validation of retention/evidence remains a production-release gate.

## 6. Disaster recovery runbook

1. **Contain:** enable fail-closed mode; Preview returns `503`; stop retention cleanup for relevant evidence.
2. **Preserve:** capture incident ID, affected time, deployment, audit/ledger/outbox ranges, backup point, and affected aggregates.
3. **Recover:** fail over only to a verified zero-loss candidate or restore one consistent authoritative point; never restore state separately from command/operation identity, audit, or outbox.
4. **Validate:** prove primary durability; scan buckets; replay ledger checkpoint; verify identities/final inspections/revisions; validate outbox/export; verify authorization/audit.
5. **Rebuild:** discard cache, restart relay/projector from durable state, and instruct Order to replay/dedupe or Search to bootstrap per-SKU availability after readiness is verified.
6. **Resume:** Inventory and Platform document gate evidence and jointly reopen writes; Order resumes normal idempotent decisions only.

Backup restore integrity is tested monthly. Full failover/restore, outbox/consumer replay, access/audit review, and discrepancy exercises run quarterly. Any failed drill blocks claiming the corresponding RPO/RTO target until remediated.
