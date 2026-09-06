# Inventory Service — Operations & Resilience Contract

> **Status:** Accepted — Phase 8 complete
>
> **Scope:** Release 1 operational model, observability, reconciliation, security, and recovery.
>
> **Non-goal:** This document does not introduce implementation code or permit any actor to repair state through direct database writes.

## 1. Purpose and inherited decisions

This document makes the previously accepted domain and consistency decisions operable in production. During a failure, preserving correct inventory state is more important than accepting a checkout request or keeping a read model fresh.

The following decisions are binding inputs to this phase:

- `Inventory` has one authoritative relational primary store; it is the sole source of truth for warehouse, stock, reservation, return, and inspection state.
- No oversell is absolute. Cache, Search, and an allocation preview never decide stock correctness.
- A reservation is an all-or-nothing whole-order decision. Each order line is supplied by exactly one warehouse; no backorder, partial reservation, or silent reroute exists in Release 1.
- A committed transaction writes business state, immutable audit/ledger evidence, command identity, and transactional-outbox record together, or writes none of them.
- `Order` owns payment, the 15-minute reservation timeout decision, retry, cancellation, shipment/delivery state, and refund closure. Inventory never silently expires or releases a reservation.
- A return is saleable only after a final `PASS` inspection. `FAIL` or held stock remains outside `available_to_sell`.
- State correction is always an audited Inventory command. No other service—and no operational runbook—may update Inventory tables directly.

Related design sources: [Design Charter](design-charter.md), [Domain Foundation](domain-foundation.md), [Lifecycle Workflows](lifecycle-workflows.md), [Warehouse Operations](warehouse-operations.md), [Consistency Model](consistency-model.md), and [Contract Catalogue](contract-catalogue.md).

## 2. Operational safety posture

### 2.1 Decision hierarchy during degradation

| Priority | Rule | Consequence |
|---|---|---|
| 1 | Do not create an unprovable stock transition. | Fail the mutation closed if authoritative state, transactional audit, or outbox durability is unavailable. |
| 2 | Do not reinterpret a completed result. | A retry with the same command identity returns the recorded terminal result; it never applies a second movement. |
| 3 | Do not let a stale read authorize a sale. | Cache/Search/preview degradation affects discovery only, never Reserve correctness. |
| 4 | Do not make operational decisions implicitly. | No automatic reservation release, warehouse reroute, inspection promotion, or reconciliation adjustment. |
| 5 | Recover evidence before convenience. | Restore authoritative state, verify invariants, then replay outbox and rebuild derived systems before declaring recovery complete. |

Automatic safety protection needs no human approval: Inventory fails closed whenever it cannot prove
an authoritative atomic transition. A manual global stop of Inventory writes/Preview is requested
by the Inventory incident commander and executed by Platform/SRE. Reopening requires the joint
authorization of Inventory and Platform after the recovery gates in Section 7; Operations ADMIN,
Order, and Search cannot override this control.

### 2.2 Failure and degraded-mode matrix

