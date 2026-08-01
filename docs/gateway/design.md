# API Gateway Architecture Design

## 1. Overview
API Gateway đóng vai trò là điểm vào duy nhất (Single Entry Point) cho toàn bộ hệ thống E-Commerce Microservices. Nhiệm vụ chính của Gateway bao gồm Routing, Rate Limiting, Authentication (Xác thực) và Logging / Tracing


## 2. Routing (Định tuyến thông minh)
Gateway đóng vai trò như một "bảng chỉ đường" trung tâm. Client chỉ cần gọi một endpoint duy nhất, Gateway sẽ dựa vào path, method, header để điều hướng request đến đúng downstream service.

### 2.1. Cơ chế Routing
- **Path-based routing:**  
  - `/api/users/**` → `user-service`  
  - `/api/orders/**` → `order-service`  
  - `/api/products/**` → `product-service`  
  - `/api/payments/**` → `payment-service`
- **Dynamic Service Discovery:** Gateway không cấu hình cứng địa chỉ service, mà sử dụng **Service Registry** (Consul, Eureka, hoặc Kubernetes DNS) để lấy danh sách các instance đang healthy. Cơ chế `Round Robin` hoặc `Least Connection` được dùng để load balancing nội bộ.

### 2.2. Webhook Passthrough — Stripe (Phase 18)

> Hệ thống chỉ dùng 1 payment provider duy nhất là Stripe. Webhook URL cố định: `POST /api/payments/webhook/stripe`.

Gateway phải route **nguyên vẹn raw body + headers** của Stripe webhook đến Payment Service. Quy tắc bắt buộc:

1. **Không filter body**: KHÔNG parse JSON, KHÔNG modify payload. Stripe SDK dùng raw body để verify signature, nếu Gateway parse/sanitize body sẽ phá HMAC.
2. **Không verify JWT cho route này**: Webhook authenticate bằng `Stripe-Signature` header (HMAC-SHA256 với `whsec_...`) ở Payment Service, không phải JWT.
3. **IP Whitelist (khuyến nghị)**: Stripe publish danh sách IP ranges trong dashboard; Gateway nên whitelist các IP này để giảm noise.
4. **Timeout ngắn (< 5s)**: Stripe retry với exponential backoff nếu response > 5s. Gateway phải forward nhanh, không buffer.
5. **Response 2xx ngay khi Payment Service trả**: KHÔNG retry webhook ở Gateway layer; retry policy thuộc Stripe.

## 3. Authentication Strategy (Uniform Security Checking)

Hệ thống sử dụng chiến lược **Stateless Authentication** thông qua JSON Web Token (JWT) kết hợp với **Redis Denylist** để xử lý bài toán thu hồi token (Token Revocation).

### 3.1. Authentication Flow
Khi một request có mang JWT đi qua API Gateway, quy trình sau sẽ được thực hiện:

1. **Token Extraction:** Trích xuất JWT từ header `Authorization: Bearer <token>`.
2. **Signature Verification (Local Cache First):**
   - Extract `kid` (Key ID) từ JWT header.
   - Tìm kiếm Public Key trong **Local Cache (Caffeine/Guava)** của Gateway.
   - **Trường hợp Cache Hit:** Dùng Public Key để verify chữ ký.
   - **Trường hợp Cache Miss:**
     - Gọi Auth Service endpoint `GET /.well-known/jwks.json`.
     - Cập nhật toàn bộ JWKS vào Local Cache.
     - Nếu tìm thấy `kid` mới -> Verify. Nếu vẫn không thấy -> Reject request (401 Unauthorized).
3. **Claim Validation:** Kiểm tra các thông tin thời gian như `exp` (Expiration Time) và `nbf` (Not Before). Nếu token đã hết hạn, lập tức reject (401 Unauthorized).
4. **Denylist Check (Redis Lookup):**
   - Extract claim `jti` (JWT ID - định danh duy nhất của token).
   - Thực hiện query tới Redis Cluster: `GET auth:denylist:{jti}`.
   - Nếu tồn tại: Token đã bị thu hồi ➔ Reject request (401 Unauthorized).
5. **Revocation Check (User Revision):** Kiểm tra claim `iat` (Issued At) với giá trị `auth:revoked_before:{user_id}` trên Redis để xử lý Force Logout/Password Change.
6. **Header Enrichment:** API Gateway extract payload (`user_id`, `roles`, `device_id`) đưa vào header (vd: `X-User-Id`, `X-User-Roles`) để truyền xuống các downstream service.

