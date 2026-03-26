# Cart Service - Redis Streams Outbox Pattern

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](cart_workflow.md)
*   [Background Workers & Jobs](cart_workers.md)
*   [Caching Schema (Redis-First)](cart_caching_schema.md)
*   [Database Schema (Cold Store)](cart_db_schema.md)
*   [Outbox Pattern (Redis Streams)](cart_outbox_pattern.md)
*   [Idempotency Pattern](cart_idempotency.md)
*   [Events Structure](cart_events.md)

---
Tài liệu này giải thích cách giải quyết bài toán **Dual-Write** trong kiến trúc của Cart Service thông qua việc sử dụng **Redis Streams** thay cho bảng Outbox truyền thống của Relational Database.

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

## 2. Giải pháp: Redis Streams làm Transactional Outbox

Vì Cart Service lấy Redis làm Primary Store, ta không thể dùng thư viện Outbox Pattern tiêu chuẩn (dành cho RDBMS SQL) được. Thay vào đó, ta tận dụng **Redis Streams** để làm Outbox.

### 2.1. Kiến Trúc Luồng Mới (Nguyên tử hóa lệnh Ghi bằng Lua Script)

Thay vì dùng Transaction `MULTI/EXEC` dễ gây tranh chấp và không thực hiện được các logic kiểm tra đọc-sửa-ghi phức tạp, cấu trúc Outbox của Cart Service đẩy thẳng lệnh tạo Message vào trong lòng **Lua Script**. Bất kể thao tác Thêm (`CART_ITEM_ADDED`), Sửa (`CART_ITEM_UPDATED`) hay Xóa (`CART_ITEM_DELETED` / `CART_CLEARED`), Event Outbox đều được sinh ra đồng thời với lệnh thay đổi Store gốc. Xem danh sách đầy đủ Event tại [Events Structure](cart_events.md).

> **Boundary quan trọng:** Nhánh cache-miss rehydrate là luồng phục hồi dữ liệu từ Cold Store, không phải business write. Vì vậy script rehydrate phải **silent** (không `XADD`), tránh tạo event replay rác và phá idempotency downstream.

