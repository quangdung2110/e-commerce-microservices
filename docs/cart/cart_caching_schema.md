# Cart Service - Caching Schema Design (Redis-First)

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](cart_workflow.md)
*   [Background Workers & Jobs](cart_workers.md)
*   [Caching Schema (Redis-First)](cart_caching_schema.md)
*   [Database Schema (Cold Store)](cart_db_schema.md)
*   [Outbox Pattern (Redis Streams)](cart_outbox_pattern.md)
*   [Idempotency Pattern](cart_idempotency.md)
*   [Events Structure](cart_events.md)

---
Tài liệu này mô tả chi tiết thiết kế lưu trữ chính (Primary Store) của Cart Service sử dụng Redis. Với đặc thù của giỏ hàng là Read/Write rải rác nhưng cần độ trễ cực thấp (dưới 10ms) để giữ trải nghiệm người dùng luân chuyển qua lại các trang, chiến lược **Redis-First** được áp dụng triệt để.

## 1. Nguyên Tắc Thiết Kế Cốt Lõi

1.  **Nguồn sự thật chính (Source of Truth — phân tách rõ ràng):**
    - **Redis** là Source of Truth cho **Cart State**: "User muốn mua SKU nào, số lượng bao nhiêu, thêm vào lúc nào". CRUD cart items được thực hiện trực tiếp trên Redis.
    - **Product Service** là Source of Truth cho **Product State**: giá hiện tại (`current_price`), trạng thái khả dụng (`is_available`), thông tin catalog. Hai SoT này độc lập nhau và không conflict.
2.  **Độ trễ thấp & Tối ưu Băng thông:** Dữ liệu giỏ hàng được thiết kế để khi gọi GET chỉ mất 1 network call tới Redis là đã đủ data phi chuẩn (denormalized) để render hiển thị mà không cần join database.
3.  **Read-Only TTL:** Các API Read hit cache (`GET /carts` đọc trúng Redis) tuyệt đối không gọi lệnh kéo dài TTL (Expiration). Việc gia hạn TTL (ví dụ 7 ngày) chỉ được thực hiện ở API Write hoặc ở nhánh **cache-miss rehydrate** (vì đây là write-recovery).
4.  **Triệt tiêu Write Storm (Pull Model over Push):** Hoàn toàn KHÔNG sử dụng Reverse Index (bảng tra ngược từ SKU ra Carts) để tránh làm sập hệ thống mỗi khi một sản phẩm Hot cập nhật giá. Thay vào đó, thiết kế áp dụng mô hình **Pull Model**: Cart Service chỉ lấy giá mới nhất dựa trên gRPC khi user truy cập giỏ hàng hoặc Checkout. Worker đồng bộ snapshot giá đã được loại bỏ để tập trung hoàn toàn hiệu năng cho luồng Checkout.

## 2. Cấu trúc Key & Kiểu Dữ liệu

Dữ liệu được lưu dưới dạng **Redis Hash** (`HSET`, `HGETALL`). Mỗi Key đại diện cho một giỏ hàng, Field đại diện cho một SKU (sản phẩm biến thể cụ thể), và Value là chuỗi JSON chứa toàn bộ dữ liệu.

### 2.1. Naming Convention (Key Pattern)

> **Quy ước cứng không thay đổi:** `cart_id` trong toàn bộ hệ thống (DB, Events, Kafka) có giá trị bằng đúng Redis Key này. Nếu format key Redis thay đổi, cần migration đồng bộ toàn bộ hệ thống.

Chỉ hỗ trợ người dùng đã đăng nhập:
*   **Logged-in User:** `{cart}:{user_id}` (Ví dụ: `{cart}:550e8400-e29b-41d4-a716-446655440000`)

