---
doc_id: user-adr-008
service: user
status: accepted
date: 2026-10-09
decision_owners:
  - User Service team
supersedes: null
evidence:
  - docs/mesh/runtime-contract.md
  - contracts/http/user/v1/openapi.yaml
  - go/services/user-service/internal/api/middleware/service_identity.go
---

# ADR-008 — Cổng internal xác thực caller qua service mesh

## Bối cảnh

Cổng internal `:8083` trả recipient name, phone và street address cho Checkout,
nhận `user_id` từ caller thay vì từ identity đã xác thực. Bản đầu tiên tự kết
thúc TLS trong ứng dụng, bắt `RequireAndVerifyClientCert`, so Common Name của
chứng chỉ client, và production không khởi động nếu thiếu chứng chỉ.

[Mesh runtime contract](../../mesh/runtime-contract.md) quy định khác: Istio ambient
(ztunnel và waypoint) kết thúc mTLS và thực thi `AuthorizationPolicy` theo principal
+ path; ứng dụng **không nhìn thấy identity caller đã xác minh**. Caller gửi
`X-Service-Identity` để log/audit/allowlist defense-in-depth. OpenAPI User v1 đã
nói đúng điều đó, còn code thì ngược lại. Hệ quả: sau mesh, kết nối tới ứng dụng là
HTTP thường nên `Request.TLS` luôn rỗng; hoặc mọi lời gọi của Checkout bị `401`, hoặc
production không khởi động được.

## Quyết định

User không tự kết thúc TLS và không kiểm chứng chỉ client. Việc cho phép
`checkout-service` chạm vào `GET /internal/v1/users/*/addresses/*/checkout-snapshot`
thuộc về mesh. Ứng dụng chỉ giữ lớp defense-in-depth giống Cart, Inventory và
Order:

- `X-Service-Identity` bắt buộc: thiếu header là `401 UNAUTHORIZED`; giá trị ngoài
  allowlist `internal.allowed_callers` (mặc định `checkout-service`) là `403 FORBIDDEN`.
- Allowlist rỗng là lỗi cấu hình lúc khởi động khi listener internal bật.
- Rate limit theo cặp (service gọi, `user_id` ở path) thay vì theo IP. Sau waypoint
  mọi pod Checkout đến từ vài địa chỉ nguồn, nên budget theo IP là một bucket dùng
  chung cho cả nền tảng (từng gây `429` rồi Checkout trả `503`).

Header không phải bằng chứng authorization duy nhất. Docker Compose không có mesh, nên
ở đó header không được xác thực — đúng như mesh contract đã ghi.

## Các phương án đã cân nhắc

- Giữ mTLS ở tầng ứng dụng và cấu hình TLS hai lớp: tăng phức tạp (cert thứ hai ngoài
  chứng chỉ workload của istiod), ztunnel không chuyển client cert nên không có gì để
  kiểm tra.
- Chỉ tin header, bỏ mesh policy: trái mesh contract; ai chạm được pod đều giả được.
- Bearer token tĩnh như Review: thêm secret phải xoay, không khớp các service khác.

## Hệ quả

Biến `USER_INTERNAL_TLS_*` và `USER_INTERNAL_ALLOWED_CLIENT_CN` bị bỏ; thay bằng
`USER_INTERNAL_ALLOWED_CALLERS`. `security.rate_limit.internal_per_client` (600/phút theo
IP) đổi thành `internal_per_user` (120/phút theo caller và user). Checkout đã gửi
`X-Service-Identity: checkout-service` nên không phải đổi. Auth và Notification còn dùng
cùng mô hình mTLS ở tầng ứng dụng; chúng nằm ngoài quyết định này.

## Xác minh

Integration test: thiếu header `401`, service khác `403`, `checkout-service` `200`,
address của user khác `404`, và hai user khác nhau không dùng chung budget. Unit test
cấu hình: production không đòi chứng chỉ, allowlist rỗng bị từ chối.
