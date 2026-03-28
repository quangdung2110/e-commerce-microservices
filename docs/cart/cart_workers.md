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

**Chi tiết luồng hoạt động (Hybrid Read + Application Dual-Write):**
1. **Phát hiện dữ liệu (Discovery):** Do mỗi giỏ có 1 Stream riêng, tiến trình Go sau khi gọi *Lua Script* thành công sẽ chạy Goroutine ghi log `ZADD active_outbox_streams` và bắn `PUBLISH outbox_notifications` lên Redis. Worker phát hiện Stream cần xử lý qua 3 lớp phòng phủ:
   - **Đường Nhanh (Pub/Sub):** Subscribe kênh `outbox_notifications`. Hễ có message, lập tức đẩy tên này vào Go Channel buffer.
   - **Đường Trì Hoãn (Delay ZSET Worker):** Một Goroutine phụ chạy mỗi 30 giây dùng lệnh `ZRANGE active_outbox_streams -inf <nows_ms - 30000> BYSCORE LIMIT 0 1000` (paginated theo block 1000) để hốt những stream có event chực chờ (do rớt mạng hoặc crash làm miss Pub/Sub), đẩy chúng vào cùng Go Channel trên. Khi đẩy xong, Worker **bắt buộc phải update lại thời gian** bằng `ZADD active_outbox_streams <nows_ms> <tên_stream>` để không bị process lặp lại ở chu kỳ 30s sau. (Tuyệt đối KHÔNG `ZREM` khỏi ZSET vì Stream Trimmer cần danh sách này để quản lý RAM).
   - **Đường Chống Lỗi Tuyệt Đối (Keyspace Reaper):** Nếu Application Worker (Go) chết ngay *sau* luồng Lua nhưng *trước* khi ghi `ZADD`/`PUBLISH`, stream đó sẽ kẹt vĩnh viễn trên Redis. Một worker **Keyspace Reaper** đặc biệt sẽ chạy **mỗi 1 tiếng** và **bắt buộc quét SCAN theo vòng lặp cursor đến khi cursor = 0** (không được chạy 1 lần `SCAN 0` rồi dừng). Pseudocode chuẩn: `cursor = 0 -> SCAN cursor MATCH {cart:user:*}:outbox COUNT 500 -> xử lý batch -> cursor = next_cursor -> lặp`. Những stream nào có dữ liệu mà thiếu trong `active_outbox_streams` sẽ được Worker này `ZADD` trở lại, đảm bảo Eventual Consistency tuyệt đối cho Outbox Pattern.
2. Khởi chạy các luồng Goroutine liên tục móc tên Stream từ Go Channel trên và thực hiện đọc bằng lệnh:
   `XREADGROUP GROUP relay_group consumer_1 COUNT 50 STREAMS <các_stream_nhặt_được> >` 
   *(Nếu trả mã lỗi `NOGROUP`, Worker tự động gọi `XGROUP CREATE {cart_key}:outbox relay_group 0 MKSTREAM` rồi vòng lại đọc).*
