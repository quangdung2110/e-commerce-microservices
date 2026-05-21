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

**Sứ mệnh:** Chuyển phát nhanh (Forwarding) Event từ Global Redis Stream Shards sang Kafka Cluster. Xem kiến trúc Outbox tại [Redis Streams Outbox Pattern](cart_outbox_pattern.md).

**Chi tiết luồng hoạt động (Đơn giản hóa - không còn Intent Index, ZSET, Pub/Sub):**

Với kiến trúc Global Stream, Worker được đơn giản hóa đáng kể. Thay vì phải quản lý nhiều tầng như per-cart streams, Worker chỉ cần đọc trực tiếp từ các Global Stream Shards.

1. **Khởi tạo Consumer Groups:**
   - Với 4 shards, Worker khởi tạo 4 Consumer Groups tương ứng: `cart_relay_group:0`, `cart_relay_group:1`, `cart_relay_group:2`, `cart_relay_group:3`.
   - Stream keys: `{cart}:events:0`, `{cart}:events:1`, `{cart}:events:2`, `{cart}:events:3`.
   - Consumer Group được tạo với `XGROUP CREATE {stream} {group} 0 MKSTREAM`.

2. **Chạy Workers cho từng Shard:**
   - Mỗi shard được xử lý bởi 1 hoặc nhiều goroutines (tùy theo cấu hình).
   - Mỗi goroutine chạy vòng lặp vô hạn: `XREADGROUP BLOCK` → Process → `XACK`.

3. **Đọc messages bằng XREADGROUP:**
   ```go
   // Đọc từ 1 shard với BLOCK 5 giây
   result, err := redis.XReadGroup(ctx, &redis.XReadGroupArgs{
       Group:    fmt.Sprintf("cart_relay_group:%d", shardID),
       Consumer: fmt.Sprintf("worker-%s", workerID),
       Streams:  []string{streamKey, ">"},
       Count:    100,
       Block:    5 * time.Second,
   }).Result()
   ```

4. **Xử lý Message:**
   - Extract fields từ Redis Stream message: `event_type`, `cart_id`, `sku_id`, `user_id`, `payload`.
   - Bổ sung Kafka headers: `event_id` (deterministic từ `stream_id + cart_id`), `timestamp`, `stream_id`, `stream_timestamp_ms`, `event_sequence`.
   - Produce message sang Kafka topic `t.cart.events` với partition key = `cart_id`.

5. **ACK sau khi Kafka thành công:**
   - Chỉ gọi `XACK` khi Kafka xác nhận đã nhận message (acks=all).
   - Nếu Kafka fail, không XACK → message sẽ được redeliver.

**Xử lý Pending Messages (Crash Recovery):**
- Khi Worker crash sau khi `XREADGROUP` nhưng trước khi `XACK`, message ở trạng thái **Pending**.
- Worker sau restart sẽ tự động nhận lại các pending messages khi gọi `XREADGROUP`.
- Hoặc dùng `XAUTOCLAIM` để chủ động nhặt lại messages pending quá 60 giây.

> Consumer Group của Redis Streams tự động quản lý việc phát hiện messages mới, pending messages, và retry.

---

## 2. Postgres Sync (Consumer Worker)
**Loại hình:** Kafka Consumer Worker Node (Thuộc hệ thống nội bộ của Cart DB tier).

**Sứ mệnh:** Ghi dữ liệu đồng bộ xuống [Cold Storage (Database Schema)](cart_db_schema.md) một cách tĩnh mịch, cam kết không mất mát dù có đến chậm (Eventual Consistency).

