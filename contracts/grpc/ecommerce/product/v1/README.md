# Product gRPC v1

`Sku.price` is the published selling price, `Sku.base_price` is the admin-managed
list price, and `Sku.category_ids` is the Product-owned category ancestry consumed
by Checkout when building trusted Promotion facts. The API never exposes Inventory
stock state.

**Trạng thái:** `proposed`

## Vấn đề

Cart cần lấy snapshot SKU để hiển thị và kiểm tra sản phẩm trước khi thêm vào
giỏ. Product sở hữu capability này; Cart chỉ là consumer.

## Contract

Nguồn chuẩn: [`product_service.proto`](product_service.proto).

- Provider: `product-service`
- Consumer đầu tiên: `cart-service`
- `GetSku` deadline đề xuất: 500 ms
- `GetBatchSkus` deadline đề xuất: 1 giây, tối đa 100 SKU
- Cả hai RPC là read-only và có thể retry trong retry budget

## Failure

- SKU không tồn tại/không khả dụng là business result trong `oneof`, không phải
  transport failure.
- Request sai dùng `INVALID_ARGUMENT`.
- Deadline hết dùng `DEADLINE_EXCEEDED`.
- Provider quá tải dùng `RESOURCE_EXHAUSTED` hoặc `UNAVAILABLE` và không để
  consumer retry vô hạn.

## Migration note

Wire cũ có package `com.platform.productservice.proto`, service name
`CartServiceGrpc` và giá `double`. Target đổi package/service name và dùng Money,
vì vậy đây là breaking migration. Provider và consumer phải được deploy có phối
hợp hoặc chạy song song v1 legacy và canonical trong thời gian chuyển đổi.
