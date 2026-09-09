---
doc_id: promotion-adr-005
service: promotion
status: accepted
date: 2026-09-09
decision_owners:
  - Promotion Service team
supersedes: null
evidence:
  - docs/promotion/interfaces.md
  - docs/promotion/operations.md
---

# ADR-005 — Direct-produce Kafka best-effort

## Status

`Accepted` ở mức thiết kế; chưa implemented.

## Context

Promotion cần phát markdown/coupon/budget events nhưng thiết kế hiện tại ưu tiên hạ tầng
đơn giản và chưa có outbox/CDC.

## Decision

Publish trực tiếp sau PostgreSQL commit. Delivery là best-effort; consumer idempotent theo
event/business ID. Không gọi đây là exactly-once và không rollback DB khi send thất bại.

## Alternatives considered

- Transactional outbox + CDC: bền vững hơn nhưng thêm schema/connector/operations.
- Kafka transaction phối hợp DB: không tạo atomicity thật với PostgreSQL.
- Publish trước commit: có thể phát fact chưa tồn tại nên bị loại.

## Consequences

Ít thành phần và latency thấp hơn. Crash sau commit trước send làm mất event, đặc biệt có
thể để Product/Search stale; phải alert/replay thủ công hoặc snapshot recovery.

## Migration impact

Producer phải có timeout/retry/metric và runbook lost event. Nếu chuyển outbox sau này,
tạo ADR thay thế và rollout consumer-compatible.

## Verification

Failure injection ở commit/send boundary; chứng minh send chỉ sau commit, metric/alert
hoạt động và replay không tạo side effect lặp.
