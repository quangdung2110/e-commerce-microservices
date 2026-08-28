--[[
=============================================================================
Cart Service — add or update item
=============================================================================
Thao tác nguyên tử duy nhất cho đường ghi "thêm SKU / đổi số lượng".

Vì sao phải là Lua script:
  * Read-modify-write ở tầng ứng dụng (HGET -> decode -> cộng dồn -> HSET) có
    race condition: nhiều request cùng đọc quantity = 1 rồi cùng ghi 2, làm mất
    lượt bấm của người dùng.
  * Thay đổi cart state và sinh outbox event phải cùng thành công hoặc cùng
    không xảy ra (CART-INV-004). Redis chạy script đơn luồng nên bảo đảm điều đó
    mà không cần transaction phân tán.

Ràng buộc Redis Cluster:
  KEYS[1] và KEYS[2] phải cùng hash tag `{cart}`, nếu không Redis trả CROSSSLOT
  và script thất bại hoàn toàn.

Tài liệu: docs/cart/data.md §7, docs/cart/workflows.md WF-CART-001
=============================================================================

KEYS[1]  cart_key             ví dụ: {cart}:550e8400-e29b-41d4-a716-446655440000
KEYS[2]  global_stream_shard  ví dụ: {cart}:events:0

ARGV[1]  sku_id
ARGV[2]  payload_json    snapshot đầy đủ do backend build sẵn
ARGV[3]  max_qty         trần số lượng của mỗi SKU
ARGV[4]  max_skus        trần số loại SKU trong giỏ
ARGV[5]  step            số lượng cộng thêm, có thể âm
ARGV[6]  event_time_ms   epoch milliseconds, business timestamp
ARGV[7]  user_id         cần cho event payload
ARGV[8]  ttl_seconds     hạn sống của giỏ

Trả về: chuỗi JSON của item sau thao tác.
--]]

local cart_key     = KEYS[1]
local stream_key   = KEYS[2]

local sku_id       = ARGV[1]
local payload_json = ARGV[2]
local max_qty      = tonumber(ARGV[3])
local max_skus     = tonumber(ARGV[4])
local step         = tonumber(ARGV[5])
local event_time   = tostring(ARGV[6])
local user_id      = ARGV[7]
local ttl_seconds  = tonumber(ARGV[8])

-- 1. Chặn phình hash key: từ chối SKU mới khi giỏ đã đạt trần số loại.
--    Tăng số lượng một SKU đã có vẫn được phép vì không làm tăng số loại.
if redis.call('HLEN', cart_key) >= max_skus
   and redis.call('HEXISTS', cart_key, sku_id) == 0 then
    return {err = "CART_FULL"}
end

-- 2. step phải là số nguyên khác 0.
if not step or step == 0 then
    return {err = "STEP_INVALID"}
end

local current_item  = redis.call('HGET', cart_key, sku_id)
local encoded_json  = payload_json
local is_new        = 1

if current_item then
    -- 3a. Item đã tồn tại: cộng dồn trên giá trị đang có trong Redis.
    is_new = 0

    local old_obj = cjson.decode(current_item)
    local new_obj = cjson.decode(payload_json)

    local new_qty = old_obj.quantity + step

    -- Chạm trần thì clamp, không báo lỗi (CART-BR-001).
    if new_qty > max_qty then
        new_qty = max_qty
    end

    -- Về 0 hoặc âm là sai ngữ nghĩa: muốn bỏ item thì dùng batch delete.
    if new_qty <= 0 then
        return {err = "QUANTITY_INVALID"}
    end

    new_obj.quantity = new_qty

    -- Dữ liệu lịch sử bất biến (CART-INV-006): giữ nguyên mốc thêm vào giỏ và
    -- giá tại thời điểm đó, bất kể payload mới mang giá trị nào.
    new_obj.added_at          = tostring(old_obj.added_at)
    new_obj.unit_price_at_add = old_obj.unit_price_at_add

    -- Timestamp lưu dạng chuỗi để cjson không đổi sang ký hiệu khoa học.
    new_obj.updated_at = event_time

    encoded_json = cjson.encode(new_obj)
else
    -- 3b. Item chưa tồn tại: không cho phép giảm số lượng của thứ không có.
    if step < 0 then
        return {err = "ITEM_NOT_FOUND"}
    end
end

-- 4. Ghi cart state.
redis.call('HSET', cart_key, sku_id, encoded_json)

-- 5. Gia hạn vòng đời giỏ. Chỉ đường ghi mới được làm việc này; đường đọc trúng
--    cache tuyệt đối không gia hạn TTL (CART-BR-008).
redis.call('EXPIRE', cart_key, ttl_seconds)

-- 6. Bổ sung định danh vào event payload. Hash value không lưu ba trường này vì
--    chúng đã ngầm định trong key và field, nhưng consumer downstream cần chúng
--    tường minh để không phải tách chuỗi từ cart_id.
local event_obj    = cjson.decode(encoded_json)
event_obj.cart_id  = cart_key
event_obj.sku_id   = sku_id
event_obj.user_id  = user_id

local event_type = "CART_ITEM_UPDATED"
if is_new == 1 then
    event_type = "CART_ITEM_ADDED"
end

-- 7. Sinh outbox event trong cùng lần thực thi này.
--    Không dùng MAXLEN: cắt theo độ dài có thể xóa message chưa được XACK khi
--    relay bị chậm, tức là mất event. Việc giải phóng bộ nhớ do Stream Trimmer
--    đảm nhiệm bằng XTRIM MINID.
--    stream_id do XADD sinh ra là khóa thứ tự chính cho toàn bộ downstream.
redis.call('XADD', stream_key, '*',
    'event_type', event_type,
    'payload',    cjson.encode(event_obj))

return encoded_json
