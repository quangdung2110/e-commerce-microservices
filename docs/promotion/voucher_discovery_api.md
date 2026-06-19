# Voucher Discovery API (Gợi ý & Hiển thị Mã Giảm Giá tại Checkout)

**Liên kết nhanh (Related Documents):**
*   [Database Schema](promotion_db_design.md)
*   [Redis Caching & Lua Scripts](redis_caching_design.md)
*   [Promotion Workflows](promotion_workflow.md)
*   [Rule Engine Design](rule_engine_design.md)
*   [Checkout Workflow](../checkout/checkout_workflow.md)
*   [Checkout Caching Schema](../checkout/checkout_caching_schema.md)

---

Tài liệu này thiết kế API `GET /v1/promotions/voucher-suggestions` — API hiển thị danh sách mã giảm giá khả dụng cho khách hàng tại màn hình Checkout. Đây là API **Read-Heavy** được gọi mỗi lần khách nhấn vào ô "Chọn hoặc nhập mã giảm giá".

---

## 1. Bài toán & Yêu cầu Nghiệp vụ

### 1.1. Nguồn mã giảm giá tại Checkout

Khách hàng có thể sử dụng voucher từ **2 nguồn nghiệp vụ**:

| Nguồn | Loại mã | Cách lấy |
|:---|:---|:---|
| **Ví voucher (`user_coupon_wallets`)** | `LIMITED_FLASH`, `UNLIMITED_ALL` (nếu đã lưu trước) | Đã có sẵn trong ví, `status = SAVED` |

> [!IMPORTANT]
> Đối với tray Voucher Sàn tại Checkout, API `GET /v1/promotions/voucher-suggestions` chỉ trả mã đã có trong ví (`source = WALLET`). Không có cơ chế gợi ý mã sàn chưa lưu rồi auto-save ở Submit.

### 1.1.1. Claim Window vs Use Window (bắt buộc)

- `promotions.start_time/end_time`: cửa sổ **tranh/lưu mã mới** (claim/save window).
- `user_coupon_wallets.expired_at`: cửa sổ **được dùng sau khi đã sở hữu mã** (use window).
- Policy hiện tại: với mã được acquire/save thành công, `expired_at = saved_at + 30 ngày`.
- Vì vậy, voucher đã nằm trong ví và chưa quá `expired_at` vẫn có thể được evaluate/redeem dù promotion đã qua `end_time` claim window.

### 1.2. Stacking Rules (Slot Model)

Một đơn hàng áp dụng theo slot:

* toàn đơn tối đa 1 `PLATFORM_VOUCHER`

Tổng số voucher áp dụng là tối đa 1 (chỉ PLATFORM sau Phase 14).

Sơ đồ slot:

```
┌─────────────────────────────────────────────────────────┐
│  SLOT PLATFORM_VOUCHER    (tối đa 1 mã toàn đơn)           │
└─────────────────────────────────────────────────────────┘
```

**Mapping từ bảng `promotions`:**
- **PLATFORM_VOUCHER**: `deleted_owner_type = PLATFORM` + `discount_type IN (PERCENTAGE, FIXED_AMOUNT)`

### 1.2.1. Dependency giữa các slot

- Sau Phase 14, chỉ còn 1 slot PLATFORM_VOUCHER duy nhất. Không còn dependency giữa các slot.



---

## 2. API Design

### 2.1. Endpoint

```
GET /v1/promotions/voucher-suggestions
Authorization: Bearer {access_token}
```

### 2.2. Request Parameters

```json
{
  "cart_context": {
    "subtotal": 530000,
    "payment_method": "VNPAY"
  }
}
```

> [!NOTE]
> `cart_context` được gửi kèm để Promotion Service có thể **pre-filter eligibility** ngay tại API này — tránh trả mã không applicable rồi frontend phải gọi lại lần nữa để validate. `item_category_ids` phục vụ điều kiện Rule Engine kiểu `cart.items.category_id NOT_IN [...]`.

### 2.3. Response Structure

