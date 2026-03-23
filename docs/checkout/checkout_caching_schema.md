# Checkout Service - Thiết kế Caching Schema (Redis Session)

**Liên kết nhanh (Related Documents):**
*   [Architecture Design](checkout_architecture_design.md)
*   [Workflows (API & Data Flow)](checkout_workflow.md)
*   [Idempotency & Anti-Tampering](checkout_idempotency.md)
*   [Security Analysis (Anti-Tampering & Session)](checkout_security_analysis.md)

**Cross-Service References:**
*   [Cart Caching Schema](../cart/cart_caching_schema.md) — Cart data source
*   [Promotion Redis Design](../promotion/redis_caching_design.md) — Budget reservation
*   [Inventory Redis Design](../inventory/inventory_redis_design.md) — Stock check
*   [Shipping Workflow](../shipping/shipping_workflow.md#1-api-calculate-shipping-fee) — Shipping fee calculation

---

Tài liệu này mô tả chiến lược Caching cho Checkout Service — một **BFF/Aggregator Stateless** sử dụng Redis để lưu trữ tạm thời Checkout Session. Checkout Service không có Database riêng — mọi dữ liệu lưu trữ đều nằm trên Redis với TTL.

---

## 1. Checkout Session Cache

### 1.1. Cấu trúc Key & Data

**Key Pattern:** `checkout:session:{user_id}`
**Type:** Hash
**TTL:** 20 phút (dài hơn Payment timeout 15 phút để tránh edge case)

**Fields:**

```json
{
  "token": "uuid-v4-checkout-token-xxxxx",
  "status": "PREVIEW_READY",
  "shipping_address_id": "addr-uuid-123",
  "cart_snapshot": "{\"shop_groups\":[...]}",
  "shop_routing": "{
    \"seller-001\": {\"total_shipping_fee\": 35000, \"parcels\": [...]},
    \"seller-002\": {\"total_shipping_fee\": 28000, \"parcels\": [...]}
  }",
  "shipping_rate_version": "2026-03-14T09:00:00Z",
  "promotion_rule_version": "2026-03-14T09:00:00Z",
  "selected_shop_vouchers": "{
    \"seller-001\": null,
    \"seller-002\": {\"promotion_id\": 55, \"discount_amount\": 50000}
  }",
  "selected_platform_voucher_id": null,
  "selected_freeship_voucher_id": null,
  "pricing_summary": "{\"total_product_amount\":44000000, \"total_shop_discount\":50000, \"platform_discount\":0, \"total_shipping_fee\":63000, \"shipping_discount\":0, \"final_amount\":44013000}",
  "pricing_hash": "sha256-abc123...",
  "items_hash": "sha256-def456...",
  "created_at": "2026-02-28T10:00:00Z",
  "updated_at": "2026-02-28T10:02:00Z"
}
```

> **Field `items_hash`:** SHA-256 của `sort_by(sku_id)("sku_id:quantity,...")`. Dùng để validate downstream cache (Inventory + Shipping) — khi user đổi quantity/thêm/xóa item, `items_hash` đổi → cache miss tự nhiên.
> 
> **Field `selected_shop_vouchers`:** Map `seller_id → voucher_info`. Mỗi lần user đổi voucher của 1 shop, chỉ cần `HSET ... selected_shop_vouchers ...` — không serialize lại toàn bộ session. Atomic update per field là lợi thế của Redis Hash.

> **Field `pricing_hash`:** SHA-256 hash của `concat(sorted sku_id + unit_price) + total_shipping_fee + total_discount_amount`. Tạo ra tại thời điểm Preview, dùng để kiểm tra staleness khi Submit — xem [Checkout Workflow Section 2.5](checkout_workflow.md#25-lightweight-price-staleness-check-bổ-sung).

### 1.2. Lifecycle

| Status | Mô tả | Transition |
|:---|:---|:---|
| `PREVIEW_READY` | Session mới tạo sau Checkout Preview API | Mặc định |
| `SUBMITTED` | User bấm Submit → Token đã dùng 1 lần | `PREVIEW_READY` → `SUBMITTED` |
| `EXPIRED` | TTL hết hạn hoặc bị invalidate | Tự động |

### 1.3. Tại sao Hash thay vì String?

- **Selective Update:** Khi user thay đổi shipping address, chỉ cần `HSET ... shipping_address ... selected_shipping ...` — không cần serialize/deserialize toàn bộ session.
- **Atomic Read:** `HGETALL` lấy về toàn bộ checkout summary trong 1 round-trip.
- **Selective Read:** API `GET /checkout/shipping-options` chỉ cần `HGET ... shipping_options`.

---

## 2. Downstream Response Cache (Tránh gọi lại Service không cần thiết)

### 2.1. Inventory Availability Cache (per-shop)

**Ý tưởng:** Sau khi Checkout Preview gọi Inventory Service kiểm tra stock và tính routing, cache kết quả **per-shop** trong 5 phút. Nếu user chỉ thay đổi voucher mà không thay đổi items → không cần gọi lại Inventory.

**Key:** `checkout:inventory:{user_id}:{seller_id}:{items_hash}`
**TTL:** 5 phút
**Invalidation:** 
- User thay đổi quantity hoặc thêm/xóa item → `items_hash` thay đổi → cache miss tự nhiên.
- Inventory Service produce `INVENTORY_QUANTITY_CHANGED` → Global L1 flag (Caffeine) → bypass cache.

> **Key per-seller_id thay vì per toàn giỏ:** Nếu user có 3 shops, sẽ có 3 cache keys riêng. Khi user đổi voucher shop A, cache routing của shop B và C vẫn hiệu lực — tiết kiệm được 2/3 Inventory calls.

### 2.2. Shipping Fee Cache (per-shop)

**Key:** `checkout:shipping:{user_id}:{seller_id}:{items_hash}:{address_hash}`
**TTL:** 10 phút
**Invalidation:** User đổi address → `address_hash` đổi → cache miss. User đổi voucher → items + address không đổi → cache vẫn dùng.

> **Key per-seller_id:** Khi user đổi địa chỉ, chỉ cần invalidate shipping cache, không cần invalidate routing cache (kho được chọn không phụ thuộc địa chỉ giao hàng).

> [!NOTE]
> Cache này chỉ áp dụng cho **cùng 1 user session** trong quá trình checkout. Không phải global cache. Khi session hết hạn, cache tự xóa.

### 2.3. Wallet Cache (Voucher Discovery)

Khi khách mở khung "Chọn Mã Giảm Giá" tại màn hình Checkout, Checkout Service gọi Promotion Service `GET /v1/promotions/voucher-suggestions`. Promotion Service sử dụng cache này để tránh query DB `user_coupon_wallets` nhiều lần trong cùng 1 session.

**Key:** `wallet:{user_id}:active_vouchers` *(owned by Promotion Service)*
**TTL:** 5 phút
**Chi tiết thiết kế:** Xem [Promotion Redis Design — Section 2.7](../promotion/redis_caching_design.md#27-wallet-cache-ví-voucher--hỗ-trợ-voucher-discovery-api) và [Voucher Discovery API](../promotion/voucher_discovery_api.md).

> **Shop Voucher Tray tái sử dụng Wallet Cache:** Khi user mở tray "Voucher của Shop X", Promotion Service lấy `wallet:{user_id}:active_vouchers` → filter `owner_id = seller_id_X` → merge thêm mã public của shop chưa lưu. Cache hit path không tạo thêm I/O nào.

### 2.4. User Address Cache

Address list được cache bởi **User Service** (không phải Checkout Service) — Checkout chỉ gọi User Service là được hưởng cache này thông qua. Thiết kế double caching bại, Checkout không có layer cache riêng cho address.

**Key (owned by User Service):** `user:{user_id}:addresses` 
**Type:** `String (JSON Array)` 
**TTL:** 7 ngày (address ít thay đổi) 
**Invalidation:** User thêm/sửa/xóa địa chỉ → User Service DEL key via Outbox event → next GET sẽ populate lại từ DB.

**Latency:** Cache HIT ~2ms, Cache MISS ~5-10ms (DB query + populate).


---

## 3. Rate Limiting Cache

### 3.1. Checkout Preview Rate Limit

**Key:** `checkout:ratelimit:preview:{user_id}`
**Type:** String (counter)
**TTL:** 1 phút (sliding window)
**Limit:** 10 requests/phút

```lua
-- Lua Script: Rate Limit Check
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])

local current = redis.call('INCR', key)
if current == 1 then
    redis.call('EXPIRE', key, window)
end
if current > limit then
    return 0 -- REJECT
end
return 1 -- ALLOW
```

### 3.2. Checkout Submit Rate Limit

**Key:** `checkout:ratelimit:submit:{user_id}`
**Limit:** 3 requests/phút (siết hơn vì Submit tạo đơn thật).

---

## 4. Checkout Token Design

### 4.1. Token Format: Server-side Random + Redis Hash

**Quyết định:** KHÔNG dùng JWT cho checkout token (vì JWT stateless → khó revoke, khó one-time-use). Thay vào đó:

- **Generate:** Backend tạo `UUID v4` → Lưu vào `checkout:session:{user_id}.token`.
- **Validate:** So khớp token trong request header với Redis stored token.
- **One-time-use:** Khi submit, `HSET checkout:session:{user_id} status SUBMITTED`. Lần submit tiếp → check status ≠ PREVIEW_READY → reject `409 Conflict`.

### 4.2. Anti-Tampering

Tất cả tính toán (giá, shipping fee, discount) được thực hiện **server-side** và lưu trong Redis session. Client chỉ gửi lên identifiers (address_id, promotion_code, payment_method). Backend tự tính lại, so khớp, rồi mới tạo order.

```
Client gửi: { token, address_id: 5, promotion_code: "HAPPYBDAY", payment_method: "VNPAY" }
Server: Bóc token → Redis session → Lấy pricing_summary đã tính sẵn ở Preview
Server: Validate address_id thuộc user + promotion_code match session → Pass
Server: Gửi toàn bộ sang Order Service: { items, amount, shipping_fee, discount, ... } -- tất cả từ Redis
```

> **Không tin client-sent amounts.** Mọi số tiền đều từ server calculation.
