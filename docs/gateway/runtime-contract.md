# Quy ước vận hành của API Gateway

> **Trạng thái:** Quy ước mục tiêu — đã được phê duyệt cho đợt tái cấu trúc.
> **Nguồn chân lý:** Tài liệu này định nghĩa hành vi vận hành và được ưu tiên hơn cấu hình hoặc kế hoạch cũ khi có mâu thuẫn.

## 1. Ranh giới trách nhiệm

Gateway chịu trách nhiệm kết thúc TLS, định tuyến, health/timeout của upstream, xác thực, bảo vệ ở edge, access log, metrics và truyền trace context. Gateway **không** chịu trách nhiệm phân quyền nghiệp vụ, tổng hợp dữ liệu nghiệp vụ hoặc truy cập database.

Các downstream service chỉ được tin cậy header `X-User-*` đến từ mạng private của gateway/auth. Mọi public boundary phải loại bỏ hoặc ghi đè identity header do client tự gửi.

## 2. Runtime được hỗ trợ

| Hạng mục | Quy ước |
|---|---|
| Edge proxy | Traefik Proxy `v3.7.1`, pin bằng exact tag và image digest |
| Discovery local | Docker provider với `exposedByDefault=false` |
| Discovery production | Kubernetes Gateway API/CRD; Docker labels chỉ là template cho môi trường development |
| Authentication | Service `gateway-auth` triển khai độc lập, gọi qua Traefik `ForwardAuth` |
| Trạng thái rate limit | Redis-backed, dùng chung giữa mọi gateway replica |
| Telemetry | OTLP tới OpenTelemetry Collector; Jaeger/Prometheus/Grafana là backend phía sau |
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

Route anonymous được limit theo trusted client IP. Route protected có IP limit thô trước authentication và identity/API-key/tenant limit sau khi identity được thiết lập. Các header `X-User-Id`, `X-User-Roles`, `X-User-Email`, `X-User-Device-Id`, `X-User-Session-Id` đi vào đều bị loại bỏ trước khi request tới service.

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
| `/api/products/**` GET | GET | product-service | Không | `public-read` | Priority tường minh |
| `/api/products/**` non-GET | non-GET | product-service | Bắt buộc | `api-authenticated` | Priority cao hơn router public |
| `/api/payments/webhook/stripe` | POST | payment-service | Stripe signature | `stripe-webhook` | Giữ raw body; bypass JWT; không retry ở gateway |
| `/api/payments/**` còn lại | do service định nghĩa | payment-service | Bắt buộc | `payment-authenticated` | Payment Service sở hữu idempotency |
| `/ws/**` | HTTP Upgrade | chat-service | Chỉ handshake | `websocket-handshake` | Service đóng session bị revoke |

Path không xác định trả về `404` từ gateway; không có catch-all router nào được forward path tùy ý đến business service.

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

`gateway-auth` phải:

1. Validate Bearer JWT: issuer, audience, allowed algorithm, expiry và not-before.
2. Dùng JWKS cache có giới hạn cùng single-flight refresh.
3. Kiểm tra Redis denylist và timestamp `revoked_before` của user.
4. Trả `401` chỉ cho credentials không hợp lệ; trả `503` khi dependency validation không sẵn sàng.
5. Chỉ trả về allowlisted identity headers sau khi validation thành công.

| Lỗi | Business route được bảo vệ | Payment route |
|---|---|---|
| Redis denylist/revocation không sẵn sàng | Fail closed: `503` | Fail closed: `503` |
| JWKS refresh lỗi, cache key còn hợp lệ | Validate từ cache đến khi hết hạn | Validate từ cache đến khi hết hạn |
| JWKS không sẵn sàng, cache không dùng được | Fail closed: `503` | Fail closed: `503` |
| Auth Service không sẵn sàng | Fail closed: `503` | Fail closed: `503` |

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

Access log là JSON xuất stdout và được thu thập tập trung. Mỗi request gồm: `timestamp`, `service`, `trace_id`, `span_id`, `request_id`, `method`, normalized `path`, `route`, `status`, `duration_ms`, `client_ip`, `upstream_service`, `rate_limit_outcome`.

Không log Authorization headers, cookies, tokens, passwords, request bodies, Stripe signatures hoặc full query strings. Email không xuất hiện trong access logs; user ID chỉ được đưa vào khi data policy cho phép.

### Metrics và cảnh báo

Prometheus metrics là private. Các signal bắt buộc: request rate/status/latency, upstream health, retry/circuit state, 429 rate, auth outcome, JWKS failures, Redis failures và telemetry-export errors. Alert phải có owner/runbook cho 5xx, p95 latency, auth dependency outage, 401 spike, 429 kéo dài, upstream unhealthy và thiếu telemetry.

## 9. Bảo mật và tiêu chí xác minh

- Dashboard bị tắt ở production, trừ khi đã có private access và strong authentication.
- Không dùng default credentials, unpinned image hoặc public Redis/observability services.
- Security headers, CORS, body limit và timeout được xác nhận với API clients thực tế.

Release chỉ hoàn thành khi: mọi route trong matrix đến đúng service; HTTP redirect sang HTTPS; token lỗi bị reject; Stripe raw body được giữ nguyên; hai replicas chia sẻ limit; Redis/JWKS outage theo đúng policy; gateway/auth/service dùng chung trace; telemetry không có dữ liệu nhạy cảm; và load test ghi nhận throughput, p95/p99, memory, error rate.

## 10. Khoảng cách với trạng thái hiện tại

Repository hiện chưa tuân thủ quy ước: thiếu `websecure` và active tracing, cross-provider middleware references không nhất quán, rate limits per-instance, và phụ thuộc vào plugin `.so` không được hỗ trợ. Kế hoạch triển khai chuyển các khoảng cách này thành công việc; một năng lực không được gọi là “hoàn thành” trước khi tiêu chí xác minh của nó đạt.