```json
{
  "voucher_tray": {
    "platform_voucher": {
      "best_voucher": {
        "promotion_id": 10,
        "code": "SIEUSALE11",
        "discount_type": "FIXED_AMOUNT",
        "discount_value": 100000,
        "max_discount_amount": null,
        "min_purchase_amount": 500000,
        "source": "WALLET",
        "eligible": false,
        "ineligible_reason": "MIN_PURCHASE_NOT_MET",
        "amount_needed": 30000,
        "estimated_discount": 0
      },
      "other_vouchers": [
        {
          "promotion_id": 12,
          "code": "BIRTHDAY50",
          "discount_type": "FIXED_AMOUNT",
          "discount_value": 50000,
          "min_purchase_amount": 300000,
          "source": "WALLET",
          "eligible": true,
          "estimated_discount": 50000
        }
      ]
    }
  },
  "cache_meta": {
    "wallet_cached_at": "2026-03-12T09:30:00Z",
    "ttl_remaining_seconds": 240,
    "promotion_rule_version": "2026-03-12T09:29:55Z",
    "promotion_budget_version": "2026-03-12T09:29:58Z",
    "budget_guard_status": "OK",
    "budget_guard_checked_promotions": 2,
    "budget_guard_checked_at": "2026-03-12T09:30:00Z"
  }
}
```

> [!IMPORTANT]
> `promotion_rule_version` và `promotion_budget_version` là contract bắt buộc cho Checkout Submit staleness check. Checkout cache snapshot phải lưu cả hai giá trị này để so sánh trước khi khóa token Submit.
>
> Theo hướng Option A, Discovery chạy thêm `budget_guard` dạng read-time best-effort để giảm stale hiển thị mã hết ngân sách. Tuy nhiên quyết định cuối cùng vẫn nằm ở Reserve khi Submit.

**Giải thích trường `source`:**
- `WALLET`: Mã đã có sẵn trong `user_coupon_wallets` (status=SAVED).
- `platform_PUBLIC`: Mã platform chưa có trong ví user (chỉ xuất hiện ở API `platform-voucher-discovery`), được lưu vào ví khi user gọi `save-and-apply`.

---

## 3. Luồng Xử lý Backend (Server-side Flow)

```
Client
  │  GET /v1/promotions/voucher-suggestions
  ▼
Promotion Service
  │
  ├─── STEP 1: Check Wallet Cache (Redis)
  │     Key: wallet:{user_id}:active_vouchers  TTL 5 phút
  │     Kèm version fence: so `wallet_cache.rule_version_snapshot` với `promo:rules:version` hiện tại.
  │     ├── Cache HIT + version khớp  → bỏ qua Step 2
  │     └── Cache MISS hoặc version lệch → STEP 2
  │
  ├─── STEP 2: Query Database
  │     SELECT w.promotion_id, w.status, w.expired_at,
  │            p.deleted_owner_type, p.owner_id, p.discount_type,
  │            p.discount_value, p.max_discount_amount,
  │            p.min_purchase_amount, p.code
  │     FROM user_coupon_wallets w
  │     JOIN promotions p ON p.id = w.promotion_id
  │     WHERE w.user_id = :user_id
  │       AND w.status = 'SAVED'
  │       AND (w.expired_at IS NULL OR w.expired_at > NOW())
  │       AND p.status = 'ACTIVE'
  │     -- Dùng Composite Index (user_id, status) → Index Scan
  │
  │     → Ghi kết quả vào Redis: wallet:{user_id}:active_vouchers
  │
  ├─── STEP 3: Pre-filter Eligibility (Rule Engine In-Memory)
  │     Với mỗi voucher ứng viên:
│     (a) Check nhanh min_purchase_amount (field riêng, O(1)):
    │         - PLATFORM_VOUCHER: so với total_subtotal
    │     (b) Eval conditions_rule AST (Caffeine L1, <1ms):
    │         - Dùng cart_context làm input
    │         - Gắn eligible=true/false + ineligible_reason
    │     (c) Tính estimated_discount (in-memory):
    │         - PERCENTAGE: min(subtotal * rate, max_discount_amount)
    │         - FIXED_AMOUNT: min(discount_value, subtotal)
  │     (d) Thu thập version fence cho Submit path:
  │         - `promotion_rule_version`: max(updated_at/rule_version) của các rule đã evaluate
  │         - `promotion_budget_version`: max(budget_version_updated_at) của các budget liên quan
  │
  ├─── STEP 4: Budget Availability Guard (Redis Read-time, Option A)
  │     Chỉ áp dụng cho tập voucher đang `eligible=true` sau Step 3:
  │     (a) Gom `promotion_id` duy nhất và đọc batch qua Redis Pipelining:
  │         HMGET promo:{id}:budget total_qty reserved_qty used_qty total_amount reserved_amount used_amount
  │     (b) Tính available runtime:
  │         - available_qty = total_qty - used_qty - reserved_qty
  │         - available_amount = total_amount - used_amount - reserved_amount (bỏ qua nếu total_amount = -1)
  │     (c) Nếu thiếu quota hoặc thiếu amount cho voucher đang xét:
  │         - set `eligible=false`
  │         - set `ineligible_reason=BUDGET_EXHAUSTED`
  │     (d) Nếu Redis timeout/lỗi nhất thời:
  │         - fail-open (không chặn response)
  │         - gắn `cache_meta.budget_guard_status=DEGRADED` để observability
  │
├─── STEP 5: Gom nhóm theo Slot Model (Group & Sort)
    │     PLATFORM_VOUCHER: filter deleted_owner_type=PLATFORM + discount_type IN (PERCENTAGE, FIXED_AMOUNT)
    │                       sort: eligible first, then estimated_discount DESC
    │                       chọn `best_voucher` + `other_vouchers` theo cùng rule sort
  │
  └─── STEP 6: Build Response & Return (kèm `promotion_rule_version`, `promotion_budget_version`, `budget_guard_status` trong `cache_meta`)
```

