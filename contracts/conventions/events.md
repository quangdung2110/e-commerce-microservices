# Event Convention

## Envelope

Kafka event mới MUST dùng CloudEvents 1.0 JSON structured format. Các field lõi:

`specversion`, `id`, `type`, `source`, `subject`, `time`, `datacontenttype`,
`dataschema`, `data`.

Extension được dùng trong dự án:

- `correlationid`: nối các bước của một business flow.
- `traceparent`: tiếp tục distributed trace nếu còn hợp lệ.
- `sequence`: chỉ dùng khi owner cung cấp sequence tăng trong cùng aggregate.

`specversion` là version CloudEvents, không phải version payload. Event type MUST
có version, ví dụ `com.ecommerce.cart.item-added.v1`.

## Kafka delivery contract

Mỗi channel/message MUST nêu:

- topic và owner;
- message key/partition key;
- ordering scope;
- delivery semantic;
- consumer dự kiến;
- max payload;
- retry/DLQ;
- retention nếu consumer phụ thuộc replay;
- PII classification.

Mặc định hiện tại là at-least-once. Consumer MUST idempotent và MUST bỏ qua field
optional chưa biết.

## Versioning

- Thêm field optional là compatible.
- Xóa/đổi tên field, đổi type hoặc làm field optional thành required là breaking.
- Breaking event tạo type/schema `v2`; producer có thời gian dual-publish nếu có
  consumer chưa migrate.
- Không dùng một `payload: map[string, any]` mà không có JSON Schema.
