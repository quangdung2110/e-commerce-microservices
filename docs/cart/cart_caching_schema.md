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

1.  **Nguồn sự thật chính (Source of Truth):** Redis giữ bản ghi mới nhất và thao tác CRUD được thực hiện trực tiếp trên Redis.
2.  **Độ trễ thấp & Tối ưu Băng thông:** Dữ liệu giỏ hàng được thiết kế để khi gọi GET chỉ mất 1 network call tới Redis là đã đủ data phi chuẩn (denormalized) để render hiển thị mà không cần join database.
3.  **Read-Only TTL:** Các API Read (`GET /carts`) tuyệt đối không gọi lệnh kéo dài TTL (Expiration). Việc gia hạn TTL (ví dụ 7 ngày) chỉ được thực hiện đính kèm trong các API Write (Add/Update).
4.  **Triệt tiêu Write Storm (Pull Model over Push):** Hoàn toàn KHÔNG sử dụng Reverse Index (bảng tra ngược từ SKU ra Carts) để tránh làm sập hệ thống mỗi khi một sản phẩm Hot cập nhật giá. Thay vào đó, thiết kế áp dụng mô hình **Pull Model**: Cart Service chỉ lấy giá mới nhất dựa trên gRPC khi user truy cập giỏ hàng hoặc Checkout. Worker đồng bộ snapshot giá đã được loại bỏ để tập trung hoàn toàn hiệu năng cho luồng Checkout.

## 2. Cấu trúc Key & Kiểu Dữ liệu

Dữ liệu được lưu dưới dạng **Redis Hash** (`HSET`, `HGETALL`). Mỗi Key đại diện cho một giỏ hàng, Field đại diện cho một SKU (sản phẩm biến thể cụ thể), và Value là chuỗi JSON chứa toàn bộ dữ liệu.