---

## 4. Chính sách Không Auto-Save ở Submit

`GET /v1/promotions/voucher-suggestions` là API read-only và chỉ phục vụ các mã đã có trong ví. Vì vậy, submit path của Checkout/Order chỉ thực hiện reserve/confirm/rollback theo tập mã đã chọn, không chèn thêm bản ghi ví ngầm.

### 4.1. Nguyên tắc contract

1. **Không auto-save mã sàn tại Submit.**
2. **Không upsert `user_coupon_wallets` trong Discovery path.**
3. **Mã muốn xuất hiện ở tray Voucher Sàn/Freeship phải tồn tại sẵn trong ví** (được lưu từ luồng claim/campaign/wallet management trước đó).

### 4.2. Vòng đời mã trong ví (không có bước insert ngầm)

```
[Đã có trong ví: SAVED]
     │
     │ -- Submit Checkout → ReservePromotionActivity --
     ▼
   RESERVED (runtime trên Redis reservation)
     │
     │ -- ORDER_CANCELLED/PAYMENT_TIMEOUT --
     ▼
   SAVED (không đổi state DB; chỉ nhả reserved runtime)
     │
     │ -- Submit lại thành công → ORDER_PAID --
     ▼
   USED (DB wallet)

  ORDER_REFUNDED:
  - Chỉ `USED -> SAVED` khi promotion cho phép tái sử dụng **và** voucher còn hạn (`expired_at > NOW()`).
  - Nếu đã quá hạn tại thời điểm restore: chuyển `EXPIRED` (hoặc giữ `USED` nếu không có row ví để cập nhật).
  - Nếu promotion không cho phép tái sử dụng: giữ `USED`.
```

---

## 5. Caching Strategy

### 5.1. Wallet Cache (L2 Redis)

Đây là cache quan trọng nhất vì API này là Read-Heavy — user mở khung voucher nhiều lần.

**Key:** `wallet:{user_id}:active_vouchers`
**Type:** `String (JSON Array)`
**TTL:** `5 phút`

**Nội dung cache:**
```json
[
  {
    "promotion_id": 55,
    "code": "TECHVIET20",
    "deleted_owner_type": "PLATFORM",
    "owner_id": 101,
    "discount_type": "PERCENTAGE",
    "discount_value": 20,
    "max_discount_amount": 50000,
    "min_purchase_amount": 200000,
    "expired_at": "2026-04-01T00:00:00Z"
  }
]
```

