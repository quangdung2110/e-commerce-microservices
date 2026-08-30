--[[
=============================================================================
Cart Service — silent rehydrate
=============================================================================
Nạp lại cart hash từ cold store sau khi Redis mất key.

Hai ràng buộc tuyệt đối:

  1. KHÔNG được XADD. Rehydrate là thao tác phục hồi dữ liệu, không phải business
     write. Phát event ở đây sẽ tạo ra một dòng event replay không tương ứng với
     hành động nào của người dùng, phá idempotency của toàn bộ downstream
     (CART-INV-004, CART-BR-010).

  2. KHÔNG được HMSET đè thẳng. Người dùng có thể đang thêm hàng đúng lúc luồng
     rehydrate đọc xong cold store; ghi đè mù sẽ xóa mất thao tác vừa rồi. Mỗi
     field chỉ được ghi khi nó chưa tồn tại, hoặc khi bản đến thực sự mới hơn.

Vì script chỉ ghi bản mới hơn, chạy lại nhiều lần cho cùng kết quả và nhiều
request cùng hụt cache có thể rehydrate song song mà không cần khóa.

Tài liệu: docs/cart/data.md §7, docs/cart/workflows.md WF-CART-003
=============================================================================

KEYS[1]  cart_key       ví dụ: cart:v1:{b17}:u:NTUwZTg0MC...:items

ARGV[1]  items_json     mảng JSON các item đã merge:
                        [{"sku_id":"...","value":"{...}"}, ...]
                        Backend chịu trách nhiệm ghép unit_price_at_add từ cột
                        riêng vào value trước khi truyền vào đây; thiếu bước đó
                        thì API đọc giỏ trả null cho trường giá lịch sử.
ARGV[2]  ttl_seconds    hạn sống của giỏ

Trả về: số field đã được ghi.
--]]

local cart_key    = KEYS[1]
local items       = cjson.decode(ARGV[1])
local ttl_seconds = tonumber(ARGV[2])

local written = 0

for i = 1, #items do
    local sku_id        = items[i].sku_id
    local incoming_json = items[i].value

    local ok_incoming, incoming_obj = pcall(cjson.decode, incoming_json)

    if ok_incoming and incoming_obj then
        local incoming_updated = tonumber(incoming_obj.updated_at) or 0
        local current_json     = redis.call('HGET', cart_key, sku_id)

        if not current_json then
            -- Field chưa có trên Redis: nạp thẳng.
            redis.call('HSET', cart_key, sku_id, incoming_json)
            written = written + 1
        else
            -- Field đã có: chỉ ghi khi bản từ cold store thực sự mới hơn.
            -- Đây là chỗ chặn race giữa rehydrate và thao tác thêm hàng đang chạy.
            local ok_current, current_obj = pcall(cjson.decode, current_json)

            if ok_current and current_obj then
                local current_updated = tonumber(current_obj.updated_at) or 0

                if incoming_updated > current_updated then
                    redis.call('HSET', cart_key, sku_id, incoming_json)
                    written = written + 1
                end
            end
        end
    end
end

-- Rehydrate là write-recovery nên được phép đặt lại TTL đúng một lần. Đây là
-- ngoại lệ có chủ đích của quy tắc Read-Only TTL, không phải kẽ hở.
redis.call('EXPIRE', cart_key, ttl_seconds)

return written
