# Cart HTTP v1

Checkout reads Cart only through
`GET /internal/v1/carts/{user_id}/checkout-snapshot` with mesh mTLS. The response
contains only `product_id`, `sku_id`, and authoritative `quantity`; it does not
enrich Product data or expose Cart's bucketed Redis keyspace. Checkout and every
other consumer must not issue direct Redis commands against Cart-owned keys.
Cart also checks `X-Service-Identity` against its allowlist
(`cart.internal_callers`, default `checkout-service`) as defence in depth: a
missing header is `401`, another service is `403`, a non-UUID `user_id` is `400`.
The route is internal-only (no Gateway router) and is not rate limited.

**Trạng thái:** `implemented` — `go/services/cart-service` phục vụ cả bốn operation;
test provider nằm ở `internal/api/router`. Chưa có contract test tự động validate
response với OpenAPI và chưa có consumer test với Checkout thật
(xem [ADOPTION](../../../ADOPTION.md)).

Nguồn chuẩn: [`openapi.yaml`](openapi.yaml). Cart ingress nằm sau API Gateway;
`X-User-Id` là trusted header nội bộ, không phải header client tự đặt ở public edge.
Local, Gateway nghe ở `http://localhost:8088`; Cart không publish cổng host (cổng 8085
thuộc Promotion).

## Quantity semantics

`POST /api/v1/carts/mine/items` nhận `delta`, không nhận quantity tuyệt đối. Delta
dương thêm/tăng SKU, delta âm giảm SKU; giá trị `0` không hợp lệ. Thiết kế này phù
hợp Redis Lua atomic và không để một request quantity cũ đến muộn ghi đè kết quả của
click mới hơn. Xóa item vẫn là `DELETE` batch rõ ràng.

## Idempotency

- Bắt buộc với POST và DELETE (`Idempotency-Key`, 16–128 ký tự).
- Scope: `user_id + HTTP method + path + Idempotency-Key`.
- Redis physical key thêm `user_token` và operation (`adjust` cho POST, `delete` cho
  DELETE), và chỉ lưu SHA-256 của header key; nó vẫn mang hash tag bucket để chạy
  cùng Lua transaction với cart state.
- Fingerprint dùng canonical request body; `sku_ids` được sort trước khi hash.
- Retention bằng cấu hình `cart.idempotency_ttl` (mặc định 24 giờ), áp dụng cho cả hai operation.
- Cùng key, cùng fingerprint trả cùng logical response (kể cả status `200`/`201`); cùng
  key khác fingerprint trả `409 IDEMPOTENCY_CONFLICT`.
- Không có trạng thái "đang xử lý": record được ghi nguyên tử cùng thay đổi cart, nên
  `409` chỉ xuất hiện khi payload khác và không có `Retry-After`.

## Rate limit

Mỗi pod giới hạn 20 request/giây (burst 40) theo user cho `GET`, `POST` và `DELETE`
dưới `/api/v1/carts/mine`; vượt giới hạn trả `429 RATE_LIMITED` (`retryable`) kèm
`Retry-After`. Gateway có policy `rate-limit-api` riêng ở phía trước.

## Read freshness

`CartItem.variant_name` tùy chọn chứa nhãn biến thể đã resolve bởi Product. Read
CURRENT của SKU còn bán lấy nhãn hiện tại (kể cả sau đổi tên attribute/option);
DEGRADED hoặc SKU không còn bán dùng nhãn snapshot khi ghi Cart gần nhất. GET không
persist nhãn mới. Cart legacy/provider cũ có thể chưa có nhãn; UI hiển thị các value
của `variant_attributes`, không dùng code làm tên hiển thị. `variant_name` chỉ phục
vụ hiển thị, không thay SKU ID/giá/quantity hoặc snapshot Checkout nội bộ.

GET không gia hạn Redis TTL và có `Cache-Control: no-store`. `catalog_freshness`
`CURRENT` nghĩa Cart vừa enrich từ Product; `DEGRADED` nghĩa Product không khả dụng,
`current_price` có thể `null` và `is_available` là snapshot catalog gần nhất.
`is_available` biểu thị Product/SKU còn active theo catalog, không biểu thị tồn kho.

## Mã lỗi

Mọi lỗi là `application/problem+json` với `code` ổn định. Danh mục đầy đủ (code, HTTP
status, retryable) nằm ở [docs/cart/interfaces.md](../../../../docs/cart/interfaces.md) §2.1.
