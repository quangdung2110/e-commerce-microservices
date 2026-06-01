# Cart Service - Redis Streams Outbox Pattern (Global Stream Architecture)

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](cart_workflow.md)
*   [Background Workers & Jobs](cart_workers.md)
*   [Caching Schema (Redis-First)](cart_caching_schema.md)
*   [Database Schema (Cold Store)](cart_db_schema.md)
*   [Outbox Pattern (Redis Streams)](cart_outbox_pattern.md)
*   [Idempotency Pattern](cart_idempotency.md)
*   [Events Structure](cart_events.md)

---
Tài liệu này giải thích cách giải quyết bài toán **Dual-Write** trong kiến trúc của Cart Service thông qua việc sử dụng **Global Redis Stream** làm Transactional Outbox với 4 shards.

## 1. Bài toán Dual-Write trong Cart Service cũ

Ở phiên bản codebase trước đây, luồng `AddItem` được thiết kế như sau:

1.  `UpsertItem` vào Redis Hash (thành công).
2.  Service chuẩn bị payload Event `CART_ITEM_ADDED`.
3.  Gọi Kafka Publisher `go publishKafkaEvent()`.

**Lỗi xảy ra khi:**
Hệ thống/Pod bị Crash (hoặc Kafka Broker bị timeout/rớt mạng) ngay sau bước (1) nhưng trước/giữa bước (3).
Hậu quả:
Redis đã có sản phẩm trong giỏ hàng. Nhưng Kafka không nhận được Event.
-> Postgres (Cold Store) vĩnh viễn không nhận được dữ liệu này.
-> Hệ thống Caching và Database vỡ tính đồng nhất (Inconsistency).
-> Khi Redis bị khởi động lại hoặc xoá Key, user mất vĩnh viễn sản phẩm trong giỏ rác.

## 2. Giải pháp: Global Redis Stream với Sharding

### 2.1. Tại sao chọn Global Stream?

**Kiến trúc cũ (Per-Cart Stream):**
- Mỗi user có 1 Redis Stream riêng: `{cart}:user:{user_id}:outbox` (kiến trúc cũ — **đã được thay thế bằng Global Stream**)
- Cần nhiều tầng tracking: `outbox_publish_intents` → `active_outbox_streams` → `outbox_notifications` → Relay → `ZREM intent`
- 1 triệu users = 1 triệu Redis Streams + 1 triệu ZSET entries

**Kiến trúc mới (Global Stream):**
- Tất cả users gửi events vào 1 stream chung: `{cart}:events:{shard_id}`
- Bỏ hoàn toàn: ZSET intent, ZADD active, PUBLISH notification, Intent Recovery Worker
- Đơn giản hóa: Lua (HSET + XADD) → Relay (XREADGROUP → Kafka → XACK)

### 2.2. Sharding Strategy

Sử dụng 4 shards để cân bằng tải và dễ mở rộng:

| Stream Key | Shard | Hash Slot |
|:---|:---:|:---:|
| `{cart}:events:0` | Shard 0 | Tính từ `{cart}` |
| `{cart}:events:1` | Shard 1 | Tính từ `{cart}` |
| `{cart}:events:2` | Shard 2 | Tính từ `{cart}` |
| `{cart}:events:3` | Shard 3 | Tính từ `{cart}` |

**Shard Assignment:**
```go
// Shard ID được tính từ CRC32 hash của user_id
shard_id = crc32(user_id) % 4
stream_key = fmt.Sprintf("{cart}:events:%d", shard_id)
```

**Ưu điểm của 4 shards:**
- Tăng throughput tuyến tính (4 workers parallel)
- Dễ mở rộng: tăng số shards khi cần
- Mỗi shard có Consumer Group riêng
- Load tự động phân bố đều qua các shards

Thay vì dùng Transaction `MULTI/EXEC` dễ gây tranh chấp và không thực hiện được các logic kiểm tra đọc-sửa-ghi phức tạp, cấu trúc Outbox của Cart Service đẩy thẳng lệnh tạo Message vào trong lòng **Lua Script**. Bất kể thao tác Thêm (`CART_ITEM_ADDED`), Sửa (`CART_ITEM_UPDATED`) hay Xóa (`CART_ITEM_DELETED` / `CART_CLEARED`), Event Outbox đều được sinh ra đồng thời với lệnh thay đổi Store gốc. Xem danh sách đầy đủ Event tại [Events Structure](cart_events.md).

