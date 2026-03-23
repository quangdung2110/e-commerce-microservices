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

Khách hàng có thể sử dụng voucher từ **2 nguồn**:

| Nguồn | Loại mã | Cách lấy |
|:---|:---|:---|
| **Ví voucher (`user_coupon_wallets`)** | `LIMITED_FLASH` đã săn, `SELLER_LIMITED` đã lưu | Đã có sẵn trong ví, `status = SAVED` |
| **Mã sàn toàn cục (`UNLIMITED_ALL`)** | Mã sàn không giới hạn lượt (sinh nhật, tân thủ, campaign rộng) | Chưa có trong ví — gợi ý và auto-save khi dùng |

### 1.2. Stacking Rules (3 Slot cứng)

Một đơn hàng **(bao gồm nhiều sản phẩm từ nhiều shop)** áp dụng tối đa **3 voucher**, chia thành 3 slot riêng biệt:

```
┌─────────────────────────────────────────────────────────┐
│  SLOT 1: SHOP_VOUCHER   (1 mã/shop — theo từng Sub-Cart)│
│  SLOT 2: PLATFORM_VOUCHER   (1 mã — toàn đơn)          │
│  SLOT 3: FREESHIP_VOUCHER   (1 mã — chỉ Platform)       │
└─────────────────────────────────────────────────────────┘
```

**Mapping từ bảng `promotions`:**
- **SHOP_VOUCHER**: `owner_type = SELLER` + `discount_type IN (PERCENTAGE, FIXED_AMOUNT)`
- **PLATFORM_VOUCHER**: `owner_type = PLATFORM` + `discount_type IN (PERCENTAGE, FIXED_AMOUNT)`
- **FREESHIP_VOUCHER**: `discount_type = FREE_SHIPPING` + `owner_type = PLATFORM` (shop không được tạo mã freeship)

### 1.3. Quy tắc Freeship (Freeship Rule)

> [!IMPORTANT]
> Slot Freeship chỉ có **1 chỗ duy nhất** và chỉ nhận mã của Platform. Seller không được tạo/không được hiển thị mã freeship trong bất kỳ slot nào.

