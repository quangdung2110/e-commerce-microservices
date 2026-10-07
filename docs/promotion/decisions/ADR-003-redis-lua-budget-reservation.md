---
doc_id: promotion-adr-003
service: promotion
status: superseded
date: 2026-09-09
decision_owners:
  - Promotion Service team
supersedes: null
superseded_by: ADR-013
evidence:
  - docs/promotion/data.md
  - docs/promotion/workflows.md
---

# ADR-003 — Budget/reservation bằng Redis Lua sau Submit

## Status

`Superseded` bởi
[ADR-013](./ADR-013-command-receipts-and-durable-finalization.md). Quyết định reserve
runtime bằng Redis Lua được giữ; phần confirm và `cancel-confirmed` Redis-first được
thay bằng durable finalization PostgreSQL-first.

## Context

Budget flash-sale có tranh chấp cao. Reserve ở Preview cho phép bot giam quota; pipeline
nhiều script không rollback atomically và không kiểm tra replay khác payload.

## Decision

Không reserve ở Preview. Sau Submit, Script 2B duy nhất validate/apply all-or-nothing cho
một voucher: qty, amount, reservation payload và ZSET index. Confirm/cancel cùng technical
`cancel-confirmed` dùng Lua atomic counter + lock ngắn; PostgreSQL wallet/budget/outbox
quyết định durable state. Reservation 15 phút cộng cleanup grace; amount dùng integer
VND với `HINCRBY`, không dùng `HINCRBYFLOAT`. Customer refund không mutate Promotion theo
[ADR-012](./ADR-012-wallet-redemption-and-no-refund.md).

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
confirm/cancel/technical-compensation/sweep race, CROSSSLOT test và
DB-fail-after-Redis recovery.
