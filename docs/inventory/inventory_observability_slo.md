# Inventory Service - Observability & SLO

**Liên kết nhanh (Related Documents):**
* [Workflows](inventory_workflow.md)
* [Events](inventory_events.md)
* [Workers](inventory_workers.md)
* [Database Design](inventory_db_design.md)

---

Tài liệu này định nghĩa SLI/SLO, metrics và alerting tối thiểu để vận hành Inventory an toàn trong production.

## 1. Core SLIs

1. `reserve_latency_ms` (p95)
2. `deduct_latency_ms` (p99)
3. `release_latency_ms` (p95)
4. `reserve_success_rate`
5. `deduct_success_rate`
6. `inventory_stream_relay_lag_seconds`
7. `ledger_consumer_lag`
8. `inventory_reconcile_drift_ratio`
9. `inventory_reservation_expiry_rate`
10. `suspended_events_backlog`

## 2. SLO Targets

| Capability | Target |
|---|---|
| Reserve API | p95 <= 60ms, success >= 99.9% |
| Deduct API | p99 <= 120ms, success >= 99.95% |
| Release API | p95 <= 120ms, success >= 99.5% |
| Redis stream relay | p95 lag <= 5s |
| Ledger consumption | lag < 10,000 records trong steady state |
| Reconciliation drift | <= 0.1% SKU mỗi chu kỳ |

## 3. Mandatory Metrics

### 3.1 API metrics

- `inventory_reserve_requests_total{result}`
- `inventory_deduct_requests_total{result}`
- `inventory_release_requests_total{result}`
- `inventory_reserve_latency_ms`
- `inventory_deduct_latency_ms`
- `inventory_release_latency_ms`

### 3.2 Lua/idempotency metrics

- `inventory_lua_reserve_applied_total`
- `inventory_lua_reserve_idempotent_hit_total`
- `inventory_lua_deduct_applied_total`
- `inventory_lua_deduct_idempotent_hit_total`
- `inventory_lua_release_applied_total`
- `inventory_lua_release_idempotent_hit_total`
- `inventory_lua_invariant_drift_total`

### 3.3 Stream/Event pipeline metrics

- `inventory_stream_outbox_pending_total`
- `inventory_stream_relay_success_total`
- `inventory_stream_relay_failed_total`
- `inventory_stream_relay_lag_seconds`
- `inventory_ledger_events_processed_total{event_type}`
- `inventory_ledger_batch_duration_ms`
- `inventory_outbox_quantity_changed_pending_total`

### 3.4 Data integrity metrics

- `inventory_reconcile_drift_total`
- `inventory_reconcile_skus_checked_total`
- `inventory_reconcile_skus_drifted_total`
- `inventory_negative_available_detected_total`
- `inventory_negative_reserved_detected_total`

### 3.5 Recovery/parking lot metrics

- `inventory_suspended_skus_total`
- `inventory_suspended_events_parked_total`
- `inventory_suspended_events_recovered_total`
- `inventory_dlq_unresolved_total`

## 4. Alert Rules

1. `reserve_success_rate < 99%` trong 5 phút -> critical.
2. `deduct_success_rate < 99.5%` trong 5 phút -> critical.
3. `inventory_stream_relay_lag_seconds > 30` trong 10 phút -> high.
4. `ledger_consumer_lag > 20000` trong 10 phút -> high.
5. `inventory_lua_invariant_drift_total` tăng liên tục -> high.
6. `suspended_events_backlog > 1000` trong 30 phút -> high.
7. `inventory_reconcile_drift_ratio > 0.5%` sau mỗi kỳ reconcile -> critical.

## 5. Tracing Contract

Mọi API và event phải propagate:

- `correlation_id`
- `trace_id`
- `route_plan_id` (nếu có)
- `order_id`
- `sku_id`
- `warehouse_id`

Span bắt buộc:

1. `inventory.reserve`
2. `inventory.deduct`
3. `inventory.release`
4. `inventory.stream.relay`
5. `inventory.ledger.consume`
6. `inventory.reconcile`

## 6. Runbook tối thiểu

1. Sự cố lag Kafka relay.
2. Sự cố drift Redis/DB.
3. Sự cố parked events tăng đột biến.
4. Sự cố reserve thất bại hàng loạt.
5. Sự cố deduct underflow/invariant breach.