**Thứ tự sort trong slot FREESHIP:**
1. `owner_type = PLATFORM` và applicable → **Hiển thị đầu tiên, badge "Ưu đãi sàn"**
2. Mã eligible = false (chưa đủ điều kiện mua tối thiểu) → Hiển thị mờ cuối danh sách

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
    "sub_carts": [
      {
        "seller_id": 101,
        "subtotal": 350000,
        "item_category_ids": [5, 12]
      },
      {
        "seller_id": 205,
        "subtotal": 180000,
        "item_category_ids": [8]
      }
    ],
    "total_subtotal": 530000,
    "total_shipping_fee": 45000,
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
    "shop_vouchers": [
      {
        "seller_id": 101,
        "seller_name": "Shop Tech Việt",
        "best_voucher": {
          "promotion_id": 55,
          "code": "TECHVIET20",
          "discount_type": "PERCENTAGE",
          "discount_value": 20,
          "max_discount_amount": 50000,
          "min_purchase_amount": 200000,
          "source": "WALLET",
          "eligible": true,
          "estimated_discount": 50000
        },
        "other_vouchers": []
      },
      {
        "seller_id": 205,
        "seller_name": "Fashion Store",
        "best_voucher": null,
        "other_vouchers": [],
        "note": "NO_VOUCHER_AVAILABLE"
      }
    ],
    "platform_voucher": {
      "best_voucher": {
        "promotion_id": 10,
        "code": "SIÊUSALE11",
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
          "source": "UNLIMITED_ALL",
          "eligible": true,
          "estimated_discount": 50000
        }
      ]
    },
    "freeship_voucher": {
      "vouchers": [
        {
          "promotion_id": 30,
          "code": "FREESHIP_PLATFORM",
          "owner_type": "PLATFORM",
          "max_freeship_amount": 45000,
          "min_purchase_amount": 200000,
          "source": "WALLET",
          "eligible": true,
          "estimated_discount": 45000,
          "badge": "ƯU ĐÃI SÀN"
        },
        {
          "promotion_id": 31,
          "code": "FREESHIP_PLUS",
          "owner_type": "PLATFORM",
          "max_freeship_amount": 30000,
          "min_purchase_amount": 500000,
          "source": "WALLET",
          "eligible": false,
          "ineligible_reason": "MIN_PURCHASE_NOT_MET",
          "estimated_discount": 0,
          "badge": "ƯU ĐÃI SÀN"
        }
      ]
    }
  },
  "cache_meta": {
    "wallet_cached_at": "2026-03-12T09:30:00Z",
    "ttl_remaining_seconds": 240
  }
}
```

**Giải thích trường `source`:**
- `WALLET`: Mã đã có sẵn trong `user_coupon_wallets` (status=SAVED).
- `UNLIMITED_ALL`: Mã sàn chưa có trong ví, sẽ được auto-save khi user xác nhận dùng.

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
  │     ├── Cache HIT  → bỏ qua Step 2
  │     └── Cache MISS → STEP 2
  │
  ├─── STEP 2: Query Database
  │     SELECT w.promotion_id, w.status, w.expired_at,
  │            p.owner_type, p.owner_id, p.discount_type,
  │            p.discount_value, p.max_discount_amount,
  │            p.min_purchase_amount, p.code, p.is_exclusive
  │     FROM user_coupon_wallets w
  │     JOIN promotions p ON p.id = w.promotion_id
  │     WHERE w.user_id = :user_id
  │       AND w.status = 'SAVED'
  │       AND (w.expired_at IS NULL OR w.expired_at > NOW())
  │       AND p.status = 'ACTIVE'
  │       AND p.end_time > NOW()
  │     -- Dùng Composite Index (user_id, status) → Index Scan
  │
  │     → Ghi kết quả vào Redis: wallet:{user_id}:active_vouchers
  │
  ├─── STEP 3: Fetch UNLIMITED_ALL Promotions (Caffeine L1 Cache)
  │     Đọc danh sách promotion type=UNLIMITED_ALL đang ACTIVE từ
  │     L1 Caffeine Cache (đã nạp sẵn metadata, refresh mỗi 1 phút).
  │     Loại trừ những promotion_id đã có trong ví (từ Step 1/2).
  │     → Gọp thêm vào danh sách ứng viên với source='UNLIMITED_ALL'
  │
  ├─── STEP 4: Pre-filter Eligibility (Rule Engine In-Memory)
  │     Với mỗi voucher ứng viên:
  │     (a) Check nhanh min_purchase_amount (field riêng, O(1)):
  │         - SHOP_VOUCHER:    so với sub_cart.subtotal của seller đó
  │         - PLATFORM_VOUCHER/FREESHIP: so với total_subtotal
  │     (b) Eval conditions_rule AST (Caffeine L1, <1ms):
  │         - Dùng cart_context làm input
  │         - Gắn eligible=true/false + ineligible_reason
  │     (c) Tính estimated_discount (in-memory):
  │         - PERCENTAGE: min(subtotal * rate, max_discount_amount)
  │         - FIXED_AMOUNT: min(discount_value, subtotal)
  │         - FREE_SHIPPING: min(max_freeship_amount, total_shipping_fee)
  │
  ├─── STEP 5: Gom nhóm thành 3 Slot (Group & Sort)
  │     SHOP_VOUCHER[]: nhóm theo seller_id, sort by estimated_discount DESC
  │     PLATFORM_VOUCHER: filter owner_type=PLATFORM + discount_type≠FREE_SHIPPING
  │                       sort: eligible first, then estimated_discount DESC
  │     FREESHIP[]: filter owner_type=PLATFORM AND discount_type=FREE_SHIPPING
  │                 sort: eligible first → estimated_discount DESC → ineligible last
  │
  └─── STEP 6: Build Response & Return
```

---

## 4. Auto-Save Logic cho UNLIMITED_ALL Voucher

Mã `UNLIMITED_ALL` không yêu cầu "tranh giành" trước, nhưng cần tạo dòng trong `user_coupon_wallets` để phục vụ luồng sử dụng voucher ở Checkout.

### 4.1. Khi nào INSERT vào `user_coupon_wallets`?

> **Phương án được chọn: INSERT tại thời điểm Submit Checkout (Temporal Saga)**

Khi khách bấm Submit Checkout, Order Service khởi chạy Temporal Saga và gọi `ReservePromotionActivity` sang Promotion Service. Với mã có `source = UNLIMITED_ALL`, Promotion Service thực hiện **upsert** vào `user_coupon_wallets` trước khi Reserve ngân sách:

