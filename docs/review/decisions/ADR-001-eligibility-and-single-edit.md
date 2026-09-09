---
doc_id: review-adr-001
service: review
status: accepted
date: 2026-09-05
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
---

# ADR-001 — Entitlement theo SKU/order và một lần sửa review

## Decision

Review tạo tối đa một entitlement cho `(order_id, product_id, sku_id)`. `order_id`
là globally unique và đã xác định buyer, nên `user_id` không thuộc business key.

Eligibility chỉ có `PENDING`, `CLAIMED`, `EXPIRED`. Nó quản lý quyền tạo review; nó
không mang attempt counter hay quota sửa. `reviews.edit_count` có range `0..1` và là
nguồn sự thật duy nhất cho quyền sửa.

Eligibility lưu `completed_at` từ `ORDER_COMPLETED` và tính
`expires_at = completed_at + review_window_days`. `created_at` chỉ ghi thời điểm
Review nhận event, không xác định hạn. Một event tới sau hạn vẫn tạo `EXPIRED`, với
`expired_at = expires_at` để giữ mốc nghiệp vụ cửa sổ đóng.

Create review atomically claim eligibility `PENDING -> CLAIMED` rồi insert review.
Edit requires `edit_count=0` and atomically sets it to 1. Không có transition quay về
`PENDING` và không có reconciliation mở lại quota.

## Rationale

Thiết kế cũ mã hóa cùng một sự thật bằng eligibility state và `attempt_count`:
`PENDING/0`, `REVIEWED/1`, `EDIT_LOCKED/2`. Constraint chỉ bảo vệ mapping dư thừa.
Đưa edit quota về Review làm state machine nhỏ hơn, đúng ownership hơn và đơn giản hóa
privacy purge vì owner identity không nằm trong unique key.

## Consequences

- Một buyer vẫn chỉ review một SKU đã mua đúng một lần và sửa tối đa một lần.
- Late delivery không làm kéo dài cửa sổ review hoặc làm consumer retry/DLQ vì
  `expires_at` đã qua `created_at`.
- `CLAIMED` không thể tồn tại không có review qua đường xử lý bình thường; anomaly chỉ
  alert, không tự sửa quota.
- Nếu nghiệp vụ cần review theo từng order line thay vì SKU, phải thêm
  `order_item_id` vào contract và business key; không được suy diễn từ quantity.

## Verification

Test concurrent create, replay order event, second edit, expired create, late
`ORDER_COMPLETED` whose expiry predates ingestion, state constraint và privacy purge
owner identity mà không tạo entitlement trùng.