| Failure / condition | Inventory behavior | Customer / business effect | Primary owner | Required recovery |
|---|---|---|---|---|
| Authoritative primary database unavailable, failover in progress, or restore not yet verified | Reject all mutations; `Allocation Preview` returns `503 INVENTORY_UNAVAILABLE`; no cache fallback. | Checkout cannot allocate/reserve; no pending reservation is invented. | Platform + Inventory | Recover/fail over, verify Section 7 gates, then reopen writes. |
| Transaction cannot atomically persist state, audit/ledger, command result, and outbox | Abort entire command; return technical retryable failure. | No stock or reservation transition occurs. | Inventory | Fix datastore capacity/health; caller retries with same command ID. |
| Lock timeout, deadlock, or serialization conflict | Retry internally at most **two** times; then return `503 BUSY`. It is never reported as out of stock. | Saga retries safely; no partial order reservation. | Inventory | Investigate contention if sustained; retry remains safe by idempotency. |
| Broker / relay unavailable after database commit | Commit remains accepted; durable outbox accumulates. Commands continue while primary-store capacity remains safe. | Order/Search facts can be delayed, but stock truth remains correct. | Platform + Inventory | Restore relay/broker and publish from durable outbox; never re-run business commands. |
| Search projection or cache unavailable/stale | Inventory writes and allocation continue against primary state. Search may show stale `in_stock` information only. | Discovery may be stale; checkout is still correct. | Search | Consume/replay export feed or bootstrap snapshot; do not alter Inventory state. |
| Order Saga, payment callback, or timeout worker unavailable | Inventory leaves `RESERVED` state unchanged. It raises the orphan signal only after the agreed age threshold. | A reservation may remain held until Order recovers and issues its explicit decision. | Order | Retry/commit/release through normal idempotent commands; Inventory never auto-releases. |
| Warehouse changes to `inactive` or `outage` | New allocation excludes it. Existing reservations/commitments remain unchanged; physical facts remain recordable by ADMIN. | New orders use another eligible warehouse if one exists; no automatic reallocation of commitments. | Inventory ADMIN / Operations | Restore warehouse status only after operational verification; handle existing commitments through Order-led exception/cancellation flow. |
| Stock count discrepancy | Never silently modify reserved, committed, or quarantine stock. | Affected orders may need an Order exception/cancellation decision. | Inventory ADMIN + Order | Follow the approved discrepancy flow: whole-root release only while `RESERVED`, then audited correction command. |
| Return / inspection command conflicts or a scope is closed | Reject the whole invalid command with no stock movement. | No accidental receipt, double inspection, or post-close mutation. | Warehouse ADMIN | Correct the source request/physical evidence; submit a new valid command if allowed. |
| Event consumer cannot process a message | The producer retains its durable event; the consumer uses its own inbox/DLQ and dedupe. | Consumer state can lag; Inventory truth does not change. | Owning consumer | Fix consumer and replay the original event through its inbox. |
| Replay window has elapsed | Export returns `410 REPLAY_WINDOW_EXPIRED`. | Search must rebuild rather than guessing missed changes. | Search | Bootstrap a consistent snapshot and continue from its watermark. |
| Audit/authorization control unavailable | Deny ADMIN mutation. A syntactically valid evidence reference alone is insufficient without the local audit transaction. | Physical operation waits; no unaudited correction occurs. | Inventory + Security | Restore auth/audit path, then resubmit idempotently. |

## 3. Observability and alert contract

### 3.1 Signals that must exist

Metrics use only bounded dimensions: command/event type, result class, caller surface, deployment
version, and owner. Correlation/causation IDs, command IDs, order IDs, SKU IDs, and warehouse IDs
belong in traces, structured logs, audit records, and drill-down queries—not metric labels. A
per-warehouse metric is allowed only after an explicit cardinality-budget review. Logs must not
expose credentials, raw evidence content, or unnecessary personal data.

| Area | Required signals |
|---|---|
| Command correctness | Accepted/rejected/technical-failure counts by command and code; idempotent replay count; duplicate-payload-mismatch count; full duration histogram for Preview and every mutation. |
| Capacity and concurrency | Primary-store health, connection saturation, lock wait, deadlock/serialization count, internal retry count, `503 BUSY` count, transaction rollback count. |
| Stock integrity | Count of bucket-invariant violations, negative-bucket attempts rejected, ledger-to-state reconciliation mismatches, unresolved stock discrepancy cases. |
| Reservation safety | `RESERVED` roots by age, roots older than 15 minutes, and orphan candidates older than 60 minutes; Commit rejected because reservation is expired. |
| Returns | Active refund scopes by age/state, receipt/inspection/disposition rejects, quarantine balance and age distribution, `INSPECTION_COMPLETED` facts emitted exactly once. |
| Outbox/export | Undelivered outbox count, age of oldest unpublished record, publish attempts/failures, oldest export sequence not consumed per registered consumer when reported, snapshot/replay requests, and `410` replay-window responses. |
| Recovery/security | Last successful backup and restore verification, recovery drill result/duration, authorization denials, ADMIN mutations by actor/reason, audit-write failures, and break-glass read access. |