### 3.2. WebSocket Authentication

**API Gateway chỉ verify JWT tại thời điểm WebSocket handshake (HTTP Upgrade)**. Sau khi WebSocket được thiết lập thành công, Gateway proxy trực tiếp các frame giữa client và downstream service mà không filter từng frame.

**Hệ quả bảo mật:**
- Nếu token bị revoke sau khi WebSocket đã established, các frame vẫn đi qua Gateway
- Hacker đã mở WebSocket trước khi bị phát hiện vẫn có thể gửi/nhận data với Chat Service với identity đã được enriched lúc handshake
- Do đó, khi Auth Service phát hiện `TOKEN_THEFT_DETECTED`, nó sẽ:
  1. SET `auth:revoked_before:{user_id}` để chặn mọi **request HTTP** tiếp theo
  2. Publish `TOKEN_THEFT_DETECTED` event để downstream services (đặc biệt là Chat Service) chủ động **đóng WebSocket connection** của user đó

**Giải pháp thay thế (nếu cần):**
- Nếu muốn Gateway active filter mọi WebSocket frame, cần implement stateful proxy tại Gateway, tốn thêm tài nguyên và phức tạp hơn nhiều.

## 4. Performance & Optimizations
- **JWKS Local Cache:** Gateway duy trì một bản sao của JWKS trong bộ nhớ nội bộ (TTL 10 phút). Điều này giúp loại bỏ hoàn toàn network call đến Auth Service trong điều kiện bình thường.
- **Immediate Invalidation (Redis Pub/Sub):** Gateway subscribe vào channel `auth:jwks:update`. Khi Auth Service xoay key, Gateway sẽ xóa cache local ngay lập tức để lấy key mới, đảm bảo tính nhất quán mà không phụ thuộc hoàn toàn vào TTL.
- **Connection Pooling:** API Gateway duy trì persistent connection tới Redis Cluster để tối ưu hóa việc lookup Denylist.

## 5. Authorization (Phân quyền)
- API Gateway **KHÔNG** làm nhiệm vụ Authorization (kiểm tra quyền hạn). 
- Gateway chỉ trả lời câu hỏi: *"Mày là ai và token này có hợp lệ không?"*.
- Câu hỏi *"Mày được làm gì (Mày có quyền sửa order không)?"* được ủy quyền hoàn toàn (delegate) cho từng Downstream Service cụ thể kiểm tra, vì chỉ có các service đó mới nắm Business Context.

## 6. Rate Limiting (Giới hạn tốc độ)
Bảo vệ hệ thống khỏi bị lạm dụng (DDoS, brute force, crawler quá mức) bằng cách giới hạn số request từ một client trong một khoảng thời gian.

## 7. Logging & Tracing (OpenTelemetry Integration)
Đây là phần giúp toàn bộ hệ thống có khả năng quan sát (observability). Gateway là điểm đầu tiên sinh ra **Trace ID** và đảm bảo nó được truyền xuyên suốt.

### 7.1. Logging chi tiết
Mỗi request qua Gateway đều được ghi log dưới dạng JSON với các trường bắt buộc:

```json
{
  "timestamp": "2025-05-02T10:23:45.100Z",
  "level": "info",
  "trace_id": "a1b2c3d4e5f6...",
  "span_id": "span001",
  "service": "api-gateway",
  "event": "request_received",
  "method": "GET",
  "path": "/api/orders/123",
  "client_ip": "192.168.1.100",
  "user_id": "789",
  "duration_ms": 0
}
```

### 7.2. Distributed Tracing với OpenTelemetry
**Trace ID sinh tại Gateway:** Nếu request không có header traceparent, Gateway sinh mới.
**Inject header:** Mỗi khi Gateway gọi downstream service (bằng HTTP/gRPC), nó tự động thêm header traceparent: 00-{trace_id}-{span_id}-01.
**Span tạo tự động:**
   - Span root: Xử lý toàn bộ request tại Gateway.
   - Span con cho mỗi lần gọi service (order-service, product-service...).
   - Span con cho database query (nếu Gateway có cache hay gọi DB).
**Log correlation:** Logger của Gateway được cấu hình để tự động lấy trace_id và span_id từ context hiện tại (xem phần 7.1). Nhờ đó, mọi dòng log đều gắn kết với trace.
