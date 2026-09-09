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

# ADR-007 — Pseudonym author/variant, deletion guard và privacy hard purge

## Decision

`reviews.author_display_name` là pseudonym do Review tự tạo khi create, không phải
profile snapshot hay email. Service tạo `Khách hàng #` cộng 12 ký tự Base32 viết hoa
đầu của `HMAC-SHA-256("review-author-alias:v1:" || canonical_lowercase_uuid(owner_user_id))`,
dùng secret `REVIEW_AUTHOR_ALIAS_HMAC_SECRET`; Review chỉ nhận `owner_user_id` qua
trusted Gateway identity. Alias chỉ để hiển thị, không cần unique và không được dùng
authorization. Nó không consume profile-update event. `review_eligibilities.variant_name_snapshot`
là snapshot nullable từ `ORDER_COMPLETED.items[].variant_name`; Review không gọi đồng
bộ Order/Product.

`USER_DELETED` tạo durable `privacy_erasure_requests` với `subject_match_key`:
`HMAC-SHA-256(key=REVIEW_PRIVACY_GUARD_HMAC_SECRET,
"review-privacy-guard:v1:" || canonical_lowercase_uuid(user_id))`, 32 byte ở v1.
Privacy, Order, mọi customer write và notification-producing branch của admin
reply/takedown có thể ghi direct customer ID lấy cùng transaction-scoped subject lock.
Khi guard đã tồn tại, Order chỉ commit receipt rồi suppress entitlement;
upload/create/edit/report bị từ chối. Admin reply/takedown không bị từ chối, nhưng
không được ghi outbox notification chứa `recipient_user_id` của subject. Với caller
không có guard, `owner_user_id = caller` và mọi ownership check giữ nguyên.

Consumer anonymize display name ngay. Tới `hard_delete_after`, privacy worker dùng
cùng lock, null hóa direct customer IDs ở eligibility, report và media asset, rồi
clear raw subject ID trong request. HMAC subject key vẫn được giữ để event tới
muộn/replay không ghi UUID trở lại. Review, rating/content và opaque object key được
giữ lại.

## Rationale

Pseudonym cho public review một nhãn tác giả ổn định mà không đưa tên/email hay PII
trực tiếp vào JWT, HTTP header, Gateway projection hay Kafka consumer mới. Auth không
sở hữu display name và JWT contract cố ý không có profile claim. Thiết kế trước giữ user
ID vô thời hạn và chỉ ghi compliance log, nên không thực hiện được hard-delete promise.
Order từng bỏ variant name dù Review cần historical snapshot, tạo mâu thuẫn với
architecture cấm synchronous Order lookup.

Không có thứ tự liên-topic cho cùng user: Auth key theo `user_id`, còn Order key theo
`order_id`. Giữ raw UUID sau purge để nhận ra input cũ sẽ phá hard-purge promise;
chỉ kiểm tra một guard không có lock vẫn để race cho write commit sau lần privacy quét.
Điều đó cũng áp dụng cho `recipient_user_id` được admin reply/takedown ghi vào
notification outbox: raw owner ID còn trong retention window không có nghĩa là còn
được phép nhận notification. HMAC guard cùng subject lock bảo vệ các tình huống này mà
không đưa profile/name vào Review.

## Consequences

- Thay đổi tên/email profile không đổi review vì chúng không được dùng.
- Cùng account có cùng pseudonym khi HMAC secret ổn định; secret rotation là product
  change có chủ ý và chỉ đổi alias của review tạo sau rotation trong v1.
- Account deletion thay pseudonym bằng `Khách hàng ẩn danh` ngay.
- New `ORDER_COMPLETED` producer gửi variant snapshot khi có; historic order có thể
  gửi null.
- Customer erasure không tự xóa admin/legal audit identity; policy retention đó cần
  compliance ownership riêng.
- `subject_match_key` là dữ liệu pseudonymous và chỉ được dọn sau horizon replay/
  redrive/backfill được Privacy chấp thuận; không log, xuất API hay dùng metric label.
- Auth giữ contract hiện tại: publish `USER_DELETED` trực tiếp sau commit, retry tối
  đa ba lần và có thể mất event. Review không thêm Auth outbox hay synchronous lookup; Auth
  alert/reconciliation/redrive phải đưa event bị mất đến Review trước privacy SLA.

## Verification

Test forged profile/email header is ignored, HMAC pseudonym is deterministic and has
fixed format, no synchronous Order/User lookup or profile consumer exists, account
deletion replaces alias without metric revision, guarded late Order only writes a
receipt, guarded customer write is rejected, a guarded admin reply/takedown persists
without a notification outbox row, concurrent privacy/input serializes, hard-purge
direct IDs but retains HMAC guard, receipt replay no-op và variant snapshot display.
Test Auth redrive of a missed deletion event because direct publish can fail.
