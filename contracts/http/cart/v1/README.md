# Cart HTTP v1

**Trạng thái:** `proposed`

Nguồn chuẩn: [`openapi.yaml`](openapi.yaml).

## Boundary

Spec này mô tả HTTP ingress của Cart sau API Gateway. `X-User-Id` là trusted
header do Gateway inject, không phải header public mà client được tự đặt. Public
edge sau này nên có OpenAPI riêng mô tả Bearer token.

## Thay đổi so với code hiện tại

- Dùng RFC 9457 Problem Details cho mọi error.
- Dùng Money integer thay cho `float64`.
- `Idempotency-Key` bắt buộc cho POST/DELETE mutation.
- Cart item là một list phẳng; bỏ wrapper group chưa có business meaning.
- Thay boolean degraded bằng enum `price_freshness` để dễ mở rộng.

Vì các thay đổi trên ảnh hưởng response wire, implementation chưa được coi là
conformant cho tới khi handler/DTO và test đã migrate.

## Idempotency target

- Scope: `user_id + operation + Idempotency-Key`.
- Retention tối thiểu: 24 giờ từ lần nhận đầu tiên.
- Cùng key và cùng payload: trả lại cùng logical result.
- Cùng key nhưng payload khác: `409 IDEMPOTENCY_KEY_REUSED`.
- Request cùng key đang xử lý: `409 IDEMPOTENCY_REQUEST_IN_PROGRESS`; client
  retry theo backoff và `Retry-After` nếu response có header này.