### 3.2 Service-level objectives and measurement

The release targets below are measured at the Inventory boundary, excluding client/network time. A valid domain result such as `REJECT_ALL` is a completed decision, not an availability error. `503 INVENTORY_UNAVAILABLE` and `503 BUSY` are technical failures for the affected command.

| SLI | Release 1 target | Measurement |
|---|---:|---|
| Reserve latency | p95 <= 200 ms; p99 <= 500 ms | Accepted or domain-rejected whole-order Reserve calls, measured from request acceptance to durable terminal result. |
| Allocation Preview latency | p95 <= 300 ms; p99 <= 750 ms | Requests whose primary store was available. |
| Search stock freshness | <= 60 seconds | Age between durable Inventory export and the Search projection's acknowledged applied sequence. Search owns this SLI. |
| Inventory committed-data loss | RPO = 0 | Proven by durable primary/failover design and restore drill; unverified capability is treated as unavailable for the target. |
| Authoritative-service recovery | RTO <= 60 minutes | From incident declaration to verified writes reopened under the recovery gates in Section 7. |
| Integrity | 0 accepted bucket-invariant or ledger reconciliation violations | Any detected violation is a severity-1 incident, not an error-budget item. |

### 3.3 Alert routes and actions

The numeric thresholds below are alerting thresholds, not permission to ignore a breached SLO. They provide time to act before the published target is materially violated.

| Trigger | Severity / owner | Immediate action |
|---|---|---|
| Any detected bucket-invariant breach, ledger/state mismatch, duplicate final inspection, or impossible negative balance | **SEV-1** — Inventory + Platform | Freeze affected writes; preserve evidence; investigate/recover under Section 7. Never conceal it with an adjustment. |
| Primary-store unavailable, failed authoritative failover, or a transaction cannot guarantee atomic state/audit/outbox | **SEV-1** — Platform + Inventory | Keep fail-closed mode; declare incident; restore authoritative path. |
| RPO=0 evidence lost/unverifiable, backup failure, or restore verification failure | **SEV-1** — Platform + Security | Treat recovery guarantee as failed; stop release promotion and remediate. |
| Oldest unpublished outbox record > 30 seconds | Warning — Platform + Inventory | Investigate relay/broker lag and capacity. |
| Oldest unpublished outbox record > 60 seconds | Page / high — Platform + Inventory; notify Search and Order | Restore publication. Search freshness may breach; do not replay commands. |
| Search applied-export freshness > 30 seconds / > 60 seconds | Warning / Page — Search | Restore consumer; replay or bootstrap snapshot. Inventory checkout path continues. |
| Any `RESERVED` reservation older than 60 minutes | Page / operational alert — Order, visible to Inventory | Order investigates and issues an explicit Commit/Release decision; Inventory does not auto-release. |
| Sustained `503 BUSY` > 1% of mutation attempts for 5 minutes, or deadlock/serialization retries sharply above baseline | High — Inventory + Platform | Reduce contention/capacity pressure; maintain all-or-nothing semantics. |
| Reserve/Preview latency target breached for 10 consecutive minutes | High — Inventory | Investigate database, allocation, and contention; never bypass primary-state validation. |
| Audit write failure, privileged-auth anomaly, or direct database write attempt | Security page — Security + Inventory | Deny mutation, preserve security evidence, revoke/contain as appropriate. |

`IN_PROGRESS` refund-scope aging and quarantine aging are dashboarded, but Release 1 deliberately has no automatic alert/closure threshold for them: Order owns the return window and physical operations own inspection cadence. A future threshold must be an explicit business decision, not an inferred cleanup job.

## 4. Reconciliation and exception handling

### 4.1 Reconciliation layers

Reconciliation detects divergence; it never repairs it automatically.