**Chi tiết luồng hoạt động:**
1. Consumer Listen (Lắng nghe) Kafka topic `t.cart.events` (Consumer Group: `cart-db-sync`).
2. Áp dụng chiến lược luỹ đẳng theo **Redis Stream ID**. Xem [Cart Idempotency](cart_idempotency.md).
3. Xử lý chia rẽ dựa trên Event Type:
   * **`CART_ITEM_DELETED`**: Thực hiện **Hard Delete** (xóa hẳn dòng) khỏi bảng `cart_items`:
     - SQL: `DELETE FROM cart_items WHERE item_id = ? AND redis_stream_id < ?`.
     - Nếu dòng không tồn tại hoặc event cũ hơn → 0 rows affected → an toàn.
   * **`CART_ITEM_ADDED` / `CART_ITEM_UPDATED`**: Gọi SQL `ON CONFLICT DO UPDATE` ghi dữ liệu giỏ xuống bảng `cart_items` (kèm cập nhật `carts` master). Chỉ update item nếu `event.stream_id` mới hơn `db.redis_stream_id`.
     Đồng thời tiến hành re-activate cart: set `carts.status = 'ACTIVE'`, cập nhật `carts.last_event_stream_id`. Tuyệt đối không bật ACTIVE nếu đây là event cũ đến trễ so với mốc Checkout/Clear. `user_id` lấy trực tiếp từ `payload.user_id`.
   * **`CART_CLEARED`**: Chỉ đổi trạng thái khi `last_event_stream_id < event.stream_id`. Khi thỏa điều kiện, set `status = 'DELETED'`, cập nhật `last_event_stream_id` và `updated_at = NOW()`. **Không xóa `cart_items`** ở đây.
   * **`CART_CHECKED_OUT`**: Áp dụng guard tương tự `CART_CLEARED`: chỉ set `status = 'CHECKED_OUT'` nếu `last_event_stream_id < event.stream_id`. Nếu cũ hơn thì **bỏ qua**. **Không xóa `cart_items`** ở đây.

> **Nguyên tắc bắt buộc:** Mọi update trạng thái trên bảng `carts` phải có guard `last_event_stream_id`, không được update mù theo kiểu `WHERE cart_id = ?`.

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
Worker poll batch 50 events từ Kafka, sau đó group by `cart_id` và batch upsert vào PostgreSQL.

**Chi tiết luồng Batch Processing:**

1. **Poll batch events:**
   - Consumer poll Kafka với `max.poll.records = 50`.
   - Nhận list events: `[{event_type, stream_id, payload, partition, offset}, ...]`

2. **Group by cart_id:**
   - Dùng map `cart_id -> []Event` để nhóm events.
   - Đảm bảo events cùng `cart_id` được xử lý tuần tự (vì Kafka partition key = `cart_id`).

3. **Batch SQL per cart_id:**
   - Với mỗi `cart_id`, tạo 1 transaction riêng.
   - Bên trong transaction, thực hiện:
     - **Upsert cart_items:** Tất cả ADD/UPDATE events trong batch cho cart này → 1 batch `INSERT ... ON CONFLICT DO UPDATE`.
     - **Hard Delete cart_items:** Tất cả DELETE events → 1 batch `DELETE FROM ... WHERE sku_id IN (...)`.
     - **Update carts status:** 1 upsert cho cart master row.
   - **Atomicity:** Nếu bất kỳ SQL nào trong transaction fail → rollback toàn bộ batch cho cart đó.

> **⚠️ Quan trọng về Transaction Boundary:**
> - Kafka poll nhận N events cho M cart_ids khác nhau (M ≤ N).
> - Mỗi cart_id được xử lý trong transaction riêng biệt.
> - **Nếu cart_id=A fail nhưng cart_id=B thành công:** cart_id=B được commit, cart_id=A sẽ được redeliver bởi Kafka.
> - **Offset commit strategy:** Commit offset SAU KHI tất cả M transactions thành công. Nếu bất kỳ transaction nào fail sau 3 retry → toàn bộ batch (bao gồm các cart_ids đã thành công) vẫn giữ nguyên offset và sẽ được redeliver.
> - **Tại sao không commit offset riêng lẻ?** Vì Kafka consumer library không hỗ trợ partial commit. Nếu commit offset 100 nhưng transaction ở offset 95 fail → sẽ miss các events 95-99 ở lần redeliver.

