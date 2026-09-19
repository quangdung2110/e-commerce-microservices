---
doc_id: checkout-adr-007
service: checkout
status: accepted
date: null
decision_owners:
  - Checkout Service team
supersedes: null
evidence:
  - docs/checkout/data.md
  - docs/checkout/workflows.md
---

# ADR-007 — Payload cache và idempotent response cache cho failed submit recovery

## Trạng thái

`Accepted` theo thiết kế cũ. Chưa có implementation evidence.

## Bối cảnh

Để tránh trùng đơn, Checkout chuyển session sang `SUBMITTED` **trước** khi gọi
Order Service và không bao giờ rollback. Vì vậy có hai tình huống mất kết nối cần
xử lý: (1) Order đã làm xong (thành công hoặc phát hiện conflict) nhưng client mất
kết nối trước khi nhận response — nếu user submit lại có thể tạo đơn mới hoặc
phải tính toán lại; (2) session đã `SUBMITTED` nhưng lời gọi Order không tới nơi
do rớt mạng — user bị stuck: token đã dùng, order chưa tạo.

## Quyết định

- **`checkout:response:{user_id}:{token}`** (TTL 20 phút): lưu kết quả Submit khi
  hoàn tất. Nhánh success lưu `{order_id, client_secret, publishable_key}`; nhánh
  conflict lưu `{error_code: PROMOTION_RECALCULATION_NEEDED, action, dropped_promotions[],
  proposal_checkout_data}` sau khi đã gọi Promotion re-evaluate. Client retry cùng
  token → Lua `-2` → trả lại response này, tránh tạo đơn mới và tránh tính lại.
- **`checkout:payload:{user_id}:{token}`** (TTL 20 phút): lưu payload đã chuẩn bị
  gửi Order ngay sau Lua OK, trước khi gọi Order. Client retry khi chưa có response
  cache → lấy payload này gọi lại Order mà không bị ảnh hưởng bởi trạng thái đã
  chuyển trên session. `DEL` khi success; giữ khi Order lỗi; hết TTL → `410 Gone`.
- Order Service nhận `idempotency_key = checkout_token` và chỉ tạo đúng một order
  logic cho mỗi token (Temporal WorkflowID, Redis fast path, FSM guard) nên
  Checkout retry bao nhiêu lần cũng an toàn.
- Khi Order timeout, Checkout retry nội bộ tối đa 3 lần rồi trả `503`; App tự
  retry và đi nhánh recovery.

> **Ghi chú 2026-09-19:** nhánh success của response cache không còn lưu
> `client_secret`/`publishable_key`, chỉ lưu `{order_id, payment_attempt_number,
> payment_transaction_id}`; client data lấy tươi từ Order ở mỗi lần trả
> ([ADR-010](./ADR-010-relay-payment-client-data-no-secret-cache.md)). Payload cache
> và nhánh conflict giữ nguyên.

## Phương án đã cân nhắc

- **Rollback token về `PREVIEW_READY` khi Order timeout:** đơn giản cho client
  nhưng có thể tạo hai order nếu Order thực ra đã nhận request; vi phạm one-time
  use; không chọn.
- **Chỉ dựa vào Order idempotency, không lưu payload:** Checkout không có gì để
  gửi lại vì session đã `SUBMITTED` và không được tính lại; không chọn.
- **Response cache + payload cache (chọn).**

## Hệ quả

- Tích cực: client nhận đúng `order_id` + `client_secret` + `publishable_key` dù
  gọi bao nhiêu lần; user không bị stuck sau rớt mạng; conflict không phải tính
  lại.
- Tiêu cực: thêm hai key Redis/token; payload chứa PII/tiền tạm thời trong Redis;
  hết 20 phút mà chưa recovery thì user phải Preview lại (`410`).
- Constraint: TTL payload = TTL response = 20 phút; mọi nhánh success phải `DEL`
  payload.

## Ảnh hưởng migration

Chưa có code. Frontend phải tự retry sau `503` và xử lý `410` bằng cách quay lại
Preview.

## Xác minh

Khi có code: retry sau success trả cùng `order_id`; retry sau conflict trả cùng
409; Order down → retry dùng payload; payload hết TTL → `410`; Order nhận lại
cùng token trả order cũ.
