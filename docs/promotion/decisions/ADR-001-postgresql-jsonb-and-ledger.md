---
doc_id: promotion-adr-001
service: promotion
status: superseded
date: 2026-09-09
decision_owners:
  - Promotion Service team
supersedes: null
superseded_by: ADR-012
evidence:
  - docs/promotion/data.md
---

# ADR-001 — PostgreSQL JSONB và ledger bền vững

## Status

`Superseded` bởi [ADR-012](./ADR-012-wallet-redemption-and-no-refund.md). PostgreSQL
và JSONB được giữ; usage/idempotency ledger riêng bị loại bỏ.

## Context

Promotion cần ACID cho wallet/usage/idempotency nhưng rule điều kiện có cấu trúc đa hình.
Budget row nóng cũng không nên khóa cùng metadata promotion.

## Decision

Dùng PostgreSQL làm system of record; lưu AST trong `promotions.conditions_rule JSONB`.
Tách `promotion_budgets`; dùng `coupon_usages` làm ledger redeem/refund và
`promotion_event_idempotency` làm final dedup gate. Không persist state RESERVED.

## Alternatives considered

- Schema quan hệ cho từng condition: query được nhưng migration/phép ghép phức tạp.
- Document database: linh hoạt AST nhưng tăng số datastore và transaction ledger khó hơn.
- Gộp budget vào promotion: đơn giản schema nhưng tăng contention row nóng.

## Consequences

ACID/constraint/audit rõ; JSONB linh hoạt. Đổi lại AST validation nằm nhiều ở application,
PostgreSQL vẫn chịu write ledger và chưa có migration chuẩn.

## Migration impact

Cần baseline migration với CHECK/unique/partial index, decimal/money policy và backfill.
Redis hot state phải được dựng từ DB cấu hình/ledger.

## Verification

Integration test schema/invariant, duplicate action, concurrent claim, partial/full refund
và chứng minh không có RESERVED trong usage ledger.
