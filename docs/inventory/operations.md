# Inventory Service — Operations and Resilience

> **Status:** Target design accepted for Release 1.
>
> **Safety rule:** Preserve correct stock state before accepting a convenient request.

## 1. Failure modes

| Condition | Behavior | Owner and recovery |
|---|---|---|
| Authoritative primary unavailable or recovery unverified | Fail closed for mutation; Preview returns `503 INVENTORY_UNAVAILABLE`; no cache fallback/pending order. | Platform + Inventory recover/fail over, validate all gates, then jointly reopen writes. |
| State/audit/command/outbox cannot commit atomically | Abort entire command as retryable technical failure. | Inventory fixes datastore health; caller retries same ID. |
| Lock timeout/deadlock/serialization conflict | Retry internally at most twice, then `503 INVENTORY_BUSY`. | Inventory + Platform investigate contention; never classify as out of stock. |
| Broker/relay unavailable after commit | Durable outbox accumulates; commands continue while capacity is safe. | Platform + Inventory restore relay and publish original records. |
| Export sequencer stalled or lease lost | Facts stay durable but unsequenced, so neither Kafka nor the change feed sees them; commands continue while capacity is safe. A stale fenced instance cannot assign sequence. | Inventory restores the single sequencer; it resumes in `outbox_id` order ([ADR-022](decisions/ADR-022-commit-ordered-export-sequence.md)). |
| Search readiness absent/stale/unavailable | Authoritative commands continue; affected per-SKU availability remains pending and is not published/exported until readiness exists. | Search registers after its Product document exists; Inventory emits the current snapshot, then Search resumes change feed/replay. |
| Availability read model has no state for a SKU, or its pending work is older than 30 s | Batch-read returns `UNKNOWN` for that SKU; authoritative commands continue. | Inventory repairs/recomputes the affected state; PDP BFF does not replay or use its own stale fallback. |
| Availability Read API unavailable | BFF receives `503` and degrades affected PDP SKU to `UNKNOWN`; authoritative commands continue. | Inventory + Platform restore read endpoint/capacity; Storefront observes dependency outcome. |
| Order/Payment workflow delayed | Leave `RESERVED` unchanged; issue orphan alert only when older than 60 minutes. | Order explicitly commits/releases; Inventory never auto-releases. |
| Order terminal refund but scope remains open | Retain scope/command truth; never auto-close or infer payout. | Order queries/replays its frozen finalization command and repairs durable dispatch, including financially terminal cases. |
| Receipt/inspection races incomplete close | Atomic guard decides; rejected close moves no stock. | Order reconciles the durable rejection; no automatic endpoint switch or Payment bypass. |
| Warehouse outage/inactive | Exclude from new allocation only. | ADMIN verifies/reconfigures; an affected order uses Order's Admin cancellation or pre-dispatch refund if needed. |
| Count discrepancy | Do not modify protected buckets silently. | Order's Admin cancellation (`RESERVED`) or pre-dispatch refund (`COMMITTED`) returns units to sellable first; ADMIN then adjusts. Other cases are incidents ([ADR-021](decisions/ADR-021-order-integration-lifecycle-corrections.md)). |
| Consumer failure / replay window expired | Producer fact remains durable; consumer can lag. | Order uses its inbox/DLQ; Search does not ACK retryable/readiness violations and starts a new per-SKU snapshot after `410`. |

Automatic fail-closed protection requires no approval; it includes outbox backpressure (more than `inventory.outbox.max-unsequenced`, default 100 000, unsequenced outbox rows). A manual global stop is requested by the Inventory incident commander and executed by Platform/SRE by setting `INVENTORY_WRITE_FREEZE=true` (`inventory.write-freeze`): every mutation, including readiness registration, returns `503 INVENTORY_UNAVAILABLE` while reads stay available ([ADR-027](decisions/ADR-027-pre-implementation-contract-corrections.md)). Inventory and Platform jointly authorize write reopening; Order, Search, and warehouse ADMIN cannot override the control.

## 2. Service objectives and alerts

| SLI | Release 1 target | Measurement |
|---|---:|---|
| Reserve latency | p95 <= 200 ms, p99 <= 500 ms | Durable terminal result at Inventory boundary. |
| Preview latency | p95 <= 300 ms, p99 <= 750 ms | Requests while primary is available. |
| Infrastructure failure rate | <= 0.1% per 30 days | Excludes valid domain `REJECT_ALL`. |
| Search freshness | <= 60 seconds | Inventory availability projection/export time to Search's applied revision for ready SKUs. |
| PDP availability read latency | p99 <= 200 ms for <= 500 SKU (starting target) | Agreed with Storefront in [Product Detail BFF ADR-004](../product-detail-bff/decisions/ADR-004-resilience-budget-and-mesh-split.md); BFF times out at 300 ms, so slower results become `UNKNOWN`. Load-test before PDP rollout. |
| PDP availability read freshness | <= 30 seconds of pending work ([ADR-025](decisions/ADR-025-pdp-availability-staleness-budget.md)) | Age of the oldest pending work behind a returned known state; beyond it the SKU is `UNKNOWN`, which is not counted as a successful known state. |
| Committed-data loss | RPO = 0 | Proven by failover/restore drill. |
| Authoritative recovery | RTO <= 60 minutes | Incident declaration to verified write reopening. |
| Integrity | 0 violations | Bucket/ledger mismatch is SEV-1, not an error-budget item. |

