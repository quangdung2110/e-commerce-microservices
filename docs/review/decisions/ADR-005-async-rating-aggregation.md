---
doc_id: review-adr-005
service: review
status: accepted
date: 2026-09-05
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
---

# ADR-005 — Aggregate bất đồng bộ, idempotent bằng event receipt

## Decision

Rating aggregate được cập nhật bất đồng bộ từ self-consume `t.review.events`, nhưng
consumer là idempotent. Trong transaction xử lý, consumer insert
`review_event_receipts(consumer_name, event_id)` trước; conflict nghĩa là event đã có
effect và transaction commit no-op. Với receipt mới, consumer apply bucket delta,
derive total/average, increment revision và insert metrics outbox snapshot.

Consumer chỉ xử lý event ảnh hưởng aggregate. `REVIEW_METRICS_UPDATED` bị bỏ qua để
tránh vòng lặp; author anonymization, reply và content-only edit cũng bị bỏ qua.

## Rationale

At-least-once delivery và crash sau database commit là điều bình thường. Chấp nhận
double-count rồi chờ reconciliation làm rating public/PDP/Search sai trong nhiều giờ.
Một receipt row rẻ hơn chi phí sự cố và biến replay/rebalance thành an toàn.

## Consequences

- Aggregate vẫn eventual để tránh hot-row contention trên command path.
- Reconciliation vẫn tồn tại nhưng chỉ phát hiện corruption, missing/malformed event
  hoặc bug; không còn là biện pháp thường xuyên sửa duplicate delivery.
- Receipt retention phải dài hơn Kafka retention cộng replay safety margin.

## Verification

Test create/edit/hide/restore/remove, duplicate event, crash-after-commit simulation,
content-only event ignored, bucket non-negative và full rebuild with revision.
