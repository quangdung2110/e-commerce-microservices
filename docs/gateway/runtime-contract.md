# Quy ước vận hành của API Gateway

> **Trạng thái:** Quy ước mục tiêu — đã được phê duyệt cho đợt tái cấu trúc.
> **Nguồn chân lý:** Tài liệu này định nghĩa hành vi vận hành và được ưu tiên hơn cấu hình hoặc kế hoạch cũ khi có mâu thuẫn.

## 1. Ranh giới trách nhiệm

Gateway chịu trách nhiệm kết thúc TLS, định tuyến, health/timeout của upstream, xác thực, bảo vệ ở edge, access log, metrics và truyền trace context. Gateway **không** chịu trách nhiệm phân quyền nghiệp vụ, tổng hợp dữ liệu nghiệp vụ hoặc truy cập database.

Các downstream service chỉ được tin cậy header `X-User-*` đến từ mạng private của gateway/auth. Traefik phải loại bỏ mọi identity header do client gửi **trước** khi gọi ForwardAuth và trước khi forward upstream; chỉ header trong allowlist do `gateway-auth` trả về sau validation mới được thêm vào request. Downstream service không được có public ingress bypass gateway/auth và phải áp dụng network policy tương ứng.

## 2. Runtime được hỗ trợ

| Hạng mục | Quy ước |
|---|---|
| Edge proxy | Traefik Proxy `v3.7.1`, pin bằng exact tag và image digest |
| Discovery local | Docker provider với `exposedByDefault=false` |
| Discovery production | Kubernetes Gateway API/CRD; Docker labels chỉ là template cho môi trường development |
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

Route anonymous được limit theo trusted client IP. Route protected có IP limit thô trước authentication và identity/API-key/tenant limit sau khi identity được thiết lập. Traefik loại bỏ các header `X-User-Id`, `X-User-Roles`, `X-User-Session-Id`, `X-Token-Jti` và `X-User-Display-Name-B64` do client gửi trước ForwardAuth, kể cả trên route anonymous; chỉ các header cùng tên do `gateway-auth` trả về trong allowlist mới được forward tới service. Ánh xạ claim sang header được chốt tại mục 6.1 và enrichment tên tại mục 6.2.

Gateway tiếp tục `traceparent`/`tracestate` W3C hợp lệ; nếu không có thì sinh trace context W3C chuẩn. Gateway không tự tạo trace ID riêng.

## 5. Ma trận định tuyến bắt buộc

Mọi tham chiếu middleware khác provider phải tường minh, ví dụ `edge-security@file`.

| Route | Methods | Upstream | Xác thực | Rate profile | Quy tắc |
|---|---|---|---|---|---|
| `/health` | GET | gateway health nội bộ | Không | private | Không phải health API công khai của service |
| `/api/v1/auth/**` | auth API | auth-service | Không | `auth-public` | IP limit chặt; không retry login/register |
| `/.well-known/**` | GET | auth-service | Không | `public-read` | Chỉ JWKS/OIDC metadata |
| `/api/users/**` | do service định nghĩa | user-service | Bắt buộc | `api-authenticated` | Authorization thuộc service |
| `/api/orders/**` | do service định nghĩa | order-service | Bắt buộc | `api-authenticated` | Không retry method không idempotent |
| `/api/v1/products/**`, `/api/v1/categories/**`, `/api/v1/attributes/**` | do service định nghĩa | product-service | Bắt buộc, role `ADMIN` | `api-authenticated` | Catalog administration; Product Service tự kiểm tra role |
| `/api/v1/reviews/products/**` | GET | review-service | Không | `public-read` | Chỉ list/aggregate review APPROVED; Gateway bỏ forged identity headers cả trên route anonymous |
| `/api/v1/reviews/**` (trừ `products/**`) | GET, POST, PATCH | review-service | Bắt buộc | `api-authenticated` | Buyer review, report và signed media upload; riêng `POST /api/v1/reviews` nhận `X-User-Display-Name-B64` từ Gateway; Review tự kiểm tra owner, Gateway không retry write |
| `/api/v1/admin/reviews/**`, `/api/v1/admin/review-reports` | GET, POST, PUT | review-service | Bắt buộc, role Review admin | `api-authenticated` | Moderation, official reply và legal takedown; Review tự authorize và lấy audit actor từ Gateway identity |
| `/api/v1/search/**` | GET | search-service | Không | `public-read` | **Không còn backend**: Search đã gỡ v1 khi viết lại theo `docs/search`. Route phải được xoá sau khi frontend chuyển sang v2 |
| `/api/v2/search/**` (trừ reindex) | GET | search-service | Không | `public-read` | Search v2 canonical và là bề mặt storefront duy nhất còn backend |
| `/api/v2/search/reindex-runs/**` | GET, POST | search-service | Bắt buộc, role `ADMIN` | `api-authenticated` | Router priority cao hơn public v2 route; không expose qua `/internal` |
| `/api/payments/webhook/stripe` | POST | payment-service | Stripe signature | `stripe-webhook` | Giữ raw body; bypass JWT; không retry ở gateway |
| `/api/payments/**` còn lại | do service định nghĩa | payment-service | Bắt buộc | `payment-authenticated` | Payment Service sở hữu idempotency |
| `/ws/**` | HTTP Upgrade | chat-service | Chỉ handshake | `websocket-handshake` | Service đóng session bị revoke |

