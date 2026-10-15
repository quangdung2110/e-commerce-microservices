# Product gRPC v1

`Sku.price` is the published selling price, `Sku.base_price` is the admin-managed
list price, and `Sku.category_ids` is the Product-owned category ancestry consumed
by Checkout when building trusted Promotion facts. `Sku.image_url` carries the object
storage key of the representative image (SKU image, else Product image; empty when
none), not an absolute URL; consumers build the CDN URL from it. The API never exposes
Inventory stock state.

**Trạng thái:** `proposed` ([ADOPTION](../../../../ADOPTION.md)). Provider `product-service` và consumer
`cart-service`/`checkout-service` đã dùng contract này; chưa `implemented` vì `.proto` vẫn là bản copy trong
service (xem "Bản copy trong service" bên dưới).

## Vấn đề

Cart cần lấy snapshot SKU để hiển thị và kiểm tra sản phẩm trước khi thêm vào
giỏ. Product sở hữu capability này; Cart chỉ là consumer.

## Contract

Nguồn chuẩn: [`product_service.proto`](product_service.proto).

- Provider: `product-service`
- Consumer: `cart-service`, `checkout-service`
- `GetSku` deadline đề xuất: 500 ms
- `GetBatchSkus` deadline đề xuất: 1 giây, tối đa 100 SKU
- Cả hai RPC là read-only và có thể retry trong retry budget

## Metadata

Caller gửi `x-service-identity`, `x-request-id` và, khi có trace đến, `traceparent` (kèm `tracestate`) theo
[gRPC Convention](../../../../conventions/grpc.md#metadata) và
[ADR-002](../../../../decisions/ADR-002-correlation-and-trace-propagation.md). Cart và Checkout đều gửi đủ.
Provider không được từ chối vì `x-request-id` sai định dạng.

Product kiểm `x-service-identity` với `product.internal.grpc-clients` (mặc định `cart-service`,
`checkout-service`) làm defense-in-depth sau mesh ([ADR-004](../../../../decisions/ADR-004-service-identity-allowlist.md)):
thiếu là `UNAUTHENTICATED`, ngoài danh sách là `PERMISSION_DENIED`. Cả hai là lỗi cấu hình của caller, không
retry. gRPC reflection tắt mặc định (chỉ profile `dev` hoặc `PRODUCT_GRPC_REFLECTION=true`).

## Failure

- SKU không tồn tại/không khả dụng là business result trong `oneof`, không phải
  transport failure.
- Request sai dùng `INVALID_ARGUMENT`.
- Deadline hết dùng `DEADLINE_EXCEEDED`.
- Provider quá tải dùng `RESOURCE_EXHAUSTED` hoặc `UNAVAILABLE` và không để
  consumer retry vô hạn.

## Bản copy trong service

`product-service/proto/` và `checkout-service/proto/` chứa bản copy của file này và của
`shared/proto/ecommerce/common/v1/money.proto`, vì Docker build context của từng service chỉ gồm thư
mục service. Đây là ngoại lệ có chủ đích của quy tắc "service không copy `.proto`":

- Sửa proto ở `contracts/` rồi chép lại; không bao giờ sửa trực tiếp bản copy.
- `ProtoCopyDriftTest` (Product) và `ProtoCopyTest` (Checkout) fail khi bản copy lệch từng byte so với
  nguồn chuẩn (Checkout kiểm cả `product_service.proto` lẫn `money.proto`).
- Ngoại lệ chỉ chấm dứt khi build đọc thẳng từ `contracts/`; lúc đó contract này chuyển `implemented`.

## Lịch sử

Wire cũ (package `com.platform.productservice.proto`, service `CartServiceGrpc`, giá `double`) đã được
thay hoàn toàn bằng package `ecommerce.product.v1` và `Money`; không còn provider hay consumer nào dùng
wire cũ.
