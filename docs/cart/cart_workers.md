# Cart Service - Danh sách Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](cart_workflow.md)
*   [Background Workers & Jobs](cart_workers.md)
*   [Caching Schema (Redis-First)](cart_caching_schema.md)
*   [Database Schema (Cold Store)](cart_db_schema.md)
*   [Outbox Pattern (Redis Streams)](cart_outbox_pattern.md)
*   [Idempotency Pattern](cart_idempotency.md)
*   [Events Structure](cart_events.md)

---
Vì hệ thống Cart áp dụng phong cách **CQRS / Event-Driven (Cơ sở dữ liệu sau cùng)** qua [Outbox Pattern](cart_outbox_pattern.md), các tác vụ ghi lưu tĩnh và dọn dẹp đều được thực hiện ngầm.

Dưới đây là sơ đồ liệt kê các Worker và Script cronjob tự động trong Service.

> **Lưu ý Kiến trúc (Cập nhật Mới):** Đã VĨNH VIỄN XÓA BỎ `Worker #7: Product Snapshot Sync Worker`. Hệ thống từ nay **KHÔNG THU THẬP Event** Product qua Kafka (Push Model) để quét lưu giá vào Reverse Index (nguyên nhân lớn nhất gây Write Storm nghẽn máy chủ). Việc đồng bộ giá được dời hoàn toàn sang thời điểm User Chủ Động thao tác theo cấu trúc **Pull (gRPC Multi-Get)** với cơ chế Circuit Breaker an toàn.

---

## 1. Outbox Relay (Producer Worker)
**Loại hình:** Hệ thống tiến trình chạy liên tục (Long-running daemon) đi kèm bên trong code Go của Cart Service (Hoặc chạy như một Sidecar/CDC độc lập).

**Sứ mệnh:** Chuyển phát nhanh (Forwarding) Event từ Redis đệm lên Kafka Cluster. Xem kiến trúc Outbox tại [Redis Streams Outbox Pattern](cart_outbox_pattern.md).

