--[[
=============================================================================
Cart Service — batch delete items
=============================================================================
Xóa một tập SKU khỏi giỏ trong một lần thực thi nguyên tử, và phát đúng tín hiệu
vòng đời khi giỏ trở nên rỗng.

Dùng cho hai luồng khác nhau, phân biệt bằng ARGV[2]:
  * User chủ động xóa           -> event kết thúc là CART_CLEARED     -> status DELETED
  * Dọn giỏ sau ORDER_PAID      -> event kết thúc là CART_CHECKED_OUT -> status CHECKED_OUT
Hai trạng thái này có chính sách retention khác nhau ở cold store, nên việc phân
biệt là bắt buộc chứ không phải chi tiết trang trí (CART-BR-012).

CẢNH BÁO: cấu trúc ARGV của script này KHÁC hoàn toàn add_or_update_item.lua.
Tuyệt đối không tái sử dụng mảng ARGV giữa hai script.

Tài liệu: docs/cart/data.md §7, docs/cart/workflows.md WF-CART-004
=============================================================================

KEYS[1]  cart_key             ví dụ: cart:v1:{b17}:u:NTUwZTg0MC...:items
KEYS[2]  outbox_stream        ví dụ: cart:v1:{b17}:outbox

ARGV[1]  current_timestamp_ms  epoch milliseconds dạng chuỗi
ARGV[2]  clear_event_type      "CART_CLEARED" hoặc "CART_CHECKED_OUT"
ARGV[3]  user_id
ARGV[4]  sku_ids_json          mảng JSON, ví dụ ["sku-1001","sku-1002"]
ARGV[5]  cart_id               logical ID, ví dụ c1:NTUwZTg0MC...

Trả về: số SKU thực sự bị xóa.
--]]

local cart_key     = KEYS[1]
local stream_key   = KEYS[2]

local current_time = tostring(ARGV[1])
local clear_event  = ARGV[2]
local user_id      = ARGV[3]
local sku_ids      = cjson.decode(ARGV[4])
local cart_id      = ARGV[5]

local deleted_count   = 0
local skus_to_delete  = {}

-- Tái sử dụng một object cho cả vòng lặp thay vì tạo mới mỗi lượt.
local deleted_event = {
    sku_id     = "",
    cart_id    = cart_id,
    user_id    = user_id,
    deleted_at = current_time
}

-- 1. Duyệt danh sách, gom các SKU thực sự tồn tại và phát event cho từng SKU đó.
--    SKU không có trong giỏ được bỏ qua im lặng: đây là điều khiến thao tác xóa
--    tự nhiên idempotent, và là lý do ORDER_PAID trùng lặp không gây lỗi.
for i = 1, #sku_ids do
    local sku_id = sku_ids[i]

    if redis.call('HEXISTS', cart_key, sku_id) == 1 then
        table.insert(skus_to_delete, sku_id)
        deleted_count = deleted_count + 1

        deleted_event.sku_id = sku_id
        redis.call('XADD', stream_key, '*',
            'event_type', 'CART_ITEM_DELETED',
            'payload',    cjson.encode(deleted_event))
    end
end

if deleted_count > 0 then
    -- 2. Xóa một lần cho cả nhóm thay vì HDEL từng field.
    redis.call('HDEL', cart_key, unpack(skus_to_delete))

    -- 3. Giỏ rỗng thì xóa hẳn key và phát tín hiệu kết thúc vòng đời.
    --    Thanh toán một phần KHÔNG chạm nhánh này: giỏ còn SKU khác nên vẫn
    --    ACTIVE và không phát CART_CHECKED_OUT (CART-BR-011).
    if redis.call('HLEN', cart_key) == 0 then
        redis.call('DEL', cart_key)

        local clear_payload = {
            cart_id = cart_id,
            user_id = user_id
        }

        if clear_event == 'CART_CHECKED_OUT' then
            clear_payload.checked_out_at = current_time
        else
            clear_payload.cleared_at = current_time
        end

        redis.call('XADD', stream_key, '*',
            'event_type', clear_event,
            'payload',    cjson.encode(clear_payload))
    end
end

return deleted_count
