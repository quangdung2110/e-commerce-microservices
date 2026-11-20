---
doc_id: auth-adr-007
service: auth
status: accepted
date: 2026-10-09
decision_owners:
  - Auth Service team
supersedes: null
evidence:
  - docs/mesh/runtime-contract.md
  - contracts/http/auth/internal/v1/openapi.yaml
  - docs/user/decisions/ADR-008-internal-caller-auth-delegated-to-mesh.md
  - go/services/auth-service/internal/api/middleware/service_identity.go
  - go/services/auth-service/internal/api/router/internal_router.go
  - go/services/auth-service/test/unit/api/internal_api_test.go
---

# ADR-007 — Listener internal xác thực caller qua service mesh

## Status

`Accepted`. Cùng quyết định với [User ADR-008](../../user/decisions/ADR-008-internal-caller-auth-delegated-to-mesh.md),
áp dụng cho Auth.

## Context

Listener internal (cổng 8081) trả email người nhận và, với template OTP/reset, cả secret
cần gửi. Bản đầu tự kết thúc TLS trong ứng dụng, bắt `RequireAndVerifyClientCert`, so
Common Name của chứng chỉ client với `internal.allowed_client_cn`, và production không khởi
động nếu thiếu `tls_cert_file`/`tls_key_file`/`client_ca_file`. Rate limit theo IP client
(`internal-delivery:ip`, 600/phút).

[Mesh runtime contract](../../mesh/runtime-contract.md) quy định khác: Istio ambient (ztunnel
và waypoint) kết thúc mTLS và thực thi `AuthorizationPolicy` theo principal + path; ứng dụng
**không nhìn thấy chứng chỉ caller**. Caller gửi `X-Service-Identity` cho log, audit và
allowlist defense-in-depth. OpenAPI internal đã nói đúng điều đó, còn code ngược lại. Hệ
quả sau mesh: kết nối tới Auth là HTTP thường, `Request.TLS` luôn rỗng, nên hoặc mọi lời gọi
của Notification bị `401`, hoặc production không khởi động được; và vì mọi pod Notification
đến từ vài địa chỉ nguồn, budget theo IP là một bucket dùng chung cho cả Notification.

## Decision

Auth không tự kết thúc TLS và không kiểm chứng chỉ client. Việc cho phép
`notification-service` chạm vào hai path internal thuộc về mesh
(`notification-resolves-auth-delivery-content`, `notification-resolves-auth-contact`). Ứng
dụng chỉ giữ lớp defense-in-depth giống User, Cart, Inventory và Order:

- `X-Service-Identity` bắt buộc: thiếu header là `401 UNAUTHORIZED`; giá trị ngoài allowlist
  `internal.allowed_callers` (mặc định `notification-service`) là `403 FORBIDDEN`.
- Allowlist rỗng là lỗi cấu hình lúc khởi động khi listener internal bật.
- Rate limit theo cặp (service gọi, id tài nguyên ở path) thay vì theo IP:
  `internal-delivery` theo `delivery_id`, `internal-contact` theo `user_id`; mặc định
  `internal_per_resource` 120/phút. Caller bị từ chối ở bước identity không tiêu budget.

Header không phải bằng chứng authorization duy nhất. Docker Compose không có mesh, nên ở đó
header không được xác thực, đúng như mesh contract đã ghi.

## Alternatives considered

- Giữ mTLS ở tầng ứng dụng và cấu hình TLS hai lớp: tăng phức tạp (cert thứ hai ngoài chứng
  chỉ workload của istiod), ztunnel không chuyển client cert nên không có gì để kiểm tra.
- Chỉ tin header, bỏ mesh policy: trái mesh contract; ai chạm được pod đều giả được.
- Bearer token tĩnh như Review: thêm secret phải xoay, không khớp các service khác.
- Rate limit toàn cục theo caller: bảo vệ tốt hơn trước việc liệt kê, nhưng một con số chung
  cho mọi pod Notification buộc phải khớp tốc độ xả backlog của Notification
  ([Notification 06](../../notification/06-backpressure.md) §3, tới hàng chục req/s) và
  khiến việc scale pod tác động vào budget. Chống liệt kê là việc của policy mesh, vì chỉ
  một principal được chạm path.

## Consequences

Biến `AUTH_INTERNAL_TLS_CERT_FILE`, `AUTH_INTERNAL_TLS_KEY_FILE`,
`AUTH_INTERNAL_CLIENT_CA_FILE` và `AUTH_INTERNAL_ALLOWED_CLIENT_CN` bị bỏ; thay bằng
`AUTH_INTERNAL_ALLOWED_CALLERS`. `security.rate_limit.internal_per_client` (600/phút theo IP)
đổi thành `internal_per_resource` (120/phút theo caller và tài nguyên). Notification đã gửi
`X-Service-Identity: notification-service` trên mọi lời gọi nên không phải đổi; các cấu hình
`NOTIFICATION_AUTH_TLS_*` của nó trở thành thừa và thuộc phạm vi dọn dẹp riêng của
Notification (audit N3; đã bỏ hẳn ngày 2026-10-10, [Notification D-21](../../notification/decisions.md)). Vì budget theo tài nguyên, không có trần tổng cho Notification; trần
đó do rate limit phía client của Notification và policy mesh đảm nhiệm.

## Migration impact

Code: `config`, `middleware/service_identity.go`, `middleware/ratelimit.go`,
`router/internal_router.go`, `cmd/server/main.go`. Config: `application.yaml`,
`.env.example`. Contract: OpenAPI internal (`403`, header `X-Service-Identity`,
`securitySchemes`). Deployment: bỏ cert/CA mount của Auth khỏi manifest; Docker Compose
không đổi vì chưa từng bật mTLS.

## Verification

`test/unit/api/internal_api_test.go`: thiếu header `401`, service khác `403`, Notification
`200`, caller bị từ chối không tiêu budget, budget theo (caller, tài nguyên) cho cả hai route.
`test/unit/config/config_test.go`: production không đòi chứng chỉ, allowlist rỗng bị từ chối,
override từ env, hai cổng phải khác nhau.
