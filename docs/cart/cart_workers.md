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

**Chi tiết luồng hoạt động (Hybrid Read + Intent Recovery, không SCAN full keyspace):**
1. **Phát hiện dữ liệu (Discovery):** Do mỗi giỏ có 1 Stream riêng, tiến trình Go phải chạy theo giao thức Intent Index trước/sau lệnh Lua có `XADD`:
   - **Bước A - Pre-register Intent (bắt buộc):** Trước khi gọi Lua script, Go gọi `ZADD outbox_publish_intents <now_ms> <stream_key>`.
   - **Bước B - Atomic Write:** Lua thực hiện ghi Hash + `XADD` vào `{cart:user:{user_id}}:outbox`.
   - **Bước C - Publish Path:** Sau khi Lua thành công, Go gọi `ZADD active_outbox_streams <now_ms> <stream_key>` và `PUBLISH outbox_notifications <stream_key>`.
   - **Bước D - Clear Intent:** Sau khi Bước C thành công, Go gọi `ZREM outbox_publish_intents <stream_key>`.
   Worker phát hiện Stream cần xử lý qua 3 lớp phòng phủ:
   - **Đường Nhanh (Pub/Sub):** Subscribe kênh `outbox_notifications`. Hễ có message, lập tức đẩy tên này vào Go Channel buffer.
   - **Đường Trì Hoãn (Delay ZSET Worker):** Một Goroutine phụ chạy mỗi 30 giây dùng lệnh `ZRANGE active_outbox_streams -inf <nows_ms - 30000> BYSCORE LIMIT 0 1000` (paginated theo block 1000) để hốt những stream có event chực chờ (do rớt mạng hoặc crash làm miss Pub/Sub), đẩy chúng vào cùng Go Channel trên. Khi đẩy xong, Worker **bắt buộc phải update lại thời gian** bằng `ZADD active_outbox_streams <nows_ms> <tên_stream>` để không bị process lặp lại ở chu kỳ 30s sau. (Tuyệt đối KHÔNG `ZREM` khỏi ZSET vì Stream Trimmer cần danh sách này để quản lý RAM).
   - **Đường Chống Lỗi Tuyệt Đối (Intent Recovery Worker):** Nếu Application Worker (Go) chết ngay *sau* Lua nhưng *trước* khi chạy xong Bước C/D, `stream_key` vẫn còn trong `outbox_publish_intents`. Một worker Recovery chạy mỗi 30 giây đọc `ZRANGE outbox_publish_intents -inf <now_ms - 5000> BYSCORE LIMIT 0 1000`, sau đó:
   - Nếu `XLEN {stream_key} > 0`: gọi lại `ZADD active_outbox_streams`, `PUBLISH outbox_notifications`, rồi `ZREM outbox_publish_intents <stream_key>`.
   - Nếu `XLEN {stream_key} == 0`: coi là intent rác (Lua fail/rollback path), chỉ `ZREM outbox_publish_intents <stream_key>`.
     - Cơ chế này thay thế hoàn toàn mô hình SCAN hourly toàn keyspace trong steady-state, giảm mạnh CPU spike trên Redis Cluster lớn.
2. Khởi chạy các luồng Goroutine liên tục móc tên Stream từ Go Channel trên và thực hiện đọc bằng lệnh:
   `XREADGROUP GROUP relay_group consumer_1 COUNT 50 STREAMS <các_stream_nhặt_được> >` 
   *(Nếu trả mã lỗi `NOGROUP`, Worker tự động gọi `XGROUP CREATE {cart_key}:outbox relay_group 0 MKSTREAM` rồi vòng lại đọc. Trước khi đánh dấu stream là "có thể trim", Relay phải đảm bảo group đã được tạo thành công hoặc đã tồn tại).* 