| Cadence / trigger | Check | Owner | Result if mismatch |
|---|---|---|---|
| Every accepted mutation | Enforce `available_to_sell + reserved + committed + quarantine = on_hand`, every bucket non-negative, exact command identity, and an outbox/audit/ledger record in the same transaction. | Inventory | Abort the mutation before commit. |
| Hourly | Scan all current `StockPosition` records for bucket invariant, valid warehouse state, and non-negative balance. | Inventory | SEV-1; freeze affected writes and investigate. |
| Daily | Replay immutable stock movements from the prior verified ledger checkpoint and compare computed positions with authoritative state. Record a signed verification checkpoint; do not change stock. | Inventory | SEV-1; preserve ledger/state snapshot for investigation. |
| Every 5 minutes | Find reservation roots in `RESERVED` older than 60 minutes. | Inventory signal, Order action | Create/refresh orphan alert only; Order decides and commands the outcome. |
| Daily | Order compares its active orders/refund workflows with Inventory state using documented Inventory queries and its own IDs. | Order | Resolve through idempotent commands or an Order exception; no database reconciliation write. |
| Continuous / consumer-owned | Search compares its applied export sequence/freshness with Inventory's feed watermark. | Search | Replay from retained events or bootstrap snapshot. |
| Before and after recovery | Full bucket scan, ledger checkpoint reconciliation, outbox count/sequence validation, and registered-consumer recovery check. | Inventory + Platform + consumers | Keep writes closed until all recovery gates pass. |

### 4.2 Permitted correction paths

1. An **ADMIN** records a physical counting discrepancy with a required reason code, evidence reference, actor, command ID, and operation identity.
2. If the affected root is `RESERVED`, Order first records an exception and calls whole-root `ReleaseReservation(STOCK_DISCREPANCY, order_exception_id)`. Inventory does not reroute or release an individual line.
3. If stock is `COMMITTED` or dispatched, Order decides cancellation/post-dispatch exception according to the existing lifecycle guard; Inventory does not modify that allocation silently.
4. ADMIN submits the appropriate audited adjustment/correction or permitted reversal command. A negative adjustment may only reduce `available_to_sell`; it cannot take from `reserved`, `committed`, or `quarantine`.
5. Inventory's normal command transaction records the movement, audit, idempotency result, and outbox event. Reconciliation is rerun and linked to the exception.

Systemic state/ledger mismatch is **not** a stock-count discrepancy. It requires incident recovery, not a compensating adjustment.

### 4.3 Operational evidence package

Every SEV-1 integrity or recovery incident preserves, before invasive action:

- incident ID, affected time window, correlation/causation IDs, and deployment version;
- immutable ledger/audit and outbox ranges relevant to the incident;
- authoritative database backup/restore point and verification outputs;
- list of affected reservations, refund scopes, and stock positions;
- the final reconciliation result and each approved recovery decision.

This package is metadata and exported evidence only; it never grants an operator a direct-write repair path.

## 5. Transactional outbox, replay, DLQ, and backpressure

### 5.1 Producer rules

- A business transaction inserts its outbox event atomically with state, ledger/audit, and accepted command result. If this insertion cannot commit, the business command fails closed.
- The relay publishes **at least once**. It leases a bounded batch, uses exponential backoff with jitter, and caps the retry interval at five minutes. It never deletes an undelivered record to recover throughput.
- An event carries its stable `event_id`, aggregate revision, schema version, and export sequence defined in the Contract Catalogue. A duplicate publish is expected; a missing committed event is not acceptable.
- Durable public events/export records are retained for at least 30 days. Undelivered records remain durable even when their normal retention time has elapsed, until publication and incident review permit cleanup.
- If retained-outbox growth threatens authoritative-store capacity or the 30-day replay commitment, Inventory raises a high-severity capacity incident and fails new mutations closed before it can lose transactional evidence.

### 5.2 Consumer rules