**Chi tiết luồng hoạt động:**
1. Khởi chạy các luồng Goroutine liên tục (`XREADGROUP GROUP relay_group consumer_1 BLOCK 5000 COUNT 50 STREAMS cart_outbox_stream_{shard_id} >`) để kéo dữ liệu mới từ Stream.
2. Với mỗi block Event nhặt được (Ví dụ: `CART_ITEM_ADDED`, `CART_ITEM_DELETED`, `CART_CLEARED`).
3. Bổ sung các trường Header cho Kafka message ([xem Events Header](cart_events.md#1-cấu-trúc-message-header-chung)): `event_id` (**Bắt buộc** lấy trực tiếp chuỗi gốc Redis Stream ID làm Key hoặc băm nội dung ID này bằng md5/sha, tuyệt đối KHÔNG tự sinh UUID random để bảo vệ tính Idempotency nếu Relay lỡ sập-restart), `timestamp` (ISO 8601 UTC), `source = "cart-service"`.
4. Thực hiện `Kafka.Produce` ném vào Topic chung `t.cart.events` (Key phân vùng = `cart_id` để đảm bảo event cùng giỏ đi tuần tự qua cùng partition).
5. Điểm trọng yếu: Chỉ sau khi Kafka `ACK` xác nhận đã ghi (cấu hình `acks=all`), Worker gọi lệnh `XACK` để đánh dấu message đã xử lý trong Consumer Group.

> **Chú ý:** `XACK` **chỉ đánh dấu message đã xử lý** trong Consumer Group, **KHÔNG** xóa message khỏi Stream Memory. Việc giải phóng RAM thực sự do [Stream Trimmer](#5-stream-trimmer-background-goroutine) đảm nhận bằng cách xóa theo ID. Xem [Outbox Pattern - Stream Memory Management](cart_outbox_pattern.md#22-stream-memory-management).

6. Nếu `Kafka.Produce` gặp lỗi: Retry với Exponential Backoff (Không `XACK` event đó, Redis sẽ giam nó đợi các lứa check Pending sau lại thử tiếp).

**Xử lý Pending Messages (Crash Recovery):**
Khi Worker crash sau khi `XREADGROUP` nhưng trước khi `XACK`, message ở trạng thái **Pending** (bị giữ vĩnh viễn).
- Mỗi 30 giây, một Goroutine phụ chạy `XAUTOCLAIM cart_outbox_stream_{shard_id} relay_group consumer_1 60000 0-0 COUNT 100` để nhặt lại message Pending quá 60s.
- Nếu một message bị retry quá 5 lần (kiểm tra qua `XPENDING` delivery count), chuyển vào Dead Letter Stream `cart_outbox_dlq` và ghi log cảnh báo.

---

## 2. Postgres Sync (Consumer Worker)
**Loại hình:** Kafka Consumer Worker Node (Thuộc hệ thống nội bộ của Cart DB tier).

**Sứ mệnh:** Ghi dữ liệu đồng bộ xuống [Cold Storage (Database Schema)](cart_db_schema.md) một cách tĩnh mịch, cam kết không mất mát dù có đến chậm (Eventual Consistency).

**Chi tiết luồng hoạt động:**
1. Consumer Listen (Lắng nghe) Kafka topic `t.cart.events` (Consumer Group: `cart-db-sync`).
2. Áp dụng nghiêm ngặt chiến lược luỹ đẳng thời gian (Timestamp Microseconds - [Cart Idempotency](cart_idempotency.md)).
3. Xử lý chia rẽ dựa trên Event Type:
   * **`CART_ITEM_DELETED`**: Kiểm tra trạng thái tồn tại của item trong bảng `cart_items`. 
     - Nếu đã tồn tại: Gọi SQL `DELETE FROM cart_items WHERE item_id = ? AND (product_snapshot->>'updated_at')::bigint < ?` (chỉ xóa nếu `deleted_at` > `updated_at` hiện tại trong DB, chặn out-of-order).
     - Nếu KHÔNG tồn tại (Lỗi Ghost Item - Event Delete đến trước Add): Insert ngay lập tức một bản ghi Tombstone với `item_id = ?`, `is_deleted = true`, `product_snapshot = {"updated_at": <event.deleted_at>}` và `tombstone_at = event.deleted_at`.
   * **`CART_ITEM_ADDED` / `CART_ITEM_UPDATED`**: Gọi SQL `ON CONFLICT DO UPDATE` ghi dữ liệu giỏ xuống bảng `cart_items` (kèm cập nhật `carts` master). Chỉ update item nếu:
     - Biến thời gian `event.updated_at > db.product_snapshot->>'updated_at'`.
     - VÀ nếu bản ghi trong DB đang là Tombstone (`is_deleted = true`), kiểm tra nếu `event.updated_at < db.tombstone_at` thì **BỎ QUA (IGNORE)** (Vì đây là event tạo ngầm cũ kĩ đến chậm bị event Delete đi trước khóa mõm rồi).
     Đồng thời tiến hành re-activate cart: set `carts.status = 'ACTIVE'` và cập nhật `carts.updated_at` theo event time mới nhất NHƯNG phải áp dụng Guard Transition (`event.updated_at >= carts.last_event_time_ms`). Tuyệt đối không bật ACTIVE nếu đây là một event cũ rích đến trễ so với mốc Checkout/Clear.
   * **`CART_CLEARED`**: Chỉ đổi trạng thái khi event mới hơn hoặc bằng mốc hiện tại của cart: `event.cleared_at >= carts.last_event_time_ms`. Khi thỏa điều kiện, set `status = 'DELETED'`, cập nhật `last_event_time_ms` và `updated_at = NOW()`. **Không xóa `cart_items`** ở đây (các event `CART_ITEM_DELETED` riêng lẻ sẽ xử lý từng item).
   * **`CART_CHECKED_OUT`**: Áp dụng guard tương tự `CART_CLEARED`: chỉ set `status = 'CHECKED_OUT'` nếu `event.checked_out_at >= carts.last_event_time_ms`; nếu cũ hơn thì **bỏ qua**. Kích hoạt từ [Order Event Consumer Worker](#6-order-event-consumer-worker-kafka-consumer) sau khi xử lý `ORDER_PAID`. **Không xóa `cart_items`** ở đây (các event `CART_ITEM_DELETED` riêng lẻ đã xử lý từng item trước đó).

> **Nguyên tắc bắt buộc:** Mọi update trạng thái trên bảng `carts` phải có điều kiện `last_event_time_ms` để chống out-of-order, không được update mù theo kiểu `WHERE cart_id = ?`.

**Scaling Strategy:**

Kafka topic `t.cart.events` nên chuẩn bị **tối thiểu 12 partitions** (scale theo số instance). Consumer group `cart-db-sync` cho phép chạy **multi-instance** (N consumers ≤ N partitions):

| Chế độ | Số consumers | Partition/consumer |
|:---|:---:|:---:|
| Bình thường | 2 pods | 6 partitions/pod |
| Flash Sale | 6 pods (HPA scale up) | 2 partitions/pod |

**Auto-scaling trigger:**
- Prometheus metric: `kafka_consumer_group_lag{group="cart-db-sync"}`
- Threshold: lag > 1000 messages sustained 2 phút → scale up
- Cool-down: lag < 100 sustained 5 phút → scale down

**Batch Processing (giảm DB round-trips):**
- Hiện tại: xử lý 1 event/lần
- Tối ưu: Poll batch 50 events → Group by `cart_id` → Batch upsert (1 SQL statement thay vì 50 statements riêng lẻ)
- Lợi ích: Giảm connection overhead, tận dụng PostgreSQL batch INSERT/UPDATE hiệu quả hơn

---

## 3. Abandoned Cart Marker (Batch Cronjob)
**Loại hình:** Lịch biểu chạy giữa đêm hàng ngày (Ví dụ: 2:00 AM UTC).

**Sứ mệnh:** Đánh dấu các giỏ hàng bỏ hoang (không tương tác quá 30 ngày) để phục vụ Marketing re-engagement và phân loại dữ liệu. Cập nhật cột `status` trên bảng `carts` từ `ACTIVE` → `ABANDONED`. Xem [Database Schema - Trạng thái](cart_db_schema.md#21-bảng-carts).

**Chi tiết luồng hoạt động:**
1. Truy vấn các giỏ hàng không tương tác quá 30 ngày:
   ```sql
   UPDATE carts 
   SET status = 'ABANDONED', updated_at = NOW()
   WHERE updated_at < NOW() - INTERVAL '30 days' 
     AND status = 'ACTIVE'
   ```
2. **Ngay tại thời điểm chuyển trạng thái**, Cronjob này sẽ publish một sự kiện `CART_ABANDONED` lên Kafka (topic `t.cart.abandoned`). Promotion Service sẽ "hook" (lắng nghe) sự kiện này để xử lý nghiệp vụ khuyến mãi, thông báo (ví dụ gửi email) vào thời điểm thích hợp tùy theo thiết kế của rào cản nghiệp vụ. Trường `subtotal` trong bảng `carts` phục vụ phân tích giá trị giỏ hàng bị bỏ.

---

## 4. Data Archival Cleaner (Batch Cronjob)
**Loại hình:** Lịch biểu chạy hàng tuần (Ví dụ: Chủ Nhật 3:00 AM UTC).

**Sứ mệnh:** Dọn sạch dữ liệu rác khỏi PostgreSQL theo chính sách TTL. Xem [Database Schema — Archival Policy](cart_db_schema.md#4-quản-lý-không-gian-archival--cold-data-policy).

**Chi tiết luồng hoạt động:**
1. **Giỏ `DELETED` / `CHECKED_OUT` quá 3 tháng:**
   ```sql
   -- Bước 1: Tìm giỏ hàng đủ điều kiện xóa
   SELECT cart_id FROM carts 
   WHERE updated_at < NOW() - INTERVAL '3 months' 
     AND status IN ('DELETED', 'CHECKED_OUT')
   ```  
   → Thực hiện Hard Delete trực tiếp (Sửa lỗi Orphan Data bằng SQL Transaction):
   ```sql
   BEGIN;
   -- Bước 2: Xóa cart_items TRƯỚC (Không có FK constraint thực sự — phải xóa thủ công bằng lock)
   DELETE FROM cart_items WHERE cart_id IN (?);
   -- Bước 3: Xóa carts SAU
   DELETE FROM carts WHERE cart_id IN (?);
   COMMIT;
   ```

2. **Giỏ `ABANDONED` quá 6 tháng (Cold Data cực rác):**
   ```sql
   SELECT cart_id FROM carts 
   WHERE updated_at < NOW() - INTERVAL '6 months' 
     AND status IN ('ABANDONED')
   ```
   → Thực hiện Hard Delete trực tiếp (Vẫn phải bọc trong `BEGIN; ... COMMIT;` transaction như trên để tránh Orphan Data nếu có event lọt vào giữa chừng).

> **Quan trọng:** KHÔNG dùng `CASCADE DELETE` vì bảng `cart_items` chỉ có FK ảo (Application-level reference). Phải xóa `cart_items` trước rồi mới xóa `carts` trong cùng 1 Transaction. Xem [DB Schema — Workflow Đồng Bộ](cart_db_schema.md#3-workflow-đồng-bộ-postgresql-là-rdbms-phụ-thuộc).

---

## 5. Stream Trimmer (Background Goroutine)
**Loại hình:** Goroutine chạy định kỳ bên trong process của Cart Service (cùng process với Outbox Relay).

**Sứ mệnh:** Giải phóng RAM Redis bằng cách cắt bớt (Trim) các message đã được `XACK` khỏi Outbox Stream. Xem [Outbox Pattern — Stream Memory Management](cart_outbox_pattern.md#22-stream-memory-management).

**Chi tiết luồng hoạt động:**
1. Mỗi 5 phút, duyệt qua tất cả `cart_outbox_stream_{shard_id}`.
2. Gọi `XINFO GROUPS cart_outbox_stream_{shard_id}` để lấy ID thấp nhất của Last Delivered ID trong các Consumer Groups.
3. Gọi `XTRIM cart_outbox_stream_{shard_id} MINID ~ <lowest_delivered_id>` để dọn các message đã được các group tiêu thụ an toàn, ngăn mất mát dữ liệu do trim quá tay.
4. Log metric số message đã trim để monitor.

---

## 6. Order Event Consumer Worker (Kafka Consumer)
**Loại hình:** Kafka Consumer chạy liên tục.
**Consumer Group:** `cart-order-consumer`
**Consume Topic:** `t.order.events`

**Sứ mệnh:** Lắng nghe event `ORDER_PAID` từ Order Service → tự động xóa các SKU đã thanh toán khỏi giỏ hàng của user, đảm bảo UX sạch sẽ sau khi mua hàng thành công.

**Cross-Service References:**
*   [Order Events - ORDER_PAID](../order/order_events.md) — Chứa `cart_id` và danh sách `sku_ids` đã checkout (hỗ trợ đọc `cart_item_ids` cho tương thích ngược)
*   [Cart Caching Schema - Lua Scripts](cart_caching_schema.md) — Dùng Lua Batch Delete

**Chi tiết luồng hoạt động:**
1. **Consume** event `ORDER_PAID` từ `t.order.events` (filter `event_type = 'ORDER_PAID'`).
2. **Extract** `user_id` và mảng `cart_item_ids` (VD: `["cart:user:999:sku-001"]`) từ payload của Order.
3. **Parse SKU ID:** Lặp qua mảng `cart_item_ids`, cắt chuỗi từ phải sang trái (dựa vào dấu `:`) để tách lấy `sku_id` và phần còn lại làm `cart_id`. Mọi thao tác sử dụng exacly mảng ID do Order cung cấp để đồng bộ.
4. **Idempotency Check:** Gọi Lua Script Batch Delete (xem [Batch Delete](cart_caching_schema.md#33-xóa-item-batch-delete-sau-checkout-hoặc-remove)). 
   - Tham số ARGV[3]: Truyền `checkout_timestamp_us` để kiểm tra Timeline (Chỉ xóa nếu hàng được Add trước đó, phòng Ghost Delete nếu KH mới thêm lại hàng).
   - Tham số ARGV[4]: Truyền Idempotency Key `cart:idemp:order_paid:{order_id}`.
   - Script Lua sẽ tự động kết hợp nguyên tử (Atomic) cả việc check Idempotency và thực thi xóa. Tránh lỗi Worker sập giữa chừng bị kẹt đơn vĩnh viễn. Khi giỏ hàng rỗng hoàn toàn, Script Lua tự động đẩy `CART_CHECKED_OUT`.

> **Lưu ý:** Nếu user đã xóa sản phẩm khỏi giỏ trước khi thanh toán (edge case), Lua Script sẽ bỏ qua các SKU không tồn tại — không gây lỗi.

---