Metrics use bounded labels only (command/event type, result class, caller surface, deployment version, owner). Correlation, command, order, SKU, and warehouse IDs belong in structured logs/traces/audit, not high-cardinality metric labels.

| Trigger | Route | Immediate response |
|---|---|---|
| Bucket invariant/ledger mismatch, duplicate final inspection, impossible negative balance | SEV-1: Inventory + Platform | Freeze affected writes, preserve evidence, incident recovery; never mask with adjustment. |
| Primary unavailable, atomicity uncertain, backup/restore failure, RPO proof lost | SEV-1: Platform + Inventory + Security | Fail closed and recover authoritative source. |
| Oldest unpublished outbox (unsequenced or unrelayed) > 30 s / > 60 s | Warning / page: Platform + Inventory | Restore sequencer/relay; notify Order/Search at page threshold. |
| Search freshness > 30 s / > 60 s, or readiness-suppressed facts grow | Warning / page: Inventory + Search | Repair readiness/projector, replay or snapshot bootstrap. |
| Availability Read API latency/error or BFF `UNKNOWN` rate breaches its agreed budget | Warning / page: Inventory + Storefront | Repair read endpoint/projector/cache; BFF continues to degrade rather than replaying data. |
| Any `RESERVED` root > 60 min | Operational page: Order | Order decides explicit outcome; no Inventory cleanup. |
| `503 INVENTORY_BUSY` > 1% of mutation attempts for 5 min, or latency breach for 10 min | High: Inventory + Platform | Reduce contention/capacity pressure without bypassing validation. |
| Audit failure, privileged anomaly, direct-write attempt | Security page | Deny/contain, preserve evidence, investigate. |

Refund-scope/quarantine age is dashboarded but has no automatic closure threshold:
Order owns return-window policy and Operations owns inspection cadence. A terminal
scope remains retained as the order's lifetime scope identity; it is not cleanup
eligible merely because the case closed.
Order separately alerts on pending/recovery/conflict finalization. A financial
terminal case paired with nonterminal Inventory scope requires Order's durable
close/cancel recovery, not an Inventory age-based cleanup.

## 3. Reconciliation and correction

| Cadence | Check | Result on mismatch |
|---|---|---|
| Every accepted mutation | Bucket/guard/identity/outbox/audit transaction checks. | Abort before commit. |
| Hourly | Full current StockPosition bucket/non-negative scan. | SEV-1 and affected-write freeze. |
| Daily | Replay movement ledger from last verified checkpoint and compare current state. | SEV-1; preserve state/ledger snapshot. |
| Every 5 minutes | Identify `RESERVED` roots older than 60 minutes. | Alert Order; do not mutate. |
| Daily | Order compares active orders/refunds and financially terminal Buyer cases with unresolved scope finalization to Inventory command/state queries. | Recover the same frozen command or audited Order exception; no direct DB write or second Payment refund. |
| Continuous | Recompute sampled `SkuAvailabilityState`, monitor dirty/fan-out backlog, BFF known-state rate and oldest pending-work age, readiness-suppressed facts, and Search applied sequence versus export watermark. Inventory implements the first two parts: every `inventory.workers.availability-audit-interval` (default 1 minute) the next `availability-audit-batch-size` (default 500) state rows, in `sku_id` order and wrapping around, are compared with stock truth in one snapshot (rows with a dirty mark or an unfinished fan-out are skipped), published as `inventory_integrity_availability_mismatches` plus an error log; `inventory_availability_readiness_suppressed` counts states with no active Search registration. Backlog and age gauges are `inventory_availability_dirty_backlog` and `inventory_availability_oldest_pending_seconds`; the known-state rate is `inventory_availability_reads_total{status}`. The Search-applied-sequence comparison belongs to Search. | Repair projector/read model; Search then replay/bootstrap if its export copy needs recovery. |
| Pre/post recovery | Bucket scan, ledger replay, identity, outbox/export, and consumer checks. | Writes stay closed until all pass. |

Reconciliation detects only: it publishes metrics and error logs and never mutates state. Correction requires ADMIN reason/evidence and normal command audit. For a `RESERVED` order, Order first cancels it through its Admin pre-payment cancellation (whole-root `ADMIN_CANCELLED` release); for a `COMMITTED` undispatched order it uses its Admin pre-dispatch refund (`ReleaseCommittedOrderBeforeDispatch`); a dispatched case is a manual Order incident. Inventory has no exception release. A systemic ledger/state mismatch requires incident recovery, never compensating adjustment.

