---
doc_id: review-adr-007
service: review
status: accepted
date: 2026-09-05
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
  - docs/order/order_events.md
---

# ADR-007 — Snapshot author/variant và privacy hard purge

## Decision

`reviews.author_display_name` là snapshot từ token khi create và không consume
profile-update event. `review_eligibilities.variant_name_snapshot` là snapshot nullable
từ `ORDER_COMPLETED.items[].variant_name`; Review không gọi đồng bộ Order/Product.

`USER_DELETED` tạo durable `privacy_erasure_requests`. Consumer anonymize display
name ngay. Tới `hard_delete_after`, privacy worker null hóa direct customer IDs ở
eligibility, report và media asset, rồi clear subject ID trong request. Review,
rating/content và opaque object key được giữ lại.

## Rationale

Snapshot tránh dependency read/write profile trên hot path. Thiết kế trước giữ user ID
vô thời hạn và chỉ ghi compliance log, nên không thực hiện được hard-delete promise.
Order từng bỏ variant name dù Review cần historical snapshot, tạo mâu thuẫn với
architecture cấm synchronous Order lookup.

## Consequences

- Người dùng đổi display name không đổi review cũ; account deletion thì đổi ngay.
- New `ORDER_COMPLETED` producer gửi variant snapshot khi có; historic order có thể
  gửi null.
- Customer erasure không tự xóa admin/legal audit identity; policy retention đó cần
  compliance ownership riêng.

## Verification

Test no profile sync, no synchronous Order lookup, author anonymization without metric
revision, hard-purge direct IDs, receipt replay no-op và variant snapshot display.
