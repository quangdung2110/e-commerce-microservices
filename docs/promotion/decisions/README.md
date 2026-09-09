# Promotion Service — Architecture Decision Records

ADR ghi quyết định đã chấp nhận ở mức thiết kế. `Accepted` không đồng nghĩa đã
implemented/verified vì Promotion Service hiện chưa có source module.

## Quy tắc

- Dùng [ADR-000-template.md](./ADR-000-template.md) cho quyết định mới.
- Không sửa lịch sử ADR Accepted để thay nghĩa; tạo ADR mới, đánh dấu Superseded và liên
  kết hai chiều.
- Quyết định phải nêu alternative, hệ quả tiêu cực, migration và verification.
- Khi contract liên-service được refactor, tạo ADR mới nếu thay boundary/semantics cốt lõi;
  chỉ đổi schema transport thì cập nhật interfaces/contract.

## Decision index

| ADR | Decision | Status | Date | Supersedes |
|---|---|---|---|---|
| [ADR-000](./ADR-000-template.md) | Template — không phải quyết định thực tế | Template | N/A | N/A |
| [ADR-001](./ADR-001-postgresql-jsonb-and-ledger.md) | PostgreSQL JSONB và ledger bền vững | Accepted | 2026-09-09 | — |
| [ADR-002](./ADR-002-single-tier-redis-snapshots.md) | Snapshot Redis đơn tầng, parse AST request-scoped | Accepted | 2026-09-09 | — |
| [ADR-003](./ADR-003-redis-lua-budget-reservation.md) | Budget/reservation bằng Redis Lua sau Submit | Accepted | 2026-09-09 | — |
| [ADR-004](./ADR-004-spop-claim-and-rescue.md) | SPOP claim với pending rescue | Accepted | 2026-09-09 | — |
| [ADR-005](./ADR-005-direct-kafka-best-effort.md) | Direct-produce Kafka best-effort | Accepted | 2026-09-09 | — |
| [ADR-006](./ADR-006-platform-only-voucher-policy.md) | Một voucher PLATFORM và policy wallet/refund | Accepted | 2026-09-09 | — |
| [ADR-007](./ADR-007-markdown-cqrs-and-expiry.md) | Markdown CQRS và expiry near-real-time | Accepted | 2026-09-09 | — |
| [ADR-008](./ADR-008-db-first-reconciliation.md) | Reconciliation một chiều DB → Redis | Accepted | 2026-09-09 | — |
| [ADR-009](./ADR-009-deterministic-json-rule-engine.md) | Rule Engine JSON AST thuần và xác định | Accepted | 2026-09-09 | — |
