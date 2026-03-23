# Luồng Hoạt động Checkout Service (API Workflow)

**Liên kết nhanh (Related Documents):**
*   [Architecture Design](checkout_architecture_design.md)
*   [Caching Schema (Redis Session)](checkout_caching_schema.md)
*   [Idempotency & Anti-Tampering](checkout_idempotency.md)
*   [Security Analysis (Anti-Tampering & Session)](checkout_security_analysis.md)

**Cross-Service References:**
*   [Order Workflow](../order/order_workflow.md#4-workflow-nhận-checkout-submit-full-saga-flow) — Nhận Submit payload
*   [Promotion Workflow](../promotion/promotion_workflow.md#4-luồng-thanh-toán-saga-checkout-flow) — Budget Reservation
*   [Inventory Smart Routing](../inventory/smart_routing_engine.md) — Stock check + Routing Options
*   [Shipping Workflow](../shipping/shipping_workflow.md#1-api-calculate-shipping-fee) — Fee calculation

---

Tài liệu này mô tả chi tiết chuỗi sự kiện (Sequence Flow) của hai API quan trọng nhất do Checkout Service quản lý: **Checkout Preview** (Khởi tạo bản nháp)## 1. Luồng 1: API Checkout Preview (`POST /v1/checkout/preview`)

Đây là API hạng nặng, được gọi mỗi khi User vào màn hình Checkout, hoặc khi họ đổi địa chỉ, chọn/bỏ Voucher, thay đổi số lượng sản phẩm.

### 1.1. Request Payload

```json
{
  "checkout_token": null, 
  "shipping_address_id": "addr-uuid-123",
  "shop_groups": [
    ...
  ],
  ...
}
```

> `checkout_token`: Nếu truyền `null`, Server hiểu là tạo mới từ Giỏ hàng. Nếu đã có Session hiện hành, Server sẽ **REJECT (409)** để chống gian lận nhiều phiên. Nếu truyền `token` cũ, Server cho phép cập nhật thông tin phiên đó.

> `shop_groups` được bò ra từ Cart Service response (giỏ đã group-by-shop thiết kế). `selected_shop_voucher_id` là nullable — null = không áp voucher cho shop này.

### 1.2. Parallel Orchestration Pattern (Chi tiết kỹ thuật)

Preview API gọi các services theo **Dependency Graph** dưới đây. Các node độc lập được gọi song song (Wave 1). Shipping phụ thuộc Inventory — gọi tuần tự trong phạm vi shop group đó (Wave 2).

```
Request ─ Fork ─┬─ [A] User Service: GET address list           (Wave 1 — độc lập)
                │
                ├─ [B] Cart Service: HGETALL cart:user:{id}     (Wave 1 — độc lập)
                │
                ├─ [C] Promotion: /calculate-discount            (Wave 1 — độc lập)
                │      (validate only, không lock budget)
                │
                └─ [D×N] Per shop group (song song N shop):     (Wave 1 — độc lập)
                          └─ Inventory: /routing-options            (Wave 2 — per shop)
                                  └─ [Sequential] Shipping:          (Wave 2 — per shop)
                                          /calculate-batch

            Join All (A, B, C, D×N) → Calculation Engine → Sign Token → Response
```

**Thứ tự:**
- **Wave 1:** A + B + C + tất cả nhánh D (mỗi shop) — chạy hoàn toàn song song.
- **Wave 2:** Shipping của mỗi shop chạy tuần tự sau Inventory *trong shop đó*, nhưng các shop vẫn song song với nhau.
- **Wave 3:** Join toàn bộ → Waterfall price calculation → sinh session token.

> **[DEEP DIVE] Tại sao luồng Sourcing phức tạp này lại cực nhanh (< 150ms)?**
> 1. **Bảng giá nội bộ (Internal Rate Cards):** Shipping Service không gọi API 3PL ở bước Preview, chỉ tra cứu bảng giá cấu hình trên RAM/Redis (< 20ms).
> 2. **Downstream Response Cache (per-shop):** Routing + Shipping của từng shop được cache riêng theo `seller_id + items_hash + address_hash` (TTL 5-10 phút). Đổi voucher không trigger gọi lại Inventory/Shipping.
> 3. **Optimization Decision Tree:** Tuỳ theo loại thay đổi, chỉ gọi lại services cần thiết (xem 1.4 bên dưới).
> 4. **Flash Sale Fast-Lane:** Sub-cart chứa Hot SKU bỏ qua Smart Routing hoàn toàn, dùng flat-rate từ config.

> **[DEEP DIVE] Soft vs Hard Allocation**
> Checkout Preview dùng **Soft Allocation** (Sourcing mô phỏng, không khóa kho) — tránh giam hàng ảo do spam request. Hard Allocation (lock inventory) chỉ xảy ra trong Temporal Saga sau Submit.
>
> **[DEEP DIVE] Promotion không lock budget ở Preview**
> Nếu lock budget tại Preview, kẻ xấu có thể tự động mở hàng loạt checkout session, áp Voucher Flash Sale và bỏ trống, làm cạn kiệt ngân sách. Lock budget chỉ xảy ra trong `ReservePromotionActivity` ngay sau Submit.

### 1.3. Calculation Engine — Waterfall Price

Sau khi join tất cả results, Checkout tính giá theo trình tự Waterfall:

```
1. sub_cart_subtotal[seller] = Σ (quantity × unit_price) cho từng shop
2. subtotal_after_shop[seller] = sub_cart_subtotal[seller] − shop_discount[seller]
3. total_after_shops = Σ subtotal_after_shop[seller]
4. total_product_price = total_after_shops − platform_discount
5. total_shipping_fee = Σ shipping_fee[seller]
6. final_shipping_fee = total_shipping_fee − freeship_discount
7. final_amount = total_product_price + final_shipping_fee
```

### 1.4. Optimization Decision Tree (Khi Preview được gọi lại)

Tuỳ theo loại thay đổi, Checkout Service chỉ gọi lại đúng services cần thiết:

```
Preview Request (đợt sau)
      │
      ├── [Chỉ đổi Voucher shop/platform/freeship]
      │     → Chỉ gọi [C] Promotion /calculate-discount
      │     → Cache A (address), B (cart), D×N (routing+shipping) vẫn dùng
      │     → Latency target: < 20ms
      │
      ├── [Đổi địa chỉ giao hàng]
      │     → Gọi [A] User Service (validate), invalidate Shipping cache D×N
      │     → Gọi lại Inventory D×N → Shipping D×N cho tất cả shops
      │     → Routing cache C[] (items_hash không đổi) vẫn dùng
      │     → Latency target: < 100ms
      │
      └── [Đổi items (qty/add/remove)]
            → Gọi Cart [B], invalidate C[] + D[]
            → Gọi lại Inventory D×N → Shipping D×N cho shop bị đổi
            → Gọi lại Promotion [C]
            → Latency target: < 150ms
```

### 1.5. Flash Sale — Hot SKU Bypass

```go
for _, shopGroup := range req.ShopGroups {
    hasHotSku := inventoryClient.CheckHotSkuFlag(shopGroup.SkuIds) // L1 Caffeine hit
    if hasHotSku {
        // Fast-Lane: không cần routing, flat-rate ship từ config
        shippingResult = config.FlatRateShipping[shopGroup.SellerId]
    } else {
        // Smart routing path với downstream cache
        routingResult = getRoutingWithCache(shopGroup, addressHash)
        shippingResult = getShippingWithCache(routingResult, req.ShippingAddressId)
    }
}
```

### 1.6. Response Payload

```json
{
  "checkout_token": "uuid-v4-xxx",
  "shipping_address": {
    "address_id": "addr-uuid-123",
    "full_name": "Nguyen Van A",
    "phone": "0901234567",
    "address": "123 Đường ABC, Quận 1, HCM"
  },
  "shop_groups": [
    {
      "seller_id": "seller-001",
      "seller_name": "Shop Tech Việt",
      "items": [
        {
          "sku_id": "sku-101",
          "product_name": "iPhone 15",
          "variant_info": "128GB - Đen",
          "image_url": "...",
          "quantity": 2,
          "unit_price": 22000000,
          "subtotal": 44000000
        }
      ],
      "subtotal": 44000000,
      "shop_voucher_applied": {
        "promotion_id": null,
        "discount_amount": 0
      },
      "subtotal_after_shop_voucher": 44000000,
      "shipping": {
        "total_fee": 35000,
        "estimated_delivery_date": "2026-03-15",
        "parcels": [
          {
            "warehouse_id": 5,
            "provider": "GHN",
            "service": "STANDARD",
            "fee": 35000,
            "estimated_delivery": "2026-03-15",
            "split_note": null
          }
        ]
      }
    }
  ],
  "platform_voucher_applied": {
    "promotion_id": null,
    "discount_amount": 0
  },
  "freeship_voucher_applied": {
    "promotion_id": null,
    "shipping_discount": 0
  },
  "price_breakdown": {
    "total_product_amount": 44000000,
    "total_shop_discount": 0,
    "total_after_shop_vouchers": 44000000,
    "platform_discount": 0,
    "total_after_platform_voucher": 44000000,
    "total_shipping_fee": 35000,
    "shipping_discount": 0,
    "final_shipping_fee": 35000,
    "final_amount": 44035000
  },
  "available_payment_methods": ["VNPAY", "MOMO", "ZALOPAY"]
}
```

### 1.7. Timeout Policy

| Scope | Timeout | Fallback |
|:---|:---|:---|
| User Service (address) | **500ms** | Địa chỉ cũ từ session (nếu có) |
| Cart Service | **1s** | HGETALL Redis |
| Inventory /routing-options (per shop) | **3s** | Flat-rate config fallback |
| Promotion /calculate-discount | **2s** | Không áp voucher, báo user |
| **Tổng Preview API** | **5s** | Fallback từng phần, không block toàn bộ |

---

## 2. Luồng 2: API Checkout Submit (`POST /v1/checkout/submit`)

Khách hàng thấy giá tiền hợp lý, nhấn nút "Xác Nhận Đặt Hàng" (Thanh toán).

### Flow Thực thi:
1. **Tiếp nhận:** Frontend chỉ cần gửi `[CheckoutToken, payment_method]` (Không cần gửi lại danh sách sản phẩm rườm rà vì nó có thể bị Hack/Sửa đổi).
   ```json
   {
     "checkout_token": "uuid-v4-xxx",
     "payment_method": "VNPAY"
   }
   ```
   > **`payment_method` chỉ chấp nhận:** `VNPAY` | `MOMO` | `ZALOPAY`. Hệ thống đã chuyển sang mô hình **Online Payment Only** — COD không được hỗ trợ.

2. **Xác thực Token (Idempotency Check):**
    * Checkout giải mã (hoặc tra Redis) chữ ký `CheckoutToken`.
    * Kiểm tra nếu Token này đã được Submit rồi -> Trả về lỗi 409 Conflict (Ngăn chặn tạo đơn đúp do lag).
3. **Lưu Checkout Payload vào Redis (Retry Safety):**
    * Sau khi Lua Script mark session `SUBMITTED` (atomic one-time-use), lưu checkout payload vào Redis key riêng:
      ```
      Key:   checkout:payload:{user_id}:{token}
      Value: {validated order creation payload từ Redis session}
      TTL:   5 phút
      ```
    * Payload này dùng để phục hồi khi lần gọi Order Service đầu tiên thất bại — xem [Failed Submit Recovery](checkout_idempotency.md#51-failed-submit-recovery).
4. **Khởi tạo Request Đặt Hàng Nội Bộ (có Auto-Retry):**
    * Checkout tái tạo lại Object dữ liệu từ Token (Nó đã có sẵn: Danh sách Sub-orders, Giá gốc, Hình ảnh SP, Tiền giảm giá).
    * Bắn một lệnh Internal gRPC `CreateCommand` với gói dữ liệu đầy đủ này sang **Order Service**.
    * **Nếu Order Service trả lỗi (timeout/down):** Retry tối đa 3 lần với exponential backoff: 500ms, 1s, 2s.
    * **Nếu sau 3 retry vẫn fail:**
      - Trả client lỗi **503 Service Unavailable** kèm message: `"Đang xử lý đơn hàng, vui lòng kiểm tra lại sau 1 phút"`
      - **KHÔNG rollback** session status về `PREVIEW_READY` (vì payload đã "đóng dấu", rollback có thể gây duplicate order)
5. **Client Retry sau khi nhận 503 (Failed Submit Recovery):**
    * Client gửi lại `POST /checkout/submit {token, payment_method}`
    * Lua Script trả `-2` (ALREADY_SUBMITTED)
    * Backend check `checkout:payload:{user_id}:{token}` còn tồn tại?
      - **Nếu CÓ** → Retry gọi Order Service lần nữa (dùng payload từ Redis)
      - **Nếu KHÔNG** (TTL hết) → Trả **410 Gone**, buộc user gọi Preview lại
6. **Bàn giao cho Order Service (Triggers Temporal):**
    * Tới đây, trách nhiệm của Checkout kết thúc. Order Service nhận được Payload siêu cấu trúc từ Checkout. 
    * Order Service bắt đầu kích hoạt **Temporal Workflow** (Reserve Inventory -> Apply Promo -> Insert DB -> Wait for Payment).
    * Order Service phản hồi nhanh bằng `[order_id, payment_url]`.
    * **Submit thành công** → `DEL checkout:payload:{user_id}:{token}` (không cần giữ)
7. **Trả về Client:** Checkout forward `order_id` và `payment_url` (Của VNPay/Momo) cho Frontend ứng dụng để bật Popup thanh toán cho khách.

> **Tại sao không rollback session khi Order Service fail?** Session đã mark `SUBMITTED` — rollback về `PREVIEW_READY` có thể dẫn đến: (1) Order Service thực ra đã nhận request nhưng response bị timeout → duplicate order nếu user gọi Preview + Submit lại. (2) Dùng `checkout:payload:{...}` + idempotency_key = checkout_token tại Order Service để chống duplicate an toàn hơn.

---

## 3. Sơ Đồ Xử lý Cứu Xét (Fallback Flow)

Do API Preview phải gọi sang 4 dịch vụ khác nhau, mạng nội bộ (Internal Network) luôn có tỉ lệ gặp sự cố (Network Partition). Checkout Service áp dụng kỹ thuật **Circuit Breaker / Fallback**:
*   *Nếu Promotion Service timeout:* Trả trạng thái `DEGRADED_PROMOTION` và bắt buộc UI hiển thị hộp xác nhận "Thanh toán không áp mã giảm giá tạm thời" trước khi cho Submit.
*   *Nếu Shipping Service timeout:* Trả trạng thái `DEGRADED_SHIPPING` kèm phí fallback cấu hình (ví dụ nội thành 20k, ngoại thành 35k) và bắt buộc UI xác nhận rõ với user trước khi Submit.
*   Mọi Submit ở chế độ degraded phải mang cờ `user_ack_degraded_pricing = true`. Thiếu cờ này, backend trả `409 DEGRADED_PRICING_NOT_CONFIRMED`.

---

## 4. Lightweight Price Staleness Check (Submit Path)

Checkout Preview tạo `pricing_summary` snapshot lưu Redis TTL 20 phút. Submit dùng `pricing_summary` từ Redis — nhưng khoảng thời gian 20 phút đủ để: giá markdown hết hạn, seller thay đổi giá, phí ship thay đổi, coupon hết hạn. Để phát hiện giá đã thay đổi trước khi tạo Order, Submit path thực hiện kiểm tra nhẹ sau:

### 4.1. Thời điểm thực hiện

Sau khi Lua Script xác nhận token valid (step 2), **trước khi** lưu payload và gọi Order Service (step 3-4).

### 4.2. Luồng kiểm tra

1. **Batch fetch giá hiện tại** của các SKU trong session:
   - Đọc Redis cache `product:sku:{id}` lấy `original_price` + `promotional_price` hiện tại.
   - Nếu cache miss → gọi Product Service API (hiếm khi xảy ra).
2. **Batch fetch phiên bản tính giá hiện tại**:
  - Shipping: lấy `shipping_rate_version` hoặc `rate_card_updated_at` từ Shipping Service/cache.
  - Promotion: lấy `promotion_rule_version` hoặc `budget_version` từ Promotion Service/cache.
3. **Tính lại hash** = SHA-256(sorted sku prices + shipping + discount + shipping_version + promotion_version).
4. **So sánh** với `pricing_hash` trong session (xem [Caching Schema](checkout_caching_schema.md#11-cấu-trúc-key--data)):
   - **Khớp** → Proceed bình thường → lưu payload → gọi Order Service.
   - **Không khớp** → Trả lỗi **409 Conflict** với body:
     ```json
     {
       "error": "PRICE_CHANGED",
       "message": "Giá đã thay đổi kể từ lần xem trước. Vui lòng review lại.",
       "action": "RECALL_PREVIEW"
     }
     ```
   - **Không rollback status session**. Session giữ trạng thái `SUBMITTED`; client phải gọi luồng tạo phiên checkout mới để lấy báo giá hợp lệ.

### 4.3. Chi phí performance

| Thao tác | Latency |
|:---|:---|
| Product/Shipping/Promotion version lookup (cache hit) | ~2-4ms |
| Hash calculation | ~0.2ms |
| **Tổng overhead** | **< 8ms** — chấp nhận được cho Submit path |

> **Lưu ý:** Submit path **bắt buộc** check lại phiên bản Shipping/Promotion (hoặc hash tương đương), không chỉ giá sản phẩm. Mục tiêu là tránh lệch tiền cuối cùng giữa Preview và Commit.

> **[DEEP DIVE] Triết lý đánh đổi của Price Staleness Check (Phát hiện "hàng thiu")**
> Mặc dù Snapshot báo giá nằm trong Redis 20 phút, nếu Shop sửa giá gốc trên hệ thống thì Snapshot trở thành "hàng thiu". Tuy nhiên, hệ thống **KHÔNG** soi lại toàn bộ hóa đơn từ đầu (việc quét Inventory & Shipping lại rất tốn kém).
> Thay vì vậy, Checkout tung 1 lệnh siêu nhẹ (Batch Fetch) sang Product Cache để cân đo lại duy nhất giá hàng hóa. Nếu giá đã bị sửa cao lên, Hash sẽ bị "gãy mạch" (`X != Y`) và hệ thống trả `409 Conflict - PRICE_CHANGED` để App tạo lại phiên checkout mới với giá hiện tại. Thao tác siêu tốc < 5ms này cứu vớt bàn thua tài chính trông thấy cho nền tảng!