- Every consumer stores an inbox/deduplication entry atomically with its own projection or workflow effect, keyed by `event_id`.
- A consumer that cannot process an event places the original event ID, payload/reference, attempt history, and error in its **own** DLQ. The DLQ is diagnostic storage, not a deletion queue.
- After a fix, replay uses the original event through the normal inbox path. Operators never manufacture a substitute event or mark an event processed without the corresponding consumer effect.
- Order may reconcile its own reservations and refund scopes by Inventory query when events are delayed or missed. Search uses replay after its last applied sequence, or the snapshot-watermark bootstrap protocol after `410 REPLAY_WINDOW_EXPIRED`.
- Inventory promises no global ordering across aggregates. Consumers use each aggregate revision/sequence according to the documented contract.

### 5.3 Backpressure boundaries

The relay and export endpoints may be throttled independently, but broker slowness must not turn into an unbounded in-memory queue. Batches are durable and bounded; primary-store correctness remains independent of delivery. If pressure reaches the durable capacity safeguard above, the only acceptable degradation is to stop accepting new Inventory mutations—not to drop events, bypass audit, or use cache as authority.

## 6. Security, access, and audit operations

### 6.1 Access model

| Principal | Minimum authority | Explicitly prohibited |
|---|---|---|
| Checkout | Allocation Preview only. | Reserve, stock mutation, warehouse management, raw stock exposure. |
| Order Temporal Saga | Documented reservation/refund-scope commands and state/outcome queries for its own order IDs. | Warehouse/admin operations, shipment-state ownership, direct stock adjustment. |
| Search Projection | Snapshot/change export and public `in_stock` projection input only. | Allocation decisions, mutation, raw bucket/on-hand exposure. |
| ADMIN | Warehouse configuration and documented physical stock/return/inspection/disposition commands, with required reason/evidence. | Direct data updates, bypassing audit/idempotency, automatic rerouting/release. |
| Platform/SRE | Infrastructure operation plus time-bounded, audited, read-only production diagnostics. | Direct Inventory business-data writes. |
| Security/Audit | Read-only audit/evidence review under least privilege. | Command execution or business-state modification. |

Interactive ADMIN access requires strong authenticated identity and MFA. Service-to-service calls use authenticated workload identity and encrypted transport. Authorization is evaluated on every request; role assertions supplied by a caller are not trusted by themselves.

### 6.2 Data protection and auditability

- Encrypt primary data, backups, and durable outbox/audit storage at rest; use encrypted transport in transit. Keys and credentials are managed outside application logs and rotated under the platform security policy.
- Audit entries retain actor, effective role, command/operation identity, reason code, evidence reference, before/after business values, timestamp, correlation/causation IDs, and result. They are immutable and retained for **at least seven years**.
- External evidence content is not copied into events or routine logs. Its retained reference and access trail must remain available for at least the same seven-year period.
- Inventory events and exports use service identifiers such as `order_id`, `sku_id`, and `warehouse_id`; they must not carry customer PII unless a separately approved contract requires it.
- No direct database write credential is issued for operational correction. Any detected direct-write attempt is a security incident. Read-only break-glass access is time-bounded, individually attributable, and reviewed after use.

The seven-year baseline and evidence handling remain subject to Compliance/Legal validation before production launch. This is a release gate, not permission to shorten retention or omit audit evidence.

## 7. Disaster recovery and recovery runbook

### 7.1 Declaring and containing an authoritative-data incident

Declare an authoritative-data incident for primary-store loss, uncertain durability, detected invariant/ledger mismatch, failed recovery validation, or suspected unauthorized write.

1. **Contain:** place mutation endpoints into fail-closed mode; Preview returns `503 INVENTORY_UNAVAILABLE`. Do not accept new authoritative transitions from cache or a replica of unknown recovery state.
2. **Preserve:** capture the evidence package in Section 4.3 and stop retention cleanup for relevant audit/outbox ranges.
3. **Recover the source of truth:** fail over only to a verified zero-data-loss candidate, or restore a consistent authoritative point. If RPO=0 cannot be proven, do not claim recovery complete.
4. **Validate before reopening:** execute the recovery gates below against the recovered primary state.
5. **Rebuild derived systems:** restart outbox relay from durable records; consumers replay/dedupe; Search uses replay or snapshot watermark. Cache is discarded/rebuilt, never promoted to truth.
6. **Resume deliberately:** Inventory and Platform jointly authorize reopening writes only after recovery gates pass. Order then resumes its normal idempotent saga decisions; it does not bulk-reissue unverified commands.

