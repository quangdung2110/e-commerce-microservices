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
  "delivery_address_id": "addr-uuid-123",
  "cart_snapshot": "{\"items\":[...]}",
  "promotion_rule_version": "2026-03-14T09:00:00Z",
  "promotion_budget_version": "2026-03-14T09:00:02Z",
  "selected_voucher_id": null,
  "pricing_summary": "{\"total_product_amount\":44000000, \"total_voucher_discount\":50000, \"final_amount\":43950000}",
  "pricing_hash": "sha256-abc123...",
  "items_hash": "sha256-def456...",
  "created_at": "2026-02-28T10:00:00Z",
  "updated_at": "2026-02-28T10:02:00Z"
}
```

> **Field `items_hash`:** SHA-256 của `sort_by(sku_id)("sku_id:quantity,...")`. Dùng để validate downstream cache (Inventory + Shipping) — khi user đổi quantity/thêm/xóa item tại Cart Service, `items_hash` đổi → cache miss tự nhiên.

> **Field `selected_vouchers`:** Map voucher đã chọn. Mỗi lần user đổi voucher, chỉ cần `HSET ... selected_voucher_id ...` — không serialize lại toàn bộ session. Atomic update per field là lợi thế của Redis Hash.

> Session Checkout không lưu cờ voucher độc quyền. Quy tắc cộng dồn voucher được xác định bởi Promotion Service theo mô hình 1 slot PLATFORM và kết quả `pricing_summary` trả về.


> **Field `pricing_hash`:** SHA-256 hash của `concat(sorted sku_id + unit_price) + total_voucher_discount + promotion_rule_version`. Tạo ra tại thời điểm Preview, dùng để kiểm tra staleness khi Submit — xem [Checkout Workflow Section 4](checkout_workflow.md#4-lightweight-price-staleness-check-submit-path). Canonical: dùng `total_voucher_discount` (không dùng `total_discount_amount` hay `total_platform_discount` — đã deprecated).


### 1.2. Lifecycle

| Status | Mô tả | Transition |
|:---|:---|:---|
| `PREVIEW_READY` | Session mới tạo sau Checkout Preview API | Mặc định |
| `SUBMITTED` | User bấm Submit → Token đã dùng 1 lần | `PREVIEW_READY` → `SUBMITTED` |
| `EXPIRED` | TTL hết hạn hoặc bị invalidate | Tự động |

> Khi submit thất bại ở nhánh nghiệp vụ `PROMOTION_RECALCULATION_NEEDED`, token vẫn giữ `SUBMITTED` (one-time-use). Client phải gọi lại Preview để lấy token mới trước khi submit tiếp.

### 1.3. Tại sao Hash thay vì String?

- **Selective Update:** Khi user thay đổi delivery address, chỉ cần `HSET ... delivery_address ... selected_voucher ...` — không cần serialize/deserialize toàn bộ session.
- **Atomic Read:** `HGETALL` lấy về toàn bộ checkout summary trong 1 round-trip.
- **Selective Read:** API `GET /checkout/warehouse-assignments` chỉ cần `HGET ... warehouse_assignments`.

---

## 2. Downstream Response Cache (Tránh gọi lại Service không cần thiết)

### 2.1. Inventory Route Cache

**Ý tưởng:** Sau khi Checkout Preview gọi Inventory Service `/routing-options`, cache kết quả route plan trong **20 phút**
**TTL:** 20 phút
**Invalidation:**
- User thay đổi quantity hoặc thêm/xóa item tại Cart Service → `items_hash` thay đổi → cache miss tự nhiên.
- User đổi địa chỉ giao hàng → `address_hash` thay đổi → cache miss tự nhiên.

> **Key pattern:** `checkout:routing:{items_hash}:{address_hash}`. Cache được invalidate tự nhiên khi items hoặc address thay đổi.

> **Quy tắc recompute kèm theo:** Đổi voucher chỉ trigger Promotion evaluate lại; không invalidate route cache.

### 2.2. Voucher Discovery (Không có cache ví)

Khi khách mở khung "Chọn Mã Giảm Giá" tại màn hình Checkout, Checkout Service gọi Promotion Service `GET /v1/promotions/voucher-suggestions`. Promotion Service **không còn dùng wallet cache** nữa; mỗi request sẽ query trực tiếp DB `user_coupon_wallets` (Index Scan `user_id, status`, ~3-5ms) và áp dụng lazy expiry inline cho các row `SAVED` đã quá `expired_at`.

**Chi tiết thiết kế:** Xem [Promotion Redis Design](../promotion/redis_caching_design.md) và [Voucher Discovery API §5.1](../promotion/voucher_discovery_api.md#51-lazy-expiry-update-inline-trong-discovery).

> **Voucher Tray đọc trực tiếp DB:** Khi user mở tray "Chọn Voucher" hoặc "Voucher Sàn", Promotion Service query `user_coupon_wallets JOIN promotions` trực tiếp rồi eval rule + Budget Guard. Không qua Redis cache ở giữa, nên không có bước DEL invalidation.

> **Claim Window vs Use Window:** Promotion chỉ dùng `promotions.start_time/end_time` để quyết định còn được claim/save mã mới; quyền sử dụng mã đã sở hữu khi checkout dựa trên `user_coupon_wallets.expired_at` (policy hiện tại: 30 ngày từ lúc acquire/save).

> **Option A - Budget Guard read-time:** Sau bước evaluate rule, Promotion chạy guard đọc batch budget Redis để downgrade mã đã cạn ngân sách thành `ineligible_reason = BUDGET_EXHAUSTED`. Cơ chế này giảm stale hiển thị nhưng không thay thế commit-time reserve ở Submit.

> **Degraded contract:** Nếu budget guard timeout/lỗi Redis ngắn hạn, Discovery có thể trả response ở chế độ degraded (fail-open). Checkout vẫn phải coi Submit Reserve là nguồn quyết định cuối cùng cho exhausted budget.

> **Boundary ghi ví rõ ràng:**
> - Checkout Submit không được ghi `user_coupon_wallets`.
> - Chỉ explicit action như `save-and-apply` (voucher) hoặc campaign/claim worker của Promotion mới tạo thêm bản ghi ví.
> - Sau khi `save-and-apply` thành công, lần Discovery kế tiếp sẽ tự thấy mã mới (vì query trực tiếp DB, không qua cache).

### 2.3. User Address Cache

Address list được cache bởi **User Service** (không phải Checkout Service) — Checkout chỉ gọi User Service là được hưởng cache này thông qua. Việc thiết kế double caching cho Address tại Checkout là dư thừa và dễ gây loạn trạng thái (Cache Invalidation complexity), do đó Checkout không duy trì layer cache riêng cho address.

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

### 3.3. Voucher Discovery Rate Limit (Owned by Promotion)

**Key:** `promotion:ratelimit:voucher_discovery:{user_id}`
**Limit:** 20 requests/phút.

> Discovery là API của Promotion Service; Checkout không sở hữu key rate-limit này. Checkout chỉ consume kết quả discovery.

---

## 4. Distributed Locks (Idempotency & Concurrency)

Giới hạn số lượng thread chạy song song của cùng 1 user, tránh race condition ghi đè session.

### 4.1. Checkout Preview Lock

**Key:** `checkout:lock:preview:{user_id}`
**TTL:** 5 giây
**Mechanism:** `SETNX` + `EXPIRE`
**Purpose:** Ngăn chặn user (hoặc hacker) spam mở hàng loạt tab/requests Preview gọi song song với nhiều mã giảm giá khác nhau, gây race condition làm hỏng Redis `checkout:session:{user_id}`.

### 4.2. Checkout Submit Lock

**Key:** `checkout:lock:submit:{user_id}`
**TTL:** 30 giây
**Mechanism:** `SETNX` + `EXPIRE`
**Purpose:** Ngăn user click nút "Submit" 2 lần liên tục hoặc dùng tool bắn nhiều request sinh duplicate order.

---

## 5. Checkout Token Design

### 5.1. Token Format: Server-side Random + Redis Hash

**Quyết định:** KHÔNG dùng JWT cho checkout token (vì JWT stateless → khó revoke, khó one-time-use). Thay vào đó:

- **Generate:** Backend tạo `UUID v4` → Lưu vào `checkout:session:{user_id}.token`.
- **Validate:** So khớp token trong request header với Redis stored token.
- **One-time-use:** Khi submit, `HSET checkout:session:{user_id} status SUBMITTED`. Lần submit tiếp → check status ≠ PREVIEW_READY → reject `409 Conflict` hoặc trả lại response đã cache (success/409 nghiệp vụ) theo token.

### 5.2. Anti-Tampering

Tất cả tính toán (giá, discount) được thực hiện **server-side** và lưu trong Redis session. Client chỉ gửi lên identifiers (address_id, promotion_code). Backend tự tính lại, so khớp, rồi mới tạo order.

> **Phase 18 (Single Payment Provider - Stripe):** Hệ thống chỉ dùng Stripe, nên field `payment_method` không còn cần thiết trong Checkout session. Checkout không cần validate `payment_method` ở Submit. Redis session chỉ lưu `pricing_summary` + `delivery_address_snapshot` + voucher selection; provider được fix cứng = Stripe ở Order Service và Payment Service.

```
Client gửi: { token, address_id: 5, promotion_code: "HAPPYBDAY" }
Server: Bóc token → Redis session → Lấy pricing_summary đã tính sẵn ở Preview
Server: Validate address_id thuộc user + promotion_code match session → Pass
Server: Gửi toàn bộ sang Order Service: { items, amount, discount, ... } -- tất cả từ Redis
```

> **Không tin client-sent amounts.** Mọi số tiền đều từ server calculation.
