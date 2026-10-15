# ADR-004: Kiểm tra caller nội bộ bằng allowlist `X-Service-Identity`

- Status: Accepted
- Date: 2026-10-15

## Context

[Mesh runtime contract §6](../../docs/mesh/runtime-contract.md) quy định caller gửi `X-Service-Identity` và
service giữ allowlist làm lớp phòng thủ thứ hai sau `AuthorizationPolicy`. Khảo sát giao tiếp nội bộ
(2026-10-15) cho thấy ba nơi không theo quy định đó:

| Nơi | Hiện trạng |
|---|---|
| Product gRPC (`ProductService`) | Không có kiểm tra caller ở tầng ứng dụng, trong khi mọi route HTTP của Product đều có allowlist. Ngoài mesh, bất kỳ ai tới được cổng 9091 đều đọc được catalog. Cart cũng chưa gửi `x-service-identity`. Reflection bật mặc định nên ai cũng đọc được schema. |
| Review `/internal/v1/**` | Dùng bearer token tĩnh (`REVIEW_INTERNAL_*_TOKENS`) thay vì `X-Service-Identity`. Hai caller (PDP BFF, Search) phải giữ secret, xoay vòng token theo thứ tự, và khác mọi service khác. |
| Payment Order-facing routes | Hard-code `"order-service".equals(identity)` trong controller và trả `400 VALIDATION_FAILED` khi sai, trong khi Inventory, Order, Auth, User trả `401`/`403` từ allowlist cấu hình được. |

## Decision

1. Mọi route hoặc RPC nội bộ của một service MUST kiểm tên caller với **allowlist cấu hình được, tách theo
   nhóm route**. Tên lấy từ `X-Service-Identity` (gRPC: `x-service-identity`), là ServiceAccount name trong
   [`services.yaml`](../catalog/services.yaml).
2. Kết quả:

   | Tình huống | HTTP | gRPC |
   |---|---|---|
   | Thiếu hoặc rỗng | `401` `UNAUTHENTICATED` | `UNAUTHENTICATED` |
   | Không thuộc allowlist của nhóm | `403` `FORBIDDEN` | `PERMISSION_DENIED` |
   | Thuộc allowlist | xử lý | xử lý |

   Lỗi HTTP là `application/problem+json` có `code`, `retryable: false`, `Cache-Control: private, no-store`,
   không liệt kê allowlist. Caller coi `401`/`403` là lỗi cấu hình, không retry.
3. Allowlist rỗng của một nhóm từ chối mọi caller. Một route nội bộ không thuộc nhóm nào cũng từ chối mọi
   caller: thêm route phải đi kèm quyết định ai được gọi.
4. Quyết định dựa trên **path đã giải mã, bỏ `;param`**, không dựa trên URI thô.
5. Giá trị header là attribution, không phải bằng chứng danh tính. Mesh vẫn là nơi thực thi chính; kiểm tra ở
   ứng dụng không thay thế `AuthorizationPolicy` và không được dùng làm cơ sở để nới nó.
6. **Không dùng bearer token tĩnh** làm service identity: thêm secret phải xoay vòng và không khớp các service
   khác.
7. Route công khai (webhook có chữ ký, actuator) không áp dụng. Route của operator (`finance-ops`,
   `notification-ops`) có allowlist riêng theo contract của chúng.
8. Reflection của gRPC tắt mặc định; bật bằng profile `dev` hoặc biến môi trường khi cần `grpcurl`.

## Alternatives considered

- **Chỉ dựa vào mesh.** Không chọn: Compose không có mesh, và các service khác đã có lớp thứ hai.
- **Giữ bearer token của Review.** Không chọn: duy trì hai cơ chế, thêm secret cần xoay vòng; token tĩnh trong
  biến môi trường là rủi ro lớn hơn so với header attribution đứng sau mesh.
- **mTLS ở tầng ứng dụng.** Đã loại ở [Auth ADR-007](../../docs/auth/decisions/ADR-007-internal-caller-auth-delegated-to-mesh.md):
  ztunnel không chuyển chứng chỉ client nên không có gì để kiểm.
- **Thống nhất mã lỗi thành `400`.** Không chọn: `401`/`403` cho client biết lỗi nằm ở danh tính, đúng cách
  alert cấu hình ở Checkout và PDP đã dựa vào.

## Consequences

- Một cơ chế duy nhất ở mọi service; PDP BFF và Search bỏ secret Review (`PDP_REVIEW_SERVICE_TOKEN`,
  `REVIEW_INTERNAL_TOKEN`), Review bỏ `REVIEW_INTERNAL_*_TOKENS`.
- **Compose mất một lớp**: bearer token của Review là lớp xác thực duy nhất dùng secret ở môi trường dev không
  có mesh. Sau thay đổi, Review bảo vệ ngang các service khác (header giả được ở local, như đã ghi trong mesh §11).
- Thêm caller mới cho Product gRPC hoặc Payment phải sửa allowlist cấu hình, không phải code.
- Payment đổi `400` thành `401`/`403` cho identity sai; Order phân loại cả ba là từ chối bền vững như trước.

## Migration

| Service | Việc |
|---|---|
| Product | Interceptor allowlist cho `ProductService` (`product.internal.grpc-clients`), warm-up gửi identity, tắt reflection mặc định ([Product ADR-022](../../docs/product/decisions/ADR-022-grpc-caller-allowlist-and-reflection-off.md)) |
| Cart | Gửi `x-service-identity` (đã làm cùng [ADR-002](ADR-002-correlation-and-trace-propagation.md)) |
| Payment | `ServiceIdentityFilter` với `payment.identity.allowed-callers`; `401`/`403` ([Payment ADR-026](../../docs/payment/decisions/ADR-026-service-identity-allowlist-and-401-403.md)) |
| Review | Allowlist `pdp-callers`/`export-callers` thay token ([Review ADR-015](../../docs/review/decisions/ADR-015-service-identity-header-replaces-bearer-tokens.md)) |
| PDP BFF, Search | Bỏ bearer token Review ([PDP ADR-009](../../docs/product-detail-bff/decisions/ADR-009-service-identity-replaces-review-bearer-token.md), [Search ADR-024](../../docs/search/decisions/ADR-024-drop-review-bearer-and-outbound-metrics.md)) |

## Verification

- Mỗi service có test cho ba trường hợp (thiếu, ngoài allowlist, thuộc allowlist), allowlist rỗng, và
  (HTTP) path mã hoá hoặc có `;param`.
- `HttpContractIT` của Review và `PaymentControllerTest` cập nhật theo `X-Service-Identity` và `401`/`403`.
- Chạy `validate_docs.ps1` và lint OpenAPI của Payment, Review internal.
