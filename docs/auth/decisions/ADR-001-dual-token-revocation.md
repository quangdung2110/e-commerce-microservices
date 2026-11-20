---
doc_id: auth-adr-001
service: auth
status: accepted
date: 2026-08-27
decision_owners:
  - Auth Service team
supersedes: null
evidence:
  - go/services/auth-service/internal/domain/service/revoker.go
  - go/services/auth-service/internal/infrastructure/redis/revocation_store.go
  - go/services/gateway-auth/internal/infrastructure/redis/revocation_store.go
  - go/services/auth-service/test/integration/http_flow_test.go
---

# ADR-001 — Dual token and access-token revocation

## Context

Access token cần sống ngắn để giảm rủi ro, nhưng logout/xóa account/đổi mật khẩu cần có
cách vô hiệu token trước khi TTL kết thúc.

## Decision

Dùng JWT access token sống ngắn với `jti`, kết hợp opaque refresh token server-side.
Logout một thiết bị denylist `jti`; logout all, đổi mật khẩu hoặc xóa account dùng
mốc `revoked_before` của user và Gateway so sánh `iat`. Cả hai tín hiệu nằm trong
namespace Redis `auth:revocation:*` dùng chung với `gateway-auth`; tên key đầy đủ
do [Gateway runtime contract](../../gateway/runtime-contract.md#6-quy-ước-xác-thực)
quy định và được nhắc lại ở [Data Design](../data.md).

## Alternatives considered

- JWT hoàn toàn stateless: đơn giản nhưng không revoke ngay được.
- Opaque access token introspection: revoke trực tiếp nhưng Gateway phải gọi Auth
  cho mỗi request.
- Denylist mọi access token: xử lý được revoke nhưng tăng memory/lookup rộng.

## Consequences

Gateway cần Redis check theo policy và token claim phải nhất quán. Redis outage
trở thành security/availability trade-off cần runbook.

## Migration impact and verification

Đã implement: `Revoker` ghi `denylist:{jti}` và `revoked_before` (Unix seconds), `gateway-auth`
đọc cả hai trong một pipeline và fail closed khi Redis lỗi. Hai chi tiết đã chốt khi
implement:

- TTL của cả hai key là access TTL + clock skew (15m30s), không phải "thời gian còn lại đến
  `exp`": Gateway chỉ chuyển `jti` chứ không chuyển `exp`, nên Auth dùng cận trên. Entry có thể
  sống lâu hơn token vài phút, không bao giờ ngắn hơn.
- `revoked_before` có độ phân giải 1 giây và Gateway từ chối `iat <= revoked_before`. Token cấp
  trong cùng giây với lần thu hồi bị từ chối: đây là chủ ý, vì `iat` cũng chỉ có độ phân giải
  giây nên không thể phân biệt token cấp ngay trước hay ngay sau lúc thu hồi, và bên an toàn
  là từ chối cả hai. Logout-all, đổi mật khẩu, đổi email và xóa account đều buộc đăng nhập lại,
  nên chỉ client đăng nhập lại trong vòng một giây mới gặp một lần `401` rồi refresh.

Test: integration `http_flow_test.go` (denylist `jti`, revoke marker, fail closed); lỗi Redis
sau commit xem [ADR-006](./ADR-006-publish-before-revocation-marker.md).
