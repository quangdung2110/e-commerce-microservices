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

Ví dụ mô phỏng bên trong Script cấu trúc Lua (xem code đầy đủ tại [Caching Schema](cart_caching_schema.md#31-thêm-add--cập-nhật-update-item-ngăn-chặn-race-condition)):
```lua
-- ... logic tính toán new_qty ...
redis.call('HSET', KEYS[1], ARGV[1], encoded_json)
redis.call('EXPIRE', KEYS[1], 604800)

-- Build event payload bổ sung cart_id + sku_id cho downstream consumer
local event_obj = cjson.decode(encoded_json)
event_obj.cart_id = KEYS[1]
event_obj.sku_id = ARGV[1]
local event_payload = cjson.encode(event_obj)

-- Điểm mấu chốt: Nhét trực tiếp XADD vào cuối khối lệnh
redis.call('XADD', KEYS[2], '*', 'event_type', 'CART_ITEM_UPDATED', 'payload', event_payload)
```
*Vì chạy chung một Script, Redis khóa Single-thread đánh sập toàn khối nếu rớt giữa chừng. Data trên Redis either CÓ CẢ HAI hoặc KHÔNG CÓ GÌ. Tính Atomicity được bảo toàn tuyệt đối.*

> **Lưu ý kiến trúc:** Event payload đẩy vào Stream **bắt buộc phải chứa `cart_id` và `sku_id`** (bổ sung bằng `cjson.encode` trong Lua), dù Hash Value không lưu các field này (vì đã implicit trong Key/Field). Consumer Worker cần chúng để biết ghi vào bảng DB nào. Xem cấu trúc payload chi tiết tại [Events Structure](cart_events.md#2-chi-tiết-các-loại-event).

> **Định danh Deterministic (Fix 2):** Cấu trúc Outbox phụ thuộc vào Relay Worker. Khi đọc Stream message (VD: ID `170853000-0`), Worker tuyệt đối KHÔNG được sinh ngẫu nhiên UUID làm event_id cho Kafka. Nó BẮT BUỘC phải dùng thuật toán băm SHA-256 tạo ra UUIDv5 (sử dụng Hash của `Stream_ID` + `cart_id`) để đảm bảo không collision và có thể Idempotent ngược lại đúng event của Kafka. từ `Stream ID -> Kafka event_id`. Tính chất Monotonic của Stream ID sẽ giúp các event bị Forward Retry hàng chục lần vẫn ánh xạ ra đúng 1 ID duy nhất nhằm bảo vệ tính Idempotency.

### 2.2. Stream Memory Management (Kiểm soát bộ nhớ Stream)

Redis Streams **không tự xóa message** sau khi Consumer đọc xong. Cần kết hợp nhiều cơ chế:

1.  **Tuyệt đối không dùng `MAXLEN` rủi ro trên `XADD`:** Lệnh `XADD` bỏ `MAXLEN` để chống hiện tượng mất event chưa ACK khi hệ thống kẹt.
2.  **`XACK` chỉ đánh dấu đã xử lý**, KHÔNG xóa message khỏi Stream. Sau khi [Outbox Relay Worker](cart_workers.md#1-outbox-relay-producer-worker) forward event sang Kafka thành công, gọi `XACK` để đánh dấu message đã được consume trong Consumer Group (tránh re-delivery cho cùng group).
3.  **`XTRIM MINID` định kỳ (CronJob bổ sung):** Một Goroutine background chạy mỗi 5 phút gọi `XTRIM cart_outbox_stream_{shard_id}
   - **Sharding Algorithm:** Sử dụng Hash nhất quán (Consistent Hashing) thuật toán `CRC32(cart_id) % N` (với N là số lượng stream) để quyết định phân luồng. Đảm bảo mọi event của cùng 1 User Cart luôn chui vào đúng 1 Stream, giữ vững thứ tự (Strict Ordering).
   - **Sharding Algorithm:** Sử dụng Hash nhất quán (Consistent Hashing) thuật toán `CRC32(cart_id) % N` (với N là số lượng stream) để quyết định phân luồng. Đảm bảo mọi event của cùng 1 User Cart luôn chui vào đúng 1 Stream, giữ vững thứ tự (Strict Ordering). MINID ~ <id>` (với `<id>` là message ID an toàn nhỏ nhất đã được all groups consume) để dọn sạch stream an toàn. Xem [Stream Trimmer](cart_workers.md#5-stream-trimmer-background-goroutine).

> **Phân biệt quan trọng:**
> - `XACK`: Đánh dấu message đã xử lý trong Consumer Group → tránh re-delivery.
> - `XDEL` / `XTRIM`: Thực sự xóa message khỏi Stream → giải phóng RAM.
> - Thiếu `XTRIM` sẽ gây **memory leak dần dần** dù đã `XACK` hết.

## 3. Lợi ích Đạt Được

1.  **Đảm bảo At-least-once Delivery:** Dù Service Crash hay Kafka chết mạng, Event không bao giờ bị mất đi, chúng nằm an toàn dưới dạng Stream lưu trên Redis đợi ngày mạng lên lại sẽ tự chạy. Stream được bảo vệ dung lượng qua cơ chế Trim MINID an toàn nhưng đủ buffer cho hàng giờ downtime.
2.  **Độ trễ thấp hoàn hảo:** Khách hàng bấm "Thêm giỏ hàng", lệnh lưu vào Redis siêu gọn (Dưới 5ms), user nhận HTTP 200 OK ngay lập tức. Việc Kafka bị chậm hay lỗi hoàn toàn bị cô lập khỏi Main Thread của HTTP API.
3.  **Hưởng lợi từ Redis Persistence:** Vì Redis hỗ trợ AOF (Append Only File) persistence, khả năng mất dữ liệu trong Redis Stream cũng được giảm tối thiểu nếu Server Reboot.
4.  **Pending Message Recovery:** Khi Relay Worker crash giữa chừng (đã đọc nhưng chưa `XACK`), Redis Streams tự động giữ message ở trạng thái Pending. Worker sau khi restart sẽ chạy `XAUTOCLAIM` / `XPENDING` để nhặt lại các message bị mắc kẹt. Xem chi tiết tại [Outbox Relay Worker](cart_workers.md#1-outbox-relay-producer-worker).
