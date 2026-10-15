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

## Metadata

Caller gửi metadata chữ thường, giá trị ASCII in được:

| Metadata | Bắt buộc | Ghi chú |
|---|---|---|
| `x-service-identity` | Có | ServiceAccount name của caller ([mesh §6](../../docs/mesh/runtime-contract.md)) |
| `x-request-id` | Có | Định dạng và quy tắc ở [tracing.md](tracing.md) |
| `traceparent`, `tracestate` | Khi có trace đến | Gửi tiếp, không bịa; `tracestate` ngoài ASCII in được làm RPC lỗi nên MUST bị bỏ |
| `x-correlation-id`, `x-causation-id` | Theo contract RPC | Chỉ khi RPC ghi header này là bắt buộc |