> [!NOTE]
> Cache **không** lưu `estimated_discount` (vì phụ thuộc cart_context được gửi lên mỗi lần gọi). Cache chỉ lưu **metadata tĩnh** của ví — việc tính discount và eval rule vẫn chạy in-memory mỗi lần.

**Invalidation Triggers:**

| Trigger | Hành động |
|:---|:---|
| `COUPON_CLAIMED` (user săn được mã mới) | `DEL wallet:{user_id}:active_vouchers` |
| `ORDER_PAID` (mã chuyển sang USED) | `DEL wallet:{user_id}:active_vouchers` |
| `ORDER_CANCELLED` (nhả reservation pre-payment, không đổi state ví) | `DEL wallet:{user_id}:active_vouchers` |
| `ORDER_REFUNDED` (mã có thể đổi trạng thái theo policy tái sử dụng) | `DEL wallet:{user_id}:active_vouchers` |
| Mã hết hạn (cron job expire sweep) | `DEL wallet:{user_id}:active_vouchers` |
| `BUDGET_EXHAUSTED` (event cấp promotion) | **Không fanout `DEL` toàn cục** theo `promotion_id`; Discovery xử lý bằng Budget Guard read-time |
| `PROMO_EVICT` / Admin update promotion | bump `promo:rules:version`; cache cũ bị coi như miss ở lần đọc kế tiếp |

**Chiến lược Invalidation:** Dùng `DEL` (xóa hẳn) thay vì update, vì thay đổi ví thiếu tần suất đủ để justify complexity của partial update. Cache miss → query DB → populate lại. Latency DB query ví là ~3-5ms (Index Scan trên `user_id, status`), chấp nhận được.

> [!NOTE]
> Option A cố ý **không** invalidation fanout theo `promotion_id -> user_ids` khi ngân sách cạn để tránh write amplification và phụ thuộc reverse-index lớn trong Redis. Đổi lại, độ tươi cho exhausted budget được cải thiện bằng Budget Guard read-time + Submit Reserve authoritative.

### 5.2. Rule Metadata Cache (L1 Caffeine)

Promotion Service dùng Caffeine L1 để cache metadata + AST rule của các promotion nhằm giảm độ trễ evaluate. Dữ liệu L1 phục vụ pre-filter eligibility cho danh sách voucher lấy từ wallet cache/DB, không dùng để trộn thêm mã chưa lưu vào response `voucher-suggestions`.

Khi Admin tạo/sửa promotion, bắt buộc phát `PROMO_EVICT` và bump `promo:rules:version` để đồng bộ L1 metadata và wallet cache fence ở Step 1.

---

## 6. Performance Profile

| Bước | Latency ước tính |
|:---|:---|
| Redis wallet cache HIT | ~2ms |
| Redis wallet cache MISS → DB query → populate | ~5-8ms |
| Rule Engine eval (AST in-memory, n mã) | ~1-3ms |
| Budget Guard (Redis pipeline theo tập eligible) | ~0.5-1.5ms |
| Tính estimated_discount + Sort | ~0.5ms |
| **Tổng (cache HIT path)** | **< 12ms** |
| **Tổng (cache MISS path)** | **< 15ms** |

> Latency mục tiêu: **< 15ms 99th percentile**. Checkout Service gọi API này **song song** với các call khác trong Preview Orchestration, nên nó không nằm trên critical path.

---

## 7. Edge Cases & Xử lý

### 7.1. Ví quá lớn (user có hàng trăm mã SAVED)

Áp page-based filter: chỉ trả tối đa **20 mã eligible** + **10 mã ineligible** trong response. Eligible sort trước. Các mã đã USED/EXPIRED được lọc bỏ ngay ở tầng DB query.

### 7.2. Voucher trong ví hết ngân sách (Option A)

API Discovery chạy **Budget Guard read-time** trên tập voucher đang eligible để giảm khả năng gợi ý mã đã cạn ngân sách. Các mã bị cạn sẽ được downgrade sang `ineligible_reason = BUDGET_EXHAUSTED` và không nên trở thành `best_voucher`.

Tuy nhiên, Discovery không lock budget và không thay thế được commit-time reserve. Vì vậy vẫn tồn tại race ngắn giữa Preview và Submit: mã có thể còn pass ở Discovery nhưng fail tại `ReservePromotionActivity` nếu ngân sách cạn ngay trước lúc commit. Khi đó Checkout trả `409 PROMOTION_RECALCULATION_NEEDED` và client phải quay lại Preview lấy token mới.

