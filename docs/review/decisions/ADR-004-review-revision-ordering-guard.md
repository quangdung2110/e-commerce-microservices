---
doc_id: review-adr-004
service: review
status: accepted
date: 2026-09-05
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - contracts/events/review/v1/asyncapi.yaml
  - contracts/http/review/internal/v1/openapi.yaml
---

# ADR-004 — `review_revision` là ordering guard của rating aggregate

## Decision

`product_rating_aggregates.review_revision` là `BIGINT` đơn điệu per `product_id`,
bắt đầu từ 1. Nó chỉ tăng khi **rating aggregate thay đổi**: create, rating/media
edit, hide/restore/remove hoặc reconciliation repair. Content-only edit, reply và
author anonymization không làm revision tăng.

Mỗi aggregate mutation cập nhật bucket/total/average, increment revision và insert
`REVIEW_METRICS_UPDATED` trong một transaction. Snapshot export và event cùng trạng
thái mang cùng revision. Search apply iff incoming revision lớn hơn document revision.

## Rationale

Revision là version của projection rating, không phải version chung của Review.
Tăng nó cho đổi tên/content tạo snapshot vô ích, hot-row contention và nhầm lẫn về
nghĩa guard, trong khi Search không lưu các field đó.

## Verification

Test duplicate/old/out-of-order metric event, export overlap, content-only edit and
anonymization without revision bump, và reconciliation repair with revision bump.
