# Auth Service - Caching Schema

**Liên kết nhanh:**
*   [Workflows (API & Data Flow)](auth_workflows.md)
*   [Background Workers & Jobs](auth_workers.md)
*   [Caching Schema (Redis)](auth_caching_schema.md)
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
    *   `auth:rate_limit:login:{ip_address}` - Đếm số lần đăng nhập từ IP
    *   `auth:rate_limit:register:{ip_address}` - Chống tạo hằng hà sa số tài khoản rác
    *   `auth:rate_limit:forgot_pw:{ip_address}` - Chống dùng API làm tools SMS Bombing
    *   `auth:rate_limit:resend_otp:{email}` - Chống spam resend OTP theo từng định danh
*   **Mô tả**: Đếm số Request gọi vào các API nhạy cảm
*   **Loại Dữ Liệu**: `INCR / Token Bucket` (bằng Redis Module hoặc basic counters)
*   **TTL**: Thường là 1 - 5 phút (ví dụ Cấm 5 phút nếu gõ sai pass 10 lần, hoặc chỉ được request 1 mã OTP mỗi phút)

### 1.3.2 Khóa Đếm Sai Mật Khẩu (Failed Login Attempts)
Cơ chế này theo dõi số lần đăng nhập sai password của từng email để tự động khóa tài khoản khi vượt ngưỡng.

*   **Key Pattern**: `auth:failed_login:{email}`
    *   **Mô tả**: Đếm số lần đăng nhập thất bại (sai mật khẩu) của một email cụ thể
    *   **Loại Dữ Liệu**: `INCR` Counter
    *   **TTL**: **60 phút** - Counter tự reset sau 1 giờ không có attempt mới
*   **Key Pattern**: `auth:failed_login:{ip_address}`
    *   **Mô tả**: Đếm số lần đăng nhập thất bại từ một IP cụ thể (chống distributed attack)
    *   **Loại Dữ Liệu**: `INCR` Counter
    *   **TTL**: **60 phút**
*   **Cấu trúc Value**: JSON Object
    ```json
    {
      "count": 3,
      "last_attempt_at": "2026-05-04T10:00:00Z",
      "locked_until": null
    }
    ```

**Quy trình xử lý khi đăng nhập thất bại:**

1. Khi user nhập sai password:
   *   Thực hiện `INCR auth:failed_login:{email}` và `EXPIRE 3600` (60 phút)
   *   Thực hiện `INCR auth:failed_login:{ip_address}` và `EXPIRE 3600`
   *   Lấy giá trị hiện tại của `auth:failed_login:{email}`

2. Kiểm tra ngưỡng:
   *   **Ngưỡng 1 (5 lần sai)**: Sau 5 lần sai liên tiếp trong vòng 60 phút, tài khoản bị `LOCKED`
   *   Khi đạt ngưỡng:
       *   Update DB: `UPDATE users SET status = 'LOCKED', locked_at = NOW() WHERE email = ? AND status = 'ACTIVE'`
       *   Reset Redis counter: `DEL auth:failed_login:{email}`
       *   Gửi Kafka Event `USER_ACCOUNT_LOCKED` cho Notification Service (thông qua **Email**)
       *   Ghi log `USER_ACCOUNT_LOCKED` vào `audit_logs`

3. Khi đăng nhập thành công:
   *   Reset counter: `DEL auth:failed_login:{email}` và `DEL auth:failed_login:{ip_address}`

4. **Lưu ý bảo mật**:
   *   Không tiết lộ số lần sai còn lại cho client (trả về generic message)
   *   Thời gian lock tối thiểu là **30 phút** (Worker auto-unlock sẽ xử lý)
   *   IP bị rate limit độc lập với email (chống distributed brute force)

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


## 2. JWKS Caching Strategy

### 2.1 Auth Service Internal Cache
Phục vụ nhanh endpoint `GET /.well-known/jwks.json` mà không cần query DB mỗi lần.
*   **Loại Cache**: In-Memory.
*   **TTL**: **5 phút**.
*   **Logic**: 
    *   `HIT`: Trả về danh sách Public Keys (JWKS).
    *   `MISS`: Query DB tìm các key có status `ACTIVE` hoặc `PHASE_OUT`, build JSON, ghi vào cache.
*   **Invalidation**: Khi Key Rotation Worker chạy xong, nó sẽ xóa cache này.

### 2.2 API Gateway Local Cache (nằm ở phần thiết kế của API Gateway)
API Gateway lưu trữ toàn bộ JWKS để verify JWT ngay tại Gateway mà không cần gọi sang Auth Service cho mỗi request.
*   **Loại Cache**: Caffeine hoặc Guava (In-Memory trên mỗi node Gateway).
*   **Cấu trúc**: Map `kid -> Public Key`.
*   **TTL**: **10 phút**.
*   **Invalidation (Gần như tức thì)**: 
    *   Gateway subscribe vào Redis channel `auth:jwks:update`.
    *   Khi nhận message `JWKS_UPDATED`, Gateway thực hiện xóa (clear) toàn bộ local cache JWKS.
*   **Workflow Fetch**: 
    *   Nếu `kid` trong JWT không có trong cache -> Gọi Auth Service JWKS endpoint -> Cập nhật cache -> Verify.
    *   Nếu vẫn không thấy sau khi fetch -> Trả về 401 Unauthorized.