3.1. **Batch SQL syntax cho ADD/UPDATE (sử dụng item_id):**
   - **Lưu ý:** Sử dụng `item_id = cart_id || ':' || sku_id` thay vì `cart_id + sku_id IN (...)` để nhất quán với Primary Key.

4. **Batch SQL syntax cho ADD/UPDATE:**
   ```sql
   INSERT INTO cart_items (
       item_id, cart_id, product_id, sku_id,
       quantity, unit_price_at_add, product_snapshot, redis_stream_id, created_at, updated_at
   )
   VALUES
       ('{cart}:550e8400-e29b-41d4-a716-446655440000:sku-001', '{cart}:550e8400-e29b-41d4-a716-446655440000', 'prod-1', 'sku-001', 2, 150000.00, '{"product_name":"A","is_available":true}', '1708616400456-0', NOW(), NOW()),
       ('{cart}:550e8400-e29b-41d4-a716-446655440000:sku-002', '{cart}:550e8400-e29b-41d4-a716-446655440000', 'prod-2', 'sku-002', 1, 200000.00, '{"product_name":"B","is_available":true}', '1708616400457-0', NOW(), NOW())
   ON CONFLICT (item_id)
   DO UPDATE SET
       quantity = EXCLUDED.quantity,
       product_snapshot = EXCLUDED.product_snapshot,
       redis_stream_id = EXCLUDED.redis_stream_id,
       updated_at = NOW()
   WHERE cart_items.redis_stream_id < EXCLUDED.redis_stream_id;
   ```

5. **Batch SQL syntax cho DELETE (Hard Delete sử dụng item_id):**
   ```sql
   DELETE FROM cart_items
   WHERE item_id IN (
       '{cart}:550e8400-e29b-41d4-a716-446655440000:sku-001',
       '{cart}:550e8400-e29b-41d4-a716-446655440000:sku-002'
   )
   AND redis_stream_id < ?;
   ```
   > **Lưu ý:** Sử dụng `item_id` (format: `cart_id || ':' || sku_id`) thay vì `cart_id + sku_id IN (...)` để nhất quán với Primary Key và tránh ambiguous khi có các cart_ids trùng lặp trong payload.

6. **Carts status update:**
   ```sql
   INSERT INTO carts (cart_id, user_id, status, last_event_stream_id, created_at, updated_at)
   VALUES ($1, $2, 'ACTIVE', $3, NOW(), NOW())
   ON CONFLICT (cart_id)
   DO UPDATE SET
       status = 'ACTIVE',
       last_event_stream_id = EXCLUDED.last_event_stream_id,
       updated_at = NOW()
   WHERE carts.last_event_stream_id < EXCLUDED.last_event_stream_id;
   ```

7. **Offset commit:**
   - Chỉ commit Kafka offset SAU KHI tất cả batch SQL thành công.
   - Nếu batch fail → không commit offset → Kafka sẽ redeliver.

8. **Ordering Guarantee:**
   - Events cùng `cart_id` đi qua cùng Kafka partition → đảm bảo ordering.
   - Batch không phá vỡ ordering vì events được xử lý tuần tự theo offset.

9. **Error Handling:**
   - Nếu batch SQL fail: rollback transaction, không commit offset.
   - Retry với exponential backoff (max 3 lần cho batch).
   - Sau 3 lần fail → log lỗi và commit offset để tránh infinite retry (message sẽ được xử lý lại ở lần redeliver tiếp theo).
   - Ghi log chi tiết: `cart_id`, error reason, failed events để phục vụ debug.

**Lợi ích:**
- Giảm connection overhead: 1 batch = 1 transaction thay vì N transactions.
- Tận dụng PostgreSQL batch INSERT/UPDATE hiệu quả hơn.
- Giảm network round-trips từ ~50 xuống còn ~1 per cart.

---

