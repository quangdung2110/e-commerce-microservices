---
doc_id: promotion-adr-003
service: promotion
status: accepted
date: 2026-09-09
decision_owners:
  - Promotion Service team
supersedes: null
evidence:
  - docs/promotion/data.md
  - docs/promotion/workflows.md
---

# ADR-003 — Budget/reservation bằng Redis Lua sau Submit

## Status

`Accepted` ở mức thiết kế; chưa implemented.

## Context

Budget flash-sale có tranh chấp cao. Reserve ở Preview cho phép bot giam quota; pipeline
nhiều script không rollback atomically và không kiểm tra replay khác payload.

## Decision

Không reserve ở Preview. Sau Submit, Script 2B duy nhất validate/apply all-or-nothing cho
một voucher: qty, amount, reservation payload và ZSET index. Confirm/cancel/refund dùng
Lua atomic counter + lock ngắn; DB idempotency/ledger quyết định cuối. Reservation 15 phút
cộng cleanup grace; amount hiện có thể dùng HINCRBYFLOAT nhưng nên chuyển minor-unit.

## Alternatives considered

- PostgreSQL row lock: bền vững nhưng contention hot row.
- Reserve ở Preview: giảm race UX nhưng mở anti-abuse vector.
- Redis pipeline các script đơn: ít round-trip nhưng không atomic/rollback toàn operation.

## Consequences

Hot path nhanh và chống double-spend trong Redis. Đổi lại có dual-write crash window,
reconciliation bắt buộc và multi-key Lua trên Redis Cluster cần cùng hash slot/topology.

## Migration impact

Cần versioned Lua, hash-tag strategy, preload budget, TTL/index rebuild và rollout tương
thích. Checkout/Order phải phân biệt Preview với Submit và xử lý 409 re-evaluation.

## Verification

Concurrency/load test, failure injection tại từng Redis command, payload mismatch,
confirm/cancel/sweep race, CROSSSLOT test và DB-fail-after-Redis recovery.