### 2.1. Naming Convention (Key Pattern)
Chỉ hỗ trợ người dùng đã đăng nhập:
*   **Logged-in User:** `cart:user:{user_id}` (Ví dụ: `cart:user:12345`)

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
  "added_at": "1708530000123000",
  "updated_at": "1708616400456000",
  "product_updated_at": "1708530000123000"
}
```

*Lưu ý: `seller_name` được **denormalize (snapshot) tại thời điểm Add** — lấy từ response của Product Service `GET /api/v1/products/skus/{sku_id}` (trường `seller_name`). Giá trị này phục vụ hiển thị tên nhóm shop mà không cần join thêm reference. Nếu shop đổi tên sau khi item đã được thêm, tên cũ vẫn hiển thị trên giỏ — điều này chấp nhận được vì giỏ hàng là ephemeral (vòng đời 7 ngày).*

*Lưu ý: `updated_at` và `product_updated_at` (Epoch timestamp tính bằng Microseconds) được lưu dưới dạng **chuỗi (String)** để tránh lỗi mất độ chính xác (Precision Loss) từ định dạng khoa học của thư viện `cjson` trên Redis. Field `product_updated_at` chuyên dùng để chống Timeline Collision cho luồng Pull gRPC ngẫu nhiên (On-demand).*

## 3. Các Luồng Thực Thi Điển Hình (Pipelines)

Để đảm bảo hiệu năng và tránh tranh chấp (Race condition) trên 1 Key, các lệnh được gộp thành **Redis Pipeline** hoặc **Lua Script**.

### 3.1. Thêm (Add) / Cập nhật (Update) Item ngăn chặn Race Condition

Nếu áp dụng cách thức Read-Modify-Write thông thường ở mức Application (GET chuỗi JSON -> Decode -> `quantity + step` -> Encode -> HSET), hệ thống sẽ gặp lỗi **Race Condition** khi request đến song song (ví dụ: 3 luồng cùng rước chung `quantity = 1` và cùng đẩy xuống `quantity = 2`, làm mất lượt click của khách).

Do đó, để đảm bảo tính Atomicity tuyệt đối, Service thi hành một khối **Lua Script** nhỏ thực thi nguyên tử trực tiếp trên Redis server:

**Kịch bản thao tác của Lua Script:**
*   **KEYS[1]:** `$cart_key` — Key Hash giỏ hàng (VD: `cart:user:12345`)
*   **KEYS[2]:** `$outbox_stream` — Stream Outbox đích (VD: `cart_outbox_stream_1`)
*   **ARGV[1]:** `$sku_id` — Mã SKU cần thêm/sửa
*   **ARGV[2]:** `$payload_json` — Chuỗi JSON đầy đủ của item mới (Backend tự build theo [Value Structure](#22-value-structure-thành-phần-json))
*   **ARGV[3]:** `$max_qty` — Giới hạn tối đa số lượng mỗi SKU (Default: 100)
*   **ARGV[4]:** `$max_skus` — Giới hạn tối đa số loại SKU trong giỏ (Default: 120)
*   **ARGV[5]:** `$step` — Số lượng cộng thêm (VD: 1 hoặc -1)
*   **ARGV[6]:** `$current_timestamp_ms` — Epoch milliseconds hiện tại

**Luồng xử lý:**
1. Kiểm tra giới hạn số loại mặt hàng (SKU) tối đa trong giỏ: `if redis.call('HLEN', KEYS[1]) >= tonumber(ARGV[4]) and redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then return {err="Cart Full"} end` (Chặn tấn công phình to Hash Key gây nghẽn Redis).
2. Đọc Hash Value của `$sku_id` trong `$cart_key` (`redis.call('HGET', KEYS[1], ARGV[1])`).
3. Nếu chưa tồn tại → Sử dụng trực tiếp `$payload_json` (ARGV[2]) mà Backend đã build sẵn với `quantity = step` và `added_at` = `updated_at` = `$current_timestamp_ms`.
4. Nếu đã tồn tại → Parse JSON hiện tại và JSON mới (để cập nhật giá mới nhất). Cộng dồn: `new_qty = current_qty + step`. Overwrite payload JSON mới thay vì giữ array cũ nhưng chỉ tăng quantity. Cập nhật `updated_at` bằng chuỗi (String) và bảo toàn `added_at`, `is_available`, `product_updated_at`.
5. Validate chặn trần giới hạn mua của từng Item: `if new_qty > tonumber(ARGV[3]) then new_qty = tonumber(ARGV[3]) end`.
6. Stringify lại JSON và ghi đè (`redis.call('HSET', KEYS[1], ARGV[1], encoded_json)`).
7. **Build event payload** bổ sung `cart_id` và `sku_id` vào JSON rồi đẩy vào Outbox Stream: `redis.call('XADD', KEYS[2], '*', 'event_type', event_name, 'payload', event_payload)`. Cắt bỏ MAXLEN để tránh mất event chưa ACK, delegate việc dọn dẹp bộ nhớ cho Stream Trimmer (sử dụng XTRIM MINID).
8. Gia hạn vòng đời (EXPIRE 7 days).

**Code Lua Script tham khảo:**
```lua
-- Tham số truyền vào
-- KEYS[1]: cart_key (VD: cart:user:12345)
-- KEYS[2]: outbox_stream (VD: cart_outbox_stream_1)
-- ARGV[1]: sku_id
-- ARGV[2]: payload JSON (Yêu cầu: Backend tự build chuỗi JSON, lưu ý lấy Epoch Microseconds hiện tại để gán vào update_at và added_at ở backend)
-- ARGV[3]: max_qty (Giới hạn của từng món)
-- ARGV[4]: max_skus (Giới hạn tổng số món trong giỏ)
-- ARGV[5]: step (Số lượng cộng thêm, VD: 1 hoặc -1)
-- ARGV[6]: current_timestamp_us (Microseconds epoch tính tại Backend Server)

-- 1. Chặn Hack/Bot làm phình to Hash Key (Giới hạn số SKUs)
if redis.call('HLEN', KEYS[1]) >= tonumber(ARGV[4]) and redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then 
    return {err="Cart Full"} 
end

-- 2. Đọc Item hiện tại
local current_item = redis.call('HGET', KEYS[1], ARGV[1])
local encoded_json = ARGV[2]
local is_new = 1

-- 3. Xử lý logic Cộng dồn (Nếu đã tồn tại)
if current_item then
    is_new = 0
    local old_item_obj = cjson.decode(current_item)
    local new_item_obj = cjson.decode(ARGV[2])
    
    local step = tonumber(ARGV[5])
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
    
    -- Cập nhật Object với dữ liệu snapshot giá/metadata mới nhất từ request
    new_item_obj.quantity = new_qty
    -- Fix 6: Lưu timestamp dạng chuỗi để tránh lỗi hiển thị khoa học e+15 của cjson
    new_item_obj.updated_at = tostring(ARGV[6])
    new_item_obj.added_at = tostring(old_item_obj.added_at) -- giữ lại timestamp Add ban đầu
    
    -- Fix 5 & 1: Bảo toàn các cờ trạng thái và phiên bản dữ liệu từ Product
    new_item_obj.is_available = old_item_obj.is_available
    new_item_obj.product_updated_at = tostring(old_item_obj.product_updated_at)
    encoded_json = cjson.encode(new_item_obj)
end

