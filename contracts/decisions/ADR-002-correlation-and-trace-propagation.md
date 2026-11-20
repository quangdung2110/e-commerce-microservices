# ADR-002: Chuẩn truyền trace và correlation giữa các service

- Status: Accepted
- Date: 2026-10-15

## Context

[Tracing và Correlation](../conventions/tracing.md) đã tách `traceparent`,
`correlation_id` và `idempotency_key`, và [HTTP Convention](../conventions/http.md)
đã liệt kê `traceparent` và `X-Request-Id`. Convention chỉ nói "MUST truyền trace
context khi có", chưa nói truyền bằng header nào, định dạng ra sao, và ai chịu
trách nhiệm khi không có request gốc. Kết quả khảo sát giao tiếp nội bộ
(2026-10-15) cho thấy các service đang làm khác nhau:

| Hiện trạng | Bằng chứng |
|---|---|
| Chỉ Checkout và Product Detail BFF (Java), cùng Auth, User, Notification (Go) có thư viện tracing. Order, Inventory, Payment, Promotion, Product, Review, Search (Java) và Cart (Go) thì không. | `pom.xml`, `go.mod` |
| Checkout gửi `X-Request-Id` cho Cart, User, Product, Promotion, Order nhưng `X-Correlation-ID` + `X-Causation-ID` cho Inventory. | `docs/checkout/interfaces.md`, `InventoryClient` |
| Order gửi `X-Correlation-ID` (Workflow ID) cho Inventory và Payment, không gửi gì cho Promotion, và không bao giờ gửi `traceparent` hay `X-Request-Id`. Phía nhận, Order không đọc `X-Request-Id` hay `traceparent`, nên log của Order không nối được với log của Checkout. | `HttpRemote`, `HttpGateways` |
| Cart gọi Product qua gRPC mà không gắn metadata nào. Cart nhận `X-Request-Id` nhưng chỉ giữ trong Gin context, không đưa vào `context.Context` của lời gọi gRPC. | `product/client.go`, `middleware.go` |
| Định dạng `X-Request-Id` khác nhau: Checkout `^[A-Za-z0-9._:-]{8,128}$`, Cart chỉ kiểm độ dài 8–128, Product (log gRPC) chỉ nhận `[a-zA-Z0-9_-]{1,128}`. | `RequestIds`, `middleware.go`, `ProductRpcTiming` |
| Payment bắt buộc `X-Correlation-Id` (1–128), Inventory bắt buộc `X-Correlation-ID` (8–128). | contract Payment, Inventory |

Hệ quả: một request đi Gateway → Checkout → Order → Inventory/Payment không thể
tra theo một ID duy nhất, và chuỗi Cart → Product đứt ở Cart. Mesh
([runtime-contract §9](../../docs/mesh/runtime-contract.md)) giao việc propagate
trong ứng dụng cho service, nên mesh không bù được chỗ đứt này.

## Decision

### 1. Bốn định danh, mỗi cái một nhiệm vụ

| Định danh | HTTP header | gRPC metadata | Ý nghĩa | Định dạng |
|---|---|---|---|---|
| Trace kỹ thuật | `traceparent`, `tracestate` | `traceparent`, `tracestate` | W3C Trace Context | theo W3C |
| Request ID | `X-Request-Id` | `x-request-id` | ID của yêu cầu gốc, đi nguyên vẹn qua mọi hop đồng bộ | `^[A-Za-z0-9._:-]{8,128}$` |
| Correlation ID | `X-Correlation-Id` | `x-correlation-id` | ID của business flow (saga/workflow) | cùng định dạng Request ID |
| Causation ID | `X-Causation-Id` | `x-causation-id` | ID của command hoặc event gây ra command này | cùng định dạng |

`Idempotency-Key` không phải định danh correlation và không được dùng thay thế.

Tên header không phân biệt hoa thường. Tài liệu viết `X-Correlation-Id` và
`X-Causation-Id`; các contract hiện ghi `X-Correlation-ID`/`X-Causation-ID` là
cùng header và không cần đổi hàng loạt.

