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
| **Ví voucher (`user_coupon_wallets`)** | `PLATFORM_VOUCHER`, `UNLIMITED_ALL` (nếu đã lưu trước) | Đã có sẵn trong ví, `status = SAVED` |

> [!IMPORTANT]
> Đối với tray Voucher Sàn tại Checkout, API `GET /v1/promotions/voucher-suggestions` chỉ trả mã đã có trong ví (`source = WALLET`). Không có cơ chế gợi ý mã sàn chưa lưu rồi auto-save ở Submit.

### 1.1.1. Claim Window vs Use Window (bắt buộc)

- `promotions.start_time/end_time`: cửa sổ **tranh/lưu mã mới** (claim/save window).
- `user_coupon_wallets.expired_at`: cửa sổ **được dùng sau khi đã sở hữu mã** (use window).
- Policy hiện tại: với mã được acquire/save thành công, `expired_at = saved_at + 30 ngày`.
- Vì vậy, voucher đã nằm trong ví và chưa quá `expired_at` vẫn có thể được evaluate/redeem dù promotion đã qua `end_time` claim window.

### 1.2. Stacking Rules (Slot Model)

Một đơn hàng áp dụng theo slot:

Tổng số voucher áp dụng là tối đa 1 (chỉ PLATFORM).

Sơ đồ slot:

```
┌─────────────────────────────────────────────────────────┐
│  SLOT PLATFORM_VOUCHER    (tối đa 1 mã toàn đơn)           │
└─────────────────────────────────────────────────────────┘
```

**Mapping từ bảng `promotions`:**
- **PLATFORM_VOUCHER**: `discount_type IN (PERCENTAGE, FIXED_AMOUNT)`


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
    "payment_method": "STRIPE"
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
  ├─── STEP 1: Lazy Expiry Sweep (Inline)
  │     Với mỗi row `user_coupon_wallets` trả về có `status = 'SAVED'`
  │     nhưng `expired_at <= NOW()`: chuyển sang `EXPIRED` bằng
  │     `UPDATE ... SET status = 'EXPIRED' WHERE id = :id AND status = 'SAVED'`
  │     (idempotent, không cần job nền).
  │
  ├─── STEP 2: Query Database
  │     SELECT w.promotion_id, w.status, w.expired_at,
  │            p.type, p.discount_type,
  │            p.discount_value, p.max_discount_amount,
  │            p.min_purchase_amount, p.code
  │     FROM user_coupon_wallets w
  │     JOIN promotions p ON p.id = w.promotion_id
  │     WHERE w.user_id = :user_id
  │       AND p.type = 'PLATFORM_VOUCHER'
  │       AND w.status = 'SAVED'
  │       AND (w.expired_at IS NULL OR w.expired_at > NOW())
  │       AND p.status = 'ACTIVE'
  │     -- Dùng Composite Index (user_id, status) → Index Scan
  │
  ├─── STEP 3: Pre-filter Eligibility (Rule Engine In-Memory)
  │     Với mỗi voucher ứng viên:
│     (a) Check nhanh min_purchase_amount (field riêng, O(1)):
    │         - PLATFORM_VOUCHER: so với total_subtotal
│     (b) Pipeline `MGET promo:rules:version + promo:{id}:snapshot × N` từ Redis, parse JSONB AST on-the-fly trong request scope thành `RuleNode`, eval với `cart_context` (request-scoped < 2ms):
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
    │     PLATFORM_VOUCHER: filter discount_type IN (PERCENTAGE, FIXED_AMOUNT)
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
  3. **Mã muốn xuất hiện ở tray Voucher Sàn phải tồn tại sẵn trong ví** (được lưu từ luồng claim/campaign/wallet management trước đó).

### 4.2. Vòng đời mã trong ví (không có bước insert ngầm)

```
[Đã có trong ví: SAVED]
     │
     │ -- Submit Checkout → ReservePromotionActivity --
     ▼
   RESERVED (runtime trên Redis reservation)
      │
      │ -- Reservation TTL hết / RollbackPromotionActivity (lane pre-payment) --
      ▼
   SAVED (không đổi state DB; chỉ nhả reserved runtime)
      │
      │ -- Submit lại thành công → ConfirmPromotionActivity --
      ▼
   USED (DB wallet)

  ORDER_REFUNDED (Phase 23):
  - Giữ nguyên `USED`, không restore về `SAVED`.
  - Voucher không còn được tái sử dụng sau refund.
```

---

## 5. Caching Strategy

### 5.1. Lazy Expiry Update (Inline trong Discovery)

Vì API này là Read-Heavy, thay vì giữ wallet cache trong Redis, Promotion áp dụng cơ chế **lazy expiry inline** để đảm bảo `user_coupon_wallets.status` luôn đúng trạng thái nghiệp vụ mà không cần job nền.

