---
title: Checkout Workflows
status: proposed
last_updated: 2026-09-20
owners:
  - domain: checkout
    team: commerce-platform
    contact: commerce-platform@example.com
review_cycle_days: 30
source_of_truth: false
evidence:
  - ../../contracts/http/checkout/v1/openapi.yaml
  - ../../contracts/http/order/internal/v1/openapi.yaml
tags: [checkout, workflows]
---

# Checkout Workflows

## WF-01 Preview

1. Gateway xác thực và chuyển trusted user context.
2. Checkout validate body và `Idempotency-Key`.
3. Nếu preview idempotency record còn hạn, replay response đã lưu.
4. Lấy Cart checkout snapshot. Cart rỗng trả `422 CART_EMPTY`; canonicalize danh sách thành `items_hash`.
5. Song song lấy address từ User và SKU batch từ Product.
6. Đối chiếu Product `product_id` với Cart, chuẩn hóa item/prices/categories, sort theo UTF-8 `sku_id` và đặt `line_reference=sku_id`; thiếu, mismatch hoặc unsaleable SKU trả conflict.
7. Gọi Inventory whole-order allocation preview. Bất kỳ timeout/5xx nào trả dependency problem; `REJECT_ALL` trả stock problem.
8. Nếu có `promotion_id`, gọi Promotion với context trusted. Không tự fallback discount về 0 khi call lỗi.
9. Tính totals integer VND, đặt `pricing_expires_at` không vượt TTL/revision của dependency.
10. Sinh UUIDv4 bằng CSPRNG; Redis key chỉ dùng HMAC token hash. Token cần cho replay/Order recovery chỉ được lưu trong encrypted value.
11. Lua transaction CAS active pointer với `previous_checkout_token`, rồi mới lưu session, pointer và idempotency replay. Chỉ một successor thắng; CAS fail trả `409 ACTIVE_CHECKOUT_CHANGED` và không để session mồ côi. Token cũ chỉ chuyển `SUPERSEDED` khi còn `PREVIEWED`.
12. Trả preview với `Cache-Control: private, no-store`.

## WF-02 Submit lần đầu

1. Validate auth và token format, hash token.
2. Lua/CAS xác minh owner và state.
3. Nếu `PREVIEWED`, kiểm tra expiry rồi atomically:
   - chuyển `SUBMITTING`;
   - đặt Order idempotency key bằng checkout token;
   - materialize và lưu submit payload bất biến;
   - gia hạn TTL recovery 30 phút.
4. Revalidate cart `items_hash`, Product pricing/catalog revision và Promotion revision. Inventory plan vẫn advisory; Order sẽ revalidate đúng plan khi Reserve.
5. Có thay đổi material thì lưu `CONFLICT` cùng `changed_fields`; client phải preview lại.
6. Không đổi thì gọi Order bằng payload/idempotency key đã lưu.
7. Order success: lưu `SUCCEEDED` và `order_id` 24 giờ; relay Payment client data trực tiếp nhưng không cache secret.
8. Order business conflict: lưu `CONFLICT` 30 phút.
9. Transport timeout/connection reset: không kết luận thất bại; chuyển `RETRYABLE` và chạy recovery.

## WF-03 Submit trùng hoặc đồng thời

- `SUCCEEDED`: gọi Order status để relay Payment client data mới rồi trả `200` với cùng `order_id`; không replay secret từ Redis.
- `CONFLICT`/`FAILED`: replay problem terminal.
- `SUBMITTING`: trả `202` với status endpoint và `Retry-After`.
- `RETRYABLE`: một request có thể CAS claim recovery; tất cả đều dùng cùng payload/idempotency key.
- `SUPERSEDED`/`EXPIRED` chưa từng submit: trả `410` và yêu cầu preview lại.

Không request nào giữ Redis lock trong lúc chờ network. Safety đến từ CAS cục bộ cộng Order idempotency.
Nếu `SUBMITTING` quá configurable owner timeout (target ban đầu 30 giây), status/reconciler
query Order trước rồi mới CAS takeover; state không quay lại `PREVIEWED`.

## WF-04 Recovery sau kết quả không rõ

1. Đọc local result.
2. Nếu chưa terminal nhưng có checkout token/idempotency key, gọi `GET /internal/v1/checkout-order-submissions/{checkout_token}`.
3. Map Order state: `PAYMENT_READY` -> lưu `SUCCEEDED`; `PREPARING` -> trả `202`;
   `RECALCULATION_REQUIRED` -> lưu `CONFLICT`; `FAILED` -> lưu terminal problem.
4. Nếu Order trả `404` và snapshot/payload vẫn retryable, retry POST với cùng payload và token/key.
5. Nếu chưa thể phân giải, trả `202 SUBMISSION_RESULT_UNKNOWN`, không trả `410` và không hướng dẫn tạo token mới có nguy cơ double order.

## WF-05 Preview mới khi submit cũ đang chạy

Preview mới được phép tạo để người dùng tiếp tục UX, nhưng không supersede/xóa token đang `SUBMITTING`, `RETRYABLE` hoặc terminal. Active pointer có thể trỏ token mới; status của token cũ vẫn truy vấn trực tiếp được.

## WF-06 Promotion thay đổi

Khi Promotion báo revision khác hoặc promotion không còn hợp lệ, Checkout lưu `CONFLICT` với `PROMOTION_CHANGED`. Vì contract hiện chỉ có một `promotion_id`, response không mô tả drop một phần của promotion stack.