3. Với mỗi block Event nhặt được (Ví dụ: `CART_ITEM_ADDED`, `CART_ITEM_DELETED`, `CART_CLEARED`).
4. Bổ sung các trường Header cho Kafka message ([xem Events Header](cart_events.md#1-cấu-trúc-message-header-chung)): `event_id` (**Bắt buộc** sinh deterministic từ `Redis Stream ID + cart_id` như UUIDv5/băm ổn định tương đương; tuyệt đối KHÔNG tự sinh UUID random), `timestamp` (ISO 8601 UTC), `source = "cart-service"`.
5. Thực hiện `Kafka.Produce` ném vào Topic chung `t.cart.events` (Key phân vùng = `cart_id` để đảm bảo event cùng giỏ đi tuần tự qua cùng partition).
6. Điểm trọng yếu: Chỉ sau khi Kafka `ACK` xác nhận đã ghi (cấu hình `acks=all`), Worker gọi lệnh `XACK` để đánh dấu message đã xử lý trong Consumer Group.

> **Chú ý:** `XACK` **chỉ đánh dấu message đã xử lý** trong Consumer Group, **KHÔNG** xóa message khỏi Stream Memory. Việc giải phóng RAM thực sự do [Stream Trimmer](#5-stream-trimmer-background-goroutine) đảm nhận bằng cách xóa theo ID. Xem [Outbox Pattern - Stream Memory Management](cart_outbox_pattern.md#22-stream-memory-management).

6. Nếu `Kafka.Produce` gặp lỗi: Retry với Exponential Backoff (Không `XACK` event đó, Redis sẽ giam nó đợi các lứa check Pending sau lại thử tiếp).

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
2. Áp dụng nghiêm ngặt chiến lược luỹ đẳng thời gian (Timestamp Milliseconds - [Cart Idempotency](cart_idempotency.md)).
3. Xử lý chia rẽ dựa trên Event Type:
   * **`CART_ITEM_DELETED`**: Kiểm tra trạng thái tồn tại của item trong bảng `cart_items`.
    - Nếu đã tồn tại: Gọi SQL Soft Delete `UPDATE cart_items SET is_deleted = true, tombstone_at = ?, updated_at = NOW() WHERE item_id = ? AND (product_snapshot->>'updated_at')::bigint < ?` (chỉ xóa logic nếu `deleted_at` > `updated_at` hiện tại trong DB, chặn out-of-order, tất cả so sánh Milliseconds).
     - Nếu KHÔNG tồn tại (Lỗi Ghost Item - Event Delete đến trước Add): Insert ngay lập tức một bản ghi Tombstone với `item_id = ?`, `is_deleted = true`, `quantity = 0`, `unit_price_at_add = 0.00`, `product_snapshot = '{"updated_at": "<event.deleted_at_ms>"}'` và `tombstone_at = event.deleted_at_ms`.
   * **`CART_ITEM_ADDED` / `CART_ITEM_UPDATED`**: Gọi SQL `ON CONFLICT DO UPDATE` ghi dữ liệu giỏ xuống bảng `cart_items` (kèm cập nhật `carts` master). Chỉ update item nếu:
     - Biến thời gian `event.updated_at > db.product_snapshot->>'updated_at'` (so sánh Milliseconds Epoch).
     - VÀ nếu bản ghi trong DB đang là Tombstone (`is_deleted = true`), kiểm tra nếu `event.updated_at < db.tombstone_at` thì **BỎ QUA (IGNORE)** (Vì đây là event tạo ngầm cũ kĩ đến chậm bị event Delete đi trước khóa mõm rồi).
    Đồng thời tiến hành re-activate cart: set `carts.status = 'ACTIVE'` và cập nhật `carts.last_event_time_ms` theo event time mới nhất NHƯNG phải áp dụng Guard Transition (`event.updated_at >= carts.last_event_time_ms`). Tuyệt đối không bật ACTIVE nếu đây là một event cũ rích đến trễ so với mốc Checkout/Clear. `user_id` lấy trực tiếp từ `payload.user_id` — không parse `cart_id`.
   > **Lưu ý Cập nhật Cột Subtotal:** Trong cùng Transaction xử lý event `ADDED`/`UPDATED`/`DELETED`, Worker cập nhật `subtotal` vào bảng `carts`: `UPDATE carts SET subtotal = (SELECT COALESCE(SUM(quantity * unit_price_at_add), 0) FROM cart_items WHERE cart_id = ? AND is_deleted = false) WHERE cart_id = ?`. `subtotal` là analytics field — có thể tạm thời inaccurate khi nhiều events chạy song song (eventual consistency chấp nhận được). Không dùng `subtotal` cho API đọc giỏ realtime.
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
       - Stream key: `{cart:user:{user_id}}:outbox`
       - `XADD {cart:user:{user_id}}:outbox * event_type CART_ABANDONED payload <json>`
       - Payload tối thiểu: `cart_id`, `user_id`, `abandoned_at` (ms epoch UTC), `subtotal`.
    - Sau khi `XADD` thành công, cập nhật marker DB: `UPDATE carts SET abandoned_event_published_at = NOW() WHERE cart_id = ?`.
    - Sau `XADD` thành công, gọi ở tầng Go:
       - `ZADD active_outbox_streams <now_ms> {cart:user:{user_id}}:outbox`
       - `PUBLISH outbox_notifications {cart:user:{user_id}}:outbox`
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
1. Mỗi 5 phút, duyệt qua các stream nằm trong danh sách đang hoạt động bằng lệnh `ZSCAN active_outbox_streams 0 COUNT 100` (paginated đến hết ZSET thay vì dùng `ZRANGE 0 -1` dễ gây tắc nghẽn bộ nhớ mạng và nghẽn tiến trình Redis). Với mỗi batch 100 streams lấy được, tiến hành giải quyết qua **3 Pipeline** để tối ưu RTT (Round-Trip Time):
2. **Pipeline 1 (Quét Health & Pending Count):** Gom 100 lệnh `XINFO GROUPS {stream_key}` cho batch đang xét và gửi đi 1 lần. Quét các group trả về:
   - *Kiểm tra Consumer Group Health:* Nếu một Consumer Group có `last-delivered-id` không thay đổi sau 30 phút (group "chết" do incident), **không trim** toàn bộ stream đó và phát alert Prometheus `cart_outbox_dlq_stale_group`.
   - Nếu `pending == 0`: Tin nhắn cuối cùng worker xử lý đã xong, chọn `last-delivered-id` làm ranh giới an toàn (`safe_id`).
   - Nếu `pending > 0`: Có tin nhắn bị treo làm sai lệch `last-delivered-id`. Phải đưa group này vào Pipeline 2 để đào sâu.
3. **Pipeline 2 (Đào sâu tin nhắn Pending - Tùy chọn):** Dùng lệnh `XPENDING {stream_key} {group_name}` gửi một loạt cho tất cả các nhóm có `pending > 0`. Lệnh này lật "sổ nợ" và chỉ đích danh ID của tin nhắn cũ nhất chưa ACK. Lấy id này làm `safe_id`. So sánh chéo `safe_id` qua từng group trong 1 stream để chốt được `<lowest_safe_id_across_groups>`.
4. **Pipeline 3 (Thực thi Trim & Xóa nguyên tử):** Gửi đồng loạt batch lệnh dọn dẹp:
   - Gọi `XTRIM {stream_key} MINID ~ <lowest_safe_id_across_groups>` cho tất cả stream để dọn rác bộ nhớ bằng số ranh giới an toàn.
5. Xóa stream trống rỗng sau khi trim ngay tại Pipeline 3. **Lưu ý Asynchronous:** Không dùng các lệnh check `XLEN` rồi tự gọi lệnh `DEL` vì có thể gây mất event nếu trong khe hở đó Lua script Add/Update vừa bắn event mới vào. Phải dùng đoạn Lua Script dưới đây để dọn dẹp nguyên tử:
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
   *(Trường hợp Worker die ngay trước khi gọi `ZREM`, tên stream lưu rác lại trong danh sách `active` nhưng key stream bên ngoài đã bị xóa. Lần quét Trimmer hoặc Fallback tiếp theo, khi gọi `XINFO`/`XREADGROUP` lên stream này sẽ nhận lỗi `ERR no such key`. Worker chỉ cần bắt lỗi này, ngầm hiểu là stream rỗng/bị xóa rồi tự động gọi `ZREM active_outbox_streams {stream_key}` để dọn rác danh sách Tracking)*.
6. Log metric số message và số stream đã dọn dẹp để monitor.

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
3. **Idempotency Check:** Gọi Lua Script Batch Delete (xem [Batch Delete](cart_caching_schema.md#33-xóa-item-batch-delete-sau-checkout-hoặc-remove)).
   - ARGV[3]: Idempotency Key `cart:idemp:order_paid:{order_id}`.
   - ARGV[4]: `user_id` (để build event payload).
   - ARGV[5]: `sku_ids_json` chứa danh sách `["sku-001","sku-002",...]` cần xóa khỏi giỏ.
   - **Lưu ý tránh nhầm:** ARGV của script Batch Delete **khác** ARGV của script Add/Update; không được tái sử dụng map vị trí tham số giữa 2 script.
   - Lua script đặt key trạng thái `PROCESSING` bằng `SET NX EX 300` ngay đầu luồng. Nếu key đã tồn tại thì skip.
   - Script xóa thẳng các `sku_id` tồn tại trong giỏ bằng `HDEL` và emit `CART_ITEM_DELETED` tương ứng.
   - Sau khi xử lý thành công và phát event cần thiết, Lua đổi key sang `DONE` với TTL 24h (chỉ khi không có runtime error).
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

Ngưỡng khuyến nghị:
- Timeout rate Product gRPC > 5% trong 5 phút: cảnh báo.
- `cart-db-sync` lag > 1000 trong 2 phút: scale up.
- Lua error > 0 liên tục 1 phút: cảnh báo khẩn.
- DLQ length > 0: alert HIGH ngay lập tức.

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
