# Service identity

Quyết định gốc: [ADR-004](../decisions/ADR-004-service-identity-allowlist.md).

Mesh là nơi thực thi chính ([mesh §5](../../docs/mesh/runtime-contract.md)); service kiểm thêm một lớp bằng
tên caller.

| | HTTP | gRPC |
|---|---|---|
| Caller gửi | `X-Service-Identity: <ServiceAccount>` | metadata `x-service-identity` |
| Thiếu hoặc rỗng | `401` `UNAUTHENTICATED` | `UNAUTHENTICATED` |
| Không thuộc allowlist | `403` `FORBIDDEN` | `PERMISSION_DENIED` |

Quy tắc:

1. Allowlist cấu hình được, tách theo nhóm route; rỗng hoặc route không thuộc nhóm nào thì từ chối mọi caller.
2. Quyết định theo path đã giải mã, bỏ `;param`.
3. Lỗi HTTP là `application/problem+json` với `code`, `retryable: false`, `Cache-Control: private, no-store`;
   không liệt kê allowlist. Caller coi là lỗi cấu hình và không retry.
4. Giá trị header là attribution, không phải bằng chứng danh tính.
5. Không dùng bearer token tĩnh làm service identity.
6. Route công khai (webhook có chữ ký, actuator) không áp dụng.
7. gRPC reflection tắt mặc định, chỉ bật ở profile `dev` hoặc bằng biến môi trường.
