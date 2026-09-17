---
doc_id: checkout-interfaces
service: checkout
scope: target
status: proposed
owners:
  - Checkout Service team
evidence:
  - contracts/http/promotion/v1/openapi.yaml
  - contracts/http/user/v1/openapi.yaml
  - contracts/http/inventory/workflow/v1/openapi.yaml
  - contracts/grpc/ecommerce/product/v1/product_service.proto
verified_against_commit: null
---

# Checkout Service — Giao diện tích hợp

> Checkout chưa có OpenAPI riêng; các payload Preview/Submit dưới đây là ví dụ
> bảo toàn từ thiết kế cũ, không phải machine-readable contract. Với các
> downstream đã có contract, spec trong `contracts/` là nguồn chuẩn cho payload;
> file này chỉ giải thích semantics mà Checkout dựa vào.

## 1. Danh mục tương tác

| Tương tác | Hướng | Giao thức | Provider | Consumer | Contract | Trạng thái |
|---|---|---|---|---|---|---|
| `POST /v1/checkout/preview` | Inbound | HTTP | Checkout | Frontend qua Gateway | Chưa có | legacy_documented |
| `POST /v1/checkout/submit` | Inbound | HTTP | Checkout | Frontend qua Gateway | Chưa có | legacy_documented |
| Voucher tray (suggestions/discovery/save) | Outbound | HTTP | Promotion | Checkout/Frontend qua Gateway | [Promotion OpenAPI v1](../../contracts/http/promotion/v1/openapi.yaml) | implemented |
| `POST /internal/v1/promotion-discount-calculations` | Outbound | HTTP nội bộ | Promotion | Checkout | [Promotion OpenAPI v1](../../contracts/http/promotion/v1/openapi.yaml) | implemented |
| Cart snapshot (`HGETALL cart:user:{id}`) | Outbound | Redis read theo tài liệu cũ | Cart | Checkout | [Cart Data](../cart/data.md) | legacy_documented |
| Address list + checkout-snapshot | Outbound | HTTP nội bộ mTLS | User | Checkout | [User OpenAPI v1](../../contracts/http/user/v1/openapi.yaml) (`getCheckoutAddressSnapshot`) | proposed |
| Batch SKU (`GetBatchProductSkus` theo tài liệu cũ) | Outbound | gRPC | Product | Checkout | [Product gRPC v1](../../contracts/grpc/ecommerce/product/v1/product_service.proto) | proposed (tên RPC cần đối soát) |
| Routing/allocation preview (`/routing-options` theo tài liệu cũ) | Outbound | HTTP nội bộ | Inventory | Checkout | [Inventory Workflow OpenAPI v1](../../contracts/http/inventory/workflow/v1/openapi.yaml) | proposed (path cần đối soát) |
| Create order from checkout | Outbound | Internal HTTP mTLS | Order | Checkout | [Order internal OpenAPI v1](../../contracts/http/order/internal/v1/openapi.yaml) | proposed |

Checkout không phát và không tiêu thụ Kafka event.

## 2. HTTP API đầu vào

Gateway xác thực JWT và chuyển `user_id` tin cậy. Cả hai API là command; không
có `Idempotency-Key` header riêng — `checkout_token` đóng vai trò idempotency key
cho Submit.

### 2.1 `POST /v1/checkout/preview`

**Mục đích:** API hạng nặng, gọi mỗi khi user vào màn hình Checkout, đổi địa chỉ,
chọn/bỏ voucher. Thay đổi số lượng sản phẩm thuộc Cart và phải hoàn tất trước khi
gọi Preview.

**Request (ví dụ):**

```json
{
  "checkout_token": null,
  "delivery_address_id": "addr-uuid-123",
  "items": [ "..." ],
  "selected_vouchers": { "PLATFORM_VOUCHER": "promo-123" }
}
```

- `checkout_token`: `null` → tạo mới từ giỏ hàng; nếu đã có session hiện hành →
  `409` (chống gian lận nhiều phiên). Token cũ → cập nhật phiên đó.
