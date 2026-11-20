# Cart Events v1

**Trạng thái:** `implemented` — `go/services/cart-service` phát event qua Redis Stream
outbox và relay; Notification đã consume (`go/services/notification-service`). Test
provider kiểm cấu trúc CloudEvent và tính ổn định của `id`; chưa có contract test
validate với JSON Schema (xem [ADOPTION](../../../ADOPTION.md)).

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
- `dataschema`: `/contracts/events/cart/v1/schemas/cart-abandoned.schema.json` (dạng
  root-relative như mọi service khác)
- Header bắt buộc: `outbox_bucket`, `stream_id`, `cart_id`
- Retention target: 7 ngày

## Retry và DLQ

- Retry là của **relay**, không phải của consumer: mỗi lượt thử một entry tối đa 3 lần
  với backoff, rồi để entry pending và thử lại ở lượt sau (`XAUTOCLAIM`). Kafka outage
  làm chậm event, không làm mất.
- `t.cart.events.dlq` chỉ nhận entry outbox hỏng (thiếu `cart_id` hoặc event type lạ).
  Message DLQ là JSON chẩn đoán, không phải CloudEvent. Consumer của `t.cart.events`
  không cần đọc DLQ này.

Payload chuẩn: [`schemas/cart-abandoned.schema.json`](schemas/cart-abandoned.schema.json).
Ví dụ: [`contracts/examples/events/cart-abandoned.v1.json`](../../../examples/events/cart-abandoned.v1.json).

## Event Cart tiêu thụ

Cart không phát thêm event khi tiêu thụ; hai input là
`com.ecommerce.order.paid.v1` ([Order AsyncAPI](../../order/v1/asyncapi.yaml)) và
`com.ecommerce.auth.user-deleted.v1` ([Auth AsyncAPI](../../auth/v1/asyncapi.yaml)).

## Lưu ý PII

`user_id` là INTERNAL identifier. Event không chứa email, phone, address, item list,
payment, token hoặc device information. Notification không được yêu cầu thêm PII nếu
không có use case rõ ràng.
