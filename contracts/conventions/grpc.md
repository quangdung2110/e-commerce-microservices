# gRPC Convention

## Ownership và package

- Provider sở hữu proto; consumer chỉ generate client từ cùng nguồn đó.
- Package MUST có bounded context và major version, ví dụ `ecommerce.product.v1`.
- Proto MUST khai báo cả `go_package` và `java_package`.
- Service name mô tả capability của provider, không đặt theo tên consumer.

## Kiểu dữ liệu

- Dùng Well-Known Types cho timestamp/duration.
- Dùng `ecommerce.common.v1.Money` cho tiền.
- Enum bắt đầu bằng `{ENUM_NAME}_UNSPECIFIED = 0`.
- Không tái sử dụng field number đã xóa; dùng `reserved` cho number và name.

## Failure và tải

Mỗi RPC MUST mô tả:

- deadline đề xuất;
- có an toàn để retry không;
- idempotency key nằm ở đâu;
- max batch size;
- các gRPC status consumer cần xử lý.

Không retry vô hạn. Consumer phải dùng deadline, exponential backoff có jitter và
retry budget.

## Kênh client

Quyết định gốc: [ADR-007](../decisions/ADR-007-grpc-channel-balancing-and-keepalive.md).

- Target dùng `dns:///host:port` và service config `round_robin` (grpc-go mặc định `passthrough` + một địa chỉ).
- Keepalive: ping sau 30 giây không có traffic, timeout 10 giây, không ping khi không có RPC. Provider đặt
  `permit-keep-alive-time` nhỏ hơn 30 giây (Product: 20 giây), nếu không sẽ trả `too_many_pings`.
- Provider đóng kết nối quá `max-connection-age` (Product: 5 phút, grace 30 giây); client kết nối lại và phân giải DNS lại.
- Không retry ở tầng kênh; retry theo [ADR-003](../decisions/ADR-003-deadline-propagation.md) và ADR của từng service.

## Lỗi

Quyết định gốc: [ADR-006](../decisions/ADR-006-grpc-error-details.md). Catalog của từng service ở `contracts/grpc/<path>/errors.yaml`.

Mọi status khác `OK` do mã ứng dụng của provider trả MUST mang `google.rpc.Status.details` (`grpc-status-details-bin`):

| Chi tiết | Khi nào | Nội dung |
|---|---|---|
| `google.rpc.ErrorInfo` | Luôn luôn | `domain` = package proto của service; `reason` = mã ổn định `^[A-Z][A-Z0-9_]{2,63}$`; metadata `retryable` = `"true"` hoặc `"false"` |
| `google.rpc.BadRequest` | `INVALID_ARGUMENT` | `field_violations[].field` là tên field của request |
| `google.rpc.RetryInfo` | `UNAVAILABLE`, `RESOURCE_EXHAUSTED` | `retry_delay`, cùng ý nghĩa với `Retry-After` ([ADR-005](../decisions/ADR-005-retry-after-on-throttling-and-unavailable.md) khi viết cho HTTP) |

Status do runtime gRPC hay mesh sinh (`DEADLINE_EXCEEDED`, `CANCELLED`, không kết nối được) không có chi tiết; consumer
MUST chấp nhận thiếu `ErrorInfo` và phân loại theo mã status.

Consumer dùng `reason` và `retryable`, không dùng `description`. Lỗi `retryable=false` không được retry. Breaker chỉ đếm lỗi
của provider (`UNAVAILABLE`, `DEADLINE_EXCEEDED`, `RESOURCE_EXHAUSTED`, `INTERNAL`, `UNKNOWN`); `INVALID_ARGUMENT`,
`UNAUTHENTICATED`, `PERMISSION_DENIED`, `NOT_FOUND` là lỗi của caller, được log kèm `reason` và không đếm vào breaker.
Kết quả nghiệp vụ vẫn nằm trong message phản hồi, không dùng status.

## Metadata

Caller gửi metadata chữ thường, giá trị ASCII in được:

| Metadata | Bắt buộc | Ghi chú |
|---|---|---|
| `x-service-identity` | Có | ServiceAccount name của caller; thiếu là `UNAUTHENTICATED`, ngoài allowlist là `PERMISSION_DENIED` ([service-identity.md](service-identity.md), [mesh §6](../../docs/mesh/runtime-contract.md)) |
| `x-request-id` | Có | Định dạng và quy tắc ở [tracing.md](tracing.md) |
| `traceparent`, `tracestate` | Khi có trace đến | Gửi tiếp, không bịa; `tracestate` ngoài ASCII in được làm RPC lỗi nên MUST bị bỏ |
| `x-correlation-id`, `x-causation-id` | Theo contract RPC | Chỉ khi RPC ghi header này là bắt buộc |

Deadline dùng cơ chế gốc của gRPC, đặt bằng timeout hiệu dụng của lời gọi ([deadlines.md](deadlines.md)).