**Cơ chế:**
- Mỗi lần Discovery query `user_coupon_wallets`, với mỗi row trả về có `status = 'SAVED'` nhưng `expired_at <= NOW()`: thực hiện `UPDATE ... SET status = 'EXPIRED' WHERE id = :id AND status = 'SAVED'` (idempotent, nhiều request cùng chạy không xung đột).
- Sau update, row đó bị filter ra khỏi response (vì điều kiện `status = 'SAVED' AND expired_at > NOW()`).
- Row `EXPIRED` được giữ lại trong DB phục vụ audit/analytics; không xóa cứng.

**Trade-off:**
- Ưu: Không tốn Redis memory cho cache ví, đơn giản hóa vận hành (thay thế job nền `Wallet Expiry Sweep` cũ).
- Nhược: User không mở app trong thời gian dài sẽ giữ `status = 'SAVED'` dù đã quá hạn (cosmetic, không ảnh hưởng business vì filter query loại ra).
- Khi cần đối soát/audit, query bổ sung `WHERE status = 'EXPIRED'` chỉ ra đúng trạng thái đã chốt.

> [!NOTE]
> Option A cố ý **không** invalidation fanout theo `promotion_id -> user_ids` khi ngân sách cạn để tránh write amplification và phụ thuộc reverse-index lớn trong Redis. Đổi lại, độ tươi cho exhausted budget được cải thiện bằng Budget Guard read-time + Submit Reserve authoritative.

### 5.2. Snapshot Cache (Redis — Single-tier)

Promotion Service dùng Redis snapshot `STRING promo:{promotion_id}:snapshot` để cache bundle `{metadata, rule_ast_json, snapshot_version}` phục vụ pre-filter eligibility cho danh sách voucher lấy từ DB, không dùng để trộn thêm mã chưa lưu vào response `voucher-suggestions`.

Khi Admin tạo/sửa promotion, transaction Postgres `DEL promo:{promotion_id}:snapshot` rồi `INCR promo:rules:version` (sau commit). Version fence `STRING promo:rules:version` là single source of truth cross-instance để snapshot cache biết khi nào cần refresh.

---

## 6. Performance Profile

| Bước | Latency ước tính |
|:---|:---|
| DB query user_coupon_wallets (Index Scan `user_id, status`) | ~3-5ms |
| Snapshot cache HIT (MGET version + snapshot) | ~0.5-1ms |
| Snapshot cache MISS → DB query → populate | ~3-5ms |
| Rule Engine eval (parse AST on-the-fly + eval, n mã) | ~1-2.5ms |
| Budget Guard (Redis pipeline theo tập eligible) | ~0.5-1.5ms |
| Tính estimated_discount + Sort | ~0.5ms |
| **Tổng (snapshot cache HIT)** | **< 13ms** |
| **Tổng (snapshot cache MISS)** | **< 16ms** |

> Latency mục tiêu: **< 15ms 99th percentile** cho happy-path (snapshot cache HIT). Snapshot cache MISS path có thể chạm ngưỡng ~16ms nhưng vẫn chấp nhận được vì hiếm khi xảy ra và Checkout Service gọi API này **song song** với các call khác trong Preview Orchestration, nên nó không nằm trên critical path.

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
| `GET /v1/promotions/voucher-suggestions` | Khách mở tray **"Voucher của Sàn"** (đã lưu) | Liệt kê Platform Voucher **đã lưu trong ví** |
| `GET /v1/promotions/platform-voucher-discovery` | Khách mở tray **"Khám phá Voucher Sàn"** | Liệt kê toàn bộ `PLATFORM_VOUCHER` hợp lệ (cả đã lưu + chưa lưu) |

```
Khách mở màn hình Checkout
  │
  ▼
Checkout Preview (gọi song song: Cart, User, Inventory×N, Promotion)
  │ (Shipping quote được lấy gián tiếp qua Inventory routing)
        │
        ├─ Khách nhấn "Khám phá Voucher Sàn" (mở platform Voucher tray)
        │       │ -- GET /v1/promotions/platform-voucher-discovery --
        │       ▼
        │   Promotion Service trả ELIGIBLE[] + INELIGIBLE[] (< 15ms)
        │       │
        │       ├─ Khách chọn mã ĐÃ CÓ trong ví (source=WALLET)
        │       │       → POST /v1/checkout/preview với selected_platform_vouchers[X] = promo_id
        │       │
        │       └─ Khách nhấn "Lưu & Chọn" mã CHƯA CÓ trong ví (source=PLATFORM_PUBLIC)
        │               → POST /v1/promotions/save-and-apply {promotion_id}
        │               → Promotion: upsert user_coupon_wallets (SAVED)
        │               → POST /v1/checkout/preview với selected_platform_vouchers[X] = promo_id
        │
        └─ Khách nhấn "Voucher của Sàn" (mở Platform Voucher tray)
                │ -- GET /v1/promotions/voucher-suggestions --
                ▼
          Promotion Service trả Platform Vouchers từ ví (< 15ms)
                │
            └─ Khách chọn mã đã lưu → POST /v1/checkout/preview với IDs mới
```