### 7.2 Mandatory recovery gates

All gates must pass before write traffic resumes:

- primary database health and durability configuration demonstrate the RPO=0 target for the selected recovery point;
- full current-position bucket scan has zero violations;
- ledger replay from the last verified checkpoint matches current stock state;
- no duplicate command identity, operation identity, final inspection, or aggregate revision is detected;
- outbox inventory/counts and export sequences are internally consistent; undelivered events are queued for replay;
- access/audit controls are functioning and no unreviewed privileged direct-write path exists;
- affected consumer owners receive replay/bootstrap instructions and Search freshness is monitored until recovered;
- incident owner records the decision, verification evidence, and time writes are reopened.

### 7.3 Readiness tests

| Test | Minimum cadence | Success criterion |
|---|---:|---|
| Backup restore integrity test | Monthly | A consistent restore passes bucket, ledger, audit, and outbox validation. |
| Full failover/restore drill | Quarterly | The system demonstrates RPO=0 and RTO <= 60 minutes with documented evidence. |
| Outbox/consumer replay drill | Quarterly | Duplicate delivery does not duplicate consumer effects; a lagging Search bootstrap produces a correct projection. |
| Access and audit-control review | Quarterly | ADMIN/MFA, service scope, evidence retention, and no-direct-write controls are verified. |
| Reconciliation exception exercise | Quarterly | A simulated count discrepancy follows the approved Order-led release/correction flow with no automatic mutation. |

A failed drill creates a remediation item and blocks any assertion that the corresponding target is met in production.

## 8. Ownership and escalation

| Concern | Accountable owner | Supporting owners |
|---|---|---|
| Inventory state, invariants, command idempotency, audit, outbox producer | Inventory team | Platform, Security |
| Database durability, backup, failover, capacity, relay/broker runtime | Platform/SRE | Inventory |
| Payment timeout, orphan-reservation decision, Order reconciliation, refund closure | Order team | Inventory |
| Search projection freshness, inbox/DLQ, replay/bootstrap | Search team | Inventory, Platform |
| Warehouse availability, physical count, receipt, inspection, disposition evidence | Operations ADMIN | Inventory |
| Privileged access, audit review, incident security response | Security/Compliance | Inventory, Platform |

No ownership transfer changes domain authority: Platform does not correct stock, Operations does not close an Order refund scope, Search does not allocate stock, and Inventory does not decide payment timeout or shipment state.

## 9. Phase 8 definition of done

- [x] Failure modes have a specified fail-closed or degraded behavior, owner, and recovery path.
- [x] Primary-store loss, cache/Search degradation, outbox/broker failure, Saga delay, warehouse outage, consumer failure, and audit failure preserve prior domain invariants.
- [x] SLOs, monitoring signals, alert thresholds, and escalation ownership are defined without classifying domain `REJECT_ALL` as a technical outage.
- [x] Reconciliation detects invariant/ledger/order/projection divergence and never auto-corrects business state.
- [x] Transactional-outbox retention, at-least-once delivery, consumer inbox/DLQ, replay, snapshot bootstrap, and capacity backpressure are defined.
- [x] ADMIN, service, platform, and audit access are least-privilege; direct production database writes are prohibited.
- [x] Audit/evidence retention, encryption, incident evidence preservation, and Compliance/Legal validation gate are explicit.
- [x] A fail-closed DR runbook, recovery gates, RPO=0/RTO<=60-minute proof requirements, and recurring readiness drills are defined.
- [x] All Phase 8 choices preserve the Phase 0–7 commitments, particularly no oversell, whole-order reservation, Order-owned timeout/closure, and quarantine-first returns.
