---
title: ADR-014 Purge the cart when the account is deleted
status: accepted
date: 2026-10-09
supersedes: —
---

# ADR-014: Purge the cart when the account is deleted

## Context

Cart giữ `user_id` (trong `meta` và payload `CART_ABANDONED`) cùng danh sách SKU, số lượng và
giá lúc thêm của user: dữ liệu hành vi gắn với một định danh người dùng. Auth phát
`USER_DELETED` trực tiếp trên `t.auth.events` cho mọi service sở hữu dữ liệu theo user
(User, Notification, Review, Chat), nhưng Cart không nằm trong danh sách. Hệ quả: giỏ của
account đã xóa vẫn tồn tại tới 37 ngày (TTL active) hoặc 7 ngày (đã abandoned), và tiếp tục
được marker quét; Cart là một PII owner không tuân thủ yêu cầu xóa. Báo cáo audit
2026-10-07 ghi nhận lỗ hổng này (mục C12).

## Decision

- Cart consume `com.ecommerce.auth.user-deleted.v1` từ `t.auth.events` bằng consumer group riêng
  `cart-auth-consumer`, độc lập với group của User, Notification, Review và Chat. Mọi event khác
  trên topic (ví dụ `USER_VERIFIED_SUCCESS`) bị bỏ qua.
- Handler dựng identity từ `data.user_id` (không nhận `cart_id` từ event) rồi gọi
  `purge_cart.lua`: `DEL items`, `DEL meta` và `ZREM` member khỏi `abandonment-index` trong một
  Lua invocation cùng hash tag `{bNN}`. Script idempotent: user chưa từng có giỏ hoặc event giao
  lại chỉ trả `purged = false`.
- Lỗi Redis trả error để offset không commit và message được giao lại; event không parse được
  hoặc thiếu trường bắt buộc bị ghi log và bỏ qua, cùng nguyên tắc với consumer `order.paid`.
  Không dùng DLQ cho consumer này.
- Cart không phát event nào để đáp lại và không giữ bản ghi receipt: tính idempotent đến từ chính
  script.
- Entry `CART_ABANDONED` đã nằm trong outbox stream không bị xóa: nó chỉ mang `cart_id` và
  `user_id` nội bộ, được relay trong vài phút, và Notification bỏ qua giao dịch gửi cho account
  đã xóa (`no_recipient`).
- Record idempotency HTTP (chỉ chứa `sku_id`, `quantity`, `updated_at`, khóa bằng hash) không bị
  xóa chủ động vì không thể liệt kê rẻ; chúng tự hết hạn sau `cart.idempotency_ttl` (24 giờ).

## Consequences

- Giỏ hàng của account đã xóa biến mất ngay khi Cart nhận event, thay vì tới 37 ngày sau.
- Auth publish trực tiếp sau commit, không có outbox ([Auth ADR-005](../../auth/decisions/)); nếu
  event bị mất thì giỏ vẫn tự hết hạn theo TTL như trước, nên rủi ro tồi nhất không tệ hơn hiện trạng.
- Một request ghi còn token truy cập hợp lệ (tối đa vài phút) có thể tạo lại giỏ sau lần purge. Gateway
  revoke phiên trước khi Auth phát `USER_DELETED`, nên cửa sổ này chỉ là race hiếm; giỏ tạo lại vẫn
  tuân thủ TTL và bị loại bỏ khi hết hạn.
- Cart thêm một consumer Kafka, một script Lua, một nhóm metric (`cart_user_deleted_outcomes_total`)
  và ba khóa cấu hình (`kafka.topic_auth_events`, `kafka.consumer_group_auth`,
  `worker.user_deleted_consumer.enabled`).
- Consumer group mới đọc từ `oldest` (mặc định `kafka.initial_offset`) nên replay tối đa 7 ngày
  `t.auth.events`; vô hại vì purge idempotent và user đã xóa không thể có giỏ mới hợp lệ.

## Verification

- `store_test.go`: purge xóa items/meta/ZSET, không đụng cart user khác, idempotent, giữ nguyên entry
  outbox đang chờ; chạy được cả trên Redis thật (`CART_TEST_REDIS_ADDR`).
- `userdeleted/consumer_test.go`: đúng identity, bỏ qua event khác type và message hỏng, trả lỗi Redis
  để redeliver.
- `config_test.go`: cấu hình đóng gói bật consumer; thiếu topic/group khi bật bị từ chối.
