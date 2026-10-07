---
doc_id: checkout-operations
service: checkout-service
scope: target
status: proposed
owners:
  - Checkout Service team
evidence:
  - ../../contracts/http/checkout/v1/openapi.yaml
  - ../mesh/runtime-contract.md
  - ../gateway/runtime-contract.md
verified_against_commit: null
---

# Checkout Service — Operations

Mọi giá trị dưới đây là giá trị khởi điểm của
[ADR-014](./decisions/ADR-014-resilience-budget-and-mesh-gateway-split.md); đây là release
gate cần load test xác nhận (giỏ 50 dòng, voucher có rule category) trước khi bật route.

## SLO

| SLI | Mục tiêu | Đo |
|---|---:|---|
| Preview availability | 99,9 % / 30 ngày | Response không phải `5xx`; `4xx` nghiệp vụ tính là thành công |
| Preview latency tại Checkout | p95 ≤ 1 s, p99 ≤ 3 s | Từ lúc nhận request tới khi ghi xong response |
| Submit availability | 99,9 % / 30 ngày | Response không phải `5xx`; `202` tính là thành công |
| Submit latency | p95 ≤ 4 s, p99 ≤ 12 s | Gồm thời gian Order chờ `PAYMENT_READY` |
| Voucher options availability | 99,5 % / 30 ngày | Không phải `5xx` |
| Voucher options latency | p95 ≤ 600 ms | |
| Kết quả chưa rõ | ≤ 0,1 % lần Submit | Submit trả `202 RESULT_UNKNOWN` |
| Đơn trùng | 0 | Hai `order_id` khác nhau cho cùng `checkout_token`; bất kỳ lần nào cũng là sự cố |

Latency kỳ vọng của provider để timeout có ý nghĩa (provider cần xác nhận và ghi vào
operations của mình; hiện là known gap):

| Dependency | p99 kỳ vọng |
|---|---:|
| Cart checkout snapshot | ≤ 100 ms |
| User address snapshot | ≤ 150 ms |
| Product `GetBatchSkus` (≤ 50 SKU) | ≤ 200 ms |
| Inventory allocation preview (≤ 50 dòng) | ≤ 500 ms |
| Promotion calculation | ≤ 300 ms |
| Promotion voucher options | ≤ 500 ms |
| Order submission POST (tới `PAYMENT_READY`) | ≤ 5 s |
| Order submission GET | ≤ 200 ms |

## Health và degradation