```sql
INSERT INTO user_coupon_wallets (user_id, promotion_id, status, saved_at)
VALUES (:user_id, :promotion_id, 'SAVED', NOW())
ON CONFLICT (user_id, promotion_id) DO NOTHING;
-- Unique Constraint (user_id, promotion_id) bảo vệ khỏi duplicate
```

Sau đó tiếp tục Reserve ngân sách bằng Lua Script 2 như bình thường.

Nếu Reserve thất bại (ví dụ `BUDGET_EXHAUSTED`, timeout, hoặc lỗi downstream), Saga sẽ rollback các bước đã giữ chỗ và trả lỗi về Checkout để hiển thị cho khách hàng chọn lại voucher hoặc thực hiện Preview lại.

> [!NOTE]
> **Tại sao không INSERT ngay khi xem gợi ý (Step 3)?** Nếu INSERT ngay khi API trả gợi ý, mỗi lần khách mở màn hình voucher sẽ tự động lưu hàng chục mã vào ví — gây noise ví, db bloat và khó đối soát audit trail. Chỉ INSERT khi khách **thực sự submit checkout** và Saga đi vào bước Reserve.

### 4.2. Trạng thái vòng đời sau auto-save

```
[Không có trong ví]
        │
  │ -- Submit Checkout → ReservePromotionActivity --
        ▼
   SAVED  (upsert vào user_coupon_wallets)
        │
        │ -- ORDER_PAID --
        ▼
   USED
        │
        │ -- ORDER_CANCELLED / ORDER_REFUNDED --
        ▼
   SAVED  (trả lại — user có thể dùng lần sau)
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
    "owner_type": "SELLER",
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

**Invalidation Events (Consumer của Promotion Service):**

| Event (Kafka) | Hành động |
|:---|:---|
| `COUPON_CLAIMED` (user săn được mã mới) | `DEL wallet:{user_id}:active_vouchers` |
| `ORDER_PAID` (mã chuyển sang USED) | `DEL wallet:{user_id}:active_vouchers` |
| `ORDER_CANCELLED` (mã trả lại SAVED) | `DEL wallet:{user_id}:active_vouchers` |
| `ORDER_REFUNDED` (mã trả lại SAVED) | `DEL wallet:{user_id}:active_vouchers` |
| Mã hết hạn (cron job expire sweep) | `DEL wallet:{user_id}:active_vouchers` |

**Chiến lược Invalidation:** Dùng `DEL` (xóa hẳn) thay vì update, vì thay đổi ví thiếu tần suất đủ để justify complexity của partial update. Cache miss → query DB → populate lại. Latency DB query ví là ~3-5ms (Index Scan trên `user_id, status`), chấp nhận được.

### 5.2. UNLIMITED_ALL Promotions Cache (L1 Caffeine)

Mã `UNLIMITED_ALL` đã được cache sẵn trong Caffeine L1 dưới dạng `promo:meta:{id}` cùng với toàn bộ metadata promotion (xem `redis_caching_design.md` Section 2.1). Promotion Service chỉ cần filter lại `type = UNLIMITED_ALL` từ danh sách đang nạp trên RAM — **không phát sinh thêm I/O nào**.

---

## 6. Performance Profile

| Bước | Latency ước tính |
|:---|:---|
| Redis wallet cache HIT | ~2ms |
| Redis wallet cache MISS → DB query → populate | ~5-8ms |
| Fetch UNLIMITED_ALL từ Caffeine L1 | ~0.01ms |
| Rule Engine eval (AST in-memory, n mã) | ~1-3ms |
| Tính estimated_discount + Sort | ~0.5ms |
| **Tổng (cache HIT path)** | **< 10ms** |
| **Tổng (cache MISS path)** | **< 15ms** |

> Latency mục tiêu: **< 15ms 99th percentile**. Checkout Service gọi API này **song song** với các call khác trong Preview Orchestration, nên nó không nằm trên critical path.

---

## 7. Edge Cases & Xử lý

### 7.1. Mã `is_exclusive = true`

Nếu một mã trong ví có `is_exclusive = true` (không được dùng chung với mã khác), response cần đánh dấu rõ:
```json
{
  "is_exclusive": true,
  "exclusive_note": "Mã này không áp dụng cùng với mã khác"
}
```
Frontend khi user chọn mã exclusive → disable tất cả slot còn lại. Logic validate conflict vẫn được kiểm tra lại ở bước `POST /v1/promotions/calculate-discount` (Promotion Service là source of truth).

### 7.2. Chỉ Platform có Freeship (`discount_type = FREE_SHIPPING`)

Do ràng buộc nghiệp vụ và DB constraint, seller không được tạo voucher freeship.
- Chỉ **1 mã freeship platform** được áp dụng cho toàn đơn (theo stacking rule).
- Nếu có nhiều mã freeship platform, chọn mã `eligible` có `estimated_discount` cao nhất làm gợi ý mặc định.
- Nếu mã tốt nhất chưa đủ điều kiện, hiển thị thêm `amount_needed` để user biết số tiền còn thiếu.

### 7.3. Ví quá lớn (user có hàng trăm mã SAVED)

Áp page-based filter: chỉ trả tối đa **20 mã eligible** + **10 mã ineligible** trong response. Eligible sort trước. Các mã đã USED/EXPIRED được lọc bỏ ngay ở tầng DB query.

### 7.4. UNLIMITED_ALL hết ngân sách

Dù mã `UNLIMITED_ALL` không cần "săn", vẫn có thể hết budget (ví dụ: chiến dịch sinh nhật giới hạn 1 tỷ). API discovery **không check budget** (để tránh thêm 1 lần đọc Redis `promo:{id}:budget` cho mỗi mã). Budget check chỉ xảy ra tại bước **Submit Checkout** trong `ReservePromotionActivity` (Lua Script 2). Nếu hết budget hoặc có lỗi trong Saga, Checkout nhận lỗi và hiển thị lại cho khách để chọn voucher khác hoặc Preview lại.

---

## 8. Tích hợp vào Checkout Flow

Màn hình Checkout sử dụng **2 API Voucher Discovery riêng biệt** tùy theo ngữ cảnh:

| API | Khi nào gọi | Mô tả |
|:---|:---|:---|
| `GET /v1/promotions/voucher-suggestions` | Khách mở tray **"Voucher của Sàn"** | Liệt kê Platform Voucher + Freeship (trong ví + UNLIMITED_ALL) |
| `GET /v1/promotions/shop-voucher-discovery` | Khách mở tray **"Voucher của Shop X"** | Liệt kê toàn bộ voucher hợp lệ của shop đó (cả đã lưu + chưa lưu) |

```
Khách mở màn hình Checkout
        │
        ▼