Request ID và Correlation ID tách nhau vì vòng đời khác nhau. Request ID chết khi
yêu cầu gốc kết thúc. Correlation ID sống qua nhiều request và nhiều lần thử lại
(Workflow ID của Order). Với chuỗi chỉ đồng bộ như Checkout Preview, hai giá trị
có thể trùng nhau; Inventory đang nhận giá trị đó qua `X-Correlation-Id`.

### 2. Quy tắc cho bên gọi

1. **Mọi** lời gọi HTTP/gRPC nội bộ MUST mang `X-Request-Id`.
2. **Lời gọi trong phạm vi một request đến** (Cart → Product, Order đọc Payment
   client-data khi đang phục vụ một request của Checkout): gửi tiếp `X-Request-Id` đã chuẩn hoá của
   request đó. Nếu header đến không có hoặc không khớp định dạng, dùng trace ID
   32 ký tự hex lấy từ `traceparent` hợp lệ; nếu cũng không có, sinh UUID. Giá trị
   sai không bao giờ làm request thất bại.
3. **Trace context**: service có thư viện tracing gửi `traceparent` của span hiện
   tại. Service chưa có thư viện tracing gửi tiếp `traceparent` và `tracestate`
   nhận được (pass-through). Service MUST NOT bịa `traceparent` khi không có trace
   đến và không có tracer, vì ID đó không tồn tại ở backend nào.
4. **Lời gọi do công việc bất đồng bộ sinh ra** (Temporal activity, outbox relay,
   consumer Kafka, scheduler) không có request gốc. Bên gọi tự phát:
   - `X-Request-Id`: UUID mới cho **mỗi lần gửi**;
   - `X-Correlation-Id`: ID ổn định của business flow, khi contract provider định
     nghĩa header này (Workflow ID với Order);
   - `Idempotency-Key` và `X-Causation-Id`: giữ nguyên khi replay cùng command.

   Request ID khác nhau giữa các lần thử của cùng một command là chủ ý; danh tính
   nghiệp vụ của command nằm ở Correlation ID và Idempotency-Key.
5. Công việc dùng chung nhiều caller (singleflight, batch) chỉ mang Request ID
   của caller khởi tạo. ID của các caller còn lại không được gửi đi.
6. Request ID và Correlation ID chỉ được vào log và `trace_id` của problem. Không
   được dùng làm label của metric.
7. gRPC dùng metadata tương ứng ở bảng trên, chữ thường, giá trị ASCII.
   `x-service-identity` vẫn theo [mesh §6](../../docs/mesh/runtime-contract.md).

### 3. Quy tắc cho bên nhận

1. Chấp nhận các header ở mục 1, không phân biệt hoa thường. `X-Request-Id`
   thiếu hoặc sai định dạng thì tự sinh UUID; không trả `400`. Chỉ khi contract
   của provider ghi header là bắt buộc (`X-Correlation-Id` của Payment và
   Inventory) mới được từ chối khi thiếu.
2. Mọi dòng log của request MUST có `request_id` và, nếu có `traceparent` hợp lệ,
   `trace_id`.
3. Trả `X-Request-Id` trong response để bên gọi đối chiếu.
4. Service chưa có thư viện tracing vẫn phải tách `trace_id` từ `traceparent` để
   ghi log và phải truyền tiếp (mục 2.3) trên lời gọi đi của chính nó.

### 4. Hai mức tuân thủ

- **Mức 1 – pass-through**: truyền header, ghi log. Không cần collector hay SDK.
  Đây là mức tối thiểu bắt buộc theo ADR này.
- **Mức 2 – tracing đầy đủ**: SDK OpenTelemetry/Micrometer sinh span và export
  OTLP, theo [mesh §9](../../docs/mesh/runtime-contract.md) và
  [gateway §8](../../docs/gateway/runtime-contract.md). Mức 2 thoả mãn mọi quy tắc
  của mức 1 và là lộ trình riêng của từng service.

### 5. Nơi ghi quy ước

[`conventions/tracing.md`](../conventions/tracing.md) là bảng chuẩn. Tài liệu
`interfaces.md` của từng service chỉ ghi phần khác biệt và dẫn về đây.

## Alternatives considered

- **Chỉ dựa vào `traceparent`, bỏ `X-Request-Id`.** Không chọn: ba service Java
  chưa có tracing nên không có ID nào để tra log; Checkout, Cart, Notification,
  PDP BFF và các contract đã dùng `X-Request-Id`.