## 4. Outbox, consumer recovery, and backpressure

- State, audit/movement, command outcome, required public outbox records and availability dirty work commit together. A single fenced sequencer assigns commit-ordered export sequence into `PublishedEvent`; Debezium relays it at least once with exponential backoff/jitter and a five-minute maximum interval ([ADR-022](decisions/ADR-022-commit-ordered-export-sequence.md)).
- Order receives only physical dispatch and selected-scope inspection completion.
  Reserve/Commit/Release/recovery use durable command receipts and query/replay; an
  unknown outcome is not resolved by waiting for a reservation-result event. Order
  retains the original command checkpoint and durable recovery work after retry
  exhaustion. Reconciliation compares the specific command result and current
  reservation state separately ([ADR-020](decisions/ADR-020-command-receipts-and-minimal-order-events.md)).
- Public/export events are retained 30 days (`published_event` by the hourly retention pass, both Kafka topics by `retention.ms` = 30 days set at provisioning — a floor and a ceiling, ADR-028); an unsequenced or undelivered record stays beyond ordinary cleanup. Platform provisions `t.inventory.stock.events.v1` and `t.inventory.order.events.v1` explicitly; broker auto-creation (local compose) uses the broker default and does not meet this. If retention/capacity becomes unsafe, stop accepting new mutations before dropping evidence.
- Order owns an inbox/dedupe record and DLQ. DLQ preserves the original event, attempt/error history, and replay goes through the normal inbox path. Operators never synthesize a replacement fact.
- Search relies on per-SKU `availability_revision`, does not use a DLQ or Inventory inbox, and never upserts a missing Product document. It uses a 24-hour per-SKU snapshot session plus ordered change feed; after `410 REPLAY_WINDOW_EXPIRED` it must bootstrap availability again in its existing Product index.

## 5. Security and audit

Service calls use authenticated workload identity and encrypted transport, both enforced by the service mesh (mTLS plus per-principal, per-path `AuthorizationPolicy` — [mesh/runtime-contract.md §5.2](../mesh/runtime-contract.md#52-namespace-ecommerce-l7-waypoint)); the caller-set `X-Service-Identity` header is attribution plus a per-path allowlist inside Inventory as defense-in-depth (`401` missing, `403` another service), never the authority; it is evaluated on the decoded request path ([interfaces.md](interfaces.md), ADR-028). ADMIN arrives only through the gateway with role `ADMIN` from `X-User-Roles`; MFA is a production release gate that depends on an Auth/gateway MFA assertion ([ADR-024](decisions/ADR-024-admin-surface-and-error-catalog.md)). Primary data, backups, audit, and outbox are encrypted at rest. No direct-write credential exists for operational correction; time-bounded, individually attributable break-glass access is read-only and reviewed.

Audit retains actor/effective role, command and operation identity, reason/evidence reference, before/after values, timestamp, correlation/causation IDs, and result for at least seven years. Events/logs omit credentials, raw evidence contents, and unnecessary customer PII. Compliance/Legal validation of retention/evidence remains a production-release gate.

## 6. Disaster recovery runbook

1. **Contain:** enable fail-closed mode (`INVENTORY_WRITE_FREEZE=true`; Preview returns `503` while the primary is unavailable); stop retention cleanup for relevant evidence.
2. **Preserve:** capture incident ID, affected time, deployment, audit/ledger/outbox ranges, backup point, and affected aggregates.
3. **Recover:** fail over only to a verified zero-loss candidate or restore one consistent authoritative point; never restore state separately from command/operation identity, audit, or outbox.
4. **Validate:** prove primary durability; scan buckets; replay ledger checkpoint; verify identities/final inspections/revisions; validate outbox/export; verify authorization/audit.
5. **Rebuild:** discard cache, restart relay/projector from durable state, and instruct Order to replay/dedupe or Search to bootstrap per-SKU availability after readiness is verified.
6. **Resume:** Inventory and Platform document gate evidence and jointly reopen writes; Order resumes normal idempotent decisions only.

For scope closure recovery, first inspect the Order intent/command ID and Inventory
command outcome. An accepted close with lost response replays that exact command;
409 requires audited reconciliation. A no-receipt `REGISTERED` return uses cancel;
incomplete close requires every received unit final-inspected. Scope cancellation/
closure does not release the lifetime identity, change a Payment result, or remove
failed quarantine. See [Order runbook](../order/operations.md) and
[Order ADR-013](../order/decisions/ADR-013-durable-refund-scope-finalization.md).

Backup restore integrity is tested monthly. Full failover/restore, outbox/consumer replay, access/audit review, and discrepancy exercises run quarterly. Any failed drill blocks claiming the corresponding RPO/RTO target until remediated.