---

## 9. PLATFORM Voucher Discovery API

### 9.1. Mục đích

API `GET /v1/promotions/platform-voucher-discovery` phục vụ tray **"Voucher Sàn"** trên màn hình Checkout. Khác với `voucher-suggestions` (chỉ show mã đã lưu trong ví), `platform-voucher-discovery` hiển thị **toàn bộ voucher `PLATFORM_VOUCHER` đang `ACTIVE`** — bao gồm cả mã user chưa lưu — để tối đa hóa cơ hội khách hàng tìm được mã tốt nhất.


### 9.2. Endpoint

```
GET /v1/promotions/platform-voucher-discovery
Authorization: Bearer {access_token}
```

### 9.3. Request Parameters

```json
{
  "cart_context": {
    "sub_cart_subtotal": 350000,
    "item_category_ids": [5, 12],
    "payment_method": "STRIPE"
  }
}
```

> `sub_cart_subtotal` là tổng giá sub-cart theo warehouse nhóm (không phải tổng đơn hàng) — dùng để evaluate `min_purchase_amount` và Rule Engine.

### 9.4. Backend Flow

```
GET /platform-voucher-discovery
  │
  ├── STEP 1: Fetch Wallet (tất cả PLATFORM voucher đã lưu)
  │     Query DB trực tiếp (không qua cache):
  │       SELECT w.promotion_id, w.status, w.expired_at, ...
  │       FROM user_coupon_wallets w
  │       JOIN promotions p ON p.id = w.promotion_id
  │       WHERE w.user_id = :user_id
  │         AND p.type = 'PLATFORM_VOUCHER'
  │         AND w.status = 'SAVED'
  │         AND (w.expired_at IS NULL OR w.expired_at > NOW())
  │         AND p.status = 'ACTIVE'
  │     → Lazy expiry: với row `SAVED` mà `expired_at <= NOW()` → UPDATE sang `EXPIRED` (idempotent)
  │     → Source: WALLET, is_saved=true
  │
  ├── STEP 2: Fetch PLATFORM Public Vouchers (chưa lưu trong ví)
  │     Query DB:
  │       SELECT p.*
  │       FROM promotions p
  │       WHERE
  │         p.type = 'PLATFORM_VOUCHER'
  │         AND p.discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT')
  │         AND p.status = 'ACTIVE'
  │         AND p.end_time > NOW()
  │         AND p.id NOT IN (saved_wallet_promotion_ids_from_step1)
  │     → Source: PLATFORM_PUBLIC, is_saved=false
  │     → Index: (type, status, end_time) — cần thêm composite index này
  │
  ├── STEP 3: Evaluate Eligibility (Rule Engine In-Memory)
  │     Với mỗi voucher (từ Step 1 + Step 2):
  │     (a) Check nhanh: sub_cart_subtotal >= min_purchase_amount
  │     (b) Pipeline `MGET promo:rules:version + promo:{id}:snapshot × N`, parse JSONB AST on-the-fly trong request scope, eval với `cart_context` (< 2ms)
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
        "source": "PLATFORM_PUBLIC",
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
| `EXPIRED` | Voucher quá `expired_at` (mã đã có trong ví) hoặc quá `end_time` claim window (mã PLATFORM_PUBLIC) |

### 9.6. Save & Apply Flow (Khi user nhấn "Lưu & Chọn" mã chưa lưu)

```
POST /v1/promotions/save-and-apply
{
  "promotion_id": 60
}
```

Promotion Service:
1. Validate: promotion tồn tại, `p.type = 'PLATFORM_VOUCHER'`, `p.status = ACTIVE`, và đang trong claim/save window hợp lệ (`p.start_time <= NOW() AND p.end_time > NOW()`).
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
3. Trả `200 OK {saved: true}`. Lần Discovery kế tiếp sẽ tự thấy row mới (query trực tiếp DB, không qua cache).

> [!NOTE]
> `expired_at` được gán ngay lúc acquire/save để tách bạch claim window (`promotions.end_time`) và use window theo ví (`saved_at + 30 ngày`).
>
> Sau khi nhận `200 OK`, Checkout Service gọi ngay `POST /v1/checkout/preview` với `selected_vouchers = promotion_id`. Budget Reserve xảy ra trong Temporal Saga khi Submit — không phải tại đây.

### 9.7. Performance Profile

| Bước | Latency |
|:---|:---|
| DB query user_coupon_wallets (Index Scan `user_id, status`) | ~3-5ms |
| DB query platform Public vouchers | ~3-5ms |
| Snapshot cache (MGET version + snapshot) + parse AST + eval | ~1-2.5ms |
| **Tổng (snapshot cache HIT)** | **< 13ms** |
| **Tổng (snapshot cache MISS)** | **< 16ms** |

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
      "user_message": "Mã giảm giá đã hết ngân sách."
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
