---
doc_id: review-adr-003
service: review
status: accepted
date: 2026-09-05
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
---

# ADR-003 — Outbox và Debezium CDC cho việc phát event

## Decision

Mọi business event được insert vào `review_outbox` trong cùng transaction với domain
change. Debezium đọc chỉ bảng này qua publication và publish `t.review.events` với
Kafka key luôn là `product_id`. Topic chứa lifecycle facts, Search-facing rating
snapshots và đúng hai Notification request: first official reply và legal removal.
Hai request chỉ có review/product/recipient IDs; không chứa reply content, legal case,
reason hoặc admin identity.

Outbox là append-only CDC source, không phải queue có state theo row. Không có
`published_at` và không đếm row cũ để suy ra backlog. Publication health được đo qua
Kafka Connect, source LSN/replication slot, retained WAL và end-to-end event age.

Outbox partition theo tháng UTC. Initial schema tạo động tháng hiện tại và tháng sau;
scheduler duy trì rolling window. Cleanup chỉ `DROP PARTITION` khi connector/slot đã
đi qua partition và retention hết hạn.

## Consequences

- Kafka/Connect failure không làm mất review đã commit; downstream chỉ trễ.
- Thiếu partition làm mọi command fail vì mọi command ghi outbox, nên là alert critical.
- PostgreSQL 14 không có global unique constraint trên partitioned parent ngoài
partition key. `event_id` vẫn là UUID CloudEvents do producer sinh và không tái dùng;
nếu cần database-enforced global key, phải đổi partition strategy sau này.

## Verification

Test domain transaction có đúng outbox, Kafka key đúng product, connector restart
không mất event và scheduler tạo partition đúng. Runbook không được dùng “old outbox
row count” làm publish backlog.
