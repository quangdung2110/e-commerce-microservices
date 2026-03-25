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
*   **Logged-in User:** `{cart:user:{user_id}}` (Ví dụ: `{cart:user:12345}`)

> **Redis Cluster Hash Tag:** Key sử dụng Hash Tag `{cart:user:{user_id}}` để đảm bảo cart key và outbox stream key nằm cùng hash slot trong Redis Cluster, cho phép Lua Script thực thi atomic mà không gặp lỗi `CROSSSLOT`. Xem chi tiết tại [Outbox Pattern — Redis Cluster](cart_outbox_pattern.md#23-redis-cluster-hash-tag).

### 2.2. Value Structure (Thành phần JSON)
Mỗi Field/Value trong Hash map với một món hàng.

*   **Hash Field:** `{sku_id}` (Ví dụ: `sku-1001`)
*   **Hash Value:** Trữ một payload JSON tĩnh (phi chuẩn hóa) để hiển thị nhanh (Snapshot tại thời điểm Add).

```json
{
  "product_id": "prod-uuid",
  "seller_id":  "seller-uuid",
  "seller_name": "Shop Tech Việt",
  "quantity":   2,
  "unit_price_at_add": 150000,
  "product_name": "Áo Thun Nam",
  "variant_info": "Màu Đen, Size L",
  "image_url": "https://cdn.domain.com/abc.jpg",
  "is_available": true,
  "added_at": "1708530000123",
  "updated_at": "1708616400456",
  "product_updated_at": "1708530000123"
}
```

*Lưu ý contract: `current_price` KHÔNG nằm trong Redis Hash Value. Đây là field runtime chỉ xuất hiện ở response của API đọc giỏ, được tính bằng Pull Model từ Product Service (`promotional_price ?? original_price`).*

*Lưu ý: `seller_name` được **denormalize (snapshot) tại thời điểm Add** — lấy từ response gRPC `GetProductSku` của Product Service (trường `seller_name`). Giá trị này phục vụ hiển thị tên nhóm shop mà không cần join thêm reference. Nếu shop đổi tên sau khi item đã được thêm, tên cũ vẫn hiển thị trên giỏ — điều này chấp nhận được vì giỏ hàng là ephemeral (vòng đời 7 ngày).*

*Lưu ý: `updated_at` và `product_updated_at` (Epoch timestamp tính bằng **Milliseconds**) được lưu dưới dạng **chuỗi (String)** để tránh lỗi mất độ chính xác (Precision Loss) từ định dạng khoa học của thư viện `cjson` trên Redis. Field `product_updated_at` chuyên dùng để chống Timeline Collision cho luồng Pull gRPC ngẫu nhiên (On-demand).*

> **Lý do dùng Milliseconds:** Epoch millisecond năm 2026 ≈ 13 chữ số, nằm trong ngưỡng safe integer của JavaScript (2^53). Epoch microsecond ≈ 16 chữ số vượt ngưỡng này, gây mất precision khi truyền qua JSON tới client JS. Với Rate Limit 100 req/s/user, collision trong 1ms là bất khả thi.

*Lưu ý về `is_available`: Nếu gRPC Pull Model trả về `is_available = false` (product BANNED/deleted/out-of-stock) trong luồng Cache Hit, **bắt buộc ghi lại field `is_available = false` vào Redis Hash** bằng `HSET cart_key sku_id updated_json` để bảo vệ các request đọc tiếp theo và luồng Checkout. Đây là ngoại lệ duy nhất của Read-Only TTL rule — chỉ ghi 1 field, không EXPIRE.*

## 3. Các Luồng Thực Thi Điển Hình (Pipelines)

Để đảm bảo hiệu năng và tránh tranh chấp (Race condition) trên 1 Key, các lệnh được gộp thành **Redis Pipeline** hoặc **Lua Script**.

### 3.1. Thêm (Add) / Cập nhật (Update) Item ngăn chặn Race Condition

Nếu áp dụng cách thức Read-Modify-Write thông thường ở mức Application (GET chuỗi JSON -> Decode -> `quantity + step` -> Encode -> HSET), hệ thống sẽ gặp lỗi **Race Condition** khi request đến song song (ví dụ: 3 luồng cùng rước chung `quantity = 1` và cùng đẩy xuống `quantity = 2`, làm mất lượt click của khách).

Do đó, để đảm bảo tính Atomicity tuyệt đối, Service thi hành một khối **Lua Script** nhỏ thực thi nguyên tử trực tiếp trên Redis server:

**Kịch bản thao tác của Lua Script:**
*   **KEYS[1]:** `$cart_key` — Key Hash giỏ hàng (VD: `{cart:user:12345}`)
*   **KEYS[2]:** `$outbox_stream` — Stream Outbox đích (VD: `{cart:user:12345}:outbox` — cùng Hash Tag để đảm bảo cùng slot)
*   **ARGV[1]:** `$sku_id` — Mã SKU cần thêm/sửa
*   **ARGV[2]:** `$payload_json` — Chuỗi JSON đầy đủ của item mới (Backend tự build theo [Value Structure](#22-value-structure-thành-phần-json))
*   **ARGV[3]:** `$max_qty` — Giới hạn tối đa số lượng mỗi SKU (Default: 100)
*   **ARGV[4]:** `$max_skus` — Giới hạn tối đa số loại SKU trong giỏ (Default: 50)
*   **ARGV[5]:** `$step` — Số lượng cộng thêm (VD: 1 hoặc -1)
*   **ARGV[6]:** `$current_timestamp_ms` — Epoch **milliseconds** hiện tại

**Luồng xử lý:**
0. Validate input bắt buộc: `step` phải là số nguyên khác 0, `payload_json` phải là JSON hợp lệ và giới hạn kích thước ở tầng Backend (khuyến nghị <= 8KB/item).
1. Kiểm tra giới hạn số loại mặt hàng (SKU) tối đa trong giỏ: `if redis.call('HLEN', KEYS[1]) >= tonumber(ARGV[4]) and redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then return {err="Cart Full"} end` (Chặn tấn công phình to Hash Key gây nghẽn Redis).
2. Đọc Hash Value của `$sku_id` trong `$cart_key` (`redis.call('HGET', KEYS[1], ARGV[1])`).
3. Nếu chưa tồn tại → Validate `step > 0` (không cho phép giảm số lượng item chưa tồn tại). Sử dụng trực tiếp `$payload_json` (ARGV[2]) mà Backend đã build sẵn với `quantity = step` và `added_at` = `updated_at` = `$current_timestamp_ms`.
4. Nếu đã tồn tại → Parse JSON hiện tại và JSON mới. Cộng dồn: `new_qty = current_qty + step`. Cập nhật `updated_at` bằng chuỗi (String), bảo toàn `added_at`, và chỉ cập nhật `is_available`/`product_updated_at` nếu bản từ request có phiên bản mới hơn.
5. Validate chặn trần giới hạn mua của từng Item: `if new_qty > tonumber(ARGV[3]) then new_qty = tonumber(ARGV[3]) end`.
6. Stringify lại JSON và ghi đè (`redis.call('HSET', KEYS[1], ARGV[1], encoded_json)`).
7. **Build event payload** bổ sung `cart_id`, `sku_id` và `user_id` vào JSON rồi đẩy vào Outbox Stream: `redis.call('XADD', KEYS[2], '*', 'event_type', event_name, 'payload', event_payload)`. Cắt bỏ MAXLEN để tránh mất event chưa ACK, delegate việc dọn dẹp bộ nhớ cho Stream Trimmer (sử dụng XTRIM MINID).
8. Gia hạn vòng đời (EXPIRE 7 days).

**Code Lua Script tham khảo:**
```lua
-- Tham số truyền vào
-- KEYS[1]: cart_key (VD: {cart:user:12345})
-- KEYS[2]: outbox_stream (VD: {cart:user:12345}:outbox — cùng Hash Tag với cart_key)
-- ARGV[1]: sku_id
-- ARGV[2]: payload JSON (Backend tự build, timestamp dùng Epoch Milliseconds)
-- ARGV[3]: max_qty (Giới hạn của từng món)
-- ARGV[4]: max_skus (Giới hạn tổng số món trong giỏ, khuyến nghị 50)
-- ARGV[5]: step (Số lượng cộng thêm, VD: 1 hoặc -1)
-- ARGV[6]: current_timestamp_ms (Milliseconds epoch tính tại Backend Server)
-- ARGV[7]: user_id (Cần bổ sung vào event payload để Worker upsert bảng carts)

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
    
    -- Chỉ cập nhật metadata Product nếu request mang version mới hơn
    local old_product_updated = tonumber(old_item_obj.product_updated_at) or 0
    local new_product_updated = tonumber(new_item_obj.product_updated_at) or 0
    if new_product_updated < old_product_updated then
        new_item_obj.is_available = old_item_obj.is_available
        new_item_obj.product_updated_at = tostring(old_item_obj.product_updated_at)
    else
        new_item_obj.product_updated_at = tostring(new_product_updated)
    end

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

-- 7. Atomicity: Đẩy Event xuống Outbox Stream (bỏ MAXLEN để tránh mất event khi relay bị chậm)
redis.call('XADD', KEYS[2], '*', 'event_type', event_name, 'payload', event_payload)

return encoded_json
```

Vì Redis thực thi Lua Script ở dạng đơn luồng (Single-threaded), kịch bản này triệt tiêu hoàn toàn Race Condition giữa các HTTP Requests và quá trình tạo Event Outbox mà không cần phân mảnh bước gọi.

### 3.2. Đọc (Get) Giỏ hàng
Luồng đọc gọi cực nhiều nhưng tốn rất ít I/O.
```redis
HGETALL {cart:user:12345}
```
*(Nếu kết quả trả về rỗng, Service sẽ qua bước Cache-Miss Fallback để lấy từ Cold Store PostgreSQL lên và gọi Silent Rehydrate Lua Script (không `XADD`) để nạp lại dữ liệu vào Redis. Mục tiêu là phục hồi cache an toàn mà không sinh event rác.)*

### 3.3. Xóa Item (Batch Delete) sau Checkout hoặc Remove
Một giỏ hàng có thể có nhiều SKU. Khi người dùng bấm xóa nhiều món hàng cùng lúc, hoặc Checkout xong một cơ số mặt hàng, hệ thống đưa toàn bộ danh sách `sku_ids` vào một lần chạy Lua duy nhất để tối ưu Network I/O và đảm bảo Atomicity:

**Code Lua Script tham khảo (Xóa Batch Items):**
```lua
-- Tham số truyền vào
-- KEYS[1]: cart_key (VD: {cart:user:12345})
-- KEYS[2]: outbox_stream (VD: {cart:user:12345}:outbox — cùng Hash Tag)
-- ARGV[1]: current_timestamp_ms (Epoch milliseconds truyền vào phải ở dạng chuỗi dài)
-- ARGV[2]: clear_event_type — Loại event phát khi giỏ hàng bị rỗng hoàn toàn
--          "CART_CLEARED"     : User chủ động xóa hết sản phẩm (default)
--          "CART_CHECKED_OUT" : Gọi từ Order Event Consumer Worker (#6) sau khi ORDER_PAID
-- ARGV[3]: checkout_timestamp_ms (Truyền 0 nếu là User tự xóa. Truyền timestamp đơn hàng để chống Ghost Delete)
-- ARGV[4]: idempotency_key (VD: 'cart:idemp:order_paid:123'. Truyền rỗng '' nếu là user tự xoá)
-- ARGV[5]: user_id (Dùng để build event payload cho Worker)
-- Từ ARGV[6] trở đi: Danh sách vô hạn các sku_id cần xóa (VD: sku-1001, sku-1002...)

-- Chốt Idempotency ngay từ đầu với trạng thái PROCESSING (TTL ngắn)
if ARGV[4] ~= '' then
    local idemp_ok = redis.call('SET', ARGV[4], 'PROCESSING', 'NX', 'EX', 300)
    if not idemp_ok then
        return 0 -- Event đã/đang được xử lý
    end
end

local deleted_count = 0
local checkout_time_ms = tonumber(ARGV[3]) or 0
local current_time_str = tostring(ARGV[1])
local has_runtime_error = false

-- 1. Quét qua mảng sku_ids để Xóa (HDEL) và đẩy Event (XADD)
for i = 6, #ARGV do
    local sku_id = ARGV[i]
    local current_item = redis.call('HGET', KEYS[1], sku_id)
    
    if current_item then
        -- Chống Ghost Delete khi Checkout:
        -- Chỉ xóa những item được thêm VÀO TRƯỚC khi checkout (added_at < checkout_time_ms).
        -- Item thêm đúng lúc hoặc sau checkout (added_at >= checkout_time_ms) là hàng user mua mới → GIỮ LẠI.
        local should_delete = true
        if checkout_time_ms > 0 then
            local item_obj = cjson.decode(current_item)
            local item_added_at = tonumber(item_obj.added_at) or 0
            if item_added_at >= checkout_time_ms then
                -- Item được thêm vào ĐÚNG LÚC hoặc SAU khi checkout → không xóa
                should_delete = false
            end
        end

        if should_delete then
            local ok, err = pcall(function()
                redis.call('HDEL', KEYS[1], sku_id)
                                
                local event_obj = {
                    sku_id = sku_id,
                    cart_id = KEYS[1],
                    user_id = ARGV[5],
                    deleted_at = current_time_str
                }
                local event_payload = cjson.encode(event_obj)
                redis.call('XADD', KEYS[2], '*', 'event_type', 'CART_ITEM_DELETED', 'payload', event_payload)
                deleted_count = deleted_count + 1
            end)
            
            if not ok then
                -- Lỗi runtime: không set DONE, để Kafka retry xử lý lại toàn bộ
                has_runtime_error = true
                return {err="Delete failed for "..sku_id..": "..tostring(err)}
            end
        end
    end
end

-- 2. Dọn dẹp Key nếu giỏ hàng rỗng (Tiết kiệm bộ nhớ)
if deleted_count > 0 then
    local remaining_items = redis.call('HLEN', KEYS[1])
    if (remaining_items == 0) then
        redis.call('DEL', KEYS[1])
        local clear_obj = {cart_id = KEYS[1], user_id = ARGV[5]}
        if ARGV[2] == 'CART_CHECKED_OUT' then
            clear_obj.checked_out_at = current_time_str
        else
            clear_obj.cleared_at = current_time_str
        end
        local clear_payload = cjson.encode(clear_obj)
        redis.call('XADD', KEYS[2], '*', 'event_type', ARGV[2], 'payload', clear_payload)
    end
end

-- 3. Ký giấy hoàn tất — CHỈ set DONE khi KHÔNG có lỗi runtime
-- Nếu has_runtime_error = true thì đã return lỗi ở trên, dòng này không bao giờ chạy
if ARGV[4] ~= '' then
    redis.call('SETEX', ARGV[4], 86400, 'DONE')
end

return deleted_count
```

> **Lưu ý về partial checkout:** Nếu user chỉ checkout một phần giỏ (không hết SKU), `remaining_items > 0` sau khi xóa → không phát `CART_CHECKED_OUT` → giỏ hàng tiếp tục ở trạng thái `ACTIVE`. Đây là hành vi đúng về nghiệp vụ. `CART_CHECKED_OUT` chỉ phát khi **toàn bộ** giỏ rỗng sau khi checkout.

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
2. **Thực thi ghi qua Lua Script Timestamp Guard (Atomic Batch):**
   - Tuyệt đối TRÁNH lệnh `HMSET` ghi đè thẳng thừng vì sẽ dẫm nát thao tác Thêm vào giỏ hàng đang chạy song song của User.
   - Thao tác Re-hydrate phải được gọi qua một Lua Rehydrate Script: lặp qua và chỉ `HSET` lại các field nếu Field chưa tồn tại, hoặc Timestamp cũ ở Redis < Timestamp lấy từ Cold Store.
   - Luôn sử dụng Pipeline gọi Lua Script kèm thẻ `EXPIRE $cart_key 604800` để đặt lại 7 ngày vòng đời.
3. **Consistency Guard:** 
   - Sau khi Re-populate, hệ thống trả về dữ liệu cho API Reader ngay lập tức.
   - Các hành động Write sau đó sẽ tiếp tục sử dụng Lua Script để đảm bảo tính nguyên tử.

### 3.6. Silent Rehydrate Lua Script (Chuẩn triển khai bắt buộc)

Script này chỉ dùng cho nhánh cache miss, mục tiêu là nạp lại Redis từ Cold Store + dữ liệu Product đã merge.

**Input:**
- `KEYS[1]`: `cart_key` (với Hash Tag, VD: `{cart:user:12345}`)
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
    if not current_json then
        redis.call('HSET', cart_key, sku_id, incoming_json)
    else
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

> **Lưu ý ORDER_PAID:** Contract chuẩn của luồng checkout là `cart_id` + `sku_ids` (tách riêng field).