- **Thêm OpenTelemetry vào mọi service trước.** Không chọn làm điều kiện: tốn
  nhiều công và cần collector, trong khi lỗi đứt chuỗi sửa được bằng truyền header.
  Giữ làm mức 2.
- **Sinh `traceparent` giả ở Order và Cart cho đủ.** Không chọn: tạo trace ID
  không có span nào ở backend, gây hiểu nhầm khi điều tra.
- **Lưu `traceparent`/`request_id` của request gốc để Temporal activity dùng lại.**
  Không chọn trong đợt này: cần migration bảng submission, đổi digest của workflow
  input và dùng span link mới đúng nghĩa. Ghi vào lộ trình mức 2.
- **Một ID dùng chung cho request và correlation.** Không chọn: saga sống lâu hơn
  request, còn retry sinh nhiều request cho cùng một flow.

## Consequences

- Log của Checkout, Order và Cart tra chéo được bằng `request_id`; chuỗi Cart →
  Product và Checkout → Order → Payment có ID chung khi lời gọi nằm trong phạm vi
  một request.
- Chuỗi từ Temporal activity của Order không nối được ngược về Checkout bằng
  `X-Request-Id`. Nối bằng `X-Correlation-Id` (Workflow ID, chứa `checkout_token`).
  Đây là giới hạn đã biết của mức 1.
- Bên nhận phải dọn log: Product (gRPC) cần nhận `.` và `:` trong request ID.
- Mỗi request đến Order và Cart thêm một lần kiểm biểu thức chính quy; không đáng kể.
- Đổi quy tắc phải sửa đồng thời `conventions/tracing.md`, ADR này và
  `interfaces.md` của các service liên quan.

## Migration

| Service | Việc | Đợt này |
|---|---|---|
| Order | Filter đọc `X-Request-Id`/`traceparent`, đưa vào MDC, trả `X-Request-Id`; `HttpRemote` gửi `X-Request-Id` (và pass-through `traceparent`, `tracestate` khi có) | Có |
| Cart | `reqctx` + middleware đưa request ID, `traceparent`, `tracestate` vào `context.Context`; interceptor gRPC gắn metadata; siết định dạng `X-Request-Id` | Có |
| Checkout, Product Detail BFF | `InventoryClient` gửi thêm `X-Request-Id` | Có |
| Product (gRPC) | Regex log nhận `.` và `:`, độ dài 8–128 ([Product ADR-021](../../docs/product/decisions/ADR-021-request-correlation-and-caller-deadline.md)) | Có (2026-10-15) |
| Inventory, Payment, Promotion, Review, Search | Ghi `request_id`/`trace_id` vào log, trả `X-Request-Id` ([Inventory ADR-030](../../docs/inventory/decisions/ADR-030-request-correlation-and-caller-deadline.md), [Payment ADR-025](../../docs/payment/decisions/ADR-025-request-correlation-and-caller-deadline.md), [Promotion ADR-022](../../docs/promotion/decisions/ADR-022-request-correlation-and-caller-deadline.md), [Review ADR-014](../../docs/review/decisions/ADR-014-request-correlation-and-caller-deadline.md), [Search ADR-023](../../docs/search/decisions/ADR-023-request-correlation-and-caller-deadline.md)); Search (export, batch) áp quy tắc 2.4; client Product của Promotion gửi request ID, trace context và timeout | Có (2026-10-15) |
| Order | Trả `trace_id` trong problem; lưu context request gốc cho activity (mức 2) | Sau |

## Verification

- Order: test `HttpRemote` kiểm header gửi đi trong cả hai chế độ (có request
  đến, và chạy trong worker không có request); test filter kiểm giá trị sai bị
  thay, `traceparent` sai bị bỏ, header trả về.
- Cart: test client gRPC với server thật kiểm metadata `x-request-id`,
  `traceparent`, `tracestate`, `x-service-identity` tới server; test router kiểm
  request ID sai định dạng bị thay.
- Checkout: `PreviewApiTest` kiểm Inventory nhận cả `X-Request-Id` và
  `X-Correlation-Id`.
- Chạy lại `contracts/tools/validate-contracts.ps1` và `validate_docs.ps1`.
