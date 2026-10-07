# Tracing và Correlation

Ba khái niệm không thay thế cho nhau:

- `traceparent`: định danh technical trace, có thể thay đổi khi sampling/restart.
- `correlation_id`: nối một business flow kéo dài qua nhiều transport.
- `idempotency_key`: nhận diện một logical command để chống thực thi lặp.

HTTP/gRPC MUST truyền trace context khi có. Kafka event SHOULD mang `traceparent`
và `correlationid`, nhưng consumer vẫn phải xử lý được event không còn trace hợp lệ.

Log MUST có `service`, `operation`, `trace_id` và business identifier phù hợp;
không log token, password, OTP hay toàn bộ PII payload.
