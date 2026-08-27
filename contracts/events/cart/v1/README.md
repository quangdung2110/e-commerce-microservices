# Cart Events v1

**Trạng thái:** `proposed`

Nguồn chuẩn:

- Channel/message: [`asyncapi.yaml`](asyncapi.yaml)
- Payload: [`schemas/cart-item-added.schema.json`](schemas/cart-item-added.schema.json)
- Fixture: [`../../../examples/events/cart-item-added.v1.json`](../../../examples/events/cart-item-added.v1.json)

## Delivery contract

- Provider: `cart-service`
- Topic: `t.cart.events`
- Kafka key: `cart_id`
- Ordering: chỉ trong cùng `cart_id`
- Delivery: at-least-once
- Giới hạn message: 256 KiB
- Retention target: 7 ngày
- Retry target: tối đa 3 lần với exponential backoff + jitter
- DLQ target: `t.cart.events.dlq`
- Consumer phải deduplicate theo CloudEvent `id`
- `CART_ITEM_ADDED` legacy sẽ migrate thành
  `com.ecommerce.cart.item-added.v1`

Pilot mới mô tả một event. Các event updated/deleted/cleared/checked-out/abandoned
sẽ được thêm từng bước sau khi envelope mới được triển khai và test.

## Lưu ý PII

`user_id` được phân loại INTERNAL identifier. Event không chứa email, phone,
address, token hoặc device info. Analytics/Notification không được yêu cầu thêm
PII chỉ để tránh lookup nếu không có use case rõ ràng.
