# gateway-auth

Verifier JWT tại edge. Traefik gọi `/verify` qua ForwardAuth trước mỗi request tới route
cần danh tính; `2xx` cho request đi tiếp kèm bốn header danh tính, mọi status khác được
trả nguyên về client. Service không phát token, không giữ private key và chỉ **đọc**
trạng thái revoke do `auth-service` ghi.

Thiết kế: [docs/gateway/design.md](../../../docs/gateway/design.md#xác-thực-và-phân-quyền).
Contract bắt buộc: [runtime-contract.md mục 6](../../../docs/gateway/runtime-contract.md#6-quy-ước-xác-thực).

## Hành vi

| Bước | Quy tắc | Khi sai |
|---|---|---|
| Bearer token | `Authorization: Bearer <jwt>` | `401 UNAUTHORIZED` |
| Chữ ký và thuật toán | Chỉ thuật toán trong `AUTH_JWT_ALLOWED_ALGORITHMS` (hiện chỉ hỗ trợ RS256); key chọn theo `kid` từ JWKS | `401 TOKEN_INVALID` |
| `iss`, `aud`, `exp`, `nbf`, `iat` | Khớp tuyệt đối issuer/audience; dung sai đồng hồ `jwt.leeway` | `401 TOKEN_INVALID` |
| Tập claim | Đúng `iss aud sub jti sid roles iat nbf exp`, không thiếu không thừa; `sub`, `sid` là UUID; `roles` là mảng chuỗi | `401 TOKEN_INVALID` |
| Revoke | `auth:revocation:denylist:{jti}` tồn tại, hoặc `iat` ≤ `auth:revocation:user:{sub}:revoked_before` | `401 TOKEN_REVOKED` |
| Phụ thuộc | Redis lỗi, JWKS chưa từng lấy được, cache quá `max_stale`, hoặc `kid` lạ khi lần làm mới gần nhất thất bại | `503 DEPENDENCY_UNAVAILABLE` + `Retry-After` |

Thành công trả `200` với `X-User-Id` (`sub`), `X-User-Roles` (`roles`, phân tách dấu phẩy),
`X-User-Session-Id` (`sid`), `X-Token-Jti` (`jti`). Lỗi là `application/problem+json` với
`code` ổn định; `instance` không bao giờ chứa query string. Token không bao giờ được log.

### Cache JWKS

- Làm mới mỗi `refresh_interval`, single-flight, giới hạn `fetch_timeout`.
- `kid` lạ kích hoạt làm mới ngay, nhưng không dày hơn `min_refresh_interval`.
- Key biến mất khỏi JWKS được giữ thêm `retired_key_grace` (≥ access-token TTL của Auth).
- Auth/JWKS không trả lời thì vẫn verify bằng cache tới `max_stale`; quá hạn thì fail closed.
- Kênh Redis `auth:jwks:update`: `key_retirement` + `kid` xoá ngay key đó; `key_rotation`
  buộc lần tra tiếp theo làm mới; `kid` rỗng hoặc `all` xoá toàn bộ cache. Message thiếu
  `type` hợp lệ bị bỏ qua.

## Cấu hình

[`configs/application.yaml`](configs/application.yaml). Biến môi trường dùng chung với
auth-service giữ nguyên tên contract: `AUTH_JWT_ISSUER`, `AUTH_JWT_AUDIENCE`,
`AUTH_JWT_ALLOWED_ALGORITHMS`, `AUTH_JWKS_URL`, `AUTH_REVOCATION_REDIS_URL`. Khoá khác ghi
đè bằng tiền tố `GATEWAY_AUTH_` (ví dụ `GATEWAY_AUTH_SERVER_PORT`). Cấu hình yếu (thiếu
issuer/audience, thuật toán `none`/HMAC) làm service từ chối khởi động.

## Endpoint

| Path | Mục đích |
|---|---|
| `/verify` (mọi method) | Đích ForwardAuth |
| `GET /health/live` | Liveness |
| `GET /health/ready` | Redis ping được và có ít nhất một key JWKS dùng được |

## Phát triển

Máy phát triển không có Go; chạy qua Docker:

```bash
scripts/go.sh vet ./...
scripts/go.sh test ./...
```

Chạy cùng stack: `docker compose up -d gateway-auth api-gateway auth-service` ở thư mục gốc.
