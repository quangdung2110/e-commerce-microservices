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

# ADR-007 — Pseudonym author/variant và privacy hard purge

## Decision

`reviews.author_display_name` là pseudonym do Review tự tạo khi create, không phải
profile snapshot hay email. Service tạo `Khách hàng #` cộng 12 ký tự Base32 viết hoa
đầu của `HMAC-SHA-256("review-author-alias:v1:" || canonical_lowercase_uuid(owner_user_id))`,
dùng secret `REVIEW_AUTHOR_ALIAS_HMAC_SECRET`; Review chỉ nhận `owner_user_id` qua
trusted Gateway identity. Alias chỉ để hiển thị, không cần unique và không được dùng
authorization. Nó không consume profile-update event. `review_eligibilities.variant_name_snapshot`
là snapshot nullable từ `ORDER_COMPLETED.items[].variant_name`; Review không gọi đồng
bộ Order/Product.

`USER_DELETED` tạo durable `privacy_erasure_requests`. Consumer anonymize display
name ngay. Tới `hard_delete_after`, privacy worker null hóa direct customer IDs ở
eligibility, report và media asset, rồi clear subject ID trong request. Review,
rating/content và opaque object key được giữ lại.

## Rationale

Pseudonym cho public review một nhãn tác giả ổn định mà không đưa tên/email hay PII
trực tiếp vào JWT, HTTP header, Gateway projection hay Kafka consumer mới. Auth không
sở hữu display name và JWT contract cố ý không có profile claim. Thiết kế trước giữ user
ID vô thời hạn và chỉ ghi compliance log, nên không thực hiện được hard-delete promise.
Order từng bỏ variant name dù Review cần historical snapshot, tạo mâu thuẫn với
architecture cấm synchronous Order lookup.

## Consequences

- Thay đổi tên/email profile không đổi review vì chúng không được dùng.
- Cùng account có cùng pseudonym khi HMAC secret ổn định; secret rotation là product
  change có chủ ý và chỉ đổi alias của review tạo sau rotation trong v1.
- Account deletion thay pseudonym bằng `Khách hàng ẩn danh` ngay.
- New `ORDER_COMPLETED` producer gửi variant snapshot khi có; historic order có thể
  gửi null.
- Customer erasure không tự xóa admin/legal audit identity; policy retention đó cần
  compliance ownership riêng.

## Verification

Test forged profile/email header is ignored, HMAC pseudonym is deterministic and has
fixed format, no synchronous Order/User lookup or profile consumer exists, account
deletion replaces alias without metric revision, hard-purge direct IDs, receipt replay
no-op và variant snapshot display.