Path không xác định trả về `404` từ gateway; không có catch-all router nào được forward path tùy ý đến business service.

Các endpoint `/internal/v1/**` của Product Service (catalog export và snapshot cho
Search Service) chỉ đi qua mạng nội bộ với service identity đã xác minh; gateway
không tạo router public cho prefix này.

Các endpoint `/internal/v1/**` của Review Service (PDP summary và rating export)
cũng chỉ đi qua mạng nội bộ với service identity đã xác minh; Gateway không tạo router
public cho prefix này.

Mỗi service có health check và upstream transport timeout giới hạn. Retry chỉ áp dụng cho `GET`, `HEAD`, `OPTIONS` khi lỗi connection/5xx đã được cấu hình. Payment write và Stripe webhook không bao giờ bị gateway retry. Circuit breaker chỉ được thêm theo upstream sau khi threshold và recovery behavior đã được test.

### 5.1. Ngân sách timeout

Timeout là một phần của API contract, không phải giá trị mặc định của Traefik. Các giá trị dưới đây là giới hạn tối đa ban đầu; bất kỳ service nào cần thời gian ngắn hơn phải khai báo timeout riêng. Không route nào được đặt timeout bằng `0` (không giới hạn).

| Lớp hoặc route | Giới hạn | Hành vi khi hết thời gian |
|---|---:|---|
| Đọc request của client tại `websecure` | 15 giây | Dừng đọc request không hoàn chỉnh; bảo vệ trước slow client |
| Ghi response về client tại `websecure` | 60 giây | Ngắt response chưa hoàn tất; API không được streaming kéo dài |
| Kết nối keep-alive không hoạt động | 120 giây | Gateway đóng kết nối idle |
| Kết nối từ gateway đến upstream | 3 giây | Xem là lỗi upstream; chỉ GET/HEAD/OPTIONS còn ngân sách mới được retry |
| Chờ response header từ public-read/auth API | 10 giây | Trả `504` cho client |
| Chờ response header từ business API | 25 giây | Trả `504` cho client |
| Chờ response header từ payment API | 20 giây | Trả `504`; gateway không retry |
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
| Token denylist | `auth:revocation:denylist:{jti}` do `auth-service` ghi khi thu hồi token. TTL phải bằng hoặc ngắn hơn thời gian còn lại đến claim `exp`; không tạo denylist entry sống sau `exp`. |
| User-wide revocation | `auth:revocation:user:{sub}:revoked_before` chứa timestamp UTC. `gateway-auth` từ chối JWT có `iat` không lớn hơn giá trị này. TTL của key không được ngắn hơn access-token lifetime tối đa đang hỗ trợ. |
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

### 6.2 Enrich display name chỉ cho create Review

Access token vẫn **không** mang profile. Để Review có snapshot tên tác giả mà không
gọi đồng bộ User Service, `gateway-auth` duy trì một projection chỉ-đọc từ
`USER_PROFILE_UPDATED` của [User Events v1](../../contracts/events/user/v1/asyncapi.yaml).
Nó không được gọi HTTP/User database khi xử lý request.

- Consumer group `gateway-profile-name-v1` chỉ ACK Kafka sau khi ghi atomically
  projection. Record có `sender_name` và `updated_at`; chỉ record có `updated_at`
  mới hơn được thay thế. Timestamp bằng nhau mà `sender_name` khác nhau là contract
  violation, phải DLQ/alert thay vì chọn tùy ý.