### 7.3. Guard Degraded Mode (Fail-open)

Nếu Budget Guard gặp timeout/lỗi Redis tạm thời, Discovery fail-open để giữ availability của API:

- Không chặn response voucher tray.
- Gắn `cache_meta.budget_guard_status = DEGRADED` để theo dõi vận hành.
- Đảm bảo correctness cuối cùng vẫn do Submit Reserve kiểm soát.

---

## 8. Tích hợp vào Checkout Flow

Màn hình Checkout sử dụng **2 API Voucher Discovery riêng biệt** tùy theo ngữ cảnh:

| API | Khi nào gọi | Mô tả |
|:---|:---|:---|
| `GET /v1/promotions/voucher-suggestions` | Khách mở tray **"Voucher của Sàn"** | Liệt kê Platform Voucher + Freeship **đã lưu trong ví** |
| `GET /v1/promotions/platform-voucher-discovery` | Khách mở tray **"Voucher của platform X"** | Liệt kê toàn bộ voucher hợp lệ của platform đó (cả đã lưu + chưa lưu) |

```
Khách mở màn hình Checkout
  │
  ▼
Checkout Preview (gọi song song: Cart, User, Inventory×N, Promotion)
  │ (Shipping quote được lấy gián tiếp qua Inventory routing)
        │
        ├─ Khách nhấn "Voucher của platform X" (mở platform Voucher tray)
        │       │ -- GET /v1/promotions/platform-voucher-discovery?platform_id=X --
        │       ▼
        │   Promotion Service trả ELIGIBLE[] + INELIGIBLE[] (< 15ms)
        │       │
        │       ├─ Khách chọn mã ĐÃ CÓ trong ví (source=WALLET)
        │       │       → POST /v1/checkout/preview với selected_platform_vouchers[X] = promo_id
        │       │
        │       └─ Khách nhấn "Lưu & Chọn" mã CHƯA CÓ trong ví (source=platform_PUBLIC)
        │               → POST /v1/promotions/save-and-apply {platform_id, promotion_id}
        │               → Promotion: upsert user_coupon_wallets (SAVED)
        │               → POST /v1/checkout/preview với selected_platform_vouchers[X] = promo_id
        │
        └─ Khách nhấn "Voucher của Sàn" (mở Platform Voucher tray)
                │ -- GET /v1/promotions/voucher-suggestions --
                ▼
          Promotion Service trả Platform Vouchers + Freeship từ ví (< 15ms)
                │
            └─ Khách chọn mã đã lưu → POST /v1/checkout/preview với IDs mới
```

---

## 9. platform Voucher Discovery API

### 9.1. Mục đích

API `GET /v1/promotions/platform-voucher-discovery` phục vụ tray **"Voucher của platform"** trên màn hình Checkout. Khác với Platform Voucher Discovery (chỉ show mã đã lưu trong ví), platform Voucher Discovery hiển thị **toàn bộ voucher hiện có của platform đó** — bao gồm cả mã user chưa lưu — để tối đa hóa cơ hội khách hàng tìm được mã tốt nhất.

### 9.2. Endpoint

```
GET /v1/promotions/platform-voucher-discovery?platform_id={platform_id}
Authorization: Bearer {access_token}
```

### 9.3. Request Parameters

```json
{
  "platform_id": 101,
  "cart_context": {
    "sub_cart_subtotal": 350000,
    "item_category_ids": [5, 12],
    "payment_method": "VNPAY"
  }
}
```

> `sub_cart_subtotal` là tổng giá sub-cart theo warehouse nhóm (không phải tổng đơn hàng) — dùng để evaluate `min_purchase_amount` và Rule Engine.

### 9.4. Backend Flow