- `/health/live`: chỉ process.
- `/health/ready`: Redis trả lời, Lua scripts đã `SCRIPT LOAD`, và cấu hình bắt buộc ở
  [Cấu hình](#cấu-hình) hợp lệ (khoá HMAC/mã hoá, base URL, CDN và placeholder). Không fan-out
  tới downstream; circuit mở không làm mất readiness.

| Điều kiện | Hành vi công khai | Tính vào circuit breaker | Owner xử lý |
|---|---|---|---|
| Cart/User/Product/Inventory timeout, lỗi kết nối, `5xx`, response sai contract | `503 CHECKOUT_UNAVAILABLE` + `Retry-After` | Có | Dependency + Checkout |
| Promotion calculation như trên (khi có voucher) | `503 CHECKOUT_UNAVAILABLE`; không bao giờ "không giảm giá" | Có | Promotion + Checkout |
| Dependency `400`/`401`/`403` | `503 CHECKOUT_UNAVAILABLE`; alert cấu hình | Không | Checkout + platform |
| User `429` | `503 CHECKOUT_UNAVAILABLE` | Không | User |
| Circuit mở, bulkhead đầy, deadline còn < 200 ms trước một wave | `503`, không gọi dependency (`circuit_open`/`bulkhead_rejected`/`budget_skipped`) | Không | Checkout |
| Kết quả nghiệp vụ (giỏ rỗng, SKU unavailable, address `404`, `REJECT_ALL`, `eligible=false`) | `422` theo error catalog | Không | — |
| Promotion voucher options lỗi | `503 VOUCHER_OPTIONS_UNAVAILABLE`; Preview/Submit không bị ảnh hưởng | Có (circuit riêng) | Promotion |
| Order POST timeout/`503` | `202 RESULT_UNKNOWN`, recovery | Có | Order + Checkout |
| Order POST `400`/`401`/`403` | `503`, token `RETRYABLE`; alert cấu hình | Không | Checkout + platform |
| Order POST `409`/`422` ngoài catalog | `409 SUBMISSION_REJECTED`; alert `contract_violation` | Không | Checkout + Order |
| Redis lỗi/timeout | `503 CHECKOUT_UNAVAILABLE`; readiness `false` nếu kéo dài | — | Checkout + platform |
| Client hủy request | Preview/voucher options: hủy mọi lời gọi. Submit sau claim: chạy tiếp, ghi kết quả | Không | — |

Response sai contract gồm body không parse được, vi phạm schema, hoặc vi phạm ràng buộc mà
Checkout kiểm tra ([interfaces.md](./interfaces.md#mapping-cart--product--báo-giá)): plan
Inventory không phủ đúng dòng, Promotion `subtotal` khác `total_amount`, `product_id` không
phải UUID.

## Resilience

| Dependency | Timeout Checkout | Retry Checkout | Timeout mesh | Retry mesh | Bulkhead khởi điểm |
|---|---:|---:|---:|---|---:|
| Cart | `min(500 ms, còn lại − 100 ms)` | 0 | 1 s | 2 lần, lỗi kết nối | 10 |
| User | `min(500 ms, còn lại − 100 ms)` | 0 | 2 s | 2 lần, lỗi kết nối | 10 |
| Product gRPC | `min(1 000 ms, còn lại − 100 ms)` | 0 | 2 s | 2 lần, lỗi kết nối/`unavailable` | 10 |
| Inventory | `min(3 000 ms, còn lại − 100 ms)` | 0 | 4 s | 0 | 20 |
| Promotion calculation | `min(2 000 ms, còn lại − 100 ms)` | 0 | 3 s | 0 | 15 |
| Promotion voucher options | `min(1 000 ms, còn lại − 100 ms)` | 0 | 2 s | 0 | 10 |
| Order POST | `min(13 000 ms, còn lại − 200 ms)` | 0 trong request; recovery dùng cùng payload/key | 15 s | 0 | 50 |
| Order GET | `min(2 000 ms, còn lại − 100 ms)` | 0 | 15 s | 0 | 10 |
| Redis | 200 ms mỗi lệnh/script | 0 | — | — | một kết nối Lettuce multiplex, không pool (không có lời gọi blocking nào cần connection riêng) |

- **Deadline:** Preview 5 000 ms, voucher options 1 500 ms, Submit và status 20 000 ms, tính
  từ lúc Checkout nhận request. Gateway chờ response header tối đa 25 giây cho route business
  nên deadline nội bộ luôn nhỏ hơn Gateway ít nhất một giây.
- **Retry:** ứng dụng không retry trong request. Retry duy nhất là retry lỗi kết nối của
  mesh (Cart, User, Product) và luồng recovery của Submit.
- **Circuit breaker** riêng cho từng dependency mỗi pod: cửa sổ 10 giây, tối thiểu 20 lời
  gọi, mở khi tỷ lệ lỗi hoặc lời gọi chậm ≥ 50 % (chậm = vượt 80 % timeout Checkout), mở 10
  giây, half-open 5 lời gọi. `4xx` nghiệp vụ và `400`/`401`/`403` không tính.
- **Bulkhead** là semaphore không hàng chờ, cỡ `max(10, ceil(RPS mỗi pod × p99 kỳ vọng × 2))`;
  bảng tính cho Preview 20 RPS, Submit 5 RPS, voucher options 10 RPS, status 10 RPS mỗi pod.
  Connection pool không nhỏ hơn bulkhead.
- **Shutdown:** readiness `false` ngay khi SIGTERM, chờ request đang chạy tối đa 25 giây.

Không dùng token, user ID, SKU hay promotion ID làm metric label.

## Metric

Mesh cung cấp golden metric theo cạnh gọi; các metric dưới đây là ngữ nghĩa chỉ Checkout
biết.

| Metric | Label | Ý nghĩa |
|---|---|---|
| `http_server_requests_seconds` | `uri` (template), `method`, `status` | RED theo route |
| `checkout_problems_total` | `operation`, `code` | Mỗi problem trả ra, theo error catalog |
| `checkout_dependency_calls_seconds` (timer) | `dependency`, `outcome` | `outcome` ∈ `success`, `business_reject`, `timeout`, `connect_error`, `5xx`, `4xx`, `contract_violation`, `circuit_open`, `bulkhead_rejected`, `budget_skipped`; lời gọi không phát đi ghi 0 giây |
| `checkout_preview_cas_total` | `result` | `created`, `superseded_previous`, `active_changed`, `in_flight_blocked`, `idempotent_replay`, `idempotency_mismatch` |
| `checkout_state_transitions_total` | `from`, `to` | Chuyển state |
| `checkout_recovery_total` | `outcome` | `resolved_success`, `resolved_conflict`, `resolved_failed`, `still_preparing`, `reposted`, `not_accepted`, `unknown` |
| `checkout_voucher_options_dropped_total` | `reason` | Item Promotion bị bỏ: `source` (UNKNOWN), `distribution_mode` (tổ hợp không map được) hoặc `discount_type` (loại discount chưa biết) |
| `checkout_promotion_budget_guard_total` | `value` | Phân bố `budget_guard` từ Promotion |
| `checkout_cart_lines` (histogram) | — | Số dòng mỗi Preview |
| `checkout_redis_seconds` (timer) | `operation` | Độ trễ lệnh/script Redis |
| `resilience4j_circuitbreaker_state` | `name` | Trạng thái circuit từng dependency |

## Alert

- Availability hoặc latency vượt SLO (burn rate).
- Bất kỳ `contract_violation` nào, bất kỳ `401`/`403` nào từ dependency, bất kỳ
  `SUBMISSION_REJECTED` nào.
- Tỷ lệ `RESULT_UNKNOWN` > 0,1 % Submit trong 15 phút; `SUBMISSION_NOT_ACCEPTED` tăng.
- Circuit Order mở; circuit dependency khác mở liên tục quá 5 phút.
- Redis lỗi, eviction > 0 hoặc memory > 80 %.
- `bulkhead_rejected` tăng đều mà dependency khoẻ (thiếu capacity).

Log JSON gồm `timestamp`, `level`, `service`, `environment`, `trace_id`, `span_id`,
`request_id`, route template, status, problem `code` và duration. Gateway và Checkout redact
segment `{checkout_token}` trong path; token chỉ log fingerprint ngắn không đảo ngược. Không
log payload Order, địa chỉ, header xác thực hay secret. Span chính `checkout.preview`,
`checkout.voucher_options`, `checkout.submit`, `checkout.recovery`; span con theo dependency
và Redis script.

## Cấu hình

Biến môi trường map sang Spring property tương ứng
([ADR-013](./decisions/ADR-013-java-spring-webflux-runtime.md)). Secret lấy từ secret manager,
không commit.

| Variable | Default | Bắt buộc | Ghi chú |
|---|---|---|---|
| `SERVER_PORT` | `8080` | Không | Khớp `servers` của OpenAPI và upstream Gateway |
| `CHECKOUT_REDIS_URL` | none | Có | Redis riêng `noeviction`, ví dụ `redis://checkout-redis:6379/0`; TLS và auth ngoài local |
| `CHECKOUT_TOKEN_HMAC_KEYS`, `CHECKOUT_TOKEN_HMAC_ACTIVE_VERSION` | none | Có | Secret, dạng `v1:<base64>,v2:<base64>` (≥ 32 byte mỗi khoá); giữ version cũ tới khi mọi record dùng nó hết TTL (≥ 24 giờ) |
| `CHECKOUT_ENCRYPTION_KEYS`, `CHECKOUT_ENCRYPTION_ACTIVE_VERSION` | none | Có | Secret, cùng định dạng, đúng 32 byte mỗi khoá (AES-256); envelope encryption; xoay vòng như HMAC |
| `CHECKOUT_CART_BASE_URL` | none | Có | Ví dụ `http://cart-service:8080` |
| `CHECKOUT_USER_BASE_URL` | none | Có | Cổng internal, ví dụ `http://user-service:8083` |
| `CHECKOUT_PRODUCT_GRPC_TARGET` | none | Có | Ví dụ `product-service:9091` |
| `CHECKOUT_INVENTORY_BASE_URL` | none | Có | Ví dụ `http://inventory-service:8080` |
| `CHECKOUT_PROMOTION_BASE_URL` | none | Có | Ví dụ `http://promotion-service:8085` |
| `CHECKOUT_ORDER_BASE_URL` | none | Có | Ví dụ `http://order-service:8080` |
| `CHECKOUT_VOUCHER_OPTIONS_ENABLED` | `false` | Không | Bật khi Promotion có `promotion-voucher-options`; tắt thì endpoint trả `503 VOUCHER_OPTIONS_UNAVAILABLE` và không gọi Promotion |
| `CHECKOUT_MEDIA_CDN_BASE_URL` | none | Có | URL tuyệt đối, không có `/` cuối; phải bằng `PDP_MEDIA_CDN_BASE_URL`/`CDN_BASE_URL` của Search |
| `CHECKOUT_MEDIA_PLACEHOLDER_URL` | none | Có | Bằng `PDP_MEDIA_PLACEHOLDER_URL` |
| `CHECKOUT_MEDIA_REQUIRE_HTTPS` | `true` | Không | Chỉ local đặt `false` |
| `CHECKOUT_QUOTE_TTL` | `20m` | Không | `expires_at` của báo giá |
| `CHECKOUT_SESSION_RETENTION` | `30m` | Không | TTL session trước submit và recovery sau claim |
| `CHECKOUT_PREVIEW_IDEMPOTENCY_TTL` | `5m` | Không | |
| `CHECKOUT_SUBMIT_OWNER_TIMEOUT` | `30s` | Không | Phải lớn hơn deadline Submit |
| `CHECKOUT_SUBMIT_RETRY_WINDOW` | `2m` | Không | Phải lớn hơn timeout mesh Order + 5 giây |
| `CHECKOUT_SUCCESS_RESULT_TTL` | `24h` | Không | |
| Deadline, timeout, circuit breaker, bulkhead | Theo [Resilience](#resilience) | Không | Đổi giá trị production phải cập nhật ADR-014 và giữ quy tắc timeout Checkout < mesh, deadline < Gateway − 1 s |
| `OTEL_EXPORTER_OTLP_ENDPOINT`, `OTEL_TRACES_SAMPLER_ARG` | none | Không | |

Thiếu cấu hình bắt buộc thì service không khởi động (validate lúc start) thay vì chạy với URL hỏng.
Các bất biến timeout (Checkout < mesh, deadline < Gateway − 1 giây, owner timeout > deadline
Submit, cửa sổ retry > 20 giây) cũng được kiểm lúc khởi động. Prometheus ở `/actuator/prometheus`;
probe ở `/health/live` và `/health/ready`.

## Runbook

**`CHECKOUT_UNAVAILABLE` tăng:** xem `checkout_dependency_calls_seconds` theo `dependency`,
`outcome` và circuit state để biết dependency nào; chuyển on-call owner. Không bật degraded
mode (không tồn tại), không tắt circuit breaker để ép gọi.

**`401`/`403` từ dependency:** `403` `text/plain` là waypoint mesh chặn principal
`checkout-service`; `401`/`403` problem+json là provider từ chối `X-Service-Identity`. Kiểm
tra `AuthorizationPolicy` và allowlist của provider.

**`RESULT_UNKNOWN` tăng:** kiểm tra latency/`503` của Order và thời gian Order chờ
`PAYMENT_READY`. Không yêu cầu người dùng đặt lại; status endpoint tự phân giải. Nếu Order
GET cũng lỗi, chờ Order phục hồi.

**Nghi ngờ đơn trùng:** truy Order theo `checkout_token`; một token chỉ có một workflow
`order-checkout-{checkout_token}`. Hai token cho cùng giỏ trong vài phút là hai lần đặt hàng
hợp lệ, không phải lỗi idempotency.

**Mất Redis:** Preview/Submit trả `503`. Với submission đã claim trước sự cố, tra Order theo
token để xác nhận kết quả và hỗ trợ người dùng thủ công.

**Voucher options lỗi:** chỉ ảnh hưởng khay voucher; đặt hàng vẫn chạy. Kiểm tra Promotion và
cờ `CHECKOUT_VOUCHER_OPTIONS_ENABLED`.

### Rollout

1. Promotion triển khai `promotion-voucher-options` (đã có; ở môi trường mà Promotion chưa có thì giữ cờ tắt).
2. Deploy Checkout tối thiểu hai replica; readiness đạt.
3. Contract test và smoke test nội bộ với Cart, User, Product, Inventory, Promotion, Order.
4. Load test đạt SLO ở trên.
5. Bật route Gateway theo canary; theo dõi problem code, `RESULT_UNKNOWN`, recovery.
6. Bật `CHECKOUT_VOUCHER_OPTIONS_ENABLED` khi Promotion sẵn sàng.

Rollback route trước nhưng giữ service chạy tối thiểu 30 phút để các token đã phát hành vẫn
truy vấn được status.