## 3. Abandoned Cart Marker (Batch Cronjob)
**Loại hình:** Lịch biểu chạy giữa đêm hàng ngày (Ví dụ: 2:00 AM UTC).

**Sứ mệnh:** Đánh dấu các giỏ hàng bỏ hoang (không tương tác quá 30 ngày) để phục vụ Marketing re-engagement và phân loại dữ liệu. Cập nhật cột `status` trên bảng `carts` từ `ACTIVE` → `ABANDONED`. Xem [Database Schema - Trạng thái](cart_db_schema.md#21-bảng-carts).

**Chi tiết luồng hoạt động:**
1. Chạy theo block nhỏ để tránh lock lớn (khuyến nghị 500-2000 carts/batch):
    ```sql
    -- Bước 1: Quét 1 batch carts đủ điều kiện
    SELECT cart_id, user_id
    FROM carts
    WHERE status = 'ACTIVE'
       AND updated_at < NOW() - INTERVAL '30 days'
    ORDER BY updated_at ASC
    LIMIT 1000;
    ```
2. Với mỗi cart trong batch, thực hiện theo thứ tự an toàn:
    - Cập nhật DB: `UPDATE carts SET status = 'ABANDONED', updated_at = NOW(), abandoned_event_published_at = NULL WHERE cart_id = ? AND status = 'ACTIVE'`.
    - Nếu `RowsAffected = 1`, publish event vào **Global Redis Stream Shard** (không dùng bảng SQL outbox):
       - Tính shard ID: `shard_id = crc32(user_id) % 4`
       - Stream key: `{cart}:events:{shard_id}`
       - `XADD {cart}:events:{shard_id} * event_type CART_ABANDONED payload <json>`
       - Payload tối thiểu: `cart_id`, `user_id`, `abandoned_at` (ms epoch UTC).
    - Sau khi `XADD` thành công, cập nhật marker DB: `UPDATE carts SET abandoned_event_published_at = NOW() WHERE cart_id = ?`.
3. Outbox Relay Worker đọc stream như các event Cart khác và publish lên Kafka `t.cart.events`.
4. Downstream phase hiện tại: **Notification Service consume `CART_ABANDONED`** để chạy flow re-engagement.

> **Lý do kiến trúc:** Cart Service đã chọn Redis Streams làm Outbox chuẩn. Cronjob ABANDONED phải đi cùng đường `XADD -> Relay -> Kafka` để giữ một mô hình duy nhất, tránh lệch thiết kế giữa write API và write cronjob.

> **At-least-once & Recovery:** Nếu crash xảy ra sau `UPDATE carts` nhưng trước `XADD`, lần chạy cron kế tiếp phải có bước reconcile quét `status = 'ABANDONED' AND abandoned_event_published_at IS NULL AND updated_at >= NOW() - INTERVAL '24 hours'` rồi publish bù. Cho phép duplicate event, downstream xử lý bằng idempotency.

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

**Sứ mệnh:** Giải phóng RAM Redis bằng cách cắt bớt (Trim) các message đã được `XACK` khỏi Global Stream Shards. Xem [Outbox Pattern — Stream Memory Management](cart_outbox_pattern.md#23-stream-memory-management).

**Stream Memory Management (Theo mô hình Redis Pipeline tối ưu):**

Với kiến trúc Global Stream (4 shards), việc trim đơn giản hơn đáng kể so với per-cart streams.

1. Mỗi 5 phút, duyệt qua 4 shards: `{cart}:events:0`, `{cart}:events:1`, `{cart}:events:2`, `{cart}:events:3`.
2. Với mỗi shard, thực hiện các bước sau:
   - **Bước 1 (State Snapshot):** Gọi `XLEN` và `XINFO GROUPS` để kiểm tra trạng thái stream.
     - **Case A - Stream rỗng:** Skip (không xóa vì stream là logical key).
     - **Case B - Có messages và có Group:** Tiếp tục tính safe ID để trim.
     - **Case C - Có messages nhưng chưa có Group:** **BẮT BUỘC SKIP** trong chu kỳ này.
   - **Bước 2 (Health Check):** Kiểm tra Consumer Group health. Nếu group "chết" (last-delivered-id không thay đổi sau 30 phút), skip và cảnh báo.
   - **Bước 3 (Tính safe ID):**
     - Nếu `pending == 0`: dùng `last-delivered-id` làm `safe_id`.
     - Nếu `pending > 0`: dùng `XPENDING` để tìm `oldest_pending_id`, so sánh với `last-delivered-id` và chọn giá trị nhỏ hơn.
   - **Bước 4 (Trim):** Gọi `XTRIM {stream} MINID ~ <lowest_safe_id>`.


---

## 6. Order Event Consumer Worker (Kafka Consumer)
**Loại hình:** Worker độc lập hoặc chạy chung luồng với luồng API Gateway tùy mô hình (Thường chung Service Process).

**Sứ mệnh:** Lắng nghe đơn hàng thanh toán thành công (Thực ra là lúc User "Nhấn MUA" và Order Service xử lý xong transaction đặt hàng). Nhiệm vụ là **xóa nháp các món hàng đã được mua khỏi Giỏ Hàng**.

**Cập nhật quan trọng:** Chốt chặn chống trùng lặp (Idempotency Key) trước đây nằm dưới DB là phương pháp sai. Worker này CHỈ tương tác gRPC/Redis với Cart Service, bỏ qua DB. Idempotency Key phải chốt ngay trên Redis. Xem [Batch Delete (Lua)](cart_caching_schema.md#33-xóa-item-batch-delete-sau-checkout-hoặc-remove).

**Chi tiết luồng hoạt động:**
1. **Consume** event `ORDER_PAID` từ `t.order.events` (filter `event_type = 'ORDER_PAID'`).
2. **Extract & Validate:** Lấy `user_id`, `cart_id` và danh sách `sku_ids` từ payload.
   - **Bắt buộc validate `cart_id` không null/rỗng và đúng format Redis key:** `^\{cart\}:[^{}]+$`.
   - Nếu `cart_id` null nhưng `user_id` có, thử tái tạo `cart_id = "{cart}:{user_id}"`. Nếu cả hai đều thiếu → log lỗi và skip message (commit offset để tránh infinite redelivery).
   - `sku_ids` phải không rỗng. Event thiếu `sku_ids` hoặc chứa SKU không hợp lệ → log lỗi và skip message.
   - **Bắt buộc bỏ qua** các field `items[]`, `parcels[]` và `quantity` trong payload ORDER_PAID khi chạy cart cleanup.
3. **Idempotency Check (Go layer):**
   - Kiểm tra `GET cart:idemp:order_paid:{order_id}`.
   - Nếu chưa có: set lock `SET cart:idemp:order_paid:{order_id} PROCESSING NX EX 300`. Nếu đã tồn tại `DONE` thì skip.
4. **Gọi Lua Script Batch Delete:**
   - Tính shard ID: `shard_id = crc32(user_id) % 4`
   - Stream key: `{cart}:events:{shard_id}`
   - ARGV chứa `sku_ids_json` cần xóa khỏi giỏ.
   - Script xóa thẳng các `sku_id` tồn tại trong giỏ bằng `HDEL` và emit `CART_ITEM_DELETED` vào Global Stream Shard.
   - **Đơn giản hóa:** Không còn `ZADD intent`, `ZADD active`, `PUBLISH`, `ZREM intent`. Event được ghi vào Global Stream Shard, Relay Worker tự động phát hiện qua Consumer Group.
5. **Sau Lua thành công:** Set `SETEX cart:idemp:order_paid:{order_id} 86400 DONE`.

> **Lưu ý:** Nếu user đã xóa sản phẩm khỏi giỏ trước khi thanh toán (edge case), Lua Script sẽ bỏ qua các SKU không tồn tại — không gây lỗi.

---
