# Cart Events v1

**Trạng thái:** `proposed`

Cart chỉ công bố [`CART_ABANDONED`](asyncapi.yaml) ở version này. Notification
Service là consumer duy nhất hiện tại; không có event item, cleared hay checked-out
cho tới khi có consumer cụ thể.

## Delivery contract

- Provider: `cart-service`
- Topic: `t.cart.events`
- Kafka key: `cart_id`
- Delivery: at-least-once
- Consumer deduplicate theo CloudEvent `id`
- `id` được Relay dựng xác định từ `outbox_key + stream_id`
- Header bắt buộc: `outbox_bucket`, `stream_id`, `cart_id`
- Retention target: 7 ngày; DLQ: `t.cart.events.dlq`

Payload chuẩn: [`schemas/cart-abandoned.schema.json`](schemas/cart-abandoned.schema.json).

## Lưu ý PII

`user_id` là INTERNAL identifier. Event không chứa email, phone, address, item list,
payment, token hoặc device information. Notification không được yêu cầu thêm PII nếu
không có use case rõ ràng.