> **Redis Cluster Hash Tag:** Key sử dụng Hash Tag `{cart}` để đảm bảo cart key và global stream shard key nằm cùng hash slot trong Redis Cluster, cho phép Lua Script thực thi atomic mà không gặp lỗi `CROSSSLOT`. Global Stream Shard key là `{cart}:events:{shard_id}` (shard_id = crc32(user_id) % 4). Xem chi tiết tại [Outbox Pattern — Redis Cluster](cart_outbox_pattern.md#24-redis-cluster-hash-tag).

### 2.2. Value Structure (Thành phần JSON)
Mỗi Field/Value trong Hash map với một món hàng.

*   **Hash Field:** `{sku_id}` (Ví dụ: `sku-1001`)
*   **Hash Value:** Trữ một payload JSON tĩnh (phi chuẩn hóa) để hiển thị nhanh (Snapshot tại thời điểm Add).

```json
{
  "product_id": "prod-uuid",
  "quantity":   2,
  "unit_price_at_add": 150000,
  "product_name": "Áo Thun Nam",
  "variant_info": "Màu Đen, Size L",
  "image_url": "https://cdn.domain.com/abc.jpg",
  "is_available": true,
  "added_at": "1708530000123",
  "updated_at": "1708616400456"
}
```

*Lưu ý contract: `current_price` KHÔNG nằm trong Redis Hash Value. Đây là field runtime chỉ xuất hiện ở response của API đọc giỏ, được tính bằng Pull Model từ Product Service (`promotional_price ?? original_price`).*

*Lưu ý: `updated_at` (Epoch timestamp tính bằng **Milliseconds**) được lưu dưới dạng **chuỗi (String)** để tránh lỗi mất độ chính xác (Precision Loss) từ định dạng khoa học của thư viện `cjson` trên Redis.*

*Lưu ý quan trọng về thứ tự sự kiện:* `updated_at` trong payload chỉ là business timestamp để hiển thị/timeline mềm. Khóa ordering/idempotency chính cho downstream là **Redis Stream Entry ID** (VD: `1708616400456-0`) do `XADD` sinh ra và Relay Worker đọc từ `msg.ID`.

> **Lý do dùng Milliseconds:** Epoch millisecond năm 2026 ≈ 13 chữ số, nằm trong ngưỡng safe integer của JavaScript (2^53). Epoch microsecond ≈ 16 chữ số vượt ngưỡng này, gây mất precision khi truyền qua JSON tới client JS. Với Rate Limit 100 req/s/user, collision trong 1ms là bất khả thi.

*Lưu ý về `is_available`: Nếu gRPC Pull Model trả về `is_available = false` (product BANNED/deleted/out-of-stock) trong luồng Cache Hit, **bắt buộc ghi lại field `is_available = false` vào Redis Hash** bằng `HSET cart_key sku_id updated_json` để bảo vệ các request đọc tiếp theo và luồng Checkout. Đây là ngoại lệ duy nhất của Read-Only TTL rule — chỉ ghi 1 field, không EXPIRE.*

## 3. Các Luồng Thực Thi Điển Hình (Pipelines)

Để đảm bảo hiệu năng và tránh tranh chấp (Race condition) trên 1 Key, các lệnh được gộp thành **Redis Pipeline** hoặc **Lua Script**.

### 3.1. Thêm (Add) / Cập nhật (Update) Item ngăn chặn Race Condition

Nếu áp dụng cách thức Read-Modify-Write thông thường ở mức Application (GET chuỗi JSON -> Decode -> `quantity + step` -> Encode -> HSET), hệ thống sẽ gặp lỗi **Race Condition** khi request đến song song (ví dụ: 3 luồng cùng rước chung `quantity = 1` và cùng đẩy xuống `quantity = 2`, làm mất lượt click của khách).

Do đó, để đảm bảo tính Atomicity tuyệt đối, Service thi hành một khối **Lua Script** nhỏ thực thi nguyên tử trực tiếp trên Redis server:

**Kịch bản thao tác của Lua Script:**
*   **KEYS[1]:** `$cart_key` — Key Hash giỏ hàng (VD: `{cart}:550e8400-e29b-41d4-a716-446655440000`)
*   **KEYS[2]:** `$global_stream_shard` — Global Stream Shard đích (VD: `{cart}:events:0` — cùng Hash Tag `{cart}` để đảm bảo cùng slot)
*   **ARGV[1]:** `$sku_id` — Mã SKU cần thêm/sửa
*   **ARGV[2]:** `$payload_json` — Chuỗi JSON đầy đủ của item mới (Backend tự build theo [Value Structure](#22-value-structure-thành-phần-json))
*   **ARGV[3]:** `$max_qty` — Giới hạn tối đa số lượng mỗi SKU (Default: 100)
*   **ARGV[4]:** `$max_skus` — Giới hạn tối đa số loại SKU trong giỏ (Default: 50)
*   **ARGV[5]:** `$step` — Số lượng cộng thêm (VD: 1 hoặc -1)
*   **ARGV[6]:** `$event_time_ms` — Epoch **milliseconds** từ Backend (business timestamp; không phải ordering arbiter)
*   **ARGV[7]:** `$user_id` — User ID dùng để build event payload
*   **ARGV[8]:** `$num_shards` — Số lượng shards (Default: 4) — dùng để tính shard ID

**Luồng xử lý:**
0. Validate input bắt buộc: `step` phải là số nguyên khác 0, `payload_json` phải là JSON hợp lệ và giới hạn kích thước ở tầng Backend (khuyến nghị <= 8KB/item).
1. Kiểm tra giới hạn số loại mặt hàng (SKU) tối đa trong giỏ: `if redis.call('HLEN', KEYS[1]) >= tonumber(ARGV[4]) and redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then return {err="Cart Full"} end` (Chặn tấn công phình to Hash Key gây nghẽn Redis).
2. Đọc Hash Value của `$sku_id` trong `$cart_key` (`redis.call('HGET', KEYS[1], ARGV[1])`).
3. Nếu chưa tồn tại → Validate `step > 0` (không cho phép giảm số lượng item chưa tồn tại). Sử dụng trực tiếp `$payload_json` (ARGV[2]) mà Backend đã build sẵn với `quantity = step` và `added_at` = `updated_at` = `$current_timestamp_ms`.
4. Nếu đã tồn tại → Parse JSON hiện tại và JSON mới. Cộng dồn: `new_qty = current_qty + step`. Cập nhật `updated_at` bằng chuỗi (String), bảo toàn `added_at`, đồng thời áp dụng snapshot mới nhất cho các field hiển thị như `is_available`.
5. Validate chặn trần giới hạn mua của từng Item: `if new_qty > tonumber(ARGV[3]) then new_qty = tonumber(ARGV[3]) end`.
6. Stringify lại JSON và ghi đè (`redis.call('HSET', KEYS[1], ARGV[1], encoded_json)`).
7. **Build event payload** bổ sung `cart_id`, `sku_id` và `user_id` vào JSON rồi đẩy vào Global Stream Shard bằng `XADD`. ID do `XADD` trả về (Stream Entry ID) là ordering token chính cho downstream. Cắt bỏ MAXLEN để tránh mất event chưa ACK, delegate việc dọn dẹp bộ nhớ cho Stream Trimmer (sử dụng XTRIM MINID).
8. Gia hạn vòng đời (EXPIRE 7 days).

**Code Lua Script tham khảo:**
```lua
-- Tham số truyền vào
-- KEYS[1]: cart_key (VD: {cart}:550e8400-e29b-41d4-a716-446655440000)
-- KEYS[2]: global_stream_shard (VD: {cart}:events:0 — cùng Hash Tag {cart})
-- ARGV[1]: sku_id
-- ARGV[2]: payload JSON (Backend tự build, timestamp dùng Epoch Milliseconds)
-- ARGV[3]: max_qty (Giới hạn của từng món)
-- ARGV[4]: max_skus (Giới hạn tổng số món trong giỏ, khuyến nghị 50)
-- ARGV[5]: step (Số lượng cộng thêm, VD: 1 hoặc -1)
-- ARGV[6]: event_time_ms (Milliseconds epoch từ Backend; dùng cho business timestamp)
-- ARGV[7]: user_id (Cần bổ sung vào event payload để Worker upsert bảng carts)
-- ARGV[8]: num_shards (Số lượng shards, mặc định 4)

-- 1. Chặn Hack/Bot làm phình to Hash Key (Giới hạn số SKUs)
if redis.call('HLEN', KEYS[1]) >= tonumber(ARGV[4]) and redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then
    return {err="Cart Full"}
end

-- 1.1 Validate step
local parsed_step = tonumber(ARGV[5])
if not parsed_step or parsed_step == 0 then
    return {err="Step Invalid"}
end

-- 2. Đọc Item hiện tại
local current_item = redis.call('HGET', KEYS[1], ARGV[1])
local encoded_json = ARGV[2]
local is_new = 1

-- 3. Xử lý logic Cộng dồn
if current_item then
    is_new = 0
    local old_item_obj = cjson.decode(current_item)
    local new_item_obj = cjson.decode(ARGV[2])

    local step = parsed_step
    local new_qty = old_item_obj.quantity + step
    local max_qty = tonumber(ARGV[3])

    -- Chặn trần số lượng
    if new_qty > max_qty then
        new_qty = max_qty
    end

    -- Không cho âm (Sẽ xử lý xóa ở API khác hoặc trả lỗi)
    if new_qty <= 0 then
        return {err="Quantity Invalid"}
    end

    -- Cập nhật Object với dữ liệu snapshot mới nhất từ request
    new_item_obj.quantity = new_qty
    -- Lưu timestamp dạng chuỗi để tránh lỗi hiển thị khoa học của cjson
    new_item_obj.updated_at = tostring(ARGV[6])
    new_item_obj.added_at = tostring(old_item_obj.added_at) -- giữ lại timestamp Add ban đầu

    encoded_json = cjson.encode(new_item_obj)
else
    -- Item mới: step phải dương (không cho giảm item chưa tồn tại)
    if parsed_step < 0 then
        return {err="Cannot decrement non-existing item"}
    end
end

-- 4. Thực thi Hash Set
redis.call('HSET', KEYS[1], ARGV[1], encoded_json)

-- 5. Gia hạn vòng đời giỏ hàng thêm 7 ngày (604800 giây)
redis.call('EXPIRE', KEYS[1], 604800)

-- 6. Build Event Payload (BỔ SUNG cart_id + sku_id + user_id vào JSON cho Consumer Worker)
--    Hash Value không lưu cart_id/sku_id/user_id (redundant với Key/Field + ARGV),
--    nhưng Event Payload gửi xuống Kafka BẮT BUỘC phải có để Worker biết ghi vào đâu.
local event_obj = cjson.decode(encoded_json)
event_obj.cart_id = KEYS[1]
event_obj.sku_id = ARGV[1]
event_obj.user_id = ARGV[7]
local event_payload = cjson.encode(event_obj)

local event_name = "CART_ITEM_ADDED"
if is_new == 0 then
    event_name = "CART_ITEM_UPDATED"
end

-- 7. Atomicity: Đẩy Event xuống GLOBAL STREAM SHARD (bỏ MAXLEN để tránh mất event khi relay bị chậm)
--    stream_id được Relay Worker đọc từ Redis message ID (msg.ID) để làm ordering/idempotency key.
local stream_id = redis.call('XADD', KEYS[2], '*', 'event_type', event_name, 'payload', event_payload)

return encoded_json
```

Vì Redis thực thi Lua Script ở dạng đơn luồng (Single-threaded), kịch bản này triệt tiêu hoàn toàn Race Condition giữa các HTTP Requests và quá trình tạo Event Outbox mà không cần phân mảnh bước gọi.

> **Lưu ý về Sharding:** Shard ID được tính từ CRC32 hash của user_id modulo số shards. Backend phải tính shard ID và truyền đúng stream key shard vào KEYS[2] trước khi gọi Lua.

> **Lưu ý Negative Cache Invalidation:** Sau mỗi lần Write thành công (Add/Update/Delete), Backend phải gọi `DEL {cart}:{user_id}:notfound` để xóa cache âm 60 giây, tránh tình trạng vừa thêm hàng nhưng API đọc tiếp theo vẫn trả giỏ rỗng giả.


### 3.2. Đọc (Get) Giỏ hàng
Luồng đọc gọi cực nhiều nhưng tốn rất ít I/O.
```redis
HGETALL {cart}:550e8400-e29b-41d4-a716-446655440000
```
*(Nếu kết quả trả về rỗng, Service sẽ qua bước Cache-Miss Fallback để lấy từ Cold Store PostgreSQL lên và gọi Silent Rehydrate Lua Script (không `XADD`) để nạp lại dữ liệu vào Redis. Mục tiêu là phục hồi cache an toàn mà không sinh event rác.)*

### 3.3. Xóa Item (Batch Delete) sau Checkout hoặc Remove
Một giỏ hàng có thể có nhiều SKU. Khi người dùng bấm xóa nhiều món hàng cùng lúc, hoặc Checkout xong, hệ thống đưa toàn bộ danh sách `sku_ids` vào một lần chạy Lua duy nhất để tối ưu Network I/O và đảm bảo Atomicity.

> **Contract ORDER_PAID (chốt mới):** Dùng `sku_ids` và **xóa thẳng dòng SKU khỏi giỏ**. Không xử lý trừ quantity ở bước cleanup sau thanh toán.

**Code Lua Script tham khảo (Batch Delete / Full-SKU Delete):**
```lua
-- Tham số truyền vào
-- KEYS[1]: cart_key (VD: {cart}:550e8400-e29b-41d4-a716-446655440000)
-- KEYS[2]: global_stream_shard (VD: {cart}:events:0 — cùng Hash Tag {cart})
-- ARGV[1]: current_timestamp_ms (Epoch milliseconds truyền vào phải ở dạng chuỗi dài)
-- ARGV[2]: clear_event_type — Loại event phát khi giỏ hàng bị rỗng hoàn toàn
--          "CART_CLEARED"     : User chủ động xóa hết sản phẩm (default)
--          "CART_CHECKED_OUT" : Gọi từ Order Event Consumer Worker (#6) sau khi ORDER_PAID
-- ARGV[3]: user_id (Dùng để build event payload cho Worker)
-- ARGV[4]: sku_ids_json
--          - Với checkout: ["sku-1001","sku-1002",...]
--          - Với user manual delete: ["sku-1001","sku-1002",...]

local current_time_str = tostring(ARGV[1])
local user_id = ARGV[3]
local sku_ids = cjson.decode(ARGV[4])

local deleted_count = 0
local skus_to_delete = {} -- Mảng chứa các SKU cần xóa vật lý để gom nhóm HDEL

-- Tái sử dụng object để tối ưu bộ nhớ trong vòng lặp
local deleted_event_obj = {
    sku_id = "",
    cart_id = KEYS[1],
    user_id = user_id,
    deleted_at = current_time_str
}

-- 1. Quét qua mảng sku_ids và gom các SKU tồn tại để xóa batch
for i = 1, #sku_ids do
    local sku_id = sku_ids[i]
    local current_item = redis.call('HGET', KEYS[1], sku_id)
    
    if current_item then
        table.insert(skus_to_delete, sku_id)
        deleted_count = deleted_count + 1

        deleted_event_obj.sku_id = sku_id
        local deleted_payload = cjson.encode(deleted_event_obj)
        redis.call('XADD', KEYS[2], '*', 'event_type', 'CART_ITEM_DELETED', 'payload', deleted_payload)
    end
end

-- 2. Xóa hàng loạt (Batch HDEL) cho các dòng cần remove hoàn toàn
if deleted_count > 0 then
    -- Sử dụng unpack để truyền mảng thành các tham số rời rạc cho HDEL
    redis.call('HDEL', KEYS[1], unpack(skus_to_delete))
    
    -- Dọn dẹp Key nếu giỏ hàng rỗng
    local remaining_items = redis.call('HLEN', KEYS[1])
    if (remaining_items == 0) then
        redis.call('DEL', KEYS[1])
        local clear_obj = {cart_id = KEYS[1], user_id = user_id}
        if ARGV[2] == 'CART_CHECKED_OUT' then
            clear_obj.checked_out_at = current_time_str
        else
            clear_obj.cleared_at = current_time_str
        end
        redis.call('XADD', KEYS[2], '*', 'event_type', ARGV[2], 'payload', cjson.encode(clear_obj))
    end
end

return deleted_count
```

> **Lưu ý về partial checkout:** Nếu user chỉ checkout một phần giỏ, script chỉ xóa các `sku_id` có trong snapshot checkout. Các SKU không nằm trong danh sách vẫn giữ nguyên. Nếu `remaining_items > 0` sau xử lý, không phát `CART_CHECKED_OUT` và giỏ tiếp tục trạng thái `ACTIVE`. `CART_CHECKED_OUT` chỉ phát khi **toàn bộ** giỏ rỗng sau checkout.

> **Lưu ý về Sharding:** Shard ID được tính từ CRC32 hash của user_id modulo số shards. Backend phải tính shard ID và truyền đúng stream key shard vào KEYS[2] trước khi gọi Lua. Công thức: `shard_id = crc32(user_id) % 4`, `stream_key = "{cart}:events:" .. shard_id`.


### 3.5. Chiến lược Re-populate Cache (từ Cold Store)

Khi xảy ra Cache-Miss, App Server thực hiện tái tạo lại Redis Hash từ dữ liệu PostgreSQL. Để đảm bảo tính nhất quán dữ liệu (như đã định nghĩa ở [Mục 2.2](#22-value-structure-thành-phần-json)), quy trình mapping được chuẩn hóa như sau:

1. **Mapping Dữ liệu:**
   - Trường `sku_id` từ DB → Redis **Hash Field**.
   - Trường `product_snapshot` (JSONB) từ DB → **base JSON** cho Redis Hash Value.
   - **⚠️ Quan trọng:** `unit_price_at_add` được lưu ở **cột riêng** (`cart_items.unit_price_at_add`) trong DB, KHÔNG nằm trong `product_snapshot` JSONB. Khi rehydrate, Worker phải lấy giá trị này từ cột riêng và **inject vào JSON object** trước khi ghi lên Redis:
     ```go
     // Rehydrate: ghép unit_price_at_add từ cột riêng vào JSON trước khi ghi Redis
     var itemJSON map[string]interface{}
     json.Unmarshal([]byte(row.ProductSnapshot), &itemJSON)
     itemJSON["unit_price_at_add"] = row.UnitPriceAtAdd  // Inject từ cột riêng
     redisValue, _ := json.Marshal(itemJSON)
     ```
   - *Nếu thiếu bước inject này, Redis Hash Value sẽ không có `unit_price_at_add`, khiến API đọc giỏ trả về `null` cho trường lịch sử giá.*

2. **Merge với Product Service (gRPC Pull):**
   - Sau khi load từ DB, gọi trực tiếp `GetBatchProductSkus` qua gRPC.
   - **Xử lý kết quả gRPC:**
     - **Nếu thành công:** Merge `is_available` từ response vào từng item:
       - Nếu `is_available = false`: **ghi đè** `is_available` trong item JSON bằng `false`.
       - Nếu `is_available = true`: **giữ nguyên** `is_available` từ DB snapshot (vì DB có thể cũ hơn nhưng không có nghĩa là unavailable).
       - Lưu map `sku_id -> {is_available, current_price}` để build response.
     - **Nếu thất bại (timeout/error):**
       - **KHÔNG merge** `is_available` từ Product Service.
       - Giữ nguyên `is_available` từ `product_snapshot` trong DB.
       - Set `grpc_success = false` để response có `is_price_degraded = true`.
   - **Output của bước merge:** Danh sách items đã merge kèm `grpc_success` flag.

3. **Thực thi ghi qua Lua Script Timestamp Guard (Atomic Batch):**
   - Tuyệt đối TRÁNH lệnh `HMSET` ghi đè thẳng thừng vì sẽ dẫm nát thao tác Thêm vào giỏ hàng đang chạy song song của User.
   - Thao tác Re-hydrate phải được gọi qua một Lua Rehydrate Script: lặp qua và chỉ `HSET` lại các field nếu Field chưa tồn tại, hoặc Timestamp cũ ở Redis < Timestamp lấy từ Cold Store.
   - **is_available đã được merge chính xác từ bước 2**, nên Lua chỉ việc ghi JSON đã chứa giá trị đúng.
   - Luôn sử dụng Pipeline gọi Lua Script kèm thẻ `EXPIRE $cart_key 604800` để đặt lại 7 ngày vòng đời.
4. **Consistency Guard:**
   - Sau khi Re-populate, hệ thống trả về dữ liệu cho API Reader ngay lập tức.
   - Nếu `grpc_success = false`, response sẽ có `is_price_degraded = true` để UI hiển thị cảnh báo.
   - Các hành động Write sau đó sẽ tiếp tục sử dụng Lua Script để đảm bảo tính nguyên tử.

### 3.6. Silent Rehydrate Lua Script (Chuẩn triển khai bắt buộc)

Script này chỉ dùng cho nhánh cache miss, mục tiêu là nạp lại Redis từ Cold Store + dữ liệu Product đã merge.

**Input:**
- `KEYS[1]`: `cart_key` (với Hash Tag, VD: `{cart}:550e8400-e29b-41d4-a716-446655440000`)
- `ARGV[1]`: JSON array của item candidates đã merge (`[{"sku_id":"...","value":"{...}"}, ...]`)
- `ARGV[2]`: TTL seconds (mặc định `604800`)

**Ràng buộc:**
- Không được phát event (`XADD`) trong script này.
- Không thay đổi quantity ngoài dữ liệu đã merge từ DB.
- Chỉ HSET khi chưa có field hoặc bản incoming mới hơn theo `updated_at`.

```lua
local cart_key = KEYS[1]
local ttl_sec = tonumber(ARGV[2]) or 604800
local arr = cjson.decode(ARGV[1])

for i = 1, #arr do
    local sku_id = arr[i].sku_id
    local incoming_json = arr[i].value
    local incoming_obj = cjson.decode(incoming_json)
    local incoming_updated = tonumber(incoming_obj.updated_at) or 0

    local current_json = redis.call('HGET', cart_key, sku_id)
    -- Trường hợp 1: Item chưa có trong giỏ của Redis -> ghi vào
    if not current_json then
        redis.call('HSET', cart_key, sku_id, incoming_json)
    else
        -- Trường hợp 2: Item đã có trong giỏ của Redis (nguy cơ Race Condition khi trong lúc merge dữ liệu từ Database với gRPC của Product Service)
        local ok, current_obj = pcall(cjson.decode, current_json)
        if ok and current_obj then
            local current_updated = tonumber(current_obj.updated_at) or 0
            if incoming_updated > current_updated then
                redis.call('HSET', cart_key, sku_id, incoming_json)
            end
        end
    end
end

redis.call('EXPIRE', cart_key, ttl_sec)
return 1
```

**Hệ quả cần nhớ:**
- Rehydrate là write-recovery nên được phép đặt lại TTL.
- Rehydrate không sinh outbox event, do đó không gây replay event rác.

> **Tham khảo:** Chi tiết trình tự API khi hụt cache tại [Cart Workflow - API Đọc Giỏ](cart_workflow.md#3-api-lấy-thông-tin-giỏ-hàng-đọc).

> **Lưu ý ORDER_PAID:** Contract chuẩn của luồng checkout cho Cart cleanup là `cart_id` + `sku_ids` (xóa thẳng SKU line khỏi giỏ).
