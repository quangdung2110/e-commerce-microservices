# ADR-006: Chi tiết lỗi gRPC bằng `google.rpc.ErrorInfo`

- Status: Accepted
- Date: 2026-10-15

## Context

HTTP có `application/problem+json` với `code`, `retryable` và `trace_id`
([http.md](../conventions/http.md)); consumer quyết định retry và đếm vào circuit breaker dựa trên mã ổn định.
gRPC chưa có gì tương đương: [grpc.md](../conventions/grpc.md) chỉ yêu cầu RPC "mô tả các gRPC status consumer
cần xử lý". `ProductService` (RPC gRPC duy nhất, consumer là Cart và Checkout) trả `INVALID_ARGUMENT`,
`UNAUTHENTICATED`, `PERMISSION_DENIED` với một dòng `description`, và để lỗi cơ sở dữ liệu thoát ra thành `UNKNOWN`
không có thông tin.

Hệ quả quan sát được ở consumer:

| Consumer | Hiện trạng |
|---|---|
| Cart (Go) | Mọi lỗi gRPC thành `PRODUCT_UNAVAILABLE` `retryable=true` và đếm vào breaker, kể cả khi Product từ chối vì Cart thiếu `x-service-identity` hay gửi `sku_id` sai. Cấu hình sai trông giống Product sập và còn làm breaker mở. |
| Checkout (Java) | Phân loại theo mã gRPC (`INVALID_ARGUMENT` → 400, `UNAUTHENTICATED` → 401...), `UNKNOWN` thành 503. Không biết lý do, nên log không phân biệt được "danh tính bị từ chối" với "batch quá lớn". |

Product mới có hai RPC nên chưa đau, nhưng gRPC tiếp theo sẽ lặp lại cùng khoảng trống.

## Decision

1. Mọi status khác `OK` do **mã ứng dụng** của provider trả MUST mang `google.rpc.Status.details` (trường
   `grpc-status-details-bin`) với một `google.rpc.ErrorInfo`:
   - `domain` = package proto của service, ví dụ `ecommerce.product.v1`;
   - `reason` = mã ổn định `^[A-Z][A-Z0-9_]{2,63}$`, không bao giờ tái sử dụng cho ý khác;
   - `metadata` MUST có `retryable` = `"true"` hoặc `"false"`; các khoá khác là `lower_snake_case`, giá trị ngắn,
     không chứa PII hay secret.
2. Chi tiết bổ sung theo mã:

   | Status | Chi tiết thêm |
   |---|---|
   | `INVALID_ARGUMENT` | `google.rpc.BadRequest` với `field_violations[].field` là tên field của request |
   | `UNAVAILABLE`, `RESOURCE_EXHAUSTED` do ứng dụng trả | `google.rpc.RetryInfo.retry_delay`, cùng ý nghĩa và giá trị mặc định (2 giây) với `Retry-After` của [ADR-005](ADR-005-retry-after-on-throttling-and-unavailable.md) |

3. Status do runtime gRPC, proxy hay mesh sinh (`DEADLINE_EXCEEDED`, `CANCELLED`, `UNAVAILABLE` khi không kết nối được)
   không có chi tiết. Consumer MUST chấp nhận thiếu `ErrorInfo` và phân loại theo mã status: `UNAVAILABLE`,
   `DEADLINE_EXCEEDED` có thể retry; còn lại thì không.
4. Mỗi service gRPC liệt kê mọi `reason` trong `contracts/grpc/<path>/errors.yaml` (reason, status, retryable, mô tả).
   Thêm `reason` là tương thích; đổi tên hoặc bỏ là breaking.
5. Consumer dùng `reason` và `retryable`, không dùng `description` (văn bản tự do, không phân tích, không hiển thị cho
   người dùng cuối). Một lỗi `retryable=false` không được retry. Breaker chỉ đếm lỗi của provider: `UNAVAILABLE`,
   `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`, `INTERNAL`, `UNKNOWN`. `INVALID_ARGUMENT`, `UNAUTHENTICATED`,
   `PERMISSION_DENIED`, `NOT_FOUND` chứng tỏ provider trả lời đúng; chúng không đếm vào breaker và được log ở mức
   lỗi kèm `reason` vì là lỗi của caller.
6. Kết quả nghiệp vụ vẫn nằm trong message phản hồi (`oneof not_found`, `unavailable` của `GetSku`), không dùng status.
   Status chỉ cho lỗi giao thức, hạ tầng và lỗi của caller.

## Product catalog (khởi đầu)

| Status | `reason` | `retryable` | Khi nào |
|---|---|---|---|
| `UNAUTHENTICATED` | `SERVICE_IDENTITY_MISSING` | false | Thiếu `x-service-identity` ([ADR-004](ADR-004-service-identity-allowlist.md)) |
| `PERMISSION_DENIED` | `SERVICE_IDENTITY_NOT_ALLOWED` | false | Caller ngoài `product.internal.grpc-clients` |
| `INVALID_ARGUMENT` | `SKU_ID_INVALID` | false | `sku_id` không phải UUID |
| `INVALID_ARGUMENT` | `BATCH_TOO_LARGE` | false | Quá 100 `sku_ids` (`metadata.max_batch_size`) |
| `UNAVAILABLE` | `CATALOG_STORE_UNAVAILABLE` | true | Product không đọc được cơ sở dữ liệu |
| `INTERNAL` | `INTERNAL_ERROR` | false | Lỗi không dự kiến; chi tiết chỉ có trong log |

## Alternatives considered

- **Chỉ dùng status và `description`.** Không chọn: consumer phải so khớp chuỗi tự do, vốn thay đổi không báo trước.
- **Trailer metadata tự đặt (`x-error-code`).** Không chọn: `ErrorInfo` là chuẩn của Google API, có thư viện sẵn ở Go
  và Java, và tương thích với Envoy/Istio khi chúng đọc `grpc-status-details-bin`.
- **Nhét lỗi vào response message.** Không chọn: đổi proto cho mọi RPC và lẫn lộn kết quả nghiệp vụ với lỗi hạ tầng.
- **Dùng `google.rpc.Status` trong proto của service.** Không chọn: trailer chuẩn đã mang đúng thông tin mà không đổi proto.

## Consequences

- Không đổi proto hay stub; compatible với client cũ, vốn bỏ qua chi tiết.
- Cart thôi coi lỗi cấu hình là Product sập: `PERMISSION_DENIED`/`UNAUTHENTICATED`/`INVALID_ARGUMENT` trả
  `PRODUCT_UNAVAILABLE` với `retryable=false` (503 không kèm `Retry-After`, theo ADR-005) và không làm breaker mở.
- Checkout log `reason` cho mọi lỗi Product; mapping status sang lỗi nội bộ không đổi.
- Product phải bắt `DataAccessException` thành `UNAVAILABLE` thay vì `UNKNOWN`.

## Verification

- Product: test từng dòng catalog (status, reason, `retryable`, `BadRequest`, `RetryInfo`), và test chứng minh mã trong
  Java khớp `errors.yaml`.
- Cart: test client với server giả trả từng loại lỗi: phân loại, `retryable`, và breaker không mở khi lỗi do caller.
- Checkout: test `translate` đọc `reason` và vẫn thiếu `ErrorInfo` thì rơi về phân loại theo mã.
