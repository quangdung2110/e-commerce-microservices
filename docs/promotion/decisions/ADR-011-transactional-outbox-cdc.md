---
doc_id: promotion-adr-011
service: promotion
status: accepted
date: 2026-09-10
decision_owners:
  - Promotion Service team
supersedes:
  - ADR-005
  - ADR-007
evidence:
  - docs/promotion/data.md
  - contracts/events/product/v1/asyncapi.yaml
---

# ADR-011 — Transactional outbox phát qua Debezium CDC

## Status

`Accepted` ở mức thiết kế; chưa implemented. Supersede direct Kafka best-effort và phần
publication của ADR-007.

## Context

Direct produce sau DB commit có crash window làm mất markdown event và để Product/Search
stale vô hạn. Kafka transaction không tạo atomicity với PostgreSQL. Product đã dùng
outbox/CDC và markdown consumer đã có full-snapshot/source-revision contract.

## Decision

- Mọi business integration event được insert vào append-only
  `promotion_outbox_events` trong cùng transaction với business state.
- Debezium/Kafka Connect relay at-least-once; retry/backoff/DLQ thuộc connector.
- Outbox có stable partition key, complete CloudEvent payload và unique business
  `dedup_key`; không có mutable publish status.
- Markdown chỉ phát `com.ecommerce.promotion.product-markdown-snapshot.v1`, key product,
  full state ACTIVE/INACTIVE và monotonic `source_revision`.
- Cấp markdown revision trong PostgreSQL transaction có advisory lock theo product.
- Internal Redis-first claim work item vẫn được pending sweeper cứu; public claim fact
  chỉ outbox sau khi claim/wallet đã persist.

## Alternatives considered

- Direct produce + manual replay: ít hạ tầng nhưng có thể mất event.
- Application outbox poller: tránh Debezium nhưng thêm polling/locking/retry state.
- Kafka transaction phối hợp DB: không atomic xuyên datastore.

## Consequences

DB commit luôn có durable event tương ứng và replay full snapshot hội tụ được. Đổi lại
cần logical replication, connector operations, outbox growth/retention và consumer
idempotency vì delivery at-least-once.

## Migration impact

Tạo outbox table/index, source-revision sequence, connector/Event Router, ACL/topic và
monitoring. Deploy compatible consumer trước producer; dừng direct producer sau khi
CDC path được xác minh. Contract markdown v1 không cần breaking payload change.

## Verification

Test rollback không có event, commit luôn có outbox, crash mọi boundary, duplicate CDC,
partition ordering, stale source revision, connector outage/recovery và safe retention
theo watermark.