- `items`: lấy từ Cart Service response (giỏ đã flat-list). `sku_ids` dùng để
  gọi Product, nhưng `quantity` luôn lấy từ cart snapshot; payload cố mutate
  `quantity` → `400 INVALID_CHECKOUT_MUTATION_SCOPE`.
- `selected_vouchers`: map `slot = promotion_id`; contract Promotion chỉ còn slot
  `PLATFORM_VOUCHER`. Voucher không áp thì không xuất hiện key (không serialize
  `null`). Validate xung đột do Promotion xử lý theo stacking rules.

**Preconditions:** JWT hợp lệ; qua rate limit 10 req/phút và lock
`checkout:lock:preview:{user_id}` 5 giây (bị chặn → `429`).

**Postconditions:** session `checkout:session:{user_id}` được ghi/ghi đè với token
mới, status `PREVIEW_READY`, TTL 20 phút; routing cache được populate.

**Response (ví dụ):**

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
  "voucher_applied": { "promotion_id": null, "discount_amount": 0 },
  "warehouse_assignments": [ { "warehouse_id": 5 } ],
  "price_breakdown": {
    "total_product_amount": 44000000,
    "total_voucher_discount": 0,
    "final_amount": 44000000
  }
}
```

Khi downstream timeout, response mang trạng thái `DEGRADED_PROMOTION` (không áp
voucher) hoặc `DEGRADED_STOCK` (giá tạm thời) và UI bắt buộc hiển thị hộp xác
nhận trước khi cho Submit.

**Lỗi:** `400 INVALID_CHECKOUT_MUTATION_SCOPE`; `409` session đã tồn tại; `422`
SKU không khả dụng (kèm danh sách); `429` rate limit/lock; lỗi từng phần theo
timeout policy trong `operations.md`.

### 2.2 `POST /v1/checkout/submit`

**Mục đích:** chốt đơn từ session đã Preview.

**Request:**

```json
{
  "checkout_token": "uuid-v4-xxx",
  "user_ack_degraded_pricing": false
}
```

Phase 18: `payment_method` đã bị loại khỏi Submit payload và response; hệ thống
chỉ dùng Stripe. Client không gửi số tiền nào; mọi giá trị lấy từ Redis session.

**Preconditions:** session tồn tại, token khớp, status `PREVIEW_READY`; qua rate
limit 3 req/phút và lock `checkout:lock:submit:{user_id}` 30 giây; nếu session
degraded thì `user_ack_degraded_pricing = true`; `pricing_hash` tính lại khớp.

**Postconditions:** session `SUBMITTED` (không rollback); payload lưu
`checkout:payload:{user_id}:{token}`; response lưu
`checkout:response:{user_id}:{token}` khi có kết quả success/conflict.

**Response success:** `{order_id, client_secret, publishable_key}` — Frontend dùng
Stripe Elements / Stripe Checkout SDK.

**Response conflict `409 PROMOTION_RECALCULATION_NEEDED`:**

```json
{
  "error_code": "PROMOTION_RECALCULATION_NEEDED",
  "message": "Giỏ hàng có sự thay đổi về mã giảm giá.",
  "action": "RECALL_PREVIEW",
  "dropped_promotions": [
    { "promotion_id": "promo-30", "reason": "BUDGET_EXHAUSTED", "user_message": "Mã giảm giá đã hết ngân sách." },
    { "promotion_id": "promo-10", "reason": "CONDITIONS_NOT_MET", "user_message": "Đơn hàng không còn đủ điều kiện áp dụng mã này do thay đổi tổng tiền." }
  ],
  "proposal_checkout_data": {
    "new_total_amount": 550000,
    "surviving_promotions": [ "promo-55" ]
  }
}
```

`dropped_promotions[]` chỉ chứa mã bị loại, theo đúng thứ tự để user hiểu hệ
quả dây chuyền; `surviving_promotions[]` là input chuẩn để client gọi lại Preview.

**Response conflict `409 PRICE_CHANGED`:**

```json
{
  "error": "PRICE_CHANGED",
  "message": "Giá đã thay đổi kể từ lần xem trước. Vui lòng review lại.",
  "action": "RECALL_PREVIEW"
}
```

**Idempotency/replay:** cùng token gọi lại → Lua `-2` → trả response cache (success
hoặc conflict) trong 20 phút; nếu chưa có response mà còn payload → retry Order;
payload hết TTL → `410 Gone`.

**Bảng lỗi:**

| HTTP | Code | Khi nào |
|---|---|---|
| `423` | Locked | Không xin được submit lock (“Đang xử lý đơn hàng, vui lòng chờ”) |
| `409` | `DEGRADED_PRICING_NOT_CONFIRMED` | Session degraded, thiếu ack |
| `409` | `PRICE_CHANGED` | Hash lệch; session vẫn `PREVIEW_READY` |
| `409` | Token used/expired (`INVALID_TOKEN`/`ALREADY_SUBMITTED` không có cache) | Token không khớp hoặc đã dùng |
| `409` | `PROMOTION_RECALCULATION_NEEDED` | Reserve voucher fail |
| `404` | Not Found | Session hết TTL |
| `410` | Gone | Payload recovery hết TTL |
| `503` | Service Unavailable | Order timeout sau 3 lần retry; client retry |
| `429` | Too Many Requests | Vượt 3 req/phút |

### 2.3 Voucher tray (policy UI/BE)

Checkout dùng policy cố định cho tray voucher Sàn (`PLATFORM_VOUCHER`):

| Tab/Thao tác | Endpoint Promotion | Semantics |
|---|---|---|
| “Voucher của tôi” | `POST /api/v1/me/promotion-voucher-suggestions` | Chỉ hiển thị mã `SAVED` trong ví |
| “Khám phá” | `POST /api/v1/promotion-discovery` | Wallet voucher + public `ACTIVE` chưa lưu; có thể downgrade mã sang `BUDGET_EXHAUSTED` bằng budget guard read-time (Option A) |
| “Lưu & Chọn” | `PUT /api/v1/me/promotion-vouchers/{promotion_id}` | Explicit write vào ví; sau đó Frontend gọi lại Preview để nhận token mới |

Hai query là safe query dù dùng POST vì checkout context lớn. Discovery rate limit
thuộc Gateway (profile `promotion-discovery`, 20 req/phút/user, coarse trusted-IP
guard trước ForwardAuth). Submit path **không** auto-save voucher.

## 3. Downstream API mà Checkout tiêu thụ

### 3.1 Cart

Đọc snapshot giỏ hàng `HGETALL cart:user:{id}` (theo tài liệu cũ, gọi trong Wave
1, timeout 50ms). Checkout chỉ đọc; không ghi. Giỏ đã flat-list; quantity là nguồn
chuẩn cho tính giá.

### 3.2 User

- Wave 1: `GET` address list (timeout 500ms; fallback địa chỉ cũ từ session nếu
  có).
- Submit/Preview: validate `address_id` thuộc user và lấy snapshot qua internal
  endpoint `GET /internal/v1/users/{user_id}/addresses/{address_id}/checkout-snapshot`
  (mTLS, security scheme `checkoutService`). Snapshot dùng chuẩn
  `recipient_name`, `recipient_phone`, `address_line`, `province_code`,
  `district_code`, `ward_code`.
- Cache địa chỉ (`user:caches:addresses:{user_id}`) thuộc User Service; Checkout
  hưởng cache gián tiếp.

### 3.3 Product (gRPC)

Batch fetch tất cả SKU trong giỏ (tài liệu cũ gọi `GetBatchProductSkus`; proto
hiện có định nghĩa `GetBatchSkus`):

- Preview: validate `is_available` + `status`; SKU `!= ACTIVE` → `422`. Lấy
  `current_price` để tính giá realtime.
- Submit: lấy `original_price`, `promotional_price`, `best_price` hiện tại cho
  Price Staleness Check. Product tự xử lý Redis/cache miss nội bộ và trả dữ liệu
  chuẩn hợp đồng; Checkout không cache giá.

Deadline/retry cụ thể chưa chốt; lookup cache hit ước ~2–4ms.

### 3.4 Inventory

Gọi per warehouse group song song (`/routing-options` theo tài liệu cũ; contract
hiện có là `POST /internal/inventory/v1/allocation-previews`, advisory, trả
`ALLOCATABLE`/`REJECT_ALL`). Timeout 3s. Là **soft allocation**: không tạo hold.
Response được cache `checkout:routing:{items_hash}:{address_hash}` 20 phút.
Timeout → `DEGRADED_STOCK`.

### 3.5 Promotion

- `POST /internal/v1/promotion-discount-calculations` (Wave 2, timeout 2s): pure
  query, validate only, không lock budget; input gồm subtotal/items mới nhất,
  `payment_method=STRIPE`, Money `{amount_minor, currency}`. Timeout → không áp
  voucher, `DEGRADED_PROMOTION`.
- Cùng endpoint được gọi lại ở Submit khi reserve fail để dựng
  `dropped_promotions[]` với tập mã còn lại.
- Submit không gọi reserve; reserve thuộc Saga của Order
  (`/internal/v1/promotion-allocation/reservations`).
- `promotion_campaign_revision` là concurrency/cache fence dùng trong hash;
  `promotion_budget_version` chỉ telemetry.

### 3.6 Order

Gửi toàn bộ payload đã khóa (items, pricing, promotions,
`delivery_address_snapshot`, canonical `allocation_plan_id` và line assignments,
`pricing_hash`, `items_hash`, `promotion_campaign_revision`,
`user_ack_degraded_pricing`) tới
`POST /internal/v1/checkout-order-submissions`. Header `Idempotency-Key` bắt buộc
bằng `checkout_token`. Order kích hoạt Temporal Saga
`CreateOrder → ReserveInventory → ReservePromotion → InitiatePayment →
WaitPaymentSignal` và dedup bằng Temporal WorkflowID, Redis fast path và FSM
guard. Kết quả:

- Success lần đầu `201`, replay `200`: `{checkout_token, order_id, order_status,
  payment:{payment_attempt_number,payment_transaction_id,client_secret,
  publishable_key,expires_at}}`.
- Lỗi nghiệp vụ có cấu trúc từ ReservePromotion: `failed_promotion_id`,
  `failed_reason`, `reserve_mode`.
- Timeout `503 retryable=true`: Checkout retry nội bộ tối đa 3 lần với cùng key/body;
  có thể query `GET /internal/v1/checkout-order-submissions/{checkout_token}`.

`pricing_hash` dùng `sha256:<64 lowercase hex>` trên UTF-8 RFC 8785 canonical JSON
`{items:[{sku_id,unit_price_minor}],promotion_campaign_revision,
total_discount_minor}` với items sort theo byte UTF-8 của unique `sku_id`.
`items_hash` dùng `{items:[{quantity,sku_id}]}` cùng canonicalization. Xem
[Order WF-ORDER-004](../order/workflows.md#wf-order-004--checkout-submit-saga).

## 4. Event phát ra

Không có. Checkout không có outbox/Kafka producer.

## 5. Event tiêu thụ

Không có. Checkout không có Kafka consumer; mọi thay đổi downstream được phát
hiện bằng cách gọi lại (Preview) hoặc hash check (Submit).

## 6. Compatibility

- Checkout chưa có versioned contract; `POST /v1/checkout/*` là version path đề
  xuất. Khi có OpenAPI, additive change phải backward compatible; đổi ý nghĩa
  `pricing_hash`, error code hoặc token semantics là breaking change và cần ADR.
- Downstream: Checkout phải bỏ qua unknown field từ Promotion/Inventory/Product/Order
  và không suy diễn semantics ngoài contract. Order HTTP/path đã chốt ở internal
  OpenAPI v1; Product RPC còn cần đối soát riêng.
- Phase 18 đã loại `payment_method` khỏi Submit request/response; Phase 14 đã loại
  Shipping Service và các field shipping khỏi Preview response.
- Trạng thái `verified` chỉ đạt khi có contract test giữa Checkout và từng
  downstream, cộng test idempotency/recovery cho Submit.
