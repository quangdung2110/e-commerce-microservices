# Luồng Hoạt động Checkout Service (API Workflow)

**Liên kết nhanh (Related Documents):**
*   [Architecture Design](checkout_architecture_design.md)
*   [Caching Schema (Redis Session)](checkout_caching_schema.md)
*   [Idempotency & Anti-Tampering](checkout_idempotency.md)
*   [Security Analysis (Anti-Tampering & Session)](checkout_security_analysis.md)

**Cross-Service References:**
*   [Order Workflow](../order/workflows.md#wf-order-004--checkout-submit-saga) — Nhận Submit payload
*   [Promotion Workflows](../promotion/workflows.md) — Budget Reservation
*   [Inventory Warehouse Selection](../inventory/smart_routing_engine.md) — Stock check + Routing

> **Phase 18 (Single Payment Provider - Stripe):** Hệ thống chỉ dùng 1 payment provider duy nhất (Stripe). Checkout **KHÔNG** nhận `payment_method` từ client nữa; `payment_method` đã bỏ khỏi Submit payload và response. Xem [CHANGELOG Phase 18](../_contracts/CHANGELOG.md#phase-18--single-payment-provider-stripe).

---

Tài liệu này mô tả chi tiết chuỗi sự kiện (Sequence Flow) của hai API quan trọng nhất do Checkout Service quản lý: **Checkout Preview** (Khởi tạo bản nháp) và **Checkout Submit** (xác nhận đặt hàng).

## 1. Luồng 1: API Checkout Preview (`POST /v1/checkout/preview`)

Đây là API hạng nặng, được gọi mỗi khi User vào màn hình Checkout, hoặc khi họ đổi địa chỉ, chọn/bỏ Voucher. Thao tác thay đổi số lượng sản phẩm thuộc phạm vi Cart Service và phải hoàn tất trước khi gọi Checkout Preview.

### 1.1. Request Payload

```json
{
  "checkout_token": null, 
  "delivery_address_id": "addr-uuid-123",
  "items": [
    ...
  ],
  ...
}
```

> `checkout_token`: Nếu truyền `null`, Server hiểu là tạo mới từ Giỏ hàng. Nếu đã có Session hiện hành, Server sẽ **REJECT (409)** để chống gian lận nhiều phiên. Nếu truyền `token` cũ, Server cho phép cập nhật thông tin phiên đó.

> `items` được lấy từ Cart Service response (giỏ đã flat-list thiết kế). Trường chọn mã theo voucher dùng map `selected_vouchers = promotion_id`; voucher không áp mã thì **không xuất hiện key** trong map (không serialize `null`).

> Checkout Preview chỉ consume snapshot SKU + quantity đã chuẩn hóa từ Cart. Mọi payload cố gắng mutate `quantity` trực tiếp ở Checkout sẽ bị reject `400 INVALID_CHECKOUT_MUTATION_SCOPE`.

> Promotion contract hiện tại chỉ còn 1 slot `PLATFORM_VOUCHER`. Checkout chỉ gửi lựa chọn voucher PLATFORM; toàn bộ validate xung đột còn lại do Promotion Service xử lý theo stacking rules.

### 1.2. Distributed Lock & Rate Limit

Trước khi thực hiện gom dữ liệu (Sourcing), Preview API đi qua 2 lớp rào chắn:
1. **Rate Limit:** Tối đa 10 requests/phút.
2. **Distributed Lock:** `SETNX checkout:lock:preview:{user_id}` (TTL 5s).
   * Phải xin được Lock mới được đi tiếp. Bị chặn trả về `429 Too Many Requests`.
   * **Tác dụng:** Chặn đứng tình trạng hacker dùng tool mở đồng thời 10 luồng Preview đập xuống hệ thống cùng lúc với 10 mã Voucher khác nhau, gây Race Condition đè vỡ `pricing_hash` trên Redis Session.

### 1.3. Parallel Orchestration Pattern (Chi tiết kỹ thuật)

Preview API gọi các services theo **Dependency Graph** dưới đây. Các node độc lập được gọi song song (Wave 1). Inventory là owner của routing cho mỗi warehouse.

```
Request ─ Fork ─┬─ [A] User Service: GET address list            (Wave 1 — độc lập)
                │
                ├─ [B] Cart Service: HGETALL {cart:user:{id}}    (Wave 1 — độc lập)
                │
                ├─ [E] Product: GetBatchProductSkus (gRPC)        (Wave 1 — độc lập)
                │      (validate is_available + status cho tất cả SKU trong giỏ)
                │      (trả current_price để tính giá realtime)
                │
                └─ [D×N] Per warehouse group (song song N kho):      (Wave 1 — độc lập)
                    └─ Inventory: /routing-options

            Join (A, B, D×N, E)
              → Build cart_context
              → [C] Promotion: /calculate-discount (Wave 2 — validate only, không lock budget)
              → Validate Product Status → Calculation Engine → Sign Token → Response
```

**Thứ tự:**
- **Wave 1:** A + B + E (Product validate) + tất cả nhánh D (mỗi kho) — chạy hoàn toàn song song.
  - [E] sử dụng danh sách `sku_ids` đã chọn từ request payload, nhưng `quantity` của từng SKU luôn lấy từ cart snapshot [B] (không tin `quantity` client override).
  - Nếu bất kỳ SKU nào có `status != ACTIVE` (BANNED, deleted, unavailable) → trả lỗi **422 Unprocessable Entity** kèm danh sách SKU không khả dụng, yêu cầu user xóa khỏi giỏ trước khi checkout.
  - `current_price` từ [E] được dùng trong Calculation Engine (Waterfall), không dùng `unit_price_at_add` từ Cart cho giá thực tế checkout.
- **Wave 2:** Join kết quả Wave 1 → gọi [C] Promotion `/calculate-discount` với `subtotals` mới nhất.
- **Wave 3:** Waterfall price calculation → sinh session token.

> **[DEEP DIVE] Tại sao luồng Sourcing phức tạp này lại cực nhanh (< 150ms)?**
> 1. **Không còn bảng giá shipping:** Phase 14 đã loại bỏ Shipping Service. Inventory chỉ chọn kho có stock đủ.
> 2. **Downstream Response Cache (per-warehouse):** Routing response của Inventory được cache theo `items_hash + address_hash` (TTL 20 phút). Đổi voucher không trigger gọi lại Inventory.
> 3. **Optimization Decision Tree:** Tuỳ theo loại thay đổi, chỉ gọi lại services cần thiết (xem 1.5 bên dưới).

> **[DEEP DIVE] Soft vs Hard Allocation**
> Checkout Preview dùng **Soft Allocation** (Sourcing mô phỏng, không khóa kho) — tránh giam hàng ảo do spam request. Hard Allocation (lock inventory) chỉ xảy ra trong Temporal Saga sau Submit.
>
> **[DEEP DIVE] Promotion không lock budget ở Preview**
> Nếu lock budget tại Preview, kẻ xấu có thể tự động mở hàng loạt checkout session, áp Voucher Flash Sale và bỏ trống, làm cạn kiệt ngân sách. Lock budget chỉ xảy ra trong Temporal Saga (`ReservePromotionActivity`) ngay sau Submit. Khi reserve thất bại, Checkout không được tự trừ số học cục bộ mà phải gọi lại Promotion evaluate để trả `409 PROMOTION_RECALCULATION_NEEDED` với danh sách mã bị loại và tổng tiền đề xuất mới.

### 1.4. Tính toán tập trung (Calculation Engine) — Waterfall Price

Sau khi join tất cả results, Checkout tính giá theo trình tự Waterfall:

```
1. cart_subtotal = Σ (quantity × unit_price)
2. total_product_price = cart_subtotal − voucher_discount
3. final_amount = total_product_price
```

### 1.5. Optimization Decision Tree (Khi Preview được gọi lại)

Tuỳ theo loại thay đổi, Checkout Service chỉ gọi lại đúng services cần thiết:

```
Preview Request (đợt sau)
      │
    ├── [Đổi Voucher PLATFORM]
    │     → Chỉ gọi [C] Promotion /calculate-discount
    │     → Cache A (address), B (cart), D×N (routing) vẫn dùng
    │     → Latency target: < 20ms
    │
    ├── [Đổi địa chỉ giao hàng]
    │     → Gọi [A] User Service (validate), invalidate cache D×N
    │     → Gọi lại Inventory D×N cho tất cả warehouses
    │     → Latency target: < 120ms
    │
    └── [Đổi items (qty/add/remove)]
          → Không xử lý mutate trong Checkout Preview
          → Frontend phải gọi Cart API trước, rồi gọi lại Preview với cart snapshot mới
          → Checkout đọc lại [B] Cart và gọi [C]/[D×N] theo dữ liệu Cart mới
```

### 1.5.1. Policy Chọn Voucher theo Tray (Contract UI/BE)

Để đồng bộ UX và tránh side-effect khó kiểm soát, Checkout dùng policy cố định:

1. **Tray Voucher Sàn (PLATFORM_VOUCHER)**
   * Hiển thị **mã đã lưu trong ví** + **mã public của sàn** chưa lưu.
   * Cho phép thao tác **"Lưu & Chọn"** (`POST /v1/promotions/save-and-apply`) ngay tại tray.
   * Sau khi Save & Apply thành công, Frontend gọi lại `POST /v1/checkout/preview` để nhận token mới và tổng tiền mới.
   * Promotion Discovery có thể downgrade mã sang `BUDGET_EXHAUSTED` bằng budget guard read-time (Option A) trước khi trả gợi ý mặc định.

2. **Submit Path**
   * `POST /v1/checkout/submit` là luồng commit nghiệp vụ (Reserve/Confirm qua Saga), **không được auto-save voucher** vào ví user.
   * Mọi ghi mới vào ví voucher phải đến từ explicit action ngoài Submit (ví dụ: Save & Apply voucher, claim campaign, worker cấp mã).


### 1.6. Response Payload

```json
{
  "checkout_token": "uuid-v4-xxx",
  "delivery_address": {
    "address_id": "addr-uuid-123",
    "full_name": "Nguyen Van A",
    "phone": "0901234567",
    "address": "123 Đường ABC, Quận 1, HCM"
  },
  "items": [
    {
      "sku_id": "sku-101",
      "product_name": "iPhone 15",
      "variant_info": "128GB - Đen",
      "image_url": "...",
      "quantity": 2,
      "unit_price": 22000000,
      "subtotal": 44000000,
      "warehouse_id": 5
    }
  ],
  "subtotal": 44000000,
  "voucher_applied": {
    "promotion_id": null,
    "discount_amount": 0
  },
  "warehouse_assignments": [
    {
      "warehouse_id": 5
    }
  ],
  "price_breakdown": {
    "total_product_amount": 44000000,
    "total_voucher_discount": 0,
    "final_amount": 44000000
  }
}
```

### 1.7. Timeout Policy

| Scope | Timeout | Fallback |
|:---|:---|:---|
| User Service (address) | **500ms** | Địa chỉ cũ từ session (nếu có) |
| Cart Service (Redis) | **50ms** | Lỗi Preview (Bắt buộc phải nhanh do gọi In-memory) |
| Inventory /routing-options | **3s** | Flat-rate config fallback |
| Promotion /calculate-discount | **2s** | Không áp voucher, báo user |
| **Tổng Preview API** | **5s** | Fallback từng phần, không block toàn bộ |

---

## 2. Luồng 2: API Checkout Submit (`POST /v1/checkout/submit`)

Khách hàng thấy giá tiền hợp lý, nhấn nút "Xác Nhận Đặt Hàng" (Thanh toán).

### Flow Thực thi (Tránh Deadlock & Tối ưu an toàn):
1. **Tiếp nhận & Khóa (Distributed Lock):**
   * Frontend gửi `[CheckoutToken, user_ack_degraded_pricing]` (Phase 18: không còn `payment_method`).
   ```json
   {
     "checkout_token": "uuid-v4-xxx",
     "user_ack_degraded_pricing": false
   }
   ```
   > **Phase 18:** `payment_method` đã được loại bỏ khỏi Submit payload. Hệ thống chỉ dùng Stripe, Checkout không cần truyền provider identifier.
   * Backend xin quyền **Distributed Lock** (`checkout:lock:submit:{user_id}`). Nếu fail trả `423 Locked`.

2. **Kiểm tra sơ bộ & Cờ Degraded:**
    * Đọc session từ Redis. Nếu `status == SUBMITTED` (Token đã dùng), lập tức rẽ nhánh sang luồng **Client Retry (Bước 7)** để hồi phục hoặc báo lỗi.
    * Kiểm tra cờ `user_ack_degraded_pricing == true` nếu session cảnh báo lỗi `DEGRADED_PROMOTION`/`DEGRADED_STOCK`. Nếu thiếu cờ, nhả Lock và từ chối `409 DEGRADED_PRICING_NOT_CONFIRMED`.

3. **Lightweight Price Staleness Check (Xem Mục 4):**
    * Batch fetch giá gốc/khuyến mãi từ Product Cache. Validate version Promotion. Tính lại Hash.
    * Nếu **Khác Hash**: Nhả Lock, trả `409 PRICE_CHANGED`. (Session vẫn an toàn ở trạng thái `PREVIEW_READY`, client phải gọi lại Preview).
    * Nếu **Khớp Hash**: Tiếp tục.

4. **Khóa Session vĩnh viễn (Lua Script Check-and-Mark):**
    * Chạy Lua Script khóa Token (đổi status `PREVIEW_READY` -> `SUBMITTED`).
    * *Từ thời điểm này*, Token coi như đã "chết", session chỉ được dùng 1 lần, không thể rollback.

5. **Lưu Checkout Payload vào Redis (Retry Safety):**
  * Lưu checkout payload vào Redis key riêng: `checkout:payload:{user_id}:{token}` TTL 20 phút. Payload này dùng để phục hồi (Recovery) nếu gọi Order bị đứt kết nối mạng.

6. **Khởi tạo Request Đặt Hàng (Gọi Order Service):**
    * Bắn lệnh Internal gRPC sang Order Service (Truyền toàn bộ Payload đã khóa, bao gồm `route_plan_id` được lưu trong session từ Preview — client không cần gửi). Kích hoạt Temporal Workflow tại Order Service theo thứ tự chuẩn: `CreateOrder -> ReserveInventory -> ReservePromotion -> InitiatePayment -> WaitPaymentSignal`.
    * Submit path giữ nguyên nguyên tắc **read/validate/reserve**; không có thao tác auto-save voucher sàn trong bước này.
  * Nếu pre-payment timeout/cancel, nhánh `RollbackPromotionActivity` chỉ nhả reservation runtime (`reserved_*`), không đổi trạng thái ví voucher trong DB.
    * **Nếu Order Service nhận:** Trả về `[order_id, client_secret, publishable_key]` (Stripe).
      * Dọn dẹp Lock, dọn dẹp Payload. Cache lại Response (Idempotent response cache). Checkout trả `client_secret` + `publishable_key` về cho Frontend (Frontend dùng Stripe Elements / Stripe Checkout SDK).
    * **Nếu Order Service trả lỗi nghiệp vụ từ ReservePromotion (all-or-nothing fail):** Checkout xử lý theo 4 bước bắt buộc:
      * (a) Nhận `failed_promotion_id` + `failed_reason` + `reserve_mode` từ lỗi có cấu trúc.
      * (b) Loại `failed_promotion_id` khỏi tập mã đã chọn và gọi lại Promotion `/calculate-discount` với cart context hiện tại + tập mã còn lại.
      * (c) Dựng `dropped_promotions[]` (chỉ gồm mã bị loại) và `proposal_checkout_data` từ kết quả re-evaluation.
      * (d) Trả `409 PROMOTION_RECALCULATION_NEEDED` + `action=RECALL_PREVIEW`.
      * Checkout cache lại phản hồi 409 này theo token để retry cùng token trả kết quả nhất quán.
      * Session giữ nguyên trạng thái `SUBMITTED` (token đã dùng), không rollback token.
    * **Nếu Order Service rớt mạng/Timeout:** Retry nội bộ tối đa 3 lần.
      * Nếu 3 lần rớt cả 3: Trả về **503 Service Unavailable** (KHÔNG rollback status Token).

7. **Client Retry sau khi nhận 503 (Failed Submit Recovery):**
    * Khi nhận 503, App tự động retry `POST /checkout/submit`.
    * Bước 2 phát hiện `status == SUBMITTED`.
    * Check `checkout:response:{user_id}:{token}` -> Chưa có (vì bận rớt mạng).
    * Check `checkout:payload:{user_id}:{token}` -> Vẫn còn (chưa hết TTL 20 phút).
    * **Tiến hành:** Lấy Payload này và gọi sang Order Service 1 lần nữa (Retry Saga Trigger). Order Service tự xử lý Idempotency. Nếu Order Service thành công thì trả về. Nếu Payload hết hạn thì trả `410 Gone`.

8. **Nhánh Reserve fail (Business Conflict Recovery):**
    * Nếu client nhận `409 PROMOTION_RECALCULATION_NEEDED`, frontend hiển thị popup minh bạch từ `dropped_promotions[]`.
    * Popup phải render đủ toàn bộ lý do trong `dropped_promotions[]` theo đúng thứ tự trả về để user hiểu hệ quả dây chuyền (ví dụ hết budget kéo theo không đủ điều kiện).
    * Khi user bấm "Đồng ý đặt hàng", frontend **bắt buộc** gọi lại `POST /v1/checkout/preview` với `surviving_promotions[]` để lấy `checkout_token` mới.
    * Chỉ sau khi có token mới, frontend mới được gọi `POST /v1/checkout/submit` lần kế tiếp.

Ví dụ payload 409:
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
    },
    {
      "promotion_id": 10,
      "reason": "CONDITIONS_NOT_MET",
      "user_message": "Đơn hàng không còn đủ điều kiện áp dụng mã này do thay đổi tổng tiền."
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

> `dropped_promotions[]` chỉ chứa các mã bị loại. Mã còn sống không nằm trong mảng này.

## 3. Sơ Đồ Xử lý Cứu Xét (Fallback Flow)

Do API Preview phải gọi sang nhiều dịch vụ downstream, mạng nội bộ (Internal Network) luôn có tỉ lệ gặp sự cố (Network Partition). Checkout Service áp dụng kỹ thuật **Circuit Breaker / Fallback**:
*   *Nếu Promotion Service timeout:* Trả trạng thái `DEGRADED_PROMOTION` và bắt buộc UI hiển thị hộp xác nhận "Thanh toán không áp mã giảm giá tạm thời" trước khi cho Submit.
* *Nếu Inventory routing timeout:* Trả trạng thái `DEGRADED_STOCK` và bắt buộc UI hiển thị hộp xác nhận "Thanh toán với giá tạm thời" trước khi cho Submit.
* Mọi Submit ở chế độ degraded phải mang cờ `user_ack_degraded_pricing = true`. Thiếu cờ này, backend trả `409 DEGRADED_PRICING_NOT_CONFIRMED`.

---

## 4. Lightweight Price Staleness Check (Submit Path)

Checkout Preview tạo `pricing_summary` snapshot lưu Redis TTL 20 phút. Submit dùng `pricing_summary` từ Redis — nhưng khoảng thời gian 20 phút đủ để: giá markdown hết hạn, admin thay đổi giá, coupon hết hạn. Để phát hiện giá đã thay đổi trước khi tạo Order, Submit path thực hiện kiểm tra nhẹ sau:

> [!NOTE]
> Dù Discovery đã có budget guard read-time (Option A), exhausted budget vẫn có thể xảy ra trong race ngắn giữa Preview và Submit. Vì vậy Submit bắt buộc giữ Reserve làm nguồn quyết định cuối cùng.

### 4.1. Thời điểm thực hiện

Sau khi Frontend xin cấp Lock thành công (bước 1), **trước khi** chạy Lua Script khóa Token vĩnh viễn (bước 4). Lúc này session vẫn linh hoạt ở trạng thái `PREVIEW_READY`.

### 4.2. Luồng kiểm tra

1. **Batch fetch giá hiện tại** của các SKU trong session:
  - Gọi Product Service gRPC `GetBatchProductSkus` để lấy `original_price` + `promotional_price` + `best_price` hiện tại.
  - Product Service tự xử lý Redis/cache miss nội bộ và trả dữ liệu chuẩn hợp đồng.
2. **Batch fetch phiên bản tính giá hiện tại**:
  - Promotion: lấy `promotion_campaign_revision` hiện hành (hard check); `promotion_budget_version` chỉ dùng telemetry/diagnostic.
    `promotion_campaign_revision` là concurrency/cache fence, không phải mutable definition
    version. Published definition thay đổi bằng promotion/campaign identity mới nên selection
    cũ phải được evaluate lại, không reinterpret theo cùng ID.
3. **Tính lại hash** = SHA-256(sorted sku prices + discount + promotion_campaign_revision).
4. **So sánh** với `pricing_hash` trong session (xem [Caching Schema](checkout_caching_schema.md#11-cấu-trúc-key--data)):
   - **Khớp** → Đi tiếp sang Bước 4 (Lua Script khóa token).
   - **Không khớp** → Gỡ Distributed Lock, trả lỗi **409 Conflict** với body:
     ```json
     {
       "error": "PRICE_CHANGED",
       "message": "Giá đã thay đổi kể từ lần xem trước. Vui lòng review lại.",
       "action": "RECALL_PREVIEW"
     }
     ```
   - **Không rollback status session** vì token thực tế vẫn là `PREVIEW_READY`, việc từ chối submit đơn giản là không chạy Lua Script nữa. (Note: Ở bản cập nhật thiết kế mới, vì Price check đứng trước Lua Script nên session không hề bị hỏng. Client có gọi lại Preview để xin báo giá mới).

### 4.3. Chi phí performance

| Thao tác | Latency |
|:---|:---|
| Product/Promotion version lookup (cache hit) | ~2-4ms |
| Hash calculation | ~0.2ms |
| **Tổng overhead** | **< 8ms** — chấp nhận được cho Submit path |

> **Lưu ý:** Submit path **bắt buộc** check lại phiên bản Promotion, không chỉ giá sản phẩm. Mục tiêu là tránh lệch tiền cuối cùng giữa Preview và Commit.

> **[DEEP DIVE] Triết lý đánh đổi của Price Staleness Check (Phát hiện "hàng thiu")**
> Mặc dù Snapshot báo giá nằm trong Redis 20 phút, nếu platform sửa giá gốc trên hệ thống thì Snapshot trở thành "hàng thiu". Tuy nhiên, hệ thống **KHÔNG** soi lại toàn bộ hóa đơn từ đầu (việc quét Inventory & Shipping lại rất tốn kém).
> Thay vì vậy, Checkout tung 1 lệnh siêu nhẹ (Batch Fetch) qua Product gRPC để cân đo lại duy nhất giá hàng hóa. Nếu giá đã bị sửa cao lên, Hash sẽ bị "gãy mạch" (`X != Y`) và hệ thống trả `409 Conflict - PRICE_CHANGED` để App tạo lại phiên checkout mới với giá hiện tại. Thao tác siêu tốc < 8ms này cứu vớt bàn thua tài chính trông thấy cho nền tảng!
