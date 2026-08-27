# HTTP Convention

## URL và version

- API dùng prefix `/api/v1` trong giai đoạn hiện tại.
- Breaking change tạo `/api/v2`; compatible addition không đổi URL.
- Resource dùng danh từ số nhiều; action chỉ dùng khi không thể biểu diễn bằng resource.

## Response

- Success trả resource trực tiếp; không bắt buộc envelope `{ "data": ... }`.
- Create thường trả `201`; delete không có body dùng `204`.
- List lớn MUST dùng cursor pagination và giới hạn page size.
- Contract MUST ghi rõ consistency/freshness nếu response đọc từ cache hoặc read model.

## Error

Error MUST dùng `application/problem+json` theo RFC 9457. Schema chung nằm tại
`shared/json-schema/v1/problem.schema.json`. Extension chuẩn của dự án:

- `code`: mã ổn định để client xử lý.
- `errors`: lỗi theo field, không chứa stack trace.
- `trace_id`: ID dùng khi tra log.
- `retryable`: client có nên retry hay không.

Không trả exception message, SQL, hostname hoặc secret cho client.

## Header

- `Idempotency-Key`: command có side effect và có thể retry.
- `traceparent`: trace xuyên service theo W3C Trace Context.
- `X-Request-Id`: correlation fallback ở edge; không thay thế `traceparent`.
- `Retry-After`: nên trả với `429` và `503` khi biết thời gian chờ.

Trusted identity header như `X-User-Id` chỉ được nhận từ API Gateway trên mạng
nội bộ. Service không được tin header này nếu endpoint có thể truy cập trực tiếp.