Checkout Preview (gọi song song: Cart, User, Inventory×N, Promotion, Shipping×N)
        │
        ├─ Khách nhấn "Voucher của Shop X" (mở Shop Voucher tray)
        │       │ -- GET /v1/promotions/shop-voucher-discovery?seller_id=X --
        │       ▼
        │   Promotion Service trả ELIGIBLE[] + INELIGIBLE[] (< 15ms)
        │       │
        │       ├─ Khách chọn mã ĐÃ CÓ trong ví (source=WALLET)
        │       │       → POST /v1/checkout/preview với selected_shop_vouchers[X] = promo_id
        │       │
        │       └─ Khách nhấn "Lưu & Chọn" mã CHƯA CÓ trong ví (source=SHOP_PUBLIC)
        │               → POST /v1/promotions/save-and-apply {seller_id, promotion_id}
        │               → Promotion: upsert user_coupon_wallets (SAVED)
        │               → POST /v1/checkout/preview với selected_shop_vouchers[X] = promo_id
        │
        └─ Khách nhấn "Voucher của Sàn" (mở Platform Voucher tray)
                │ -- GET /v1/promotions/voucher-suggestions --
                ▼
            Promotion Service trả Platform Vouchers + Freeship (< 15ms)
                │
                └─ Khách chọn mã → POST /v1/checkout/preview với IDs mới
