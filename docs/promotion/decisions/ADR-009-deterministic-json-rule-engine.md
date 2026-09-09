---
doc_id: promotion-adr-009
service: promotion
status: accepted
date: 2026-09-09
decision_owners:
  - Promotion Service team
supersedes: null
evidence:
  - docs/promotion/RuleEngineDemo.java
  - docs/promotion/domain.md
---

# ADR-009 — Rule Engine JSON AST thuần và xác định

## Status

`Accepted` ở mức thiết kế; chưa implemented.

## Context

Voucher cần nested condition linh hoạt, evaluation nhanh và cùng kết quả giữa Preview với
submit-time re-evaluation. Rule không được ngầm gọi DB/network hoặc mutate state.

## Decision

Lưu AST polymorphic JSONB với `node_type`; `LOGICAL` dùng AND/OR + non-empty `children`,
`CONDITION` dùng operator/field/value. Evaluate depth-first O(n), pure, decimal-safe.
Missing field=false; IN collection ANY; NOT_IN collection ALL-not-match; empty collection
lần lượt false/true. Validate unknown node/operator lúc create/update. Response mang
rule_version và reason chuẩn.

Supported fields hiện tại: `cart.total_amount`, `cart.payment_method`,
`cart.checkout_channel`, `cart.items.category_id`, `user.id`, `user.tier`.

## Alternatives considered

- Hard-code condition columns: nhanh nhưng khó mở rộng/nested.
- Scripting expression tùy ý: linh hoạt nhưng khó sandbox/determinism/security.
- Query service/DB trong evaluator: dữ liệu tươi hơn nhưng latency và kết quả không ổn định.

## Consequences

Rule linh hoạt, test/replay được và không có side effect. Đổi lại cần schema validation,
type coercion rõ, parse cost mỗi request và governance field/operator/version.

## Migration impact

Cần JSON Schema/validator, Jackson sealed hierarchy/Visitor, rule_version, migration AST
cũ và contract reason. Demo hiện có phải được chuyển thành test production.

## Verification

Unit/property/golden test mọi operator/nesting/missing/empty/numeric, deterministic replay,
malformed AST rejection, performance N rules và chứng minh không gọi hạ tầng.
