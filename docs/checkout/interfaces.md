---
title: Checkout Interfaces
status: proposed
last_updated: 2026-09-20
owners:
  - domain: checkout
    team: commerce-platform
    contact: commerce-platform@example.com
review_cycle_days: 30
source_of_truth: false
evidence:
  - ../../contracts/http/checkout/v1/openapi.yaml
  - ../../contracts/http/cart/v1/openapi.yaml
  - ../../contracts/http/user/v1/openapi.yaml
  - ../../contracts/http/inventory/workflow/v1/openapi.yaml
  - ../../contracts/http/promotion/v1/openapi.yaml
  - ../../contracts/http/order/internal/v1/openapi.yaml
  - ../../contracts/grpc/ecommerce/product/v1/product_service.proto
tags: [checkout, api, integration]
---

# Checkout Interfaces

## Inbound

OpenAPI chuẩn nằm tại `contracts/http/checkout/v1/openapi.yaml`.

| Endpoint | Input do client được phép gửi | Success |
|---|---|---|
| `POST /api/v1/checkout/preview` | `previous_checkout_token?`, `delivery_address_id`, `promotion_id?`; header `Idempotency-Key` | `200 CheckoutPreview` |
| `POST /api/v1/checkout/submit` | `checkout_token` | `201` mới tạo, `200` replay, `202` đang xử lý |
| `GET /api/v1/checkout/submissions/{checkout_token}` | Token trong path | `200 SubmissionStatus` |

Mọi response chứa token/snapshot dùng `Cache-Control: private, no-store`. Auth context đến từ Gateway; client không gửi `user_id`, tier, role hay channel trong body.

## Downstream contracts

### Cart

`GET /internal/v1/carts/{user_id}/checkout-snapshot`

- Service identity: Checkout qua mTLS.
- Trả tối đa 50 `items[{product_id, sku_id, quantity}]`; Checkout sort theo UTF-8 `sku_id`, dùng `line_reference=sku_id` ở v1 và tính `items_hash` đúng RFC 8785 recipe của Order contract.
- Checkout tuyệt đối không đọc `cart:user:*` trong Redis của Cart.

### Product

`GetBatchSkus` trong Product gRPC v1.

Checkout dùng `sku_id`, `product_id`, `product_name`, `price`, `base_price`, `catalog_revision`, `category_ids`, và `status` hiện có trong contract. `category_ids` là ancestry do Product sở hữu để Promotion đánh giá rule theo category.

### User

`GET /internal/v1/users/{user_id}/addresses/{address_id}/checkout-snapshot` xác minh ownership và trả address snapshot. Checkout giữ `address_id` cho session/public response nhưng bỏ field này khi map sang Order `delivery_address_snapshot`, vì Order chỉ nhận recipient và các trường địa chỉ. Trong v1 không có tier authority nên Checkout luôn omit/null `user_tier`; bổ sung tier sau này cần version đồng bộ cả Checkout, Order và Promotion.

### Inventory

`POST /internal/inventory/v1/allocation-previews`

Request là whole-order `lines[{line_reference, sku_id, quantity}]`. Checkout chỉ chấp nhận `outcome=ALLOCATABLE` khi `allocation_plan_id` và planned lines bao phủ đúng mọi dòng; `REJECT_ALL` là stock conflict. Timeout/5xx là dependency error và không được degraded.

### Promotion

`POST /internal/v1/promotion-discount-calculations`

Mapping:

- `promotion_id`: scalar từ preview request.
- `user_id`: trusted identity.
- `user_tier`: omit/null nếu không có authoritative source.
- `checkout_channel`: constant `CHECKOUT`.
- `items`: giá từ Product và `category_ids` từ Product.

Infrastructure error không được đổi thành “promotion không áp dụng”. Business rejection hợp lệ được trả về client dưới dạng promotion problem.
Khi không có `promotion_id`, Checkout không cần gọi Promotion, đặt discount và
`promotion_campaign_revision` bằng `0`, đồng thời gửi `promotions=[]` sang Order.

### Order

`POST /internal/v1/checkout-order-submissions`

- Header `Idempotency-Key` phải bằng chính UUID `checkout_token`, đúng Order contract.
- Payload được lưu trước network call và không đổi giữa các retry.
- `user_ack_degraded_pricing=false` là field tương thích; Checkout không hỗ trợ degraded path.
- `allocation_plan`, pricing snapshots và promotion campaign revision đến từ preview/revalidation.
- Mỗi Order item mang nguyên `category_ids` Product-owned để Order có thể dựng lại Promotion Reserve context.
- `promotions` có 0 hoặc 1 phần tử; không có promotion thì campaign revision bằng `0`.
- `items_hash` và `pricing_hash` dùng chính canonical JSON/SHA-256 recipe mô tả trong Order OpenAPI; không định nghĩa công thức riêng ở Checkout.

Recovery dùng `GET /internal/v1/checkout-order-submissions/{checkout_token}`. Checkout phải query endpoint này khi POST timeout hoặc trước khi kết luận token submit đã mất/hết hạn. Payment client data chỉ được relay từ Order response và không lưu trong Checkout cache/result.

## Propagation và deadline

- Propagate `traceparent`, `tracestate`, `x-request-id` và service identity theo mesh contract.
- Không retry mù các business `4xx`.
- Retry transport cho Order chỉ với cùng payload và cùng idempotency key.
- Tổng deadline của Checkout phải nhỏ hơn deadline Gateway; mỗi downstream call có deadline riêng trong ngân sách đó.

## Error contract

Mọi HTTP error dùng `application/problem+json` theo RFC 9457 với `type`, `title`, `status`, `code`, `detail`, `request_id`; có thể kèm `retry_after_seconds` hoặc `changed_fields`. Raw downstream payload không được leak ra public API.