```

---

## 9. Shop Voucher Discovery API

### 9.1. Mục đích

API `GET /v1/promotions/shop-voucher-discovery` phục vụ tray **"Voucher của Shop"** trên màn hình Checkout. Khác với Platform Voucher Discovery (chỉ show mã trong ví + UNLIMITED_ALL), Shop Voucher Discovery hiển thị **toàn bộ voucher hiện có của shop đó** — bao gồm cả mã user chưa lưu — để tối đa hóa cơ hội khách hàng tìm được mã tốt nhất.

### 9.2. Endpoint

```
GET /v1/promotions/shop-voucher-discovery?seller_id={seller_id}
Authorization: Bearer {access_token}
```

### 9.3. Request Parameters

```json
{
  "seller_id": 101,
  "cart_context": {
    "sub_cart_subtotal": 350000,
    "item_category_ids": [5, 12],
    "payment_method": "VNPAY"
  }
}
```

> `sub_cart_subtotal` là tổng giá sub-cart của riêng shop này (không phải tổng đơn hàng) — dùng để evaluate `min_purchase_amount` và Rule Engine.

### 9.4. Backend Flow

```
GET /shop-voucher-discovery?seller_id=101
  │
  ├── STEP 1: Fetch Wallet (Filter by seller_id)
  │     Check Redis: wallet:{user_id}:active_vouchers (TTL 5 phút)
  │     → Filter: owner_type=SELLER AND owner_id=101
  │     → Source: WALLET, is_saved=true
  │
  ├── STEP 2: Fetch Shop Public Vouchers (chưa lưu trong ví)
  │     Query DB:
  │       SELECT p.*
  │       FROM promotions p
  │       WHERE p.owner_type = 'SELLER'
  │         AND p.owner_id = 101
  │         AND p.discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT')
  │         AND p.status = 'ACTIVE'
  │         AND p.end_time > NOW()
  │         AND p.id NOT IN (wallet_promotion_ids_from_step1)
  │     → Source: SHOP_PUBLIC, is_saved=false
  │     → Index: (owner_type, owner_id, status) — cần thêm index này
  │
  ├── STEP 3: Evaluate Eligibility (Rule Engine In-Memory)
  │     Với mỗi voucher (từ Step 1 + Step 2):
  │     (a) Check nhanh: sub_cart_subtotal >= min_purchase_amount
  │     (b) Eval conditions_rule AST (Caffeine L1, <1ms)
  │     (c) Tính estimated_discount in-memory
  │     (d) Gán eligible=true/false + ineligible_reason nếu false
  │
  └── STEP 4: Sort & Group
        eligible[]:   sort by estimated_discount DESC (Best Choice)
        ineligible[]: sort by (amount_needed ASC) — gần đủ điều kiện nhất lên đầu
        Return: {eligible[], ineligible[]}
```

### 9.5. Response Structure

```json
{
  "seller_id": 101,
  "seller_name": "Shop Tech Việt",
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
        "end_time": "2026-04-01T00:00:00Z"
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
        "source": "SHOP_PUBLIC",
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
| `EXPIRED` | Voucher hết hạn (hiếm — đã filter ở Step 2, nhưng để phòng race condition) |

### 9.6. Save & Apply Flow (Khi user nhấn "Lưu & Chọn" mã chưa lưu)

```
POST /v1/promotions/save-and-apply
{
  "seller_id": 101,
  "promotion_id": 60
}
```

Promotion Service:
1. Validate: promotion tồn tại, `owner_id = seller_id`, `status = ACTIVE`.
2. Upsert vào `user_coupon_wallets`:
   ```sql
   INSERT INTO user_coupon_wallets (user_id, promotion_id, status, saved_at)
   VALUES (:user_id, :promotion_id, 'SAVED', NOW())
   ON CONFLICT (user_id, promotion_id) DO NOTHING;
   ```
3. Invalidate Redis wallet cache: `DEL wallet:{user_id}:active_vouchers`.
4. Trả `200 OK {saved: true}`.

> [!NOTE]
> Sau khi nhận `200 OK`, Checkout Service gọi ngay `POST /v1/checkout/preview` với `selected_shop_vouchers[seller_id] = promotion_id`. Budget Reserve xảy ra trong Temporal Saga khi Submit — không phải tại đây.

### 9.7. Performance Profile

| Bước | Latency |
|:---|:---|
| Wallet cache HIT + filter | ~2ms |
| DB query Shop Public vouchers | ~3-5ms |
| Rule Engine eval (n mã) | ~1-3ms |
| **Tổng (cache HIT)** | **< 12ms** |
| **Tổng (cache MISS)** | **< 15ms** |

> Rate Limiting: `checkout:ratelimit:voucher_shop:{user_id}` — 20 req/phút (xem `checkout_caching_schema.md`).
