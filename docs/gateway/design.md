# Thiết kế kiến trúc API Gateway

## Mục đích

API Gateway là biên công khai của nền tảng E-Commerce microservices. Nó cung cấp một ranh giới API ổn định, trong khi các service vẫn sở hữu hành vi domain và phân quyền. Hành vi vận hành được định nghĩa trong [runtime-contract.md](runtime-contract.md); tài liệu này giải thích kiến trúc.

## Kiến trúc

```
Client
  │ HTTPS + W3C trace context
  ▼
API Gateway (Traefik)
  ├─ TLS, routing, health, timeouts/retry
  ├─ distributed rate/concurrency protection
  ├─ access logs, metrics, OTLP traces
  └─ ForwardAuth
       │
       ▼
gateway-auth ───► Auth Service (JWKS)
       │                 │
       └──────────────► Redis (denylist/revocation)
       │
       ▼
User / Order / Product / Payment / Chat services
       │
       ▼
OpenTelemetry Collector → traces (Jaeger), access logs qua filelog receiver (Loki)
(Prometheus scrape metrics trực tiếp từ gateway/auth, không qua Collector)
```

Gateway được giữ mỏng: không tổng hợp business data, không query database của service và không quyết định resource ownership.

## Định tuyến và khả năng phục hồi

Route dùng host/path/method rules và chỉ map đến service đã khai báo. Docker label discovery hỗ trợ local development; Kubernetes Gateway API/CRDs là nguồn production. Mọi service có health checks, timeouts giới hạn và retry behavior theo idempotency. Quy ước định nghĩa route priority, hành vi với path không xác định, WebSocket và Stripe webhook exceptions.

### Ngân sách timeout và hủy request

Gateway áp dụng một ngân sách timeout xuyên suốt thay vì để từng lớp dùng giá trị mặc định không giới hạn. Đọc request client tối đa 15 giây, ghi response tối đa 60 giây và keep-alive idle tối đa 120 giây. Kết nối upstream tối đa 3 giây; response header của public/auth API, business API và payment API lần lượt tối đa 10, 25 và 20 giây.

Stripe webhook có tổng ngân sách proxy 5 giây, không retry và không buffer/biến đổi body. ForwardAuth có deadline 3 giây; JWKS là 3 giây và Redis là 1 giây. Nếu bất kỳ dependency xác thực nào hết thời gian, route protected trả `503` và tuyệt đối không fail open.

Khi client hủy request hoặc gateway hết deadline, context bị hủy được truyền xuống upstream và `gateway-auth`. Service đặt deadline nội bộ ngắn hơn gateway ít nhất một giây. Retry chỉ được bắt đầu nếu còn đủ ngân sách cho lần thử kế tiếp và chỉ áp dụng cho method idempotent. Quy ước vận hành quy định đầy đủ các giá trị, mã lỗi và graceful shutdown trong [runtime-contract.md](runtime-contract.md#51-ngân-sách-timeout).

## Xác thực và phân quyền

Traefik gọi `gateway-auth` thông qua ForwardAuth trên protected routes. Service này verify JWT issuer, audience, signature algorithm, expiry, JWKS, denylist và user-wide revocation, sau đó phát một tập identity headers tối thiểu trong allowlist.

Thiết kế này thay legacy Traefik plugin bằng service lifecycle bình thường, có thể test độc lập. Authentication dependency failure fail closed.

Mỗi downstream service sở hữu authorization. Ví dụ, Order Service quyết định identity đã authenticate có được sửa một order cụ thể hay không. Service chỉ tin cậy identity headers từ gateway/auth private network và chặn việc client inject trực tiếp chúng.

## Giới hạn tốc độ

Redis-backed shared state làm quota đúng trên nhiều gateway replicas. Chính sách phân biệt anonymous IP traffic, authenticated identity/API-key/tenant traffic, payment action, WebSocket handshake và Stripe delivery. Body size và concurrency controls bổ trợ rate limits. Payment write và provider webhook không bao giờ bị gateway retry; Payment Service sở hữu idempotency.

## Khả năng quan sát

Gateway tiếp tục hoặc bắt đầu W3C Trace Context và export OTLP traces tới OpenTelemetry Collector. Access logs là JSON xuất ra stdout; cùng một Collector đọc log qua `filelog` receiver và export sang Loki, giữ nguyên `trace_id`/`span_id` để correlate log với trace. Private metrics (Prometheus scrape trực tiếp endpoint `metrics`, không qua Collector) hỗ trợ SLOs và alerts. Tokens, cookies, request bodies, Stripe signatures, passwords và full query strings được redact khỏi cả log lẫn trace.

Application services phải được instrument riêng để trace có thể đi xuyên gateway, authentication và business operation.

## WebSocket và webhook

WebSocket authenticate tại HTTP Upgrade handshake, sau đó frame được proxy mà không inspect từng frame. Chat Service có trách nhiệm đóng sessions sau revocation events.

WebSocket handshake có timeout 10 giây. Sau khi kết nối được nâng cấp, HTTP timeout không quản lý frame; Chat Service gửi ping mỗi 30 giây và đóng connection không có pong trong 90 giây. Khi gateway drain lúc rollout, client cần reconnect.

`POST /api/payments/webhook/stripe` bypass JWT vì Payment Service verify `Stripe-Signature` trên raw body không thay đổi. Gateway áp dụng IP/rate policy riêng, forward nhanh và không retry.

## Ranh giới bảo mật và các mục tiêu không thuộc phạm vi

Traffic công khai chỉ dùng HTTPS; forwarded headers chỉ tin cậy từ proxy đã cấu hình. Metrics và dashboard là private operational endpoints. Secrets được externalize, images/configuration được validate trong CI và containers áp dụng least privilege.

Gateway không phải backend-for-frontend aggregation layer, centralized business-authorization engine, per-WebSocket-frame security proxy, hoặc thay thế service telemetry.
