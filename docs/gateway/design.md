# API Gateway Architecture Design

## 1. Overview
API Gateway đóng vai trò là điểm vào duy nhất (Single Entry Point) cho toàn bộ hệ thống E-Commerce Microservices. Nhiệm vụ chính của Gateway bao gồm Routing, Rate Limiting, Monitoring, và đặc biệt là **Authentication (Xác thực) tập trung**. 

## 2. Authentication Strategy (Uniform Security Checking)

Hệ thống sử dụng chiến lược **Stateless Authentication** thông qua JSON Web Token (JWT) kết hợp với **Redis Denylist** để xử lý bài toán thu hồi token (Token Revocation).

### 2.1. Authentication Flow
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

## 3. Performance & Optimizations
- **JWKS Local Cache:** Gateway duy trì một bản sao của JWKS trong bộ nhớ nội bộ (TTL 10 phút). Điều này giúp loại bỏ hoàn toàn network call đến Auth Service trong điều kiện bình thường.
- **Immediate Invalidation (Redis Pub/Sub):** Gateway subscribe vào channel `auth:jwks:update`. Khi Auth Service xoay key, Gateway sẽ xóa cache local ngay lập tức để lấy key mới, đảm bảo tính nhất quán mà không phụ thuộc hoàn toàn vào TTL.
- **Connection Pooling:** API Gateway duy trì persistent connection tới Redis Cluster để tối ưu hóa việc lookup Denylist.

## 4. Authorization (Phân quyền)
- API Gateway **KHÔNG** làm nhiệm vụ Authorization (kiểm tra quyền hạn). 
- Gateway chỉ trả lời câu hỏi: *"Mày là ai và token này có hợp lệ không?"*.
- Câu hỏi *"Mày được làm gì (Mày có quyền sửa order không)?"* được ủy quyền hoàn toàn (delegate) cho từng Downstream Service cụ thể kiểm tra, vì chỉ có các service đó mới nắm Business Context.