> **Boundary quan trọng:** Nhánh cache-miss rehydrate là luồng phục hồi dữ liệu từ Cold Store, không phải business write. Vì vậy script rehydrate phải **silent** (không `XADD`), tránh tạo event replay rác và phá idempotency downstream.

Ví dụ mô phỏng bên trong Script cấu trúc Lua (xem code đầy đủ tại [Caching Schema](cart_caching_schema.md#31-thêm-add--cập-nhật-update-item-ngăn-chặn-race-condition)):
```lua
-- ... logic tính toán new_qty ...
-- KEYS[1]: cart_key dùng Hash Tag (VD: {cart}:550e8400-e29b-41d4-a716-446655440000)
-- KEYS[2]: global stream shard (VD: {cart}:events:0)
redis.call('HSET', KEYS[1], ARGV[1], encoded_json)
redis.call('EXPIRE', KEYS[1], 604800)

-- Build event payload bổ sung cart_id + sku_id + user_id cho downstream consumer
local event_obj = cjson.decode(encoded_json)
event_obj.cart_id = KEYS[1]
event_obj.sku_id = ARGV[1]
event_obj.user_id = ARGV[7]  -- ARGV[7]: user_id truyền từ Backend

-- Điểm mấu chốt: XADD vào GLOBAL STREAM SHARD
redis.call('XADD', KEYS[2], '*', 'event_type', 'CART_ITEM_UPDATED', 'payload', cjson.encode(event_obj))
```
*Vì chạy chung một Script, Redis khóa Single-thread đánh sập toàn khối nếu rớt giữa chừng. Data trên Redis either CÓ CẢ HAI hoặc KHÔNG CÓ GÌ. Tính Atomicity được bảo toàn tuyệt đối mà không vi phạm chuẩn Hash Tag của Redis Cluster.*

> **Lưu ý kiến trúc:** Event payload đẩy vào Stream **bắt buộc phải chứa `cart_id`, `sku_id` và `user_id`** (bổ sung bằng `cjson.encode` trong Lua), dù Hash Value không lưu các field này (vì đã implicit trong Key/Field). Consumer Worker cần chúng để biết ghi vào bảng DB nào và upsert bảng `carts` mà không parse chuỗi `cart_id`. Xem cấu trúc payload chi tiết tại [Events Structure](cart_events.md#2-chi-tiết-các-loại-event).

> **Định danh Deterministic (Fix 2):** Cấu trúc Outbox phụ thuộc vào Relay Worker. Khi đọc Stream message (VD: ID `170853000-0`), Worker tuyệt đối KHÔNG được sinh ngẫu nhiên UUID làm `event_id` cho Kafka. Worker bắt buộc sinh `event_id` theo cách deterministic từ `Stream ID + cart_id` (ví dụ UUIDv5 hoặc băm ổn định tương đương) để mọi lần retry vẫn ánh xạ về cùng một ID.
>
> **Contract ordering:** Relay Worker bắt buộc propagate `stream_id`, `stream_timestamp_ms`, `event_sequence` vào Kafka message. Downstream idempotency/out-of-order guard dùng `stream_id` làm khóa ordering.

### 2.3. Stream Memory Management (Kiểm soát bộ nhớ Stream)

Redis Streams **không tự xóa message** sau khi Consumer đọc xong. Cần kết hợp nhiều cơ chế:

1.  **Tuyệt đối không dùng `MAXLEN` rủi ro trên `XADD`:** Lệnh `XADD` bỏ `MAXLEN` để chống hiện tượng mất event chưa ACK khi hệ thống kẹt.
2.  **`XACK` chỉ đánh dấu đã xử lý**, KHÔNG xóa message khỏi Stream. Sau khi [Outbox Relay Worker](cart_workers.md#1-outbox-relay-producer-worker) forward event sang Kafka thành công, gọi `XACK` để đánh dấu message đã được consume trong Consumer Group (tránh re-delivery cho cùng group).
3.  **`XTRIM MINID` định kỳ (CronJob bổ sung):** Stream Trimmer chạy mỗi 5 phút quét 4 shards và gọi `XTRIM {cart}:events:{shard} MINID ~ <lowest_safe_id>`. Nếu stream rỗng và không còn message pending, gọi `DEL` xóa hẳn stream.
    - **Guard bắt buộc chống race:** Nếu `XLEN > 0` nhưng `XINFO GROUPS` trả về rỗng (chưa có Consumer Group), Trimmer phải **bỏ qua (skip)** stream ở chu kỳ hiện tại, tuyệt đối không trim/delete.
4.  **Luồng Đọc của Relay Worker (Đơn giản hóa):**
    - Relay Worker sử dụng `XREADGROUP` trên mỗi shard stream để đọc messages.
    - Mỗi shard có Consumer Group riêng: `cart_relay_group:{shard_id}`.
    - Worker chạy parallel trên 4 shards để xử lý events.
    - Redis Streams Consumer Group tự động quản lý pending messages và retry.

> **Phân biệt quan trọng:**
> - `XACK`: Đánh dấu message đã xử lý trong Consumer Group → tránh re-delivery.
> - `XDEL` / `XTRIM` / `DEL`: Thực sự xóa/cắt message khỏi Stream, phá nát stream metadata khi trống rỗng → giải phóng RAM.
> - Thiếu `XTRIM` và dọn nhóm rỗng sẽ gây **memory leak dần dần** dù đã `XACK` hết.

### 2.4. Redis Cluster Hash Tag (Constraint bắt buộc)

Lua Script đưa ra yêu cầu **tất cả KEYS nằm trên cùng một Redis node**. Trong Redis Cluster, nếu `KEYS[1]` (cart hash) và `KEYS[2]` (global stream shard) nằm khác slot, Redis sẽ trả lỗi `CROSSSLOT Keys in request don't hash to the same slot` và **Lua Script sẽ thất bại hoàn toàn**.

**Giải pháp bắt buộc — Redis Hash Tag:**

| Key | Format | Ví dụ |
|:---|:---|:---|
| Cart Hash (KEYS[1]) | `{cart}:{user_id}` | `{cart}:550e8400-e29b-41d4-a716-446655440000` |
| Global Stream Shard (KEYS[2]) | `{cart}:events:{shard_id}` | `{cart}:events:0` |

Phần `{...}` trong tên key là Hash Tag — Redis Cluster chỉ hash phần nằm trong `{}` để quyết định slot. Vì cả hai key cùng chứa `{cart}`, Redis đảm bảo chúng vào cùng một slot → Lua Script vẫn atomic.

**Lưu ý về Sharding:**
- Mỗi shard stream có Hash Tag riêng: `{cart}:events:0`, `{cart}:events:1`, v.v.
- Cart Hash và Stream Shard cùng nằm trong hash slot của `{cart}`.
- Shard ID được tính từ CRC32 hash của `user_id` modulo số shards.

> **Quan trọng:** Tất cả code Lua trong các thiết kế phải sử dụng format key với Hash Tag. Backend khi xây dựng `KEYS[]` để gọi Lua cũng phải đảm bảo truyền key đã có Hash Tag và tính đúng shard ID.

## 3. Lợi ích Đạt Được

1.  **Đảm bảo At-least-once Delivery:** Dù Service Crash hay Kafka chết mạng, Event không bao giờ bị mất đi, chúng nằm an toàn dưới dạng Stream lưu trên Redis đợi ngày mạng lên lại sẽ tự chạy. Stream được bảo vệ dung lượng qua cơ chế Trim MINID an toàn nhưng đủ buffer cho hàng giờ downtime.
2.  **Ordering mạnh hơn cho downstream:** `stream_id` (Redis message ID) là ordering token tự nhiên, không phụ thuộc clock của Pod ứng dụng.
3.  **Độ trễ thấp hoàn hảo:** Khách hàng bấm "Thêm giỏ hàng", lệnh lưu vào Redis siêu gọn (Dưới 5ms), user nhận HTTP 200 OK ngay lập tức. Việc Kafka bị chậm hay lỗi hoàn toàn bị cô lập khỏi Main Thread của HTTP API.
4.  **Hưởng lợi từ Redis Persistence:** Vì Redis hỗ trợ AOF (Append Only File) persistence, khả năng mất dữ liệu trong Redis Stream cũng được giảm tối thiểu nếu Server Reboot.
5.  **Pending Message Recovery:** Khi Relay Worker crash giữa chừng (đã đọc nhưng chưa `XACK`), Redis Streams tự động giữ message ở trạng thái Pending. Worker sau khi restart sẽ chạy `XAUTOCLAIM` / `XPENDING` để nhặt lại các message bị mắc kẹt. Xem chi tiết tại [Outbox Relay Worker](cart_workers.md#1-outbox-relay-producer-worker).