Ví dụ mô phỏng bên trong Script cấu trúc Lua (xem code đầy đủ tại [Caching Schema](cart_caching_schema.md#31-thêm-add--cập-nhật-update-item-ngăn-chặn-race-condition)):
```lua
-- ... logic tính toán new_qty ...
-- KEYS[1]: cart_key dùng Hash Tag (VD: {cart:user:12345})
-- KEYS[2]: outbox stream cùng Hash Tag (VD: {cart:user:12345}:outbox)
redis.call('HSET', KEYS[1], ARGV[1], encoded_json)
redis.call('EXPIRE', KEYS[1], 604800)

-- Build event payload bổ sung cart_id + sku_id + user_id cho downstream consumer
local event_obj = cjson.decode(encoded_json)
event_obj.cart_id = KEYS[1]
event_obj.sku_id = ARGV[1]
event_obj.user_id = ARGV[7]  -- ARGV[7]: user_id truyền từ Backend
local event_payload = cjson.encode(event_obj)

-- Điểm mấu chốt: Nhét trực tiếp XADD vào cuối khối lệnh
redis.call('XADD', KEYS[2], '*', 'event_type', 'CART_ITEM_UPDATED', 'payload', event_payload)
```
*Vì chạy chung một Script, Redis khóa Single-thread đánh sập toàn khối nếu rớt giữa chừng. Data trên Redis either CÓ CẢ HAI hoặc KHÔNG CÓ GÌ. Tính Atomicity được bảo toàn tuyệt đối mà không vi phạm chuẩn Hash Tag của Redis Cluster.*

> **Lưu ý kiến trúc:** Event payload đẩy vào Stream **bắt buộc phải chứa `cart_id`, `sku_id` và `user_id`** (bổ sung bằng `cjson.encode` trong Lua), dù Hash Value không lưu các field này (vì đã implicit trong Key/Field). Consumer Worker cần chúng để biết ghi vào bảng DB nào và upsert bảng `carts` mà không parse chuỗi `cart_id`. Xem cấu trúc payload chi tiết tại [Events Structure](cart_events.md#2-chi-tiết-các-loại-event).

> **Định danh Deterministic (Fix 2):** Cấu trúc Outbox phụ thuộc vào Relay Worker. Khi đọc Stream message (VD: ID `170853000-0`), Worker tuyệt đối KHÔNG được sinh ngẫu nhiên UUID làm `event_id` cho Kafka. Worker bắt buộc sinh `event_id` theo cách deterministic từ `Stream ID + cart_id` (ví dụ UUIDv5 hoặc băm ổn định tương đương) để mọi lần retry vẫn ánh xạ về cùng một ID.

### 2.2. Stream Memory Management (Kiểm soát bộ nhớ Stream)

Redis Streams **không tự xóa message** sau khi Consumer đọc xong. Cần kết hợp nhiều cơ chế:

1.  **Tuyệt đối không dùng `MAXLEN` rủi ro trên `XADD`:** Lệnh `XADD` bỏ `MAXLEN` để chống hiện tượng mất event chưa ACK khi hệ thống kẹt.
2.  **`XACK` chỉ đánh dấu đã xử lý**, KHÔNG xóa message khỏi Stream. Sau khi [Outbox Relay Worker](cart_workers.md#1-outbox-relay-producer-worker) forward event sang Kafka thành công, gọi `XACK` để đánh dấu message đã được consume trong Consumer Group (tránh re-delivery cho cùng group).
3.  **`XTRIM MINID` định kỳ (CronJob bổ sung):** Một Goroutine background (Stream Trimmer) chạy mỗi 5 phút quét các stream và gọi `XTRIM {cart_key}:outbox MINID ~ <lowest_delivered_id>`. Nếu stream rỗng và không còn message pending, gọi `DEL` xóa hẳn stream và xóa khỏi ZSET `active_outbox_streams`.
4.  **Luồng Đọc Hybrid (Pub/Sub + ZSET Fallback) qua Application-Level Dual-Write:** 
    - Lệnh Lua tạo event chỉ ghi vào Hash và Stream của chính user đó (cùng Hash Tag).
    - Ngay sau khi Lua script thành công, ứng dụng (Go) chạy một background goroutine gọi `ZADD active_outbox_streams` và lệnh `PUBLISH outbox_notifications` độc lập. Điều này giúp né lỗi `CROSSSLOT` trên Redis Cluster do `active_outbox_streams` là global key.
    - Worker nhận real-time qua Pub/Sub, hoặc qua luồng Fallback định kỳ scan ZSET để chống rớt event. Để chống mọi lỗi do sập node Go ngay đúng lúc kẹp giữa đoạn chạy Lua và gọi ZADD, hệ thống có thêm một **Keyspace Reaper** quét toàn bộ stream định kỳ.

Xem chi tiết trong [Các Background Workers](cart_workers.md).

> **Phân biệt quan trọng:**
> - `XACK`: Đánh dấu message đã xử lý trong Consumer Group → tránh re-delivery.
> - `XDEL` / `XTRIM` / `DEL`: Thực sự xóa/cắt message khỏi Stream, phá nát stream metadata khi trống rỗng → giải phóng RAM.
> - Thiếu `XTRIM` và dọn nhóm rỗng sẽ gây **memory leak dần dần** dù đã `XACK` hết.

### 2.3. Redis Cluster Hash Tag (Constraint bắt buộc)

Lua Script đưa ra yêu cầu **tất cả KEYS nằm trên cùng một Redis node**. Trong Redis Cluster, nếu `KEYS[1]` (cart hash) và `KEYS[2]` (outbox stream) nằm khác slot, Redis sẽ trả lỗi `CROSSSLOT Keys in request don't hash to the same slot` và **Lua Script sẽ thất bại hoàn toàn**.

**Giải pháp bắt buộc — Redis Hash Tag:**

| Key | Format | Ví dụ |
|:---|:---|:---|
| Cart Hash (KEYS[1]) | `{cart:user:{user_id}}` | `{cart:user:12345}` |
| Outbox Stream (KEYS[2]) | `{cart:user:{user_id}}:outbox` | `{cart:user:12345}:outbox` |

Phần `{...}` trong tên key là Hash Tag — Redis Cluster chỉ hash phần nằm trong `{}` để quyết định slot. Vì cả hai key cùng chứa `{cart:user:12345}`, Redis đảm bảo chúng vào cùng một slot → Lua Script vẫn atomic.

**Sharding cho Outbox Stream:**

Mỗi cart có outbox stream riêng (`{cart:user:{user_id}}:outbox`) thay vì stream chia sẻ (`cart_outbox_stream_{shard_id}`). Điều này đảm bảo ordering per-cart tự nhiên mà không cần sharding index riêng. Stream Trimmer có thể duyệt qua ZSET `active_outbox_streams` (hoặc dùng `SCAN` dự phòng) để lấy danh sách toàn bộ các stream cần dọn dẹp dung lượng.

> **Quan trọng:** Tất cả code Lua trong các file thiết kế phải sử dụng format key với Hash Tag. Backend khi xây dựng `KEYS[]` để gọi Lua cũng phải đảm bảo truyền key đã có Hash Tag.

## 3. Lợi ích Đạt Được

1.  **Đảm bảo At-least-once Delivery:** Dù Service Crash hay Kafka chết mạng, Event không bao giờ bị mất đi, chúng nằm an toàn dưới dạng Stream lưu trên Redis đợi ngày mạng lên lại sẽ tự chạy. Stream được bảo vệ dung lượng qua cơ chế Trim MINID an toàn nhưng đủ buffer cho hàng giờ downtime.
2.  **Độ trễ thấp hoàn hảo:** Khách hàng bấm "Thêm giỏ hàng", lệnh lưu vào Redis siêu gọn (Dưới 5ms), user nhận HTTP 200 OK ngay lập tức. Việc Kafka bị chậm hay lỗi hoàn toàn bị cô lập khỏi Main Thread của HTTP API.
3.  **Hưởng lợi từ Redis Persistence:** Vì Redis hỗ trợ AOF (Append Only File) persistence, khả năng mất dữ liệu trong Redis Stream cũng được giảm tối thiểu nếu Server Reboot.
4.  **Pending Message Recovery:** Khi Relay Worker crash giữa chừng (đã đọc nhưng chưa `XACK`), Redis Streams tự động giữ message ở trạng thái Pending. Worker sau khi restart sẽ chạy `XAUTOCLAIM` / `XPENDING` để nhặt lại các message bị mắc kẹt. Xem chi tiết tại [Outbox Relay Worker](cart_workers.md#1-outbox-relay-producer-worker).
