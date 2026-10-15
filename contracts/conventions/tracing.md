# Tracing và Correlation

Quyết định gốc: [ADR-002](../decisions/ADR-002-correlation-and-trace-propagation.md).

Bốn định danh, mỗi cái một nhiệm vụ và không thay thế cho nhau:

| Định danh | HTTP header | gRPC metadata | Ý nghĩa | Định dạng |
|---|---|---|---|---|
| Trace kỹ thuật | `traceparent`, `tracestate` | `traceparent`, `tracestate` | W3C Trace Context; có thể đổi khi sampling/restart | theo W3C |
| Request ID | `X-Request-Id` | `x-request-id` | ID của yêu cầu gốc, đi nguyên vẹn qua mọi hop đồng bộ | `^[A-Za-z0-9._:-]{8,128}$` |
| Correlation ID | `X-Correlation-Id` | `x-correlation-id` | ID của business flow (saga/workflow), sống qua nhiều request và nhiều lần thử lại | như Request ID |
| Causation ID | `X-Causation-Id` | `x-causation-id` | ID của command hoặc event gây ra command này | như Request ID |

`Idempotency-Key` nhận diện một logical command để chống thực thi lặp; nó không phải định danh
correlation. Tên header không phân biệt hoa thường: `X-Correlation-ID` trong contract cũ và
`X-Correlation-Id` là cùng một header.

## Bên gọi

1. Mọi lời gọi HTTP/gRPC nội bộ MUST mang `X-Request-Id`.
2. Trong phạm vi một request đến: gửi tiếp Request ID đã chuẩn hoá. Header đến thiếu hoặc không
   khớp định dạng thì dùng trace ID (32 hex) của `traceparent` hợp lệ, rồi mới tới UUID mới. Giá trị
   sai không bao giờ làm request thất bại.
3. `traceparent`: service có tracer gửi span hiện tại; service không có tracer gửi tiếp
   `traceparent` và `tracestate` nhận được. MUST NOT bịa `traceparent` khi không có trace đến và
   không có tracer. `tracestate` chỉ đi cùng một `traceparent` hợp lệ, tối đa 512 ký tự ASCII in được.
4. Công việc không có request gốc (Temporal activity, outbox relay, consumer Kafka, scheduler): gửi
   `X-Request-Id` mới cho mỗi lần gửi, `X-Correlation-Id` ổn định của business flow khi contract
   provider định nghĩa header này, và giữ nguyên `Idempotency-Key`/`X-Causation-Id` khi replay.
5. Công việc dùng chung nhiều caller (singleflight, batch) chỉ mang Request ID của caller khởi tạo.
6. Request ID và Correlation ID chỉ vào log và `trace_id` của problem, không vào label của metric.

## Bên nhận

1. Chấp nhận các header trên. `X-Request-Id` thiếu hoặc sai định dạng thì tự sinh UUID, không trả
   `400`. Chỉ khi contract ghi một header là bắt buộc (`X-Correlation-Id` của Payment, Inventory)
   mới được từ chối khi thiếu.
2. Mọi dòng log của request MUST có `request_id` và, nếu có `traceparent` hợp lệ, `trace_id`.
3. Trả `X-Request-Id` trong response.
4. Service chưa có tracer vẫn phải tách `trace_id` từ `traceparent` để ghi log và truyền tiếp trên
   lời gọi đi của chính nó.

## Mức tuân thủ

- **Mức 1 – pass-through**: truyền header và ghi log. Bắt buộc.
- **Mức 2 – tracing đầy đủ**: SDK sinh span và export OTLP, theo
  [mesh §9](../../docs/mesh/runtime-contract.md) và
  [gateway §8](../../docs/gateway/runtime-contract.md). Lộ trình riêng của từng service.

## Kafka và log

Kafka event SHOULD mang `traceparent` và `correlationid`, nhưng consumer vẫn phải xử lý được
event không còn trace hợp lệ.

Log MUST có `service`, `operation`, `trace_id` và business identifier phù hợp;
không log token, password, OTP hay toàn bộ PII payload.
