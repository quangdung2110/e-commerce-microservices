# Quy ước vận hành của API Gateway

> **Trạng thái:** Quy ước mục tiêu — đã được phê duyệt cho đợt tái cấu trúc.
> **Nguồn chân lý:** Tài liệu này định nghĩa hành vi vận hành và được ưu tiên hơn cấu hình hoặc kế hoạch cũ khi có mâu thuẫn.

## 1. Ranh giới trách nhiệm

Gateway chịu trách nhiệm kết thúc TLS, định tuyến, health/timeout của upstream, xác thực, bảo vệ ở edge, access log, metrics và truyền trace context. Gateway **không** chịu trách nhiệm phân quyền nghiệp vụ, tổng hợp dữ liệu nghiệp vụ hoặc truy cập database.

Các downstream service chỉ được tin cậy header `X-User-*` khi caller là Traefik. Trên Kubernetes, điều đó được service mesh thực thi: `AuthorizationPolicy` chỉ cho principal `cluster.local/ns/edge/sa/traefik` chạm tới route public của service ([mesh/runtime-contract.md §5](../mesh/runtime-contract.md#5-ma-trận-phân-quyền)); NetworkPolicy L3/L4 là lớp phòng thủ thứ hai. Trong Docker Compose không có mesh nên "mạng private" chỉ là giả định ở mức dev. Traefik phải loại bỏ mọi identity header do client gửi **trước** khi gọi ForwardAuth và trước khi forward upstream; chỉ header trong allowlist do `gateway-auth` trả về sau validation mới được thêm vào request. Downstream service không được có public ingress bypass gateway/auth.

## 2. Runtime được hỗ trợ

| Hạng mục | Quy ước |
|---|---|
| Edge proxy | Traefik Proxy `v3.7.1`, pin bằng exact tag và image digest |
| Discovery local | Docker provider với `exposedByDefault=false` |
| Discovery production | Kubernetes Gateway API/CRD; Docker labels chỉ là template cho môi trường development |
| Service mesh | Traefik và `gateway-auth` là workload trong namespace `edge` của Istio ambient (ztunnel, không waypoint); hop Traefik → service được mTLS và mang principal Traefik. Không deploy Istio Ingress Gateway. Xem [mesh/runtime-contract.md](../mesh/runtime-contract.md) |
| Authentication | Service `gateway-auth` triển khai độc lập, gọi qua Traefik `ForwardAuth` |
| Trạng thái rate limit | Redis-backed, dùng chung giữa mọi gateway replica |
| Telemetry | OTLP traces tới OpenTelemetry Collector; access log (stdout) được Collector thu qua `filelog` receiver; Jaeger (traces), Prometheus (metrics), Loki (logs) là backend phía sau, hiển thị qua Grafana |
| Cấu hình | Static config được version hóa; dynamic routes/middlewares được review và version hóa |

Hướng Go plugin `.so` compiled legacy không thuộc kiến trúc này.

## 3. Mạng và ranh giới tin cậy

| Entrypoint | Port | Phơi ra ngoài | Mục đích |
|---|---:|---|---|
| `web` | 80 | Công khai | Redirect sang HTTPS, trừ ACME challenge khi cần |
| `websecure` | 443 | Công khai | API và webhook từ client |
| `metrics` | 8090 | Riêng tư | Chỉ Prometheus scrape |
| `traefik` | nội bộ | Riêng tư | Dashboard/API, không bao giờ public port mapping |

- TLS là bắt buộc trên `websecure`; development dùng certificate local đáng tin cậy, production dùng certificate manager hoặc ACME.
- Chỉ tin cậy `X-Forwarded-*` từ CIDR của load balancer/proxy đã cấu hình. Header do client tự gửi không được quyết định IP dùng để rate limit.
- Production không mount Docker socket. Container chạy non-root, least-privilege và nhận secrets từ secret manager của platform.

## 4. Luồng xử lý request

```
client → TLS/trusted proxy → size & IP policy → route → rate/concurrency limit
       → ForwardAuth → identity-header sanitization/enrichment → upstream
       → logs, metrics, trace export
```

Route anonymous được limit theo trusted client IP. Route protected có IP limit thô trước authentication và identity/API-key/tenant limit sau khi identity được thiết lập. Traefik loại bỏ các header `X-User-Id`, `X-User-Roles`, `X-User-Session-Id`, `X-Token-Jti`, `X-Service-Identity` và `X-Request-Timeout-Ms` (deadline giữa các service, [contracts ADR-003](../../contracts/decisions/ADR-003-deadline-propagation.md)) do client gửi trước ForwardAuth, kể cả trên route anonymous; chỉ các header `X-User-*` do `gateway-auth` trả về trong allowlist mới được forward tới service. `X-Service-Identity` không bao giờ được Traefik đặt: nó là header attribution giữa các service bên trong mesh ([mesh/runtime-contract.md §6](../mesh/runtime-contract.md#6-quy-ước-identity-header)). Ánh xạ claim sang header được chốt tại mục 6.1.

Gateway tiếp tục `traceparent`/`tracestate` W3C hợp lệ; nếu không có thì sinh trace context W3C chuẩn. Gateway không tự tạo trace ID riêng.

## 5. Ma trận định tuyến bắt buộc

Mọi tham chiếu middleware khác provider phải tường minh, ví dụ `edge-security@file`.

| Route | Methods | Upstream | Xác thực | Rate profile | Quy tắc |
|---|---|---|---|---|---|
| `/health` | GET | gateway health nội bộ | Không | private | Không phải health API công khai của service |
| `/api/v1/auth/**` | auth API | auth-service | Không | `auth-public` | IP limit chặt; không retry login/register |
| `/.well-known/**` | GET | auth-service | Không | `public-read` | Chỉ JWKS/OIDC metadata |
| `/api/users/**` | do service định nghĩa | user-service | Bắt buộc | `api-authenticated` | Authorization thuộc service |
| `/api/v1/carts/mine` | GET | cart-service | Bắt buộc | `api-authenticated` | Theo [Cart OpenAPI v1](../../contracts/http/cart/v1/openapi.yaml); Cart xác định cart từ `X-User-Id` do `gateway-auth` inject, client không gửi `cart_id`/`user_id`; chỉ retry connection failure; giữ nguyên `Cache-Control: no-store` do Cart đặt. `/internal/v1/carts/**` chỉ trong mesh cho principal `checkout-service` |
| `/api/v1/carts/mine/items` | POST, DELETE | cart-service | Bắt buộc | `api-authenticated` | `Idempotency-Key` bắt buộc, Cart tự dedupe; gateway không retry write; `429` của Cart OpenAPI là limit theo user của profile `api-authenticated` |
| `/api/v1/checkout/preview` | POST | checkout-service | Bắt buộc | `checkout-preview` | `Idempotency-Key` bắt buộc; gateway không retry POST; response `private, no-store` |
| `/api/v1/checkout/previews/**` | GET | checkout-service | Bắt buộc | `checkout-voucher-options` | Chỉ `/{checkout_token}/voucher-options` ([Checkout ADR-017](../checkout/decisions/ADR-017-voucher-options-read-composition.md)); log route template/redact token path segment; chỉ retry connection failure; response `private, no-store` |
| `/api/v1/checkout/submit` | POST | checkout-service | Bắt buộc | `checkout-submit` | `checkout_token` là identity; gateway không retry POST; response `private, no-store` |
| `/api/v1/checkout/submissions/**` | GET | checkout-service | Bắt buộc | `api-authenticated` | Recovery read (có thể gửi lại submission sang Order trong cửa sổ retry, nên không coi là read thuần); log route template/redact token path segment; chỉ retry connection failure trong deadline, response `private, no-store` |
| `/api/v1/orders/**`, `/api/v1/order-operations/**` | OpenAPI Order v1 | order-service | Bắt buộc | `api-authenticated` | Buyer scope; không retry command ở gateway |
| `/api/v1/admin/orders/**`, `/api/v1/admin/order-operations/**` | OpenAPI Order v1 | order-service | `ADMIN` | `api-authenticated` + role policy | Admin scope; không retry command ở gateway |
| `/api/v1/admin/inventory/**` | GET, POST, PATCH theo [Inventory ADMIN OpenAPI v1](../../contracts/http/inventory/admin/v1/openapi.yaml) | inventory-service | `ADMIN` | `api-authenticated` + role policy | Warehouse/physical stock command và operational queue; Inventory tự kiểm role từ `X-User-Roles` và ghi audit actor; không retry write ở gateway; MFA là production gate ([Inventory ADR-024](../inventory/decisions/ADR-024-admin-surface-and-error-catalog.md)) |
| `/api/v1/me/promotion-voucher-suggestions` | POST | promotion-service | Bắt buộc | `api-authenticated` | Safe query với body checkout context; không gateway retry, response `no-store`; trang checkout dùng voucher options của Checkout thay vì route này |
| `/api/v1/promotion-discovery` | POST | promotion-service | Bắt buộc | `promotion-discovery` | Quota 20 request/user/phút tại edge; safe query nhưng không gateway retry POST; trang checkout không dùng |
| `/api/v1/me/promotion-vouchers/**` | PUT | promotion-service | Bắt buộc | `api-authenticated` | Save explicit, evaluate chỉ khi body có `context` ([Promotion ADR-018](../promotion/decisions/ADR-018-checkout-voucher-options-and-context-free-save.md)); Promotion tự xử lý natural idempotency |
| `/api/v1/me/promotion-voucher-claims` | POST | promotion-service | Bắt buộc | `api-authenticated` | Limited claim; không gateway retry, hỗ trợ `202 CLAIM_PENDING` |
| `/api/v1/admin/promotion-campaigns`, `/api/v1/admin/promotion-campaigns/**`, `/api/v1/admin/promotions/**`, `/api/v1/admin/markdown-rules/**` | GET, POST, PATCH | promotion-service | Bắt buộc, role `ADMIN` | `api-authenticated` | Promotion tự authorize; mutation dùng `If-Match`/natural key, gateway không retry write |
| `/api/v1/products/**`, `/api/v1/categories/**`, `/api/v1/attributes/**` | do service định nghĩa | product-service | Bắt buộc, role `ADMIN` | `api-authenticated` | Catalog administration; Product Service tự kiểm tra role |
| `/api/v1/storefront/products/**` | GET | product-detail-bff | Không | `public-read` | PDP theo [Product Detail BFF OpenAPI v1](../../contracts/http/product-detail-bff/v1/openapi.yaml); response-header timeout 3 giây (mục 5.1); chỉ retry lỗi kết nối tới BFF, không retry `5xx`/`504` vì BFF đã degrade hoặc fail fast; giữ nguyên `Cache-Control` do BFF đặt; bỏ forged identity headers ([Product Detail BFF ADR-004](../product-detail-bff/decisions/ADR-004-resilience-budget-and-mesh-split.md)) |
| `/api/v1/reviews/products/**` | GET | review-service | Không | `public-read` | Chỉ list/aggregate review APPROVED; Gateway bỏ forged identity headers cả trên route anonymous |
| `/api/v1/reviews/**` (trừ `products/**`) | GET, POST, PATCH | review-service | Bắt buộc | `api-authenticated` | Buyer review, report và signed media upload; Review tự kiểm tra owner, Gateway không retry write |
| `/api/v1/admin/reviews`, `/api/v1/admin/reviews/**` | GET, POST, PUT | review-service | Bắt buộc, role Review admin | `api-authenticated` | Moderation queue theo review, dismiss report, official reply và legal takedown; Review tự authorize và lấy audit actor từ Gateway identity |
| `/api/v1/notifications/inbox`, `/api/v1/notifications/inbox/**` | GET, PATCH, DELETE, POST (`mark-all-read`) | notification-service | Bắt buộc | `api-authenticated` | In-app inbox của user hiện tại theo [Notification OpenAPI v1](../../contracts/http/notification/v1/openapi.yaml); mọi response `private, no-store`; không có route preference (thuộc User), không có webhook route (SES event đi SNS → SQS); `/internal/v1/notifications/**` chỉ trong mesh |
| `/api/v2/search/products`, `/suggestions`, `/categories`, `/categories/{id}/facets` | GET | search-service | Không | `public-read` | Bốn route v2 chính xác trong Compose, strip identity; Search v1 đã bỏ router ở bước 4 frontend |
| `/api/v2/search/reindex-runs/**` | GET, POST | search-service | Bắt buộc, role `ADMIN` | `api-authenticated` | Router priority cao hơn public v2 route; không expose qua `/internal` |
| `/api/payments/webhook/stripe` | POST | payment-service | Stripe signature | `stripe-webhook` | Rewrite upstream thành `/v1/payments/webhook/stripe`; chỉ đổi path, giữ nguyên raw body và `Stripe-Signature`; bypass JWT; không retry ở gateway. **Route public duy nhất** của Payment: buyer/CSKH đọc payment qua Order (`docs/payment/architecture.md §1.2`), không có route `/api/payments/**` khác |
| `/ws/**` | HTTP Upgrade | chat-service | Chỉ handshake | `websocket-handshake` | Service đóng session bị revoke |

Path không xác định trả về `404` từ gateway; không có catch-all router nào được forward path tùy ý đến business service.

Product Detail BFF đã có runtime (`java/services/product-detail-bff`); docker-compose khai báo
router `product-detail-bff` cho dòng `/api/v1/storefront/products/**` ở trên, với serversTransport
`product-detail-bff` (response-header timeout 3 giây) và middleware `retry-connection-only` (Traefik
chỉ phát lại khi upstream không trả lời, không retry theo status). Riêng môi trường local có thêm
router `/media/**` (GET/HEAD) trỏ vào bucket MinIO `product-media` làm CDN giả cho ảnh Product; route
này **không** thuộc ma trận production, nơi media được phát từ domain CDN riêng
([Product Detail BFF ADR-005](../product-detail-bff/decisions/ADR-005-pdp-catalog-scope-and-media-url.md)).

Promotion Service đã có runtime module (`java/services/promotion-service`) theo
[Promotion OpenAPI v1](../../contracts/http/promotion/v1/openapi.yaml). docker-compose khai báo
ba router: `promotion-admin` (`/api/v1/admin/promotion-campaigns`, `/api/v1/admin/promotions`,
`/api/v1/admin/markdown-rules`, role ADMIN, priority 40, middleware `authenticated` — role lấy từ
claim `roles` của access token đã verify, đã chuyển khỏi `dev-admin-auth` ở bước 10 frontend ngày
06/10/2026), `promotion-discovery`
(`POST /api/v1/promotion-discovery`, middleware `rate-limit-promotion-discovery` 20 request/
user/phút theo `X-User-Id`, priority 35) và `promotion-me` (`/api/v1/me/promotion-vouchers/**`,
`/api/v1/me/promotion-voucher-suggestions`, `/api/v1/me/promotion-voucher-claims`, priority 30);
hai router customer này dùng `authenticated@file` từ 07/10/2026 (trước đó là `dev-auth`, identity cố định).
Docker Compose dùng rate limit trong bộ nhớ. Bundle GitOps dùng Redis TLS để chia sẻ quota
giữa các replica Traefik cho promotion discovery và checkout. Các policy theo user chạy sau
`authenticated`, dùng `X-User-Id` do gateway-auth xác thực, và trả `429` khi cạn quota.
Toàn bộ Promotion `/internal/v1/**` chỉ đi trong mesh: caller được
phép do `AuthorizationPolicy` quyết định theo principal, `X-Service-Identity` do service caller tự
đặt và chỉ dùng cho attribution ([mesh/runtime-contract.md §5.2](../mesh/runtime-contract.md#52-namespace-ecommerce-l7-waypoint)); không có public router ở Gateway.

Các endpoint `/internal/v1/**` của Product Service (catalog export và snapshot cho
Search Service) và `/internal/v1/**` của Review Service (PDP summary và rating export) cũng
chỉ đi trong mesh với principal được allowlist theo cùng ma trận; gateway không tạo router
public cho prefix `/internal/**`, `/exports/**` của bất kỳ service nào.

Mỗi service có health check và upstream transport timeout giới hạn. Retry chỉ áp dụng cho `GET`, `HEAD`, `OPTIONS` khi lỗi connection/5xx đã được cấu hình. Stripe webhook không bao giờ bị gateway retry (Payment không có route write public). Circuit breaker chỉ được thêm theo upstream sau khi threshold và recovery behavior đã được test.

### 5.1. Ngân sách timeout

Timeout là một phần của API contract, không phải giá trị mặc định của Traefik. Các giá trị dưới đây là giới hạn tối đa ban đầu; bất kỳ service nào cần thời gian ngắn hơn phải khai báo timeout riêng. Không route nào được đặt timeout bằng `0` (không giới hạn).

| Lớp hoặc route | Giới hạn | Hành vi khi hết thời gian |
|---|---:|---|
| Đọc request của client tại `websecure` | 15 giây | Dừng đọc request không hoàn chỉnh; bảo vệ trước slow client |
| Ghi response về client tại `websecure` | 60 giây | Ngắt response chưa hoàn tất; API không được streaming kéo dài |
| Kết nối keep-alive không hoạt động | 120 giây | Gateway đóng kết nối idle |
| Kết nối từ gateway đến upstream | 3 giây | Xem là lỗi upstream; chỉ GET/HEAD/OPTIONS còn ngân sách mới được retry |
| Chờ response header từ public-read/auth API | 10 giây | Trả `504` cho client |
| Chờ response header từ Product Detail BFF (`/api/v1/storefront/products/**`) | 3 giây | Trả `504`; BFF có deadline nội bộ 1 giây nên chỉ chạm khi BFF lỗi |
| Chờ response header từ business API | 25 giây | Trả `504` cho client. Checkout (`/api/v1/checkout/**`) dùng giá trị này với deadline nội bộ Preview 5 giây, voucher options 1,5 giây, Submit/status 20 giây ([Checkout ADR-014](../checkout/decisions/ADR-014-resilience-budget-and-mesh-gateway-split.md)) |
| Chờ response header từ payment webhook route | 20 giây | Trả `504`; gateway không retry (Stripe sẽ retry) |
| Stripe webhook — toàn bộ thời gian proxy | 5 giây | Trả `504`; gateway không retry, Stripe tự delivery lại |
| ForwardAuth → `gateway-auth` | 3 giây | Trả `503`; không được cho request đi tiếp |
| `gateway-auth` → JWKS | 3 giây tổng, connect tối đa 1 giây | Áp dụng policy cache/fail-closed trong phần xác thực |
| `gateway-auth` → Redis | 1 giây cho dial/read/write | Trả `503` cho protected route |
| WebSocket handshake | 10 giây | Từ chối handshake không hoàn tất |
| WebSocket sau khi nâng cấp | Ping 30 giây, không có pong trong 90 giây | Chat Service đóng kết nối; không dùng HTTP timeout để quản lý frame |

Khi gateway nhận cancellation từ client hoặc hết deadline, nó phải hủy request context tới upstream và `gateway-auth`. Downstream service phải quan sát context cancellation và dừng công việc có thể hủy. Deadline nội bộ của service phải nhỏ hơn deadline gateway ít nhất một giây để service có thời gian trả response có kiểm soát.

Không bắt đầu retry nếu phần thời gian còn lại không đủ cho một lần kết nối và response-header timeout nữa. Không retry request không idempotent, kể cả khi deadline chưa hết. `408` chỉ dùng cho request client gửi không hoàn chỉnh; `504` chỉ dùng khi upstream không đáp ứng trong ngân sách; timeout của `gateway-auth`, JWKS hoặc Redis là lỗi dependency và trả `503`.

Khi shutdown hoặc rollout, gateway ngừng nhận request mới trong 10 giây để load balancer loại instance khỏi rotation, sau đó cho các request HTTP đang chạy tối đa 30 giây để hoàn tất. WebSocket client phải reconnect sau khi connection được drain.

## 6. Quy ước xác thực

`auth-service` là issuer: nó phát JWT, giữ private signing key, công bố JWKS và ghi trạng thái revoke. `gateway-auth` là verifier tại edge: nó không phát token, không giữ private signing key và chỉ đọc trạng thái revoke từ Redis. Hai service phải dùng cùng contract sau; giá trị cụ thể được inject bằng cấu hình/secrets version hóa, không hard-code vào image.

| Hạng mục contract | Quy ước bắt buộc |
|---|---|
| Issuer và audience | `AUTH_JWT_ISSUER` và `AUTH_JWT_AUDIENCE` phải khớp tuyệt đối giá trị claim `iss` và `aud` trong JWT. |
| Thuật toán ký | Chỉ chấp nhận danh sách `AUTH_JWT_ALLOWED_ALGORITHMS` được cấu hình tường minh; cấm `none` và không chọn thuật toán từ header JWT một cách không kiểm soát. |
| Claim set của access token | Đúng tập sau, không hơn: `iss`, `aud`, `sub`, `jti`, `sid`, `roles`, `iat`, `nbf`, `exp`. `sid` là `family_id` ổn định của session, không đổi qua refresh-token rotation. `roles` chỉ là danh tính/entitlement được chuyển tiếp nếu được allowlist, không thay thế authorization nghiệp vụ. Access token **không** chứa `email` và **không** chứa `device_id`; service cần email phải hỏi Auth Service chứ không đọc từ token. |
| JWKS URL | `AUTH_JWKS_URL` là URL HTTPS canonical do `auth-service` công bố dưới `/.well-known/**`; chỉ lấy key từ URL này. |
| JWKS cache và rotation | Cache có TTL và kích thước giới hạn, refresh single-flight. Khi gặp `kid` mới thì refresh có kiểm soát; key cũ còn cache phải được giữ đến khi không còn JWT hợp lệ nào có thể tham chiếu nó. Nếu Auth Service/JWKS không truy cập được nhưng key cache hợp lệ, vẫn verify từ cache; chỉ fail closed khi không có key cache dùng được. |
| Redis logical store | `AUTH_REVOCATION_REDIS_URL` trỏ tới cùng Redis logical store/database cho cả hai service, với namespace `auth:revocation:*`. `auth-service` có quyền ghi; `gateway-auth` chỉ có quyền đọc các key này. |
| Token denylist | `auth:revocation:denylist:{jti}` do `auth-service` ghi khi thu hồi token. TTL không được ngắn hơn thời gian còn lại đến claim `exp`. `gateway-auth` chỉ chuyển tiếp `jti` đã verify (không chuyển `exp`) nên `auth-service` dùng cận trên là access-token lifetime + clock skew (mặc định 15m30s): entry có thể sống lâu hơn token vài phút, không bao giờ ngắn hơn ([Auth ADR-001](../auth/decisions/ADR-001-dual-token-revocation.md)). |
| User-wide revocation | `auth:revocation:user:{sub}:revoked_before` chứa timestamp UTC. `gateway-auth` từ chối JWT có `iat` không lớn hơn giá trị này (`iat <= revoked_before`). Cả hai đều là Unix seconds nên token cấp trong cùng giây với lần thu hồi cũng bị từ chối; đây là chủ ý, vì không thể phân biệt token cấp ngay trước hay ngay sau lúc thu hồi. TTL của key không được ngắn hơn access-token lifetime tối đa đang hỗ trợ. |
| JWKS invalidation payload | Message trên channel `auth:jwks:update` là JSON `{"type": "...", "kid": "...", "timestamp": "..."}`. `type` nhận `key_rotation`, `key_retirement` hoặc `all`; `kid` rỗng nghĩa là xóa toàn bộ cache. `type` là field bắt buộc — message thiếu `type` phải bị bỏ qua và ghi log, không được coi là tín hiệu xóa cache. |

### 6.1 Ánh xạ identity header

Sau khi validate thành công, `gateway-auth` inject đúng bốn header dưới đây, mỗi header lấy từ một claim. Không header identity nào khác được sinh ra từ access token.

| Header | Claim nguồn | Bắt buộc | Ghi chú |
|---|---|---|---|
| `X-User-Id` | `sub` | Có | UUID của account |
| `X-User-Roles` | `roles` | Có | Danh sách role phân tách bằng dấu phẩy |
| `X-User-Session-Id` | `sid` | Có | `family_id` của session hiện tại |
| `X-Token-Jti` | `jti` | Có | Auth dùng để denylist access token khi thu hồi session hiện tại |

`X-User-Email` và `X-User-Device-Id` không tồn tại trong contract này: token không mang claim tương ứng, nên không service nào được thiết kế phụ thuộc vào chúng.

`gateway-auth` phải:

1. Validate Bearer JWT theo contract: issuer, audience, allowed algorithm, signature, expiry và not-before.
2. Dùng JWKS cache và key-rotation policy trong bảng trên.
3. Kiểm tra Redis denylist theo `jti` và `revoked_before` theo `sub`.
4. Trả `401` chỉ cho credentials không hợp lệ; trả `503` khi dependency validation bắt buộc không sẵn sàng.
5. Chỉ trả về allowlisted identity headers sau khi validation thành công.

| Lỗi | Business route được bảo vệ |
|---|---|
| Redis denylist/revocation không sẵn sàng | Fail closed: `503` |
| Auth Service/JWKS không sẵn sàng hoặc JWKS refresh lỗi, nhưng key cache cho JWT còn hợp lệ | Validate từ cache đến khi key hết hạn |
| Auth Service/JWKS không sẵn sàng hoặc JWKS refresh lỗi, không có key cache dùng được cho JWT | Fail closed: `503` |

Route webhook Stripe không đi qua JWT validation nên không chịu ảnh hưởng của bảng này; nó chỉ phụ thuộc rate-limit state (`stripe-webhook`).

Không route authentication nào được fail open, kể cả read-only route.

## 7. Chính sách rate và concurrency

| Profile | Chủ thể | Chính sách khởi điểm | Mục đích |
|---|---|---|---|
| `public-read` | Trusted client IP | 100 req/s, burst 200 | Bảo vệ catalog/JWKS |
| `auth-public` | Trusted client IP và route | 10 req/s, burst 20 | Chống brute force |
| `api-authenticated` | User ID hoặc API key/tenant | 50 req/s, burst 100 | Fair-use baseline |
| `promotion-discovery` | Authenticated user ID; coarse trusted-IP guard trước ForwardAuth | 20 request/user/phút | Bảo vệ query discovery; distributed state ở Gateway, không service-local limiter |
| `checkout-preview` | Authenticated user ID; coarse trusted-IP guard trước ForwardAuth | 10 request/user/phút | Chặn fan-out Preview; trả `429` + `Retry-After` |
| `checkout-submit` | Authenticated user ID; coarse trusted-IP guard trước ForwardAuth | 3 request/user/phút | Chặn click/spam Submit; replay hợp lệ vẫn có status GET riêng |
| `checkout-voucher-options` | Authenticated user ID; coarse trusted-IP guard trước ForwardAuth | 20 request/user/phút | Bảo vệ fan-out sang Promotion khi mở khay voucher; Checkout không có limiter riêng |
| `stripe-webhook` | Stripe allow-list + endpoint quota | 50 req/s, burst 100 | Bảo vệ delivery burst |
| `websocket-handshake` | User ID và trusted IP | 5/phút | Hạn chế connection churn |

**Trusted client IP** ([mesh ADR-004](../mesh/decisions/ADR-004-secrets-client-ip-network-split-and-internal-signing.md)): entrypoint
`web` chỉ nhận `X-Forwarded-For`/`X-Real-Ip` từ frontend (`forwardedHeaders.trustedIPs`: địa chỉ cố định `10.89.8.10` ở compose, pod CIDR
ở `deploy/k8s`); frontend ghi đè hai header đó bằng địa chỉ TCP của trình duyệt. Từ mọi nguồn khác Traefik bỏ chúng và đặt `X-Real-Ip` =
địa chỉ peer. Các profile theo IP dùng `sourceCriterion.requestHeaderName: X-Real-Ip`, không dùng `ipStrategy.depth` (độ sâu khác nhau
giữa request qua Next và request gọi thẳng gateway). Service phía sau (Auth) chỉ tin `X-Real-Ip` khi peer là gateway. Router công khai
`/api/v1/auth/**` mang profile `auth-public` (`rate-limit-auth`).

Các giá trị là chính sách khởi điểm và phải được load test xác nhận. Khi có trên một gateway replica, shared state là bắt buộc. Traffic protected fail closed khi distributed rate-limit state không sẵn sàng; public fallback cần approval tường minh và monitoring. Khi bị limit trả `429` cùng retry guidance nếu hỗ trợ.

## 8. Quy ước khả năng quan sát

### Trace

- Traefik và `gateway-auth` export OTLP/gRPC tới OpenTelemetry Collector.
- Service name tương ứng là `api-gateway` và `gateway-auth`.
- Development sample 100%; production sampling phải được ghi nhận và error trace phải được giữ lại.
- Downstream service phải tiếp tục cùng W3C trace để có end-to-end tracing.

### Nhật ký

Access log là JSON xuất stdout. Mỗi request gồm: `timestamp`, `service`, `trace_id`, `span_id`, `request_id`, `method`, normalized `path`, `route`, `status`, `duration_ms`, `client_ip`, `upstream_service`, `rate_limit_outcome`.

Không log Authorization headers, cookies, tokens, passwords, request bodies, Stripe signatures hoặc full query strings. Email không xuất hiện trong access logs; user ID chỉ được đưa vào khi data policy cho phép.

**Log pipeline.** OpenTelemetry Collector đọc container stdout bằng `filelog` receiver — cùng instance Collector nhận OTLP traces từ Traefik/`gateway-auth` — parse JSON log thành log record, giữ nguyên `trace_id`/`span_id` sẵn có trong record, rồi export sang Loki qua Loki exporter. Đây là điểm hợp nhất duy nhất: Collector nhận cả traces (OTLP push) và logs (filelog tail trên cùng container), tách theo signal type và route tới đúng backend; metrics vẫn đi ngoài đường này vì Prometheus scrape trực tiếp endpoint `metrics` (mục 2, mục 3), không qua Collector.

Loki index theo label tối thiểu: `service`, `route`, `status`. Không đưa `user_id`, `trace_id`, `client_ip` hay field high-cardinality khác vào label — các field này chỉ nằm trong log line, để tránh label cardinality explosion trên Loki. Vì `trace_id`/`span_id` có mặt trong cả log line và trace export, Grafana correlate log ↔ trace cho cùng request qua Loki + Jaeger datasource mà không cần thêm hệ thống trung gian.

Retention Loki mặc định 30 ngày cho access log. Kéo dài thời gian giữ log liên quan sự cố bảo mật do runbook incident quyết định riêng, không phải retention mặc định. Loki là private observability service — áp dụng cùng ràng buộc network/auth như Prometheus và Traefik dashboard ở mục 9.

### Metrics và cảnh báo

Prometheus metrics là private. Các signal bắt buộc: request rate/status/latency, upstream health, retry/circuit state, 429 rate, auth outcome, JWKS failures, Redis failures và telemetry-export errors. Alert phải có owner/runbook cho 5xx, p95 latency, auth dependency outage, 401 spike, 429 kéo dài, upstream unhealthy và thiếu telemetry.

**Exemplars.** Latency histogram (request duration) mang exemplar `trace_id` của active span khi ghi nhận sample. Prometheus bật `--enable-feature=exemplar-storage`; Grafana Prometheus datasource cấu hình exemplar link trỏ Jaeger theo `trace_id`, cho phép click từ điểm trên biểu đồ metric sang đúng trace. `gateway-auth` (service tự viết) phải emit exemplar; hỗ trợ exemplar của Traefik `v3.7.1` trên metric request duration **chưa được xác minh** — xem mục 10. Nếu Traefik không hỗ trợ, phần traffic đó fallback về time-range matching thủ công trong Grafana Explore giữa Prometheus và Loki, exemplar của `gateway-auth` không bị ảnh hưởng.

## 9. Bảo mật và tiêu chí xác minh

- Dashboard bị tắt ở production, trừ khi đã có private access và strong authentication.
- Không dùng default credentials, unpinned image hoặc public Redis/observability services.
- Security headers, CORS, body limit và timeout được xác nhận với API clients thực tế.

Release chỉ hoàn thành khi: mọi route trong matrix đến đúng service; HTTP redirect sang HTTPS; token lỗi bị reject; Stripe raw body được giữ nguyên; hai replicas chia sẻ limit; Redis/JWKS outage theo đúng policy; gateway/auth/service dùng chung trace; log Loki correlate được với trace qua `trace_id`; exemplar `gateway-auth` mở đúng trace trên Jaeger từ Grafana (Traefik exemplar là best-effort, không chặn release nếu chưa được framework hỗ trợ — ghi nhận là known gap); telemetry không có dữ liệu nhạy cảm; và load test ghi nhận throughput, p95/p99, memory, error rate.

## 10. Khoảng cách với trạng thái hiện tại

Repository hiện chưa tuân thủ quy ước: thiếu `websecure` và active tracing, cross-provider middleware references không nhất quán, rate limits per-instance, và chưa có OpenTelemetry Collector hay log pipeline tập trung (Loki) — hiện tại chỉ có Jaeger nhận trace trực tiếp, access log chưa được thu thập/export ở đâu. Prometheus exemplar cũng chưa triển khai: Prometheus chưa bật `exemplar-storage`, và khả năng Traefik `v3.7.1` emit exemplar trên latency histogram chưa được xác minh với phiên bản đang pin — cần spike riêng trước khi cam kết click-through metric → trace cho traffic qua Traefik (`gateway-auth` không phụ thuộc gap này vì team tự kiểm soát code). Chưa có manifest Kubernetes hay service mesh: route matrix còn dùng một số prefix legacy như `/api/users/**`, `/api/payments/**`; Order đã chốt `/api/v1/orders/**` và các operation/Admin namespace, Product Detail BFF đã có dòng `/api/v1/storefront/products/**`, Cart đã có dòng `/api/v1/carts/mine` và `/api/v1/carts/mine/items` theo Cart OpenAPI v1; Inventory admin đã có dòng `/api/v1/admin/inventory/**`, khớp [mesh/runtime-contract.md §5.2](../mesh/runtime-contract.md#52-namespace-ecommerce-l7-waypoint). Kế hoạch triển khai chuyển các khoảng cách này thành công việc; một năng lực không được gọi là “hoàn thành” trước khi tiêu chí xác minh của nó đạt.