- Projection dùng Redis namespace riêng `gateway:profile-name:*`, ACL chỉ cho
  `gateway-auth`. Suffix key là `HMAC-SHA-256(user_id)` với secret của Gateway, không
  phải UUID thô. Value hết hạn không quá 48 giờ; vì vậy event mất hoặc cache bị mất
  chỉ có thể làm dùng fallback, không giữ tên vô hạn. Redis failure khi đọc projection
  trả `503`; không được giả vờ fallback cho dependency failure. HMAC secret phải được
  version hóa; khi rotate, Gateway dual-read/dual-delete cả key version cũ trong tối
  thiểu thời gian tombstone trước khi bỏ version cũ.
- `gateway-auth` cũng consume `USER_DELETED` trực tiếp từ Auth Events v1. Nó xóa
  tên ngay và ghi tombstone theo cùng keyed suffix trong ít nhất 8 ngày (Kafka
  retention 7 ngày cộng recovery margin). Profile event có `updated_at <= deleted_at`
  bị bỏ qua. User chỉ phát/reconcile profile còn active, nên tombstone ngăn event
  profile đến muộn khôi phục PII sau deletion.

Sau khi JWT hợp lệ, **chỉ** `POST /api/v1/reviews` nhận thêm
`X-User-Display-Name-B64`. Giá trị là Base64url RFC 4648 **không padding** của UTF-8
`sender_name`; cách mã hóa này tránh đưa Unicode/control character thô vào HTTP
header. Nếu projection không có hoặc đã hết hạn, Gateway encode fallback cố định
`Người dùng`. Các route khác, kể cả endpoint Review đã xác thực, không nhận header
này. Header luôn bị strip ở edge trước ForwardAuth như mục 4 và không được log.

Review phải strict-decode Base64url/UTF-8, sau đó áp cùng giới hạn contract User
`1..201` ký tự. Thiếu hoặc không hợp lệ là lỗi Gateway context (`503`), không phải
lỗi request của client. Tên là snapshot eventual tại thời điểm create; Review không
consume profile update và không cập nhật review cũ khi profile đổi.

| Lỗi | Business route được bảo vệ | Payment route |
|---|---|---|
| Redis denylist/revocation không sẵn sàng | Fail closed: `503` | Fail closed: `503` |
| Auth Service/JWKS không sẵn sàng hoặc JWKS refresh lỗi, nhưng key cache cho JWT còn hợp lệ | Validate từ cache đến khi key hết hạn | Validate từ cache đến khi key hết hạn |
| Auth Service/JWKS không sẵn sàng hoặc JWKS refresh lỗi, không có key cache dùng được cho JWT | Fail closed: `503` | Fail closed: `503` |

Không route authentication nào được fail open, kể cả read-only route.

## 7. Chính sách rate và concurrency

| Profile | Chủ thể | Chính sách khởi điểm | Mục đích |
|---|---|---|---|
| `public-read` | Trusted client IP | 100 req/s, burst 200 | Bảo vệ catalog/JWKS |
| `auth-public` | Trusted client IP và route | 10 req/s, burst 20 | Chống brute force |
| `api-authenticated` | User ID hoặc API key/tenant | 50 req/s, burst 100 | Fair-use baseline |
| `payment-authenticated` | User ID hoặc API key/tenant | 20 req/s, burst 40 | Bảo vệ payment |
| `stripe-webhook` | Stripe allow-list + endpoint quota | 50 req/s, burst 100 | Bảo vệ delivery burst |
| `websocket-handshake` | User ID và trusted IP | 5/phút | Hạn chế connection churn |

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

Repository hiện chưa tuân thủ quy ước: thiếu `websecure` và active tracing, cross-provider middleware references không nhất quán, rate limits per-instance, phụ thuộc vào plugin `.so` không được hỗ trợ, và chưa có OpenTelemetry Collector hay log pipeline tập trung (Loki) — hiện tại chỉ có Jaeger nhận trace trực tiếp, access log chưa được thu thập/export ở đâu. Prometheus exemplar cũng chưa triển khai: Prometheus chưa bật `exemplar-storage`, và khả năng Traefik `v3.7.1` emit exemplar trên latency histogram chưa được xác minh với phiên bản đang pin — cần spike riêng trước khi cam kết click-through metric → trace cho traffic qua Traefik (`gateway-auth` không phụ thuộc gap này vì team tự kiểm soát code). Kế hoạch triển khai chuyển các khoảng cách này thành công việc; một năng lực không được gọi là “hoàn thành” trước khi tiêu chí xác minh của nó đạt.