3. Với mỗi block Event nhặt được (Ví dụ: `CART_ITEM_ADDED`, `CART_ITEM_DELETED`, `CART_CLEARED`).
4. Bổ sung các trường Header cho Kafka message ([xem Events Header](cart_events.md#1-cấu-trúc-message-header-chung)): `event_id` (**Bắt buộc** sinh deterministic từ `Redis Stream ID + cart_id` như UUIDv5/băm ổn định tương đương; tuyệt đối KHÔNG tự sinh UUID random), `timestamp` (ISO 8601 UTC), `source = "cart-service"`, `stream_id` (chính là `msg.ID` từ Redis Stream), `stream_timestamp_ms`, `event_sequence`.
5. Thực hiện `Kafka.Produce` ném vào Topic chung `t.cart.events` (Key phân vùng = `cart_id` để đảm bảo event cùng giỏ đi tuần tự qua cùng partition).
6. Điểm trọng yếu: Chỉ sau khi Kafka `ACK` xác nhận đã ghi (cấu hình `acks=all`), Worker gọi lệnh `XACK` để đánh dấu message đã xử lý trong Consumer Group.

> **Chú ý:** `XACK` **chỉ đánh dấu message đã xử lý** trong Consumer Group, **KHÔNG** xóa message khỏi Stream Memory. Việc giải phóng RAM thực sự do [Stream Trimmer](#5-stream-trimmer-background-goroutine) đảm nhận bằng cách xóa theo ID. Xem [Outbox Pattern - Stream Memory Management](cart_outbox_pattern.md#22-stream-memory-management).

7. Nếu `Kafka.Produce` gặp lỗi: Retry với Exponential Backoff (Không `XACK` event đó, Redis sẽ giam nó đợi các lứa check Pending sau lại thử tiếp).

**Xử lý Pending Messages (Crash Recovery):**
Khi Worker crash sau khi `XREADGROUP` nhưng trước khi `XACK`, message ở trạng thái **Pending** (bị giữ vĩnh viễn).
- Nhờ quá trình dò quét `ZSET active_outbox_streams` mỗi 30 giây ở luồng Fallback, với mỗi stream phát hiện được, Worker chạy thêm `XAUTOCLAIM {stream_key} relay_group consumer_1 60000 0-0 COUNT 100` để nhặt lại message Pending quá 60s.
- Nếu một message bị retry quá 5 lần (kiểm tra qua `XPENDING` delivery count), chuyển vào Dead Letter Stream `cart_outbox_dlq` và ghi log cảnh báo. Xem [Worker #8 DLQ Handler](#8-dlq-handler-goroutine).

---

## 2. Postgres Sync (Consumer Worker)
**Loại hình:** Kafka Consumer Worker Node (Thuộc hệ thống nội bộ của Cart DB tier).

**Sứ mệnh:** Ghi dữ liệu đồng bộ xuống [Cold Storage (Database Schema)](cart_db_schema.md) một cách tĩnh mịch, cam kết không mất mát dù có đến chậm (Eventual Consistency).

**Chi tiết luồng hoạt động:**
1. Consumer Listen (Lắng nghe) Kafka topic `t.cart.events` (Consumer Group: `cart-db-sync`).
2. Áp dụng chiến lược luỹ đẳng theo **Redis Stream ID (primary)** và **Timestamp Milliseconds (fallback tương thích)**. Xem [Cart Idempotency](cart_idempotency.md).
3. Xử lý chia rẽ dựa trên Event Type:
   * **`CART_ITEM_DELETED`**: Kiểm tra trạng thái tồn tại của item trong bảng `cart_items`.
   - Nếu đã tồn tại: Gọi SQL Soft Delete với guard `stream_id` primary: `UPDATE cart_items SET is_deleted = true, tombstone_at = ?, redis_stream_id = ?, updated_at = NOW() WHERE item_id = ? AND (COALESCE(redis_stream_id, '') < ? OR (redis_stream_id IS NULL AND (product_snapshot->>'updated_at')::bigint < ?))`.
   - Nếu KHÔNG tồn tại (Lỗi Ghost Item - Event Delete đến trước Add): Insert ngay lập tức một bản ghi Tombstone với `item_id = ?`, `is_deleted = true`, `redis_stream_id = <event.stream_id>`, `quantity = 0`, `unit_price_at_add = 0.00`, `product_snapshot = '{"updated_at": "<event.deleted_at_ms>"}'` và `tombstone_at = event.deleted_at_ms`.
    * **`CART_ITEM_ADDED` / `CART_ITEM_UPDATED`**: Gọi SQL `ON CONFLICT DO UPDATE` ghi dữ liệu giỏ xuống bảng `cart_items` (kèm cập nhật `carts` master). Chỉ update item nếu:
       - **Ưu tiên 1 (Primary):** `event.stream_id` mới hơn `db.redis_stream_id` (so sánh chuỗi theo format `<ms>-<seq>`).
       - **Ưu tiên 2 (Fallback cho dữ liệu cũ chưa có stream_id):** `event.updated_at > db.product_snapshot->>'updated_at'`.
       - VÀ nếu bản ghi trong DB đang là Tombstone (`is_deleted = true`), kiểm tra nếu event cũ hơn mốc tombstone thì **BỎ QUA (IGNORE)**.
   Đồng thời tiến hành re-activate cart: set `carts.status = 'ACTIVE'`, cập nhật `carts.last_event_stream_id` + `carts.last_event_time_ms` với Guard Transition theo version (`event.stream_id` mới hơn là primary; fallback `event.updated_at >= carts.last_event_time_ms` cho bản ghi legacy chưa có stream_id). Tuyệt đối không bật ACTIVE nếu đây là event cũ đến trễ so với mốc Checkout/Clear. `user_id` lấy trực tiếp từ `payload.user_id` — không parse `cart_id`.
    > **Lưu ý Cập nhật Cột Subtotal (đã tối ưu):** Trong cùng Transaction xử lý event `ADDED`/`UPDATED`/`DELETED`, Worker cập nhật `subtotal` theo **delta** (`subtotal = subtotal + delta`) thay vì chạy `SUM(...)` toàn giỏ mỗi event. Mỗi event tính `delta` từ before/after quantity của SKU đang xử lý. Để tự sửa drift hiếm gặp, chạy một Reconciliation Job định kỳ (ví dụ 15-60 phút) mới dùng `SUM(...)` để đối soát. `subtotal` vẫn là analytics field, không dùng cho API đọc giỏ realtime.
   * **`CART_CLEARED`**: Chỉ đổi trạng thái khi event mới hơn theo `stream_id` (primary) hoặc theo `cleared_at >= carts.last_event_time_ms` (fallback legacy). Khi thỏa điều kiện, set `status = 'DELETED'`, cập nhật `last_event_stream_id`, `last_event_time_ms` và `updated_at = NOW()`. **Không xóa `cart_items`** ở đây (các event `CART_ITEM_DELETED` riêng lẻ sẽ xử lý từng item).
   * **`CART_CHECKED_OUT`**: Áp dụng guard tương tự `CART_CLEARED`: chỉ set `status = 'CHECKED_OUT'` nếu event mới hơn theo `stream_id` (hoặc fallback theo `checked_out_at`). Nếu cũ hơn thì **bỏ qua**. Kích hoạt từ [Order Event Consumer Worker](#6-order-event-consumer-worker-kafka-consumer) sau khi xử lý `ORDER_PAID`. **Không xóa `cart_items`** ở đây (các event `CART_ITEM_DELETED` riêng lẻ đã xử lý từng item trước đó).

> **Nguyên tắc bắt buộc:** Mọi update trạng thái trên bảng `carts` phải có guard `last_event_stream_id` (primary) và `last_event_time_ms` (fallback legacy), không được update mù theo kiểu `WHERE cart_id = ?`.

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
1. Chạy theo block nhỏ để tránh lock lớn (khuyến nghị 500-2000 carts/batch):
    ```sql
    -- Bước 1: Quét 1 batch carts đủ điều kiện
    SELECT cart_id, user_id, subtotal
    FROM carts
    WHERE status = 'ACTIVE'
       AND updated_at < NOW() - INTERVAL '30 days'
    ORDER BY updated_at ASC
    LIMIT 1000;
    ```
2. Với mỗi cart trong batch, thực hiện theo thứ tự an toàn:
    - Cập nhật DB: `UPDATE carts SET status = 'ABANDONED', updated_at = NOW(), abandoned_event_published_at = NULL WHERE cart_id = ? AND status = 'ACTIVE'`.
    - Nếu `RowsAffected = 1`, publish event vào **Redis Stream Outbox per-cart** (không dùng bảng SQL outbox):
       - Trước khi `XADD`, Go pre-register intent: `ZADD outbox_publish_intents <now_ms> {cart:user:{user_id}}:outbox`.
       - Stream key: `{cart:user:{user_id}}:outbox`
       - `XADD {cart:user:{user_id}}:outbox * event_type CART_ABANDONED payload <json>`
       - Payload tối thiểu: `cart_id`, `user_id`, `abandoned_at` (ms epoch UTC), `subtotal`.
    - Sau khi `XADD` thành công, cập nhật marker DB: `UPDATE carts SET abandoned_event_published_at = NOW() WHERE cart_id = ?`.
    - Sau `XADD` thành công, gọi ở tầng Go (publish path):
       - `ZADD active_outbox_streams <now_ms> {cart:user:{user_id}}:outbox`
       - `PUBLISH outbox_notifications {cart:user:{user_id}}:outbox`
       - `ZREM outbox_publish_intents {cart:user:{user_id}}:outbox`
    - Nếu `XADD` lỗi, bắt buộc `ZREM outbox_publish_intents {cart:user:{user_id}}:outbox` để không giữ intent rác.
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

**Sứ mệnh:** Giải phóng RAM Redis bằng cách cắt bớt (Trim) các message đã được `XACK` khỏi Outbox Stream. Xem [Outbox Pattern — Stream Memory Management](cart_outbox_pattern.md#22-stream-memory-management).

**Stream Memory Management (Theo mô hình Redis Pipeline tối ưu):**
1. Mỗi 5 phút, duyệt qua các stream nằm trong danh sách đang hoạt động bằng lệnh `ZSCAN active_outbox_streams 0 COUNT 100` (paginated đến hết ZSET thay vì dùng `ZRANGE 0 -1` dễ gây tắc nghẽn bộ nhớ mạng và nghẽn tiến trình Redis). Với mỗi batch 100 streams lấy được, tiến hành giải quyết qua **5 Pipeline** để tối ưu RTT (Round-Trip Time) và chống race mất dữ liệu.
2. **Pipeline 1 (State Snapshot):** Gom theo batch các lệnh `XLEN {stream_key}` + `XINFO GROUPS {stream_key}` để phân loại stream thành 3 trạng thái:
   - **Case A - `XLEN == 0`:** Stream rỗng thực sự -> đưa vào danh sách cleanup.
   - **Case B - `XLEN > 0` và có Group:** Stream đủ điều kiện tính `safe_id` -> đưa vào danh sách trim bình thường.
   - **Case C - `XLEN > 0` và KHÔNG có Group:** **BẮT BUỘC SKIP** stream này, tuyệt đối không trim/delete trong chu kỳ hiện tại. Đây là cửa sổ race giữa `XADD` và `XGROUP CREATE`.
   - Nếu `Case C` lặp lại liên tiếp >= 2 chu kỳ (>= 10 phút), phát cảnh báo `cart_outbox_no_group_stale` để điều tra Relay Worker.
3. **Pipeline 2 (Health & Pending cho Case B):** Với stream có Group, quét các group trả về:
   - *Kiểm tra Consumer Group Health:* Nếu một Consumer Group có `last-delivered-id` không thay đổi sau 30 phút (group "chết" do incident), **không trim** stream đó và phát alert Prometheus `cart_outbox_dlq_stale_group`.
   - Nếu `pending == 0`: chọn `last-delivered-id` làm `safe_id`.
   - Nếu `pending > 0`: đưa group này sang Pipeline 3 để đào sâu.
4. **Pipeline 3 (Đào sâu Pending - Case B):** Dùng `XPENDING {stream_key} {group_name}` cho tất cả group có `pending > 0` để lấy ID cũ nhất chưa ACK. So sánh chéo qua các group trong cùng stream để chốt `<lowest_safe_id_across_groups>`.
5. **Pipeline 4 (Trim - Chỉ Case B):** Gọi `XTRIM {stream_key} MINID ~ <lowest_safe_id_across_groups>` cho các stream đủ điều kiện an toàn.
6. **Pipeline 5 (Atomic Cleanup - Chỉ Case A):** Dọn stream rỗng bằng Lua nguyên tử. **Lưu ý Asynchronous:** Không dùng flow `XLEN` rồi `DEL` tách rời vì có thể mất event nếu Lua Write vừa bắn event mới vào khe hở đó.
   ```lua
   -- KEYS[1] = {stream_key}, KEYS[2] = active_outbox_streams
   if redis.call('XLEN', KEYS[1]) == 0 then
       redis.call('DEL', KEYS[1])
       -- Mặc dù không cùng Hash Tag, việc ZREM chỉ chạy khi dọn dẹp định kỳ
       -- thay vì chạy ở luồng ghi chính, nên ta kéo lệnh ZREM ra Go Backend 
       -- (Worker nhận kết quả 1 từ Lua rồi gọi vòng ngoài lệnh ZREM)
       return 1
   end
   return 0
   ```
   Nếu Lua trả về `1`, Worker sẽ gọi `ZREM active_outbox_streams {stream_key}`.
   *(Trường hợp Worker die ngay trước khi gọi `ZREM`, tên stream lưu rác lại trong danh sách `active` nhưng key stream bên ngoài đã bị xóa. Lần quét Trimmer hoặc Fallback tiếp theo, khi gọi `XINFO`/`XREADGROUP` lên stream này sẽ nhận lỗi `ERR no such key`. Worker chỉ cần bắt lỗi này, ngầm hiểu là stream rỗng/bị xóa rồi tự động gọi `ZREM active_outbox_streams {stream_key}` để dọn rác danh sách Tracking).* 
7. Log metric số message/stream đã dọn, số stream bị skip no-group, và backlog intent recovery để monitor.

---

## 6. Order Event Consumer Worker (Kafka Consumer)
**Loại hình:** Worker độc lập hoặc chạy chung luồng với luồng API Gateway tùy mô hình (Thường chung Service Process).

**Sứ mệnh:** Lắng nghe đơn hàng thanh toán thành công (Thực ra là lúc User "Nhấn MUA" và Order Service xử lý xong transaction đặt hàng). Nhiệm vụ là **xóa nháp các món hàng đã được mua khỏi Giỏ Hàng**.

**Cập nhật quan trọng:** Chốt chặn chống trùng lặp (Idempotency Key) trước đây nằm dưới DB là phương pháp sai. Worker này CHỈ tương tác gRPC/Redis với Cart Service, bỏ qua DB. Idempotency Key phải chốt ngay trên Redis. Xem [Batch Delete (Lua)](cart_caching_schema.md#33-xóa-item-batch-delete-sau-checkout-hoặc-remove).

**Chi tiết luồng hoạt động:**
1. **Consume** event `ORDER_PAID` từ `t.order.events` (filter `event_type = 'ORDER_PAID'`).
2. **Extract & Validate:** Lấy `user_id`, `cart_id` và danh sách `sku_ids` từ payload.
   - **Bắt buộc validate `cart_id` không null/rỗng và đúng format Redis key:** `^\{cart:user:[^{}]+\}$`.
   - Nếu `cart_id` null nhưng `user_id` có, thử tái tạo `cart_id = "{cart:user:{user_id}}"`. Nếu cả hai đều thiếu → push message sang DLQ với alert severity HIGH để ops xử lý thủ công.
   - `sku_ids` phải không rỗng. Event thiếu `sku_ids` hoặc chứa SKU không hợp lệ phải vào DLQ.
   - **Bắt buộc bỏ qua** các field `items[]`, `parcels[]` và `quantity` trong payload ORDER_PAID khi chạy cart cleanup.
3. **Idempotency Check (Go layer, không nhét key vào Lua):**
   - Trước Lua: kiểm tra `GET cart:idemp:order_paid:{order_id}`.
   - Nếu chưa có: set lock `SET cart:idemp:order_paid:{order_id} PROCESSING NX EX 300`. Nếu đã tồn tại `DONE` thì skip.
   - Gọi Lua Script Batch Delete (xem [Batch Delete](cart_caching_schema.md#33-xóa-item-batch-delete-sau-checkout-hoặc-remove)).
   - Trước khi gọi Lua, pre-register intent: `ZADD outbox_publish_intents <now_ms> {cart:user:{user_id}}:outbox`.
   - ARGV[3]: `user_id` (để build event payload).
   - ARGV[4]: `sku_ids_json` chứa danh sách `["sku-001","sku-002",...]` cần xóa khỏi giỏ.
   - **Lưu ý tránh nhầm:** ARGV của script Batch Delete **khác** ARGV của script Add/Update; không được tái sử dụng map vị trí tham số giữa 2 script.
   - Script xóa thẳng các `sku_id` tồn tại trong giỏ bằng `HDEL` và emit `CART_ITEM_DELETED` tương ứng.
   - Sau khi Lua thành công, Go chạy publish path `ZADD active_outbox_streams` + `PUBLISH outbox_notifications`, rồi `ZREM outbox_publish_intents`.
   - Sau publish path thành công, Go set `SETEX cart:idemp:order_paid:{order_id} 86400 DONE`.
   - Nếu Lua lỗi, bắt buộc `ZREM outbox_publish_intents` để dọn intent rác.
   - Cách này chặn redelivery và tránh chạy lặp trên cùng `order_id`.

> **Lưu ý:** Nếu user đã xóa sản phẩm khỏi giỏ trước khi thanh toán (edge case), Lua Script sẽ bỏ qua các SKU không tồn tại — không gây lỗi.

---

## 7. Bộ chỉ số bắt buộc (Observability Baseline)

Để tránh mù vận hành, Cart Service phải publish tối thiểu các metric sau:

1. `cart_read_cache_hit_ratio` theo cửa sổ 5 phút.
2. `cart_read_cache_miss_ratio` theo cửa sổ 5 phút.
3. `cart_product_grpc_timeout_rate` và `cart_product_grpc_latency_ms` (p95, p99).
4. `cart_outbox_pending_count` tổng hợp từ các active_outbox_streams.
5. `kafka_consumer_group_lag{group="cart-db-sync"}`.
6. `cart_rehydrate_conflict_skip_count` (số item bị bỏ qua do timestamp guard).
7. `cart_lua_error_count{script="add_update|batch_delete|rehydrate"}`.
8. `cart_outbox_dlq_length` — alert ngay khi > 0.
9. `cart_outbox_dlq_stale_group` — alert khi Consumer Group không tiến sau 30 phút.
10. `cart_singleflight_errors` — phát hiện cache miss rehydrate leader bị stuck/timeout.
11. `cart_outbox_intent_backlog` — số stream còn nằm trong `outbox_publish_intents`.
12. `cart_outbox_intent_recovered_total` — số stream được Recovery Worker cứu lại từ Intent Index.
13. `cart_outbox_no_group_skip_count` — số lần Trimmer bỏ qua stream vì `XLEN > 0` nhưng chưa có Group.
14. `cart_outbox_no_group_stale` — số stream no-group kéo dài >= 10 phút.

Ngưỡng khuyến nghị:
- Timeout rate Product gRPC > 5% trong 5 phút: cảnh báo.
- `cart-db-sync` lag > 1000 trong 2 phút: scale up.
- Lua error > 0 liên tục 1 phút: cảnh báo khẩn.
- DLQ length > 0: alert HIGH ngay lập tức.
- `cart_outbox_intent_backlog` > 1000 trong 2 phút: cảnh báo HIGH (điều tra worker publish path).
- `cart_outbox_no_group_stale` > 0 trong 10 phút: cảnh báo WARNING; > 5 stream: nâng CRITICAL.

> **Singleflight Timeout:** Request tiên phong (leader) trong cache-miss rehydrate phải có context timeout cứng (khuyến nghị 1 giây, bao gồm cả DB query + gRPC GetBatchProductSkus). Nếu leader bị stuck quá 1 giây, context cancel sẽ unblock tất cả waiter threads ngay lập tức thay vì block vô thời hạn.

---

## 8. DLQ Handler (Goroutine)
**Loại hình:** Goroutine chạy định kỳ bên trong process của Cart Service (cùng process với Outbox Relay).
**Dead Letter Stream:** `cart_outbox_dlq`

**Sứ mệnh:** Xử lý các message Outbox bị retry quá 5 lần và bị chuyển vào DLQ, tránh tích lũy vô hạn và đảm bảo ops có thể can thiệp kịp thời.

**Chi tiết luồng hoạt động:**
1. **Alert ngay khi DLQ có message:** Prometheus metric `cart_outbox_dlq_length > 0` → alert ops on-call ngay lập tức (severity HIGH).
2. **Manual Replay:** Ops có thể trigger replay toàn bộ hoặc từng message qua admin API internal:
   ```
   POST /internal/dlq/replay?stream=cart_outbox_dlq&from=<message_id>&to=<message_id>
   ```
3. **Auto-Quarantine:** Message tồn tại trong DLQ quá 24 giờ mà không được replay → tự động xóa và ghi audit log để retrospective.
4. **TTL cap:** Stream `cart_outbox_dlq` bị giới hạn `XLEN` tối đa 10,000 messages (`XTRIM MAXLEN 10000`). Nếu vượt, các message cũ nhất bị xóa và phát cảnh báo CRITICAL.

> **Lý do tách DLQ Worker:** Relay Worker (kéo từ outbox stream chính) không nên block vì DLQ processing. Tách riêng cho phép cấu hình retry policy và alert threshold độc lập.
