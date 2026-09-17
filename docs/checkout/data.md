---
doc_id: checkout-data
service: checkout
scope: target
status: proposed
owners:
  - Checkout Service team
evidence: []
verified_against_commit: null
---

# Checkout Service — Thiết kế dữ liệu

> Checkout là BFF/Aggregator stateless: **không có database riêng**, mọi dữ liệu
> lưu trữ đều nằm trên Redis với TTL. File này bảo toàn toàn bộ key, TTL, Lua
> script và lifecycle của thiết kế cũ. Chưa có cấu hình Redis làm evidence.

**Cross-service references:**

- [Cart Data Design](../cart/data.md) — Cart data source
- [Promotion Data Design](../promotion/data.md) — Redis keys và budget reservation
- [Inventory Data Design](../inventory/data.md) — Redis key, TTL và stock check
- [Order Data — Redis, outbox và idempotency](../order/data.md#7-redis-outbox-và-idempotency-storage)

## 1. Quyền sở hữu dữ liệu

| Key pattern | Owner | Source of truth | Ghi chú |
|---|---|---|---|
| `checkout:session:{user_id}` | Checkout | Redis (tạm thời) | Bản nháp báo giá được server công nhận |
| `checkout:payload:{user_id}:{token}` | Checkout | Redis (tạm thời) | Payload gửi Order, phục vụ recovery |
| `checkout:response:{user_id}:{token}` | Checkout | Redis (tạm thời) | Idempotent response |
| `checkout:routing:{items_hash}:{address_hash}` | Checkout (cache) | Inventory | Downstream response cache |
| `checkout:lock:preview:{user_id}`, `checkout:lock:submit:{user_id}` | Checkout | Redis | Distributed lock |
| `checkout:ratelimit:preview:{user_id}`, `checkout:ratelimit:submit:{user_id}` | Checkout | Redis | Rate-limit counter |
| `cart:user:{id}` | Cart | Cart | Checkout chỉ `HGETALL` theo tài liệu cũ |
| `user:caches:addresses:{user_id}` | User | User PostgreSQL | Checkout không tạo key này; hưởng cache gián tiếp |
| Ví voucher, budget | Promotion | Promotion PostgreSQL/Redis | Không có wallet cache; tray query DB trực tiếp |

Không có bảng, migration hay entity quan hệ. Service khác không đọc/ghi keyspace
`checkout:*`.

## 2. Logical data model

```
CheckoutSession (1 per user_id)
 ├── token (UUID v4) ──────────┐
 ├── status                     │ 1:1 theo token
 ├── cart_snapshot              ├── SubmitPayload  (checkout:payload:{user_id}:{token})
 ├── pricing_summary            └── SubmitResponse (checkout:response:{user_id}:{token})
 ├── pricing_hash / items_hash
 └── voucher selection, address, revision
RoutingCache (N per items_hash × address_hash) — độc lập với session, share giữa các Preview
```

Lifecycle: session sinh ở Preview → cập nhật ở Preview kế tiếp → `SUBMITTED` ở
Submit → biến mất sau 20 phút. Payload/response gắn với một token cụ thể và sống
tối đa 20 phút.

## 3. Schema sources

Không có migration. Schema chuẩn hiện tại là mô tả trong file này; khi có
implementation, model/serializer của session và Lua script trong source sẽ là
nguồn chuẩn và file này phải link tới chúng.

## 4. Checkout Session

### 4.1 Cấu trúc key và data

- **Key:** `checkout:session:{user_id}`
- **Type:** Hash
- **TTL:** 20 phút (dài hơn Payment timeout 15 phút để tránh edge case)
- `token` là một **field** trong Hash, không nằm ở key path.

**Fields:**

```json
{
  "token": "uuid-v4-checkout-token-xxxxx",
  "status": "PREVIEW_READY",
  "delivery_address_id": "addr-uuid-123",
  "delivery_address_snapshot": "{...}",
  "cart_snapshot": "{\"items\":[...]}",
  "allocation_plan": "{\"allocation_plan_id\":\"plan-123\",\"lines\":[...]}",
  "degraded_flags": "[]",
  "promotion_campaign_revision": 42,
  "promotion_budget_version": "2026-03-14T09:00:02Z",
  "selected_voucher_id": null,
  "pricing_summary": "{\"total_product_amount\":44000000, \"total_voucher_discount\":50000, \"final_amount\":43950000}",
  "pricing_hash": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "items_hash": "sha256:abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
  "created_at": "2026-02-28T10:00:00Z",
  "updated_at": "2026-02-28T10:02:00Z"
}
```

| Field | Ý nghĩa |
|---|---|
| `token` | UUID v4 sinh ở Preview; so khớp với token trong request khi Submit |
| `status` | `PREVIEW_READY` / `SUBMITTED` (xem §4.2) |
| `delivery_address_id` | Địa chỉ đã chọn; snapshot address (`delivery_address_snapshot`) theo chuẩn User Service (`recipient_name`, `recipient_phone`, `address_line`, `province_code`, `district_code`, `ward_code`) được lưu để gửi Order |
| `delivery_address_snapshot` | JSON snapshot từ User, là payload lịch sử gửi Order; client không dựng field này |
| `cart_snapshot` | Items từ Cart (SKU + quantity) |
| `promotion_campaign_revision` | Optimistic concurrency/cache fence của Promotion; tham gia `pricing_hash`. Promotion definition đã publish không update in-place; cấu hình mới có campaign/promotion identity mới và Checkout phải chạy lại Preview thay vì diễn giải selection cũ bằng rule mới |
| `promotion_budget_version` | Chỉ telemetry/diagnostic; không tham gia hard hash gate |
| `selected_voucher_id` / `selected_vouchers` | Voucher đã chọn (map slot PLATFORM → `promotion_id`). Mỗi lần user đổi voucher chỉ cần `HSET` một field — không serialize lại toàn bộ session. Session **không** lưu cờ voucher độc quyền; quy tắc cộng dồn do Promotion xác định theo mô hình 1 slot PLATFORM và `pricing_summary` trả về |
| `pricing_summary` | `{total_product_amount, total_voucher_discount, final_amount}`; canonical dùng `total_voucher_discount` (`total_discount_amount`, `total_platform_discount` đã deprecated) |
| `pricing_hash` | `sha256:<64 lowercase hex>` của UTF-8 RFC 8785 canonical JSON `{items:[{sku_id,unit_price_minor}],promotion_campaign_revision,total_discount_minor}`; items sort theo byte UTF-8 của unique `sku_id`; tạo ở Preview, kiểm tra lại ở Submit và Order |
| `items_hash` | Cùng format/canonicalization trên `{items:[{quantity,sku_id}]}`; user đổi quantity/thêm/xóa SKU làm hash đổi và cache miss tự nhiên |
| `allocation_plan` | JSON kết quả Inventory allocation Preview gồm canonical `allocation_plan_id` và từng `line_reference`/SKU/quantity/warehouse; gửi nguyên sang Order |
| `degraded_flags` | JSON array chứa `DEGRADED_PROMOTION` / `DEGRADED_STOCK` khi Preview timeout downstream |
| `created_at`, `updated_at` | Timestamp |

Phase 18: session không lưu `payment_method`; provider cố định Stripe ở
Order/Payment. Session chỉ lưu `pricing_summary` + `delivery_address_snapshot` +
voucher selection.

### 4.2 Lifecycle

| Status | Mô tả | Transition |
|---|---|---|
| `PREVIEW_READY` | Session mới tạo sau Checkout Preview | Mặc định |
| `SUBMITTED` | User bấm Submit → token đã dùng một lần | `PREVIEW_READY → SUBMITTED` |
| `EXPIRED` | TTL hết hạn hoặc bị invalidate | Tự động |

Khi submit thất bại ở nhánh nghiệp vụ `PROMOTION_RECALCULATION_NEEDED`, token vẫn
giữ `SUBMITTED` (one-time-use). Client phải gọi lại Preview để lấy token mới.

Token expiration & renewal:

| Hành động | Hệ quả |
|---|---|
| User đổi voucher PLATFORM | Preview lại theo nhánh tối ưu (Promotion evaluate) → token **mới** → ghi đè session |
| User bấm “Lưu & Chọn” voucher chưa lưu | Promotion xử lý `PUT /api/v1/me/promotion-vouchers/{promotion_id}` để ghi ví, sau đó Preview lại → token **mới** |
| User chọn voucher Sàn | Chỉ mã đã có trong ví và chưa quá `expired_at`; expiry đã được Promotion snapshot theo validity policy khi save/claim, không có default 30 ngày; Discovery có thể hạ mã thành `BUDGET_EXHAUSTED` |
| User đổi địa chỉ | Preview lại, recompute Inventory (routing) + Promotion → token **mới** |
| User đổi quantity/add/remove SKU | Cart API trước (ngoài phạm vi Checkout), rồi Preview lại với snapshot mới → token **mới** |
| User không thao tác 20 phút | TTL hết → session biến mất → Submit → `404 Not Found` |
| User submit, rồi Back + Submit lại | Token cũ đã `SUBMITTED` → `409 Conflict` |
| User mở 2 tab cùng checkout | Cùng token (1 session/user) → tab submit trước thắng, tab sau `409` |
| Submit fail do reserve all-or-nothing | `409 PROMOTION_RECALCULATION_NEEDED`, token vẫn `SUBMITTED`, bắt buộc Preview lại |

Campaign `[start_time, end_time)` là claim/save window; promotion không có lịch
riêng. Use window khi submit theo `user_coupon_wallets.expired_at`, nên voucher đã
lưu có thể còn dùng sau natural campaign end. Limited claim đóng hard boundary
bằng Redis `TIME` + campaign revision trong cùng Lua với `SPOP`; public `PUT`
kiểm phase bằng PostgreSQL clock trong transaction (thuộc Promotion).

### 4.3 Tại sao Hash thay vì String

- **Selective update:** đổi address/voucher chỉ cần `HSET` field tương ứng — không
  serialize/deserialize toàn bộ session. Atomic update per field là lợi thế của
  Redis Hash.
- **Atomic read:** `HGETALL` lấy toàn bộ checkout summary trong một round-trip.
- **Selective read:** ví dụ `GET /checkout/warehouse-assignments` chỉ cần
  `HGET ... warehouse_assignments`.

## 5. Constraints và query patterns

Redis không có constraint quan hệ. Các ràng buộc nghiệp vụ được enforce bằng key
design và Lua:

| Ràng buộc | Cơ chế |
|---|---|
| Một session/user | Key `checkout:session:{user_id}` |
| Token one-time-use | Lua check-and-mark (§8.1) |
| Một luồng Preview/Submit tại một thời điểm | `SETNX` lock (§7) |
| Quota request | `INCR` + `EXPIRE` (§8.3) |
| Payload/response gắn đúng token | Key có `{token}` trong path |

Query pattern: `HGETALL` session khi Preview/Submit; `HGET token,status` trong
Lua; `GET` payload/response theo token; `GET` routing cache theo hash.

## 6. Ranh giới transaction

Không có transaction quan hệ. Đơn vị atomic là từng lệnh Redis hoặc Lua script:

- Lua check-and-mark: check token + check status + `HSET status` atomic.
- Lock: `SETNX` + `EXPIRE`.
- Ghi session ở Preview: `HSET` nhiều field dưới preview lock.

Side effect ngoài (gọi Order) xảy ra **sau** Lua và **sau** khi payload đã `SET`;
lệch giữa hai bước này được phục hồi bằng WF-CHK-004. Lost update giữa hai
Preview đồng thời được chặn bằng preview lock 5 giây.

## 7. Distributed locks

Giới hạn số luồng chạy song song của cùng một user, tránh race condition ghi đè
session.

### 7.1 Preview lock

- **Key:** `checkout:lock:preview:{user_id}`
- **TTL:** 5 giây
- **Mechanism:** `SETNX` + `EXPIRE`
- **Purpose:** ngăn user (hoặc hacker) spam mở hàng loạt tab/requests Preview song
  song với nhiều mã giảm giá khác nhau. Vì Promotion chưa lock ngân sách thực tế,
  hệ thống sẽ chấp nhận tính toán cho tất cả các luồng đó; nếu Redis session bị
  ghi đè chéo, phiên cuối cùng có thể vỡ `pricing_hash` hoặc phát sinh lỗi logic
  gây kiệt quệ tài nguyên. Chỉ cho phép một luồng Preview/user tại một thời điểm.

### 7.2 Submit lock

- **Key:** `checkout:lock:submit:{user_id}`
- **TTL:** 30 giây
- **Mechanism:** `SETNX` + `EXPIRE`
- **Purpose:** ngăn user click “Submit” hai lần liên tục hoặc dùng tool bắn nhiều
  request sinh duplicate order; bao bọc cả Price Staleness Check.

```
IF SETNX checkout:lock:submit:{user_id} = 1 THEN
    -- Xóa Lock cũ ở Preview nếu còn (hoặc tự hết)
    -- Thực hiện Price Staleness Check (Read-only từ Cache)
    -- Xử lý token Lua Script (Atomic mark SUBMITTED) và Submit
    -- Sau khi xong → DEL lock
ELSE
    -- 423 Locked: "Đang xử lý đơn hàng, vui lòng chờ"
END
```

## 8. Idempotency storage và Lua scripts

### 8.1 Lua check-and-mark (one-time-use token)

```lua
-- Lua Script: Atomic Check-and-Mark
local session_key = KEYS[1]
local expected_token = ARGV[1]

local stored_token = redis.call('HGET', session_key, 'token')
local stored_status = redis.call('HGET', session_key, 'status')

-- Check 1: Token phải khớp
if stored_token ~= expected_token then
    return -1  -- INVALID_TOKEN
end

-- Check 2: Status phải là PREVIEW_READY
if stored_status ~= 'PREVIEW_READY' then
    return -2  -- ALREADY_SUBMITTED
end

-- Mark as used (atomic)
redis.call('HSET', session_key, 'status', 'SUBMITTED')
return 1  -- OK
```

Vì sao Lua: bảo đảm check + update atomic. Nếu dùng hai lệnh riêng (`HGET` rồi
`HSET`), hai request cùng lúc có thể cùng thấy `PREVIEW_READY` → cùng pass →
duplicate order.

### 8.2 Idempotent response cache

- **Key:** `checkout:response:{user_id}:{token}`
- **TTL:** 20 phút
- **Value:**
  - Success: `{order_id, client_secret, publishable_key}` (Phase 18: Stripe trả
    `client_secret` thay vì `payment_url`).
  - Conflict: `{error_code: "PROMOTION_RECALCULATION_NEEDED", action:
    "RECALL_PREVIEW", dropped_promotions: [...], proposal_checkout_data: {...}}`.
- **Read pattern:** client retry cùng token → Lua trả `-2` → đọc key này → trả
  lại response cũ bất kể success hay conflict nghiệp vụ.
- Trong nhánh conflict: `dropped_promotions[]` chỉ chứa mã bị loại;
  `proposal_checkout_data.surviving_promotions[]` là input chuẩn để Preview lại;
  token cũ vẫn `SUBMITTED`, không tái sử dụng dù user chấp nhận phương án mới.

### 8.3 Failed submit recovery payload

- **Key:** `checkout:payload:{user_id}:{token}`
- **TTL:** 20 phút (bằng response cache TTL)
- **Value:** payload identical với request gửi sang Order Service.
- **Lifecycle:** `SET` sau Lua OK; `DEL` khi submit thành công; giữ khi Order
  lỗi; client retry đọc lại để gọi Order; hết TTL → `410 Gone`.

### 8.4 Rate limiting

**Preview**

- **Key:** `checkout:ratelimit:preview:{user_id}`
- **Type:** String (counter)
- **TTL:** 1 phút (tài liệu cũ gọi là sliding window)
- **Limit:** 10 requests/phút

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

**Submit**

- **Key:** `checkout:ratelimit:submit:{user_id}`
- **Limit:** 3 requests/phút (siết hơn vì Submit tạo đơn thật).

**Voucher Discovery (owned by Gateway)**

Discovery là API của Promotion nhưng không tạo Redis key trong Promotion hoặc
Checkout. Gateway áp profile `promotion-discovery`: tối đa 20 request/phút cho
authenticated user, cùng coarse trusted-IP guard trước ForwardAuth. Checkout chỉ
consume kết quả; quota và failure behavior của edge nằm trong
[Gateway Runtime Contract](../gateway/runtime-contract.md#7-chính-sách-rate-và-concurrency).

## 9. Caching

### 9.1 Inventory route cache

- **Key:** `checkout:routing:{items_hash}:{address_hash}`
- **TTL:** 20 phút
- **Source of truth:** Inventory routing/allocation preview.
- **Read/write:** Preview Wave 1 đọc theo hash; miss → gọi Inventory → ghi.
- **Invalidation:** tự nhiên — user đổi quantity/thêm/xóa item tại Cart →
  `items_hash` đổi; đổi địa chỉ → `address_hash` đổi. Đổi voucher chỉ trigger
  Promotion evaluate lại; **không** invalidate route cache.
- **Stale policy:** tối đa 20 phút; hard allocation trong Saga là quyết định cuối.
- **Khi unavailable:** gọi thẳng Inventory; timeout → `DEGRADED_STOCK`.

### 9.2 Voucher discovery — không có cache ví

Khi user mở tab “Voucher của tôi”, Checkout gọi
`POST /api/v1/me/promotion-voucher-suggestions` (chỉ voucher `SAVED`). Khi mở
tab “Khám phá”, Checkout gọi `POST /api/v1/promotion-discovery` (wallet voucher +
public `ACTIVE`). Hai endpoint là safe query dù dùng POST vì checkout context
lớn. Promotion **không còn dùng wallet cache**; query DB `user_coupon_wallets`
và lazy-expire row `SAVED` đã quá `expired_at`.

- **Tray đọc trực tiếp DB:** Promotion query `user_coupon_wallets JOIN
  promotions` rồi eval rule + Budget Guard; không qua Redis cache ở giữa nên
  không có bước `DEL` invalidation.
- **Claim window vs use window:** Promotion suy ra campaign phase từ
  `campaigns.publication_status` và `[start_time, end_time)` để quyết định còn
  được claim/save; quyền sử dụng voucher đã sở hữu dựa trên
  `user_coupon_wallets.expired_at`, snapshot theo validity mode `CAMPAIGN_END`,
  `DURATION_AFTER_SAVE`, `FIXED_DATE`; không có default 30 ngày.
- **Option A — Budget Guard read-time:** sau evaluate rule, Promotion đọc batch
  budget Redis để downgrade mã đã cạn thành `ineligible_reason = BUDGET_EXHAUSTED`.
  Giảm stale hiển thị nhưng không thay thế commit-time reserve ở Submit.
- **Degraded contract:** budget guard timeout/lỗi Redis ngắn hạn → Discovery có
  thể trả degraded (fail-open). Checkout vẫn coi Submit Reserve là quyết định
  cuối cho exhausted budget.
- **Boundary ghi ví:** Checkout Submit không ghi `user_coupon_wallets`; chỉ
  `PUT /api/v1/me/promotion-vouchers/{promotion_id}`,
  `POST /api/v1/me/promotion-voucher-claims` hoặc campaign worker của Promotion
  tạo bản ghi ví. Sau PUT thành công, Discovery/Suggestion kế tiếp tự thấy mã mới.

Chi tiết: [Promotion Data Design](../promotion/data.md),
[Promotion Workflows](../promotion/workflows.md).

### 9.3 User address cache (owned by User Service)

Address list được cache bởi **User Service**, không phải Checkout — Checkout chỉ
gọi User Service và hưởng cache gián tiếp. Double caching address tại Checkout là
dư thừa và dễ gây loạn trạng thái (cache invalidation complexity), nên Checkout
không duy trì layer cache riêng cho address.

- **Key (owned by User):** `user:caches:addresses:{user_id}`
- **Type:** String (JSON Array)
- **TTL:** 12 giờ; list rỗng dùng TTL ngắn 15–30 phút.
- **Invalidation:** user thêm/sửa/xóa hoặc đổi default address → commit
  PostgreSQL → User `DEL` key; lần GET tiếp theo populate lại từ DB. User không
  dùng outbox cho invalidation này.
- **Latency:** cache HIT ~2ms, MISS ~5–10ms (DB query + populate).

### 9.4 Giá sản phẩm

Checkout không cache giá. Product tự xử lý Redis/cache miss nội bộ; lookup cache
hit ước ~2–4ms.

## 10. Data lifecycle

| Dữ liệu | Retention | Cleanup |
|---|---|---|
| Session | 20 phút TTL | Redis expire |
| Payload | 20 phút TTL; `DEL` khi success | Redis expire |
| Response | 20 phút TTL | Redis expire |
| Routing cache | 20 phút TTL | Redis expire |
| Locks | 5 giây / 30 giây TTL; `DEL` khi xong | Redis expire |
| Rate-limit counter | 1 phút TTL | Redis expire |

Không có archive, soft delete, migration, backfill hay backup: mất Redis đồng
nghĩa user phải Preview lại; Order idempotency theo `checkout_token` ngăn duplicate
order cho token đã gửi. Reconciliation không cần vì Checkout không phải source of
truth cho dữ liệu bền vững.

## 11. Security và privacy

- Session chứa PII (địa chỉ, tên, số điện thoại người nhận) trong
  `delivery_address_snapshot`; TTL 20 phút giới hạn thời gian lưu.
- Payload/response chứa `order_id` và `client_secret`/`publishable_key` Stripe;
  chính sách masking/redaction trong log chưa được thiết kế.
- Mọi số tiền trong session là server-side; client không thể ghi vào session.
- Token là UUID v4 ngẫu nhiên, không chứa payload (non-JWT), không thể suy diễn.
- Access control: chỉ Checkout Service truy cập keyspace `checkout:*`; user chỉ
  chạm session của chính mình qua `user_id` từ JWT.
- Encryption at rest/in transit của Redis và audit trail chưa được thiết kế.
