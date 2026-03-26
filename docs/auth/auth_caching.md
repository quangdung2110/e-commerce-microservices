# Auth Service - Caching Schema & Rate Limiting (Redis)

**Liên kết nhanh:**
*   [Database Schema](auth_database.md)
*   [Workflows (API)](auth_workflow.md)

---
Auth Service sử dụng Redis cho 3 mục đích cốt lõi: **Rate Limiting (Chống Brute-force/Spam)**, **Lưu trữ OTP/Trạng thái tạm thời**, và **Quản lý Session Blacklist/Whitelist (JWT)**. Tốc độ xác thực ảnh hưởng đến mọi Service khác nên việc tra cứu trên Redis là tối quan trọng ở Auth Service.

## 1. Cơ Chế Rate Limiting (Bảo vệ hệ thống)
Ngăn chặn Brute-force dò mật khẩu và tấn công Exhaustion (làm cạn kiệt tài khoản trả phí SMS/Email). Các thao tác đếm dùng `INCR` và `EXPIRE` hoặc thuật toán Fixed Window/Sliding Window.

### 1.1 Tính năng OTP Request (Spam SMS/Email)
*   **Key**: `auth:rl:otp:req:{action}:{identity_value}` (Ví dụ: `auth:rl:otp:req:register:0981234567`)
*   **Giá trị**: Counter (Số nguyên đếm số lần gọi OTP).
*   **TTL**: 1 Tuần (Tùy chính sách chặn rác, có thể là 24h).
*   **Logic**: Chứa các rule (Max 3 lần / 15 phút, Max 5 lần / ngày). Vượt ngưỡng sẽ Return HTTP 429 Too Many Requests.

### 1.2 Giới hạn Đăng nhập sai (Brute-Force Account)
*   **Key**: `auth:rl:login:acc:{identity_value}`
*   **Giá trị**: Biến đếm số lần sai mật khẩu liên tiếp.
*   **TTL**: 30 phút.
*   **Logic**: Tăng lên 1 mỗi khi sai mật khẩu. Khi sai quá 5 lần, thiết lập một key khóa tạm thời `auth:lockout:{identity_value}` với TTL 1 giờ. Trong thời gian Lockout, bất kể gửi pass đúng hay sai cũng văng 429/403. Nếu đăng nhập đúng, xóa Key `auth:rl:login:acc:*`.

### 1.3 Giới hạn Đăng nhập IP (Brute-Force / Credential Stuffing)
*   **Key**: `auth:rl:login:ip:{client_ip}`
*   **Giá trị**: Số lượt đăng nhập (bất kể tài khoản nào).
*   **TTL**: 1 Giờ.
*   **Logic**: Chống việc một IP Botnet chạy credential stuffing bằng hàng chục ngàn tài khoản bị rò rỉ trên mạng. (Max 30 requests/hour/IP trên endpoint Login).

## 2. Dữ liệu Tạm thời (Ephemeral Data)

### 2.1 OTP Cache (Lưu mã xác nhận)
*   **Key**: `auth:otp:{action}:{identity_value}` 
    *   `action` bao gồm: `REGISTER`, `RESET_PASSWORD`, `CHANGE_MFA`.
*   **Giá trị**: Mã OTP 6 chữ số (Khuyến nghị **Hash bằng SHA256** trước khi lưu vào Redis để đề phòng DB Dump do Server Admin).
*   **TTL**: 5 Phút (Thời hạn hiệu lực mã OTP).

### 2.2 Device Tracking Cache
*   **Key**: `auth:devices:{user_id}`
*   **Cấu trúc**: Redis Set chứa các `device_id` đã xác thực gần đây.
*   **TTL**: 24 giờ.
*   **Mục đích**: Tăng tốc độ xác nhận "Thiết bị lạ" (Suspicious Login) thay vì query CSDL PostgreSQL.

## 3. JWT & Token Management

Auth Service cấp phát Access Token dạng JWT (Stateless). Tuy nhiên, để có khả năng **Thu hồi quyền bắt buộc (Immediate Revocation)** (Khi có báo cáo hack, user nhấn Đăng xuất, hoặc Admin Ban account), ta cần kết hợp Redis.

### 3.1 Token Blacklist (Denylist Pattern)
API Gateway trước khi tin tưởng Access Token sẽ cầm ID của Token (`jti`) xuống hỏi Auth/Redis.
*   **Key**: `auth:blacklist:{jti}`
*   **Giá trị**: `1` (Hoặc timestamp khoá).
*   **TTL**: Bằng đúng **thời gian sống còn lại (Remaining Time-to-live)** của Access Token bị thu hồi.
*   **Logic**: Khi Logout, lấy `exp` (Expiration) của Token trừ đi thời gian hiện tại ra TTL của Redis Key. Gateway thấy `jti` nằm trong Blacklist -> Từ chối yêu cầu (401 Unauthorized).

### 3.2 User Token Family/Whitelist (Tùy chọn cho High-Security)
Thay vì Blacklist, hệ thống có thể lưu một version khóa để invalid toàn bộ token.
*   **Key**: `auth:session_version:{user_id}`
*   **Giá trị**: `v1`, `v2`, `v3`... (Một UUID hoặc Integer).
*   **TTL**: Không thời hạn (cho đến khi user chưa đổi).
*   **Logic**: Khi User đổi mật khẩu, tăng `session_version` này lên. Trong payload của JWT Access Token phát hành có chứa trường `session_version`. Nếu Token version nhỏ hơn Redis Version -> Bị loại bỏ. (Kiểu này cho phép Kick-out All Devices ngay lập tức chỉ với 1 thao tác ghi).
