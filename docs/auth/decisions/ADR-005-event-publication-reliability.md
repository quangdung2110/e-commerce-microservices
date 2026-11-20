---
doc_id: auth-adr-005
service: auth
status: accepted
date: 2026-08-27
decision_owners:
  - Auth Service team
supersedes: null
evidence:
  - go/services/auth-service/internal/infrastructure/kafka/publisher.go
  - go/services/auth-service/internal/domain/service/notifier.go
---

# ADR-005 — Guarantee reliable identity-event publication

> 2026-10-09: thứ tự các bước sau commit (phát event trước, ghi marker Redis sau) được chốt
> tại [ADR-006](./ADR-006-publish-before-revocation-marker.md). Quyết định dưới đây không đổi.

## Context

Tài liệu cũ mô tả direct Kafka producer at-least-once, nhưng chưa xác định cách
phục hồi khi Auth commit database thành công mà publish event thất bại.

## Decision

Auth publish Kafka event trực tiếp sau khi transaction PostgreSQL commit; không
dùng transactional outbox hoặc durable inbox. Publisher retry có giới hạn với cùng
`event_id`, ghi metric/alert khi retry cạn và để lại trường hợp thất bại cần
reconciliation vận hành. Đây là trade-off được chấp nhận để giảm độ phức tạp.

## Alternatives considered

- Direct producer với bounded retry: đơn giản, không thêm schema/worker nhưng có
  thể mất event sau DB commit nếu retry cạn.
- Transactional outbox: tăng schema/worker nhưng đóng khoảng trống dual write.
- Durable retry/reconciliation: linh hoạt nhưng cần persistent state riêng.

## Consequences

Event không có exactly-once guarantee và không được coi là durable chỉ nhờ Kafka
producer. Contract phải gồm `event_id`, retry và monitoring; consumer downstream
vẫn cần idempotency. Alert/routine reconciliation là chi phí vận hành đã chấp nhận.

## Migration impact and verification

Đã implement: `Publisher` retry tối đa `kafka.publish_max_attempts` (3) với cùng CloudEvent
`id`, producer idempotent, `acks=all`. Retry cạn chỉ ghi log `error` (`identity event could
not be published`). Chưa có metric/alert, dashboard, runbook hay quy trình reconciliation
vận hành: đây vẫn là khoảng trống phải đóng trước production. Không cần migration cho
outbox/inbox.
