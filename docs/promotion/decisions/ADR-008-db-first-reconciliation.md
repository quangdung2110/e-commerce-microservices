---
doc_id: promotion-adr-008
service: promotion
status: superseded
date: 2026-09-09
decision_owners:
  - Promotion Service team
supersedes: null
superseded_by: ADR-012
evidence:
  - docs/promotion/data.md
  - docs/promotion/operations.md
---

# ADR-008 — Reconciliation một chiều DB → Redis

## Status

`Superseded` bởi [ADR-012](./ADR-012-wallet-redemption-and-no-refund.md). Nguyên tắc
DB → Redis được giữ nhưng source query không còn usage/idempotency ledger.

## Context

Lua có thể commit trước khi PostgreSQL ledger commit; retry/timeout/refund có thể làm
counter Redis drift. Redis không đủ bằng chứng để sửa ledger tài chính.

## Decision

Mỗi 15 phút và full-scan off-peak, dựng total từ `promotion_budgets`, used từ
`coupon_usages` + idempotency gate, reserved từ payload/index còn hiệu lực sau khi lọc
REDEEM FINAL. So actual Redis, lock promotion, re-read và repair Redis. Audit before/after.
Không bao giờ ghi PostgreSQL từ Redis.

## Alternatives considered

- Redis là source of truth: nhanh nhưng mất/evict/corrupt làm sai ledger bền vững.
- Đồng bộ hai chiều: không có quy tắc phân xử đáng tin, có thể khuếch đại drift.
- Không reconciliation: crash window tồn tại vĩnh viễn.

## Consequences

Có recovery xác định và audit được. Đổi lại job tốn DB/Redis, reserved chỉ có ở Redis nên
payload loss vẫn cần repair policy; drift có thể tồn tại đến chu kỳ job.

## Migration impact

Cần query/index ledger, reservation retention grace, repair script/lock, audit store,
metrics/alerts và dry-run/canary trước auto-repair.

## Verification

Inject drift mọi chiều, missing payload, concurrent mutation/lock contention và chứng
minh repair hội tụ, invariant giữ đúng, DB không bị update từ Redis.