```
GET /platform-voucher-discovery?platform_id=101
  │
  ├── STEP 1: Fetch Wallet (Filter by platform_id)
  │     Check Redis: wallet:{user_id}:active_vouchers (TTL 5 phút)
  │     → Filter: deleted_owner_type=PLATFORM AND owner_id=101 AND (expired_at IS NULL OR expired_at > NOW())
  │     → Source: WALLET, is_saved=true
  │
  ├── STEP 2: Fetch platform Public Vouchers (chưa lưu trong ví)
  │     Query DB:
  │       SELECT p.*
  │       FROM promotions p
  │       WHERE p.deleted_owner_type = 'PLATFORM'
  │         AND p.owner_id = 101
  │         AND p.discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT')
  │         AND p.status = 'ACTIVE'
  │         AND p.end_time > NOW()
  │         AND p.id NOT IN (wallet_promotion_ids_from_step1)
  │     → Source: platform_PUBLIC, is_saved=false
  │     → Index: (deleted_owner_type, owner_id, status) — cần thêm index này
  │
  ├── STEP 3: Evaluate Eligibility (Rule Engine In-Memory)
  │     Với mỗi voucher (từ Step 1 + Step 2):
  │     (a) Check nhanh: sub_cart_subtotal >= min_purchase_amount
  │     (b) Eval conditions_rule AST (Caffeine L1, <1ms)
  │     (c) Tính estimated_discount in-memory
  │     (d) Gán eligible=true/false + ineligible_reason nếu false
  │
  ├── STEP 4: Budget Guard (Option A)
  │     Chỉ với voucher đang eligible sau Step 3:
  │     (a) Đọc batch `promo:{id}:budget` bằng Redis pipelining
  │     (b) Nếu budget không còn khả dụng: set `eligible=false`, `ineligible_reason=BUDGET_EXHAUSTED`
  │     (c) Nếu guard lỗi tạm thời: fail-open + đánh dấu degraded trong telemetry
  │
  └── STEP 5: Sort & Group
        eligible[]:   sort by estimated_discount DESC (Best Choice)
        ineligible[]: sort by (amount_needed ASC) — gần đủ điều kiện nhất lên đầu
        Return: {eligible[], ineligible[]}
```

### 9.5. Response Structure

```json
{
  "platform_id": 101,
    "vouchers": {
    "eligible": [
      {
        "promotion_id": 55,
        "code": "TECHVIET20",
        "discount_type": "PERCENTAGE",
        "discount_value": 20,
        "max_discount_amount": 50000,
        "min_purchase_amount": 200000,
        "estimated_discount": 50000,
        "source": "WALLET",
        "is_saved": true,
        "expired_at": "2026-04-10T00:00:00Z"
      }
    ],
    "ineligible": [
      {
        "promotion_id": 60,
        "code": "TECHVIET100K",
        "discount_type": "FIXED_AMOUNT",
        "discount_value": 100000,
        "min_purchase_amount": 500000,
        "estimated_discount": 0,
        "source": "platform_PUBLIC",
        "is_saved": false,
        "ineligible_reason": "MIN_PURCHASE_NOT_MET",
        "amount_needed": 150000,
        "end_time": "2026-04-01T00:00:00Z"
      }
    ]
  }
}
```

**Giải thích `ineligible_reason`:**

| Code | Ý nghĩa |
|:---|:---|
| `MIN_PURCHASE_NOT_MET` | Chưa đủ giá trị đơn tối thiểu (`amount_needed` = số tiền còn thiếu) |
| `CATEGORY_NOT_MATCHED` | Sản phẩm không thuộc danh mục áp dụng |
| `PAYMENT_METHOD_NOT_MATCHED` | Phương thức thanh toán không hợp lệ |
| `BUDGET_EXHAUSTED` | Voucher cạn ngân sách/lượt tại thời điểm guard read-time |
| `EXPIRED` | Voucher quá `expired_at` (mã đã có trong ví) hoặc quá `end_time` claim window (mã platform public) |

### 9.6. Save & Apply Flow (Khi user nhấn "Lưu & Chọn" mã chưa lưu)

```
POST /v1/promotions/save-and-apply
{
  "platform_id": 101,
  "promotion_id": 60
}
```

