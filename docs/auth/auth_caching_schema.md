# Auth Service - Caching Schema

**Liên kết nhanh:**
*   [Workflows (API & Data Flow)](auth_workflows.md)
*   [Background Workers & Jobs](auth_workers.md)
*   [Caching Schema (Redis & Caffeine)](auth_caching_schema.md)
*   [Database Schema](auth_db_design.md)
*   [Events Structure & Kafka Topics](auth_events.md)

---
Tài liệu này mô tả chiến lược Caching áp dụng trong Auth Service, chú trọng vào bài toán hiệu năng phát hành/thu hồi token (Dual Token Pattern) và cơ chế chống tái sử dụng song song.

## 1. Cấu Trúc Khóa (Key Pattern) trên Redis

Tất cả các Redis Key của Auth Service bắt đầu bằng prefix: `auth:`

### 1.1 Khóa Từ Chối Một Access Token (Single Device Denylist)
Dành cho trường hợp Đăng xuất tài khoản thông thường từ 1 thiết bị.

*   **Key Pattern**: `auth:denylist:{jti}`
    *   **Mô tả**: Lưu trữ Unique ID (`jti`) của JWT bị thu hồi.
    *   **Loại Dữ Liệu**: `String` (Value có thể là '1' hoặc timestamp).
    *   **TTL**: Đặt đúng bằng `Thời gian sống còn lại` (Remaining Expiration) của JWT.

### 1.1.2 Khóa Thu Hồi Toàn Bộ (User Revision Timestamp)
Dành cho trường hợp Đổi mật khẩu, Bị Banned hoặc Force Logout mọi máy. Do không biết hết các `jti` đang sống, hệ thống sẽ chốt một mốc thời gian chết.

*   **Key Pattern**: `auth:revoked_before:{user_id}`
    *   **Mô tả**: Lưu Timestamp thời điểm user bị kick. Gateway sẽ chặn mọi request dùng JWT có chứa claim `iat` <= Timestamp này.
    *   **Loại Dữ Liệu**: `String` (Value là Epoch Timestamp).
    *   **TTL**: Xấp xỉ TTL dài nhất của Access Token (Ví dụ Access_Token sống 15 phút thì TTL của key này là 16 phút). Sau 16 phút, mọi token cũ đều tự rụng theo exp, nên ko cần chặn nữa.

### 1.2 Khóa Thời Gian Ân Hạn (Refresh Grace Period)
Đây là Core Logic để giải quyết tình huống App (Frontend/Mobile) gửi đồng thời N requests refresh token vì Access Token hết hạn, khiến 1 request ăn được token mới, còn N-1 request kia bị Server kết luận là "Hành vi đánh cắp" (Token Repaly).

*   **Key Pattern**: `auth:refresh_grace:{old_refresh_token_hash}`
    *   **Mô tả**: Lưu lại BỘ JWT MỚI (bao gồm `access_token` và `refresh_token_mới`) dành riêng cho token cũ vừa bị xoay (rotated).
    *   **Loại Dữ Liệu**: `String` (Chứa JSON Serialized của Access/Refresh tokens).
    *   **TTL**: **Chính xác 10 Giây (10s)**. Đây là Grace Period.
    *   **Quy trình Invalidation**: Tự sinh ra lúc xoay Token cũ (Cache-Aside từ DB Create operation) và tự biến mất sau 10 giây.
    *   **Tác dụng**: Nếu 1 HTTP context Request đến Server, mang theo Token Hash A, nhưng Token Hash A ở DB đã bị `revoked_at`. Thay vì la làng lên là "Đánh cắp", hệ thống query Redis `auth:refresh_grace:{A}`:
        *   -> `HIT`: Hiểu là request này là The same client gửi lặp lại trong 10s vừa rồi do lag. Trả về Response y xì đúc kết quả HTTP 200 mà Request đầu tiên nhận được.
        *   -> `MISS`: Key sau 10s đã bốc hơi => Đây chính xác là Đánh Cắp (có đứa móc trộm file text ở đâu đó 2 tiếng sau mang lên xài). Xử lý trừng phạt.

### 1.3 Khóa Chống Brute-Force & Spam (Rate Limiting)
*   **Key Pattern**:
    *   `auth:rate_limit:login:{ip_address}` và `auth:rate_limit:login:{email}`
    *   `auth:rate_limit:register:{ip_address}` (Chống tạo hằng hà sa số tài khoản rác).
    *   `auth:rate_limit:forgot_pw:{ip_address}` (Chống dùng API làm tools SMS Bombing).
    *   `auth:rate_limit:resend_otp:{email_or_phone}` (Chống spam resend OTP theo từng định danh).
    *   **Mô tả**: Đếm số Request gọi vào các API nhạy cảm.
    *   **Loại Dữ Liệu**: `INCR / Token Bucket` (bằng Redis Module hoặc basic counters).
    *   **TTL**: Thường là 1 - 5 phút (ví dụ Cấm 5 phút nếu gõ sai pass 10 lần, hoặc chỉ được request 1 mã OTP mỗi phút).

### 1.4 Khóa OTP Xác Thực Email/Phone (Verification)
*   **Key Pattern**: `auth:otp:register:{email}`
    *   **Mô tả**: Lưu trữ mã OTP 6 chữ số sinh ra để kích hoạt tài khoản. Giảm tải IO cho Database thay vì lưu ở bảng tạm.
    *   **Loại Dữ Liệu**: `String`. Value: `Mã OTP`.
    *   **TTL**: 5 phút. Hết giờ yêu cầu bấm Gửi lại.

### 1.5 Khóa Đặt Lại Mật Khẩu (Forgot Password)
*   **Key Pattern**: `auth:reset_pw:{token_hash}`
    *   **Mô tả**: Khi User quên mật khẩu, hệ thống sinh ra một token random 32 bytes (rồi mã hoá hash), gửi email. Redis lưu token hash này trỏ về `user_id`.
    *   **Loại Dữ Liệu**: `String`. Value: `{user_id}`.
    *   **TTL**: 15 - 30 phút.

### 1.6 Khóa Xác Thực 2FA (MFA Pending)
*   **Key Pattern**: `auth:mfa_pending:{mfa_token}`
    *   **Mô tả**: Dùng ở màn hình Đăng nhập. Nếu Account đã bật 2FA, lần nhập Password đúng Server sẽ trả về một `mfa_token` sống siêu ngắn để Client cầm mã này gọi tiếp API xác nhận OTP (Google Authenticator). Tránh việc cấp Access_token trước khi hoàn tất 2FA.
    *   **Loại Dữ Liệu**: `String`. Value: `{user_id}`.
    *   **TTL**: 5 phút.

---

## 2. In-Memory JWKS (Caffeine)
API Gateway sẽ gọi lên Auth để lấy Public Keys (JWKS). Dữ liệu Public Key có thể sống tới cả tuần lễ (trước chu kỳ Rotation).

*   **Local Cache**: Do Public Key JSON là dữ liệu Global ai xem cũng được, Auth Service có thể nhét hẳn chuỗi JSON này vào bộ nhớ nội bộ (Caffeine) với `TTL = 5-10 phút`.
*   **Invalidation**: Khi xoay khóa, worker phát tín hiệu nội bộ (Pub/Sub hoặc event) để xóa cache JWKS ngay lập tức.
*   **Workflow**: Get JWKS -> Trả từ Caffeine (0.1ms). Nếu rỗng -> Fetch Redis hoặc Database -> Set Caffeine. Tốc độ cực hạn.
