# Cart HTTP v1

**Trạng thái:** `proposed`

Nguồn chuẩn: [`openapi.yaml`](openapi.yaml). Cart ingress nằm sau API Gateway;
`X-User-Id` là trusted header nội bộ, không phải header client tự đặt ở public edge.

## Quantity semantics

`POST /api/v1/carts/mine/items` nhận `delta`, không nhận quantity tuyệt đối. Delta
dương thêm/tăng SKU, delta âm giảm SKU; giá trị `0` không hợp lệ. Thiết kế này phù
hợp Redis Lua atomic và không để một request quantity cũ đến muộn ghi đè kết quả của
click mới hơn. Xóa item vẫn là `DELETE` batch rõ ràng.

## Idempotency target

- Bắt buộc với POST và DELETE.
- Scope: `user_id + HTTP method + path + Idempotency-Key`.
- Redis physical key thêm `user_token` và operation, và chỉ lưu SHA-256 của header
  key; nó vẫn mang hash tag bucket để chạy cùng Lua transaction với cart state.
- Fingerprint dùng canonical request body; `sku_ids` được sort trước khi hash.
- Retention tối thiểu 24 giờ.
- Cùng key, cùng fingerprint trả cùng logical response; cùng key khác fingerprint
  trả `409 IDEMPOTENCY_CONFLICT`.
- Request đang xử lý có thể trả `409` kèm `Retry-After`.

## Read freshness

GET không gia hạn Redis TTL và có `Cache-Control: no-store`. `catalog_freshness`
`CURRENT` nghĩa Cart vừa enrich từ Product; `DEGRADED` nghĩa Product không khả dụng,
`current_price` có thể `null` và `is_available` là snapshot catalog gần nhất.
`is_available` biểu thị Product/SKU còn active theo catalog, không biểu thị tồn kho.