Promotion Service:
1. Validate: promotion tồn tại, `owner_id = platform_id`, `status = ACTIVE`, và đang trong claim/save window hợp lệ.
2. Upsert vào `user_coupon_wallets`:
   ```sql
  INSERT INTO user_coupon_wallets (user_id, promotion_id, status, saved_at, expired_at)
  VALUES (:user_id, :promotion_id, 'SAVED', NOW(), NOW() + INTERVAL '30 days')
  ON CONFLICT (user_id, promotion_id) DO UPDATE
  SET status = 'SAVED',
     saved_at = NOW(),
     expired_at = NOW() + INTERVAL '30 days',
     used_at = NULL
  WHERE user_coupon_wallets.status = 'EXPIRED';
   ```
  - Nếu row đã `SAVED` và còn hạn: trả success idempotent, không kéo dài `expired_at`.
  - Nếu row đã `USED`: mặc định từ chối `409 ALREADY_USED_VOUCHER` (trừ khi promotion policy cho phép reacquire rõ ràng).
3. Invalidate Redis wallet cache: `DEL wallet:{user_id}:active_vouchers`.
4. Trả `200 OK {saved: true}`.

> [!NOTE]
> `expired_at` được gán ngay lúc acquire/save để tách bạch claim window (`promotions.end_time`) và use window theo ví (`saved_at + 30 ngày`).
>
> Sau khi nhận `200 OK`, Checkout Service gọi ngay `POST /v1/checkout/preview` với `selected_vouchers = promotion_id`. Budget Reserve xảy ra trong Temporal Saga khi Submit — không phải tại đây.

### 9.7. Performance Profile

| Bước | Latency |
|:---|:---|
| Wallet cache HIT + filter | ~2ms |
| DB query platform Public vouchers | ~3-5ms |
| Rule Engine eval (n mã) | ~1-3ms |
| **Tổng (cache HIT)** | **< 12ms** |
| **Tổng (cache MISS)** | **< 15ms** |

> Rate Limiting: `promotion:ratelimit:voucher_discovery:{user_id}` — 20 req/phút (xem `checkout_caching_schema.md`).

---

## 10. Submit-time Re-evaluation Contract (Reserve Fail)

Khi reserve thất bại do all-or-nothing (Script 2B), Promotion Service và Checkout Service phải dùng chung contract reason để FE hiển thị minh bạch.

### 10.1. Reason Codes chuẩn

| Code | Nguồn | Ý nghĩa |
|:---|:---|:---|
| `BUDGET_EXHAUSTED` | Budget Guard (Discovery) / Reserve (Redis) | Mã không còn ngân sách/lượt ở read-time hoặc commit-time |
| `CONDITIONS_NOT_MET` | Rule Engine | Mã không còn hợp lệ sau khi loại mã fail ban đầu |
| `MIN_PURCHASE_NOT_MET` | Rule Engine | Không đạt giá trị đơn tối thiểu |
| `PAYMENT_METHOD_NOT_MATCHED` | Rule Engine | Phương thức thanh toán không khớp điều kiện rule |
| `CATEGORY_NOT_MATCHED` | Rule Engine | Không có sản phẩm thuộc danh mục áp dụng của mã |
| `EXPIRED` | Rule Engine | Mã vừa hết hạn tại submit-time |

> Ở Discovery, `BUDGET_EXHAUSTED` đến từ Budget Guard read-time (best-effort). Ở Submit, Reserve trả `failed_reason` chi tiết (`BUDGET_QTY_EXHAUSTED` hoặc `BUDGET_AMOUNT_EXHAUSTED`) phục vụ debug/observability; Checkout map về reason chuẩn `BUDGET_EXHAUSTED` để giữ contract ổn định.

### 10.2. Payload 409 giữa Checkout và Frontend

```json
{
  "error_code": "PROMOTION_RECALCULATION_NEEDED",
  "message": "Giỏ hàng có sự thay đổi về mã giảm giá.",
  "action": "RECALL_PREVIEW",
  "dropped_promotions": [
    {
      "promotion_id": 30,
      "reason": "BUDGET_EXHAUSTED",
      "user_message": "Mã Freeship đã hết ngân sách."
    }
  ],
  "proposal_checkout_data": {
    "new_total_amount": 550000,
    "surviving_promotions": [
      55
    ]
  }
}
```

> `dropped_promotions[]` chỉ chứa các mã bị loại. Mã vẫn hợp lệ không xuất hiện trong danh sách này.
