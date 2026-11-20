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
tránh vòng lặp; hai Notification request và content-only edit được ACK mà không áp
dụng delta.

## Rationale

At-least-once delivery và crash sau database commit là điều bình thường. Chấp nhận
double-count rồi chờ reconciliation làm rating public/PDP/Search sai trong nhiều giờ.
Một receipt row rẻ hơn chi phí sự cố và biến replay/rebalance thành an toàn.

## Consequences

- Aggregate vẫn eventual để tránh hot-row contention trên command path.
- Reconciliation vẫn tồn tại nhưng chỉ phát hiện corruption, missing/malformed event
  hoặc bug; không còn là biện pháp thường xuyên sửa duplicate delivery.
- Receipt retention phải dài hơn Kafka retention cộng replay safety margin.
- Reconciliation hằng ngày so sánh `reviews` với aggregate tại một thời điểm, trong khi
  aggregate luôn trễ vài giây so với `reviews`. Một review vừa tạo mà event của nó chưa được
  aggregator xử lý sẽ bị reconciliation coi là drift: nó ghi giá trị đã tính cả review đó
  rồi event đến muộn cộng thêm một lần nữa. Giới hạn đã biết và được chấp nhận: reconciliation
  chạy lúc 02:15 khi tải thấp, và lần chạy kế tiếp tự sửa sai lệch (kèm tăng revision). Nó
  không phải đường thường xuyên sửa aggregate; xem
  [operations.md mục 6](../operations.md#6-runbooks).

## Verification

Test create/edit/hide/restore/remove, duplicate event, crash-after-commit simulation,
content-only event ignored, bucket non-negative và full rebuild with revision.