-- 4. Thực thi Hash Set ở tầng đầu tiên
redis.call('HSET', KEYS[1], ARGV[1], encoded_json)

-- 5. Gia hạn vòng đời giỏ hàng thêm 7 ngày (604800 giây)
redis.call('EXPIRE', KEYS[1], 604800)

-- 6. Build Event Payload (BỔ SUNG cart_id + sku_id vào JSON cho Consumer Worker)
--    Hash Value không lưu cart_id/sku_id (vì redundant với Key/Field),
--    nhưng Event Payload gửi xuống Kafka BẮT BUỘC phải có để Worker biết ghi vào đâu.
local event_obj = cjson.decode(encoded_json)
event_obj.cart_id = KEYS[1]
event_obj.sku_id = ARGV[1]
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
HGETALL cart:user:12345
```
*(Nếu kết quả trả về rỗng, Service sẽ qua bước Cache-Miss Fallback để lấy từ Cold Store PostgreSQL lên và tự build lại Pipeline HSET vào Redis. Lưu ý: Sử dụng lệnh HSET/HMSET thuần (Silent Rebuild) kèm EXPIRE mà KHÔNG sử dụng Lua Script ghi Outbox, để tránh sinh ra event rác gây ra lỗi TTL replay idempotency).*

### 3.3. Xóa Item (Batch Delete) sau Checkout hoặc Remove
Một giỏ hàng có thể có nhiều SKU. Khi người dùng bấm xóa nhiều món hàng cùng lúc, hoặc Checkout xong một cơ số mặt hàng, hệ thống đưa toàn bộ danh sách `sku_ids` vào một lần chạy Lua duy nhất để tối ưu Network I/O và đảm bảo Atomicity:

**Code Lua Script tham khảo (Xóa Batch Items):**
```lua
-- Tham số truyền vào
-- KEYS[1]: cart_key (VD: cart:user:12345)
-- KEYS[2]: outbox_stream (VD: cart_outbox_stream_1)
-- ARGV[1]: current_timestamp_us (Epoch truyền vào phải ở dạng chuỗi dài)
-- ARGV[2]: clear_event_type — Loại event phát khi giỏ hàng bị rỗng hoàn toàn
--          "CART_CLEARED"     : User chủ động xóa hết sản phẩm (default)
--          "CART_CHECKED_OUT" : Gọi từ Order Event Consumer Worker (#6) sau khi ORDER_PAID
-- -- Phụ lục tham số FIX 3 & 4 (Đặc thù Checkout) --
-- ARGV[3]: checkout_timestamp_us (Truyền 0 nếu là User tự xóa. Truyền timestamp đơn hàng để chống Ghost Delete)
-- ARGV[4]: idempotency_key (VD: 'cart:idemp:order_paid:123'. Truyền rỗng '' nếu là user tự xoá)
-- Từ ARGV[5] trở đi: Danh sách vô hạn các sku_id cần xóa (VD: sku-1001, sku-1002...)

-- Fix 4: Kiểm tra Idempotency nguyên tử ngay trong Lua (để dùng cho Order Check Out)
if ARGV[4] ~= '' then
    if redis.call('EXISTS', ARGV[4]) == 1 then
        return 0 -- Đã xử lý, an toàn thoát lệnh
    end
end

local deleted_count = 0
local delete_results = {}
local checkout_time_us = tonumber(ARGV[3]) or 0
local current_time_str = tostring(ARGV[1])

-- 1. Quét qua mảng sku_ids để Xóa (HDEL) và đẩy Event (XADD)
for i = 5, #ARGV do
    local sku_id = ARGV[i]
    local current_item = redis.call('HGET', KEYS[1], sku_id)
    
    if current_item then
        -- Fix 3: Chống Ghost Delete khi Checkout: Bỏ qua món đồ user mới Thêm lại sau khi chốt đơn.
        local should_delete = true
        if checkout_time_us > 0 then
            local item_obj = cjson.decode(current_item)
            local item_added_at = tonumber(item_obj.added_at) or 0
            if item_added_at > checkout_time_us then
    -- Sửa lỗi logic: item được thêm VÀO SAU khi checkout (nghĩa là user tiếp tục mua sắm)
    -- thì KHÔNG ĐƯỢC XÓA khỏi giỏ.
    should_delete = false
    -- Sửa lỗi logic: item được thêm VÀO SAU khi checkout (nghĩa là user tiếp tục mua sắm)
    -- thì KHÔNG ĐƯỢC XÓA khỏi giỏ.
    should_delete = false
                should_delete = false -- Hàng mới thêm sau checkout, không xóa
            end
        end

        if should_delete then
            -- Chống lỗi Atomicity cục bộ: Bọc Transaction ảo qua pcall
            local ok, err = pcall(function()
                redis.call('HDEL', KEYS[1], sku_id)
                                
                -- Fix 6: Gửi deleted_at qua dạng chuỗi
                local event_obj = {sku_id = sku_id, cart_id = KEYS[1], deleted_at = current_time_str}
                local event_payload = cjson.encode(event_obj)
                redis.call('XADD', KEYS[2], '*', 'event_type', 'CART_ITEM_DELETED', 'payload', event_payload)
                table.insert(delete_results, {sku_id=sku_id, status="OK"})
                deleted_count = deleted_count + 1
            end)
            
            if not ok then
                return {err="Delete failed for "..sku_id..": "..tostring(err)}
            end
        end
    end
end

-- 2. Dọn dẹp Key nếu giỏ hàng rỗng (Tiết kiệm bộ nhớ)
-- Chú ý: Chỉ kiểm tra khi có phát sinh thao tác xóa thành công hoặc nếu giỏ đã rỗng sẵn
if deleted_count > 0 then
    local remaining_items = redis.call('HLEN', KEYS[1])
    if (remaining_items == 0) then
        redis.call('DEL', KEYS[1])
        -- Bắn luôn event CART_CLEARED / CART_CHECKED_OUT để Master DB Table Update status sang DELETED / CHECKED_OUT
        local clear_obj = {cart_id = KEYS[1], cleared_at = current_time_str}
        if ARGV[2] == 'CART_CHECKED_OUT' then
            clear_obj = {cart_id = KEYS[1], checked_out_at = current_time_str}
        end
        local clear_payload = cjson.encode(clear_obj)
        redis.call('XADD', KEYS[2], '*', 'event_type', ARGV[2], 'payload', clear_payload)
    end
end

-- Fix 4: Ký giấy hoàn tất Checkout (Set Idempotency Key)
if ARGV[4] ~= '' then
    -- TTL 24h = 86400 giây. Thiết lập ngay trên Script để tránh Worker sập ở Bước 5.
    redis.call('SETEX', ARGV[4], 86400, '1')
end

return deleted_count
```

### 3.5. Chiến lược Re-populate Cache (từ Cold Store)

Khi xảy ra Cache-Miss, App Server thực hiện tái tạo lại Redis Hash từ dữ liệu PostgreSQL. Để đảm bảo tính nhất quán dữ liệu (như đã định nghĩa ở [Mục 2.2](#22-value-structure-thành-phần-json)), quy trình mapping được chuẩn hóa như sau:

1. **Mapping Dữ liệu:**
   - Trường `sku_id` từ DB → Redis **Hash Field**.
   - Trường `product_snapshot` (JSONB) từ DB → Redis **Hash Value** (JSON string).
   - *Lưu ý:* `product_snapshot` trong DB đã chứa đầy đủ các trường snapshot (`product_name`, `image_url`, `unit_price_at_add`, `added_at`, `updated_at`) nên có thể đẩy trực tiếp vào Redis mà không cần xử lý thêm.
2. **Thực thi ghi qua Lua Script Timestamp Guard (Atomic Batch - Fix 7):**
   - Tuyệt đối TRÁNH lệnh `HMSET` ghi đè thẳng thừng vì sẽ dẫm nát thao tác Thêm vào giỏ hàng đang chạy song song của User.
   - Thao tác Re-hydrate phải được gọi qua một Lua Rehydrate Script: lặp qua và chỉ `HSET` lại các field nếu Field chưa tồn tại, hoặc Timestamp cũ ở Redis < Timestamp lấy từ Cold Store.
   - Luôn sử dụng Pipeline gọi Lua Script kèm thẻ `EXPIRE $cart_key 604800` để đặt lại 7 ngày vòng đời.
3. **Consistency Guard:** 
   - Sau khi Re-populate, hệ thống trả về dữ liệu cho API Reader ngay lập tức.
   - Các hành động Write sau đó sẽ tiếp tục sử dụng Lua Script để đảm bảo tính nguyên tử.

> **Tham khảo:** Chi tiết trình tự API khi hụt cache tại [Cart Workflow - Fallback Cache Miss](cart_workflow.md#4-fallback---xử-lý-cache-miss).

> **Lưu ý tương thích ORDER_PAID:** Contract ưu tiên của luồng checkout là `cart_id` + `sku_ids` (tách riêng field). Nếu còn phải nhận payload cũ chứa `cart_item_ids` dạng ghép chuỗi, worker parse từ phải sang trái để lấy `sku_id` nhằm tránh lỗi khi `cart_id` chứa dấu `:`.
