# Authentication Service Architecture Design

## 1. Overview
Auth Service đóng vai trò là Identity Provider trung tâm của hệ thống E-Commerce Microservices. Nhiệm vụ chính bao gồm Đăng ký, Đăng nhập, Quản lý Vòng đời Token (cấp phát, làm mới, thu hồi), cung cấp Public Key (JWKS) cho API Gateway, và kiêm luôn vai trò **Hộp đen ghi âm (Security & Audit Center)** để lưu trữ các lịch sử đăng nhập, thay đổi mật khẩu và hành vi nhạy cảm của toàn bộ hệ thống.
## 2. Token Lifecycle Management

Hệ thống triển khai kiến trúc bảo mật nhiều tầng (Dual Token pattern kết hợp Redis Denylist).

### 2.1. Access Token (Tầng 1 - Hàng rào chính)
- **Chuẩn:** JWT (JSON Web Token), tự chứa (self-contained) thông tin xác thực cơ bản (`user_id`, `roles`, `jti`).
- **Short TTL:** Thời gian sống ngắn (ví dụ: 15 phút). Mục đích là thu hẹp cửa sổ rủi ro nếu token bị đánh cắp. Sau 15 phút, token tự vô hiệu hóa mà không tốn chi phí đồng bộ nào.
- **Claim `jti` (JWT ID):** Yêu cầu bắt buộc phải sinh một mã UUID duy nhất đưa vào trường `jti` cho từng Access Token được phát hành. Đây là key để quản lý thu hồi linh hoạt.

### 2.2. Refresh Token
- **Chuẩn:** Opaque Token (chuỗi ngẫu nhiên không có ý nghĩa tự thân).
- **Long TTL:** Thời gian sống dài (ví dụ: 7 ngày hoặc 30 ngày).
- **Lưu trữ:** Lưu trữ server-side trong Database hoặc Redis. Được liên kết với User ID và Device ID.
- **Cách dùng:** Khi Access Token hết hạn, Client dùng Refresh Token để xin cấp Access Token mới. Nếu Refresh Token bị đánh dấu là revoked trên server, quá trình cấp mới thất bại, vô hiệu hóa phiên đăng nhập một cách triệt để.

## 3. Token Revocation Mechanism (Tầng 2 - Thu Hồi Khẩn Cấp)

Bài toán: Cần thu hồi Access Token NGAY LẬP TỨC (trước khi 15 phút TTL kết thúc) trong các kịch bản quan trọng như: Đăng xuất (Logout) thiết bị hiện tại, Đổi mật khẩu, Block tài khoản, hoặc Đăng xuất khỏi mọi thiết bị (Force Logout).

### 3.1. Thu hồi Thiết bị hiện tại (Single Device - Denylist)
Dành cho trường hợp User chủ động bấm **Đăng xuất** trên máy đang dùng (Client có gửi lên Token cụ thể).
1. **Refresh Token Revocation:** Đánh dấu Refresh Token của phiên liên quan thành vô hiệu trong DB.
2. **Access Token Denylist:**
   - Đưa định danh `jti` của JWT hiện tại vào danh sách bị từ chối trong Redis.
   - **Command:** `SET auth:denylist:{jti} 1 EX {remaining_seconds}`
   - **Lợi ích:** Tự động dọn dẹp bộ nhớ theo TTL. Gateway chặn đứng Request mang `jti` này.

### 3.2. Thu hồi Toàn bộ Thiết bị (Force Logout - User Revision Timestamp)
Dành cho trường hợp Đổi mật khẩu hoặc Admin Ban tài khoản. Máy chủ Auth **không thể biết** `jti` của các Token trên các thiết bị khác (do thiết kế Stateless JWT). Tùy chọn Denylist là bất khả thi.
1. **Trigger:** User đổi mật khẩu hoặc bị Khóa.
2. **Gắn cờ toàn Server:** Đánh dấu một mốc thời gian thu hồi tất cả cấp độ User vào Redis.
   - **Command:** `SET auth:revoked_before:{user_id} {current_timestamp}`
3. **Cơ chế Gateway:** Gateway khi Verify một JWT hợp lệ, tiếp tục check nếu `jwt.iat` (thời điểm cấp phát token) NHỎ HƠN hoặc BẰNG `auth:revoked_before:{user_id}` => Chặn lập tức. Điều này bãi bỏ toán bộ Token cũ khỏi vòng đời mà không cần nắm giữ `jti`.

## 4. Key Management (Distribution)
- Auth Service chịu trách nhiệm sinh cặp khóa Asymmetric (ví dụ: RSA256).
- **Khóa Private:** Giữ tuyệt mật tại Auth Service, chỉ dùng để ký token.
- **Khóa Public:** Expose qua một endpoint chuẩn JSON Web Key Set (JWKS), ví dụ: `GET /.well-known/jwks.json`.
- API Gateway sẽ gọi JWKS endpoint này và cache lại public key để thực hiện thao tác verify signature tại Gateway cực kỳ nhanh chóng mà không cần network call về Auth Service ở mọi request. Khóa này có thể được rotation định kỳ để tăng cường bảo mật.
