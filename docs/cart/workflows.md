---
doc_id: cart-workflows
service: cart
scope: current
status: implemented
owners:
  - Cart Service team
evidence:
  - go/services/cart-service/internal/service/cart_service.go
  - go/services/cart-service/internal/workers
  - docs/cart/scripts
verified_against_commit: null
---

# Cart Service — Workflows

## Workflow index

| ID | Workflow | Trigger | Actor |
|---|---|---|---|
| `WF-CART-001` | Thêm SKU hoặc điều chỉnh quantity theo delta | HTTP | User |
| `WF-CART-002` | Đọc cart | HTTP | User |
| `WF-CART-003` | Xóa item theo lô | HTTP | User |
| `WF-CART-004` | Dọn SKU sau thanh toán | Kafka `com.ecommerce.order.paid.v1` | Order consumer |
| `WF-CART-005` | Đánh dấu cart abandoned | Schedule | Marker loop |
| `WF-CART-006` | Relay abandoned event | Continuous | Relay loop |
| `WF-CART-007` | Trim stream an toàn | Schedule | Trimmer loop |
| `WF-CART-008` | Xóa giỏ của account đã xóa | Kafka `com.ecommerce.auth.user-deleted.v1` | User-deleted consumer |

Không có workflow rehydrate hoặc Postgres Sync.

## WF-CART-001 — Thêm SKU hoặc điều chỉnh quantity theo delta

1. Gateway inject `user_id`; Cart dựng `user_token`, `cart_id`, bucket và các key cùng `{bNN}`.
2. SKU mới luôn gọi `GetSku`; SKU có validation hết hạn cũng refresh Product. LRU + `singleflight` gộp refresh đồng thời. Chỉ validate catalog status và giá, không gọi Inventory.
3. Cart gọi `upsert_item.lua` với `items`, `meta`, ZSET deadline và idempotency key.
4. Script kiểm tra fingerprint, `max_skus`, delta khác 0, giữ immutable historical fields, đặt status `ACTIVE`, dời deadline 30 ngày và TTL active 37 ngày.
5. Cùng `Idempotency-Key` và payload trả logical result đã lưu.

Không có Kafka event cho add/update. Product hoặc Redis lỗi thì cart không đổi.

## WF-CART-002 — Đọc cart

1. `HGETALL items`; hash không tồn tại trả cart rỗng, không fallback store khác.
2. Nếu có item, gọi `GetBatchSkus` với deadline 200ms.
3. Response giữ `unit_price_at_add`, phủ `current_price` và catalog saleability hiện tại; không có quantity/stock availability.
4. Product lỗi/breaker mở trả `200` với `catalog_freshness = DEGRADED` và `current_price` có thể null.

GET không gia hạn TTL, không chạm ZSET, không cập nhật snapshot Redis và không phát
event. Catalog validation của write có độ cũ bị chặn trên bởi validation TTL;
Checkout xác thực catalog/giá và lấy allocation preview; Order mới gọi Inventory Reserve để xác nhận stock.

## WF-CART-003 — Xóa item theo lô

1. User gửi tối đa 100 `sku_ids` kèm `Idempotency-Key`.
2. Cart gọi `remove_items.lua` với `touch_activity = 1`.
3. Nếu còn item, script reset `ACTIVE`, dời deadline và TTL; nếu rỗng thì xóa `items`, `meta` và `ZREM` member scheduler.
4. Trả `deleted_count`; xóa SKU không tồn tại là idempotent.

Không phát `CART_ITEM_DELETED` hoặc `CART_CLEARED` vì chưa có consumer.

## WF-CART-004 — Dọn SKU sau `com.ecommerce.order.paid.v1`

1. Consumer validate CloudEvents envelope, canonical string `order_id`, `user_id`,
   unique `sku_ids[]` và dựng bucket từ user.
2. Gọi `remove_items.lua` với `touch_activity = 0`, không truyền HTTP idempotency key.
3. Commit Kafka offset sau khi script thành công. Redelivery an toàn vì `HDEL` theo SKU không có Cart event và không gia hạn activity.

Không phát `CART_CHECKED_OUT`. Nếu toàn bộ SKU biến mất, cart Redis bị xóa.

## WF-CART-005 — Đánh dấu cart abandoned

Marker loop round-robin qua 64 ZSET `abandonment-index`. Với mỗi member có score `<= now`, nó gọi `mark_abandoned.lua` bằng items/meta/index/stream cùng bucket.

Script xác minh deadline vẫn đúng và cart `ACTIVE`, tăng `abandonment_cycle`, chuyển `ABANDONED`, gỡ ZSET member, đổi TTL sang 7 ngày và `XADD CART_ABANDONED` nguyên tử. Score cũ hoặc cart đã expire chỉ bị bỏ qua/dọn ZSET, không phát event.

Race user write/marker được Redis tuần tự hóa: write trước làm deadline cũ stale; marker trước có thể phát abandoned rồi write kế tiếp tái kích hoạt cart.

## WF-CART-006 — Relay `CART_ABANDONED` sang Kafka

Một relay loop xử lý 64 stream theo round-robin. Với từng stream, nó `XAUTOCLAIM` pending entry cũ nhất trước, sau đó `XREADGROUP ... >` entry mới. Mỗi entry được produce Kafka với key `cart_id` và CloudEvent ID xác định từ `outbox_key + stream_id`. Chỉ `XACK` sau Kafka acknowledgement.

Không dùng một `XREADGROUP` multi-stream vì streams thuộc Cluster slot khác nhau. Khi scale, bucket ownership được chia giữa deployment; mỗi stream chỉ có một active reader. Notification dedupe CloudEvent ID vì Kafka delivery at-least-once.

## WF-CART-007 — Trim Redis Stream

Trimmer loop round-robin 64 stream. Nó bỏ qua stream không có consumer group hoặc có pending entry chưa an toàn. Khi có mốc an toàn, gọi `XTRIM MINID` chỉ trên stream đó. `XACK` không giải phóng RAM; trimmer phải giữ lại pending entry cũ nhất.

## WF-CART-008 — Xóa giỏ khi account bị xóa

1. Consumer group `cart-auth-consumer` đọc `t.auth.events`; event không phải `com.ecommerce.auth.user-deleted.v1` được
   bỏ qua và commit.
2. Consumer validate envelope (`specversion`, `source=/services/auth`, `id`, `data.user_id` UUID, `data.deleted_at`)
   và dựng bucket từ `user_id`; không nhận `cart_id` từ event.
3. Gọi `purge_cart.lua`: `DEL items`, `DEL meta`, `ZREM` member khỏi `abandonment-index` trong một invocation.
4. Commit Kafka offset sau khi script thành công. Redelivery hoặc user chưa từng có giỏ chỉ trả `purged = false`.

Entry `CART_ABANDONED` đã nằm trong outbox không bị xóa và vẫn được relay (Notification bỏ qua account đã xóa). Lỗi Redis
trả error để Kafka giao lại. Không phát event ([ADR-014](./decisions/ADR-014-purge-cart-on-user-deleted.md)).

## Yêu cầu test chung

Test script atomicity, cross-slot key builder, duplicate idempotency key, Product refresh coalescing, cart expiry, marker deadline stale, marker/write race, crash trước `XACK`, `XAUTOCLAIM`, Kafka duplicate, Notification dedupe, và purge `USER_DELETED` (idempotent, không đụng cart user khác, giữ outbox đang chờ).
