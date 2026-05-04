# Auth Service - Database Schema Design

**Liên kết nhanh:**
*   [Workflows (API & Data Flow)](auth_workflows.md)
*   [Background Workers & Jobs](auth_workers.md)
*   [Caching Schema (Redis)](auth_caching_schema.md)
*   [Database Schema](auth_db_design.md)
*   [Events Structure & Kafka Topics](auth_events.md)

---
Tài liệu này mô tả chi tiết schema CSDL (PostgreSQL) cho Auth Service. Theo thiết kế phân rã (Bounded Context), Auth Service **chỉ quản lý Credentials** (Dữ liệu phục vụ hệ thống xác thực người dùng bao gồm Email/Username, Mật khẩu, Roles, và Trạng thái bảo mật). Thông tin cá nhân như Tên thật, Địa chỉ, SĐT sẽ được đẩy sang `Profile Service`.

## 1. Bảng `users` (Quản lý Định danh & Credentials)

Bảng này lưu trữ thông tin đăng nhập tối thiểu và trạng thái của một tài khoản.

| Column Name | Data Type | Constraints / Indexes | Description |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | Primary Key, Auto Gen | Định danh toàn cầu của User dùng xuyên suốt hệ thống. |
| `email` | `VARCHAR(255)` | Unique, Indexed | Email/Username dùng để đăng nhập. |
| `password_hash` | `VARCHAR(255)` | Not Null | Chuỗi hash mật khẩu (Sử dụng Argon2id / BCrypt). **Tuyệt đối không lưu plain text**. |
| `roles` | `JSONB` hoặc `VARCHAR[]` | Default `['USER']` | Danh sách quyền của User. Ví dụ: `["USER", "ADMIN"]`. Auth cấp phát claim này vào JWT. |
| `status` | `VARCHAR(50)` | Default `'PENDING_VERIFICATION'` | `PENDING_VERIFICATION` (Chờ kích hoạt Email), `ACTIVE`, `LOCKED` (sai pass nhiều), `BANNED`. |
| `locked_at` | `TIMESTAMP` | Nullable, Indexed | Lưu thời điểm tài khoản bị khóa (`LOCKED`). Dùng để tính thời gian tự động mở khóa sau 30 phút. |
| `failed_login_attempts` | `INTEGER` | Default `0` | Số lần đăng nhập sai liên tiếp. Reset về 0 khi đăng nhập thành công hoặc bị khóa. |
| `email_verified_at` | `TIMESTAMP` | Nullable | Lưu thời điểm xác thực email thành công. |
| `created_at` | `TIMESTAMP` | Default NOW() | |
| `updated_at` | `TIMESTAMP` | Mapped qua Trigger | |

## 2. Bảng `refresh_tokens` (Quản lý Phiên bản của Token)

Bảng này đặc biệt quan trọng để áp dụng cơ chế **Refresh Token Rotation có Grace Period 10s**, giúp hệ thống vừa chống đánh cắp (Token Theft), vừa chống gián đoạn ở phía app do lỗi mạng / concurrency.

> **Giải thích Rotation Cùng Grace Period:** Khi Token A được đổi lấy Token B, Token A bị đánh dấu là `revoked_at` (vào chính thời điểm đổi). B và A cùng nằm chung một `family_id`. 

| Column Name | Data Type | Constraints / Indexes | Description |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | Primary Key | ID nội bộ của refresh token record. |
| `user_id` | `UUID` | Indexed, FK -> `users(id)` | Chủ sở hữu token này. |
| `token_hash` | `VARCHAR(255)` | Unique, Indexed | Chuỗi Hash (SHA-256) của chuỗi Opaque Refresh Token gửi về Client. *Vì sao dùng Hash?* Đề phòng DB leak, hacker cũng ko có chuỗi token gốc để gửi request. |
| `family_id` | `UUID` | Indexed | (Tùy chọn) Mã chuỗi phiên đăng nhập từ lúc bắt đầu login đến lần xoay cuối cùng. Hỗ trợ truy vết. |
| `replaced_by` | `VARCHAR(255)` | Nullable | Lưu UUID của Refresh Token record mới sinh ra thay thế. **CHÚ Ý:** Cột này KHÔNG phục vụ luồng Grace Period trả token về Client (Redis đảm nhận việc này vì phải trả plain-text), mà chỉ dùng thuần túy cho mục đích Audit Security. |
| `device_id` | `VARCHAR(255)` | Indexed | Fingerprint ID sinh tĩnh tại Client để hệ thống nhận diện "Thiết bị đáng tin cậy" hoặc bắt Bot/Spam qua cùng 1 Device ID. |
| `device_info` | `VARCHAR(255)` | Nullable | Client-Agent, IP, Device Name gửi lúc login để user nhận diện máy khi xem trang "Lịch sử đăng nhập". |
| `expires_at` | `TIMESTAMP` | Indexed | Thời điểm token hết hạn tự nhiên. Ex: 30 ngày từ lúc sinh. |
| `revoked_at` | `TIMESTAMP` | Nullable | Thời điểm token bị thu hồi do Request Rotate hoặc do phát hiện đánh cắp / Logout. |
| `created_at` | `TIMESTAMP` | Default NOW() | |

### Mối quan hệ với Grace Period Timeout:
Tại DB, ta có thể dùng công thức toán dựa theo `revoked_at` để check tính hợp lệ của Grace Period: `NOW() <= revoked_at + INTERVAL '10 seconds'`. Tuy nhiên, để tối ưu tốc độ và lấy lại được chuỗi Opaque token gốc gửi về client, chúng ta **chỉ xử lý logic này tại Redis cache** (xem chi tiết ở schema Caching). Bảng DB đóng vai trò là Source of Truth lưu trữ hành trình biến đổi của token.

## 3. Bảng `outbox_events` (Transactional Outbox Pattern qua Debezium)

Sử dụng mô hình Outbox để đảm bảo Transaction SQL cập nhật User và Publish Event (e.g. `USER_VERIFIED_SUCCESS`, `USER_REGISTERED`) là một quá trình ALL-OR-NOTHING. 

*   Chúng ta sử dụng **Debezium CDC** (Change Data Capture) cắm vào Write-Ahead Log (WAL) của PostgreSQL thay vì làm background Polling Worker để tiết kiệm Resource Database. Do đó bảng `outbox_events` cần được thiết kế chỉ tuân thủ đúng yêu cầu của bộ Connector.

| Column Name | Data Type | Constraints / Indexes | Description |
| :--- | :--- | :--- | :--- |
| `event_id` | `UUID` | Primary Key, Auto Gen | Định danh Event |
| `event_type` | `VARCHAR(100)` | Not Null | Tên luồng Event (Ex: `USER_VERIFIED_SUCCESS`) |
| `aggregate_type` | `VARCHAR(100)` | Indexed | Tên kiểu Aggregate (VD: `User`) |
| `aggregate_id` | `UUID` | Indexed | UUID của `user_id` |
| `partition_key` | `UUID` | Indexed | Partition Key của Kafka để đảm bảo thứ tự gửi của user |
| `payload` | `JSONB` | Not Null | Data bắn qua Kafka (Không chứa thông tin nhạy cảm) |
| `created_at` | `TIMESTAMP` | Default NOW() | Debezium không tự xóa bản ghi nếu không cấu hình rõ ràng. Cần cron dọn rác định kỳ. |

**Chính sách dọn rác đề xuất:** Cron xóa outbox cũ hơn 7-14 ngày (tùy SLA audit), ví dụ:
`DELETE FROM outbox_events WHERE created_at < NOW() - INTERVAL '14 days';`

## 4. Bảng `jwt_keys` (Keystore cho JWKS Rotation)

Keystore là nguồn dữ liệu gốc để Auth Service xuất JWKS và ký JWT. Dữ liệu nhạy cảm (private_key) cần mã hóa ở tầng ứng dụng hoặc KMS.

| Column Name | Data Type | Constraints / Indexes | Description |
| :--- | :--- | :--- | :--- |
| `kid` | `VARCHAR(100)` | Primary Key | Key ID dùng trong JWT header. |
| `public_key` | `TEXT` | Not Null | Public key ở dạng PEM/JWK. |
| `private_key` | `TEXT` | Not Null | Private key đã mã hóa. |
| `status` | `VARCHAR(20)` | Indexed | `ACTIVE`, `PHASE_OUT`, `RETIRED`. |
| `created_at` | `TIMESTAMP` | Default NOW() | Thời điểm tạo key. |
| `rotated_at` | `TIMESTAMP` | Nullable | Thời điểm đưa vào PHASE_OUT. |
| `expires_at` | `TIMESTAMP` | Nullable | Thời điểm có thể xóa khỏi keystore. |

## 5. Bảng `audit_logs` (Quản lý Nhật ký Kiểm toán & Bảo mật)

Bảng này đóng vai trò như hộp đen ghi lại mọi hành vi liên quan tới danh tính và bảo mật. Nhờ cơ chế hợp nhất, Auth Service trực tiếp lưu trữ các log này thay vì phải gửi đi đâu xa. Dữ liệu payload có cấu trúc linh hoạt nhờ kiểu `JSONB`.

| Column Name | Data Type | Constraints / Indexes | Description |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | Primary Key | Định danh của log (nhận giá trị từ `event_id` nếu là event ngoại bộ để đảm bảo lũy đẳng). |
| `actor_id` | `VARCHAR(255)` | Indexed | UUID của người dùng hoặc hệ thống kích hoạt sự kiện này. |
| `actor_type` | `VARCHAR(50)` | | Phân loại actor: `USER`, `ADMIN`, `SYSTEM`, `GUEST` (ẩn danh hacker rà password). |
| `event_type` | `VARCHAR(100)` | Indexed | Khóa định danh sự kiện: `USER_LOGGED_IN`, `PROFILE_SENSITIVE_UPDATED`. |
| `occurred_at` | `TIMESTAMP` | Indexed | Thời gian chính xác diễn ra sự kiện. |
| `ip_address` | `VARCHAR(45)` | | Địa chỉ IP gửi Request gốc tạo ra sự kiện này. |
| `device_id` | `VARCHAR(255)` | | Tùy chọn, Mã fingerprint tĩnh cố định thiết bị. |
| `device_info` | `VARCHAR(255)` | | Tùy chọn, trích từ User-Agent. |
| `security_level` | `VARCHAR(50)` | Indexed | Mức độ nguy hiểm: `INFO`, `WARNING`, `CRITICAL`. |
| `payload` | `JSONB` | GIN Indexed (tuỳ chọn)| Trường ĐA HÌNH chứa thông tin cụ thể (Dynamic). |
| `log_category` | `VARCHAR(50)` | Indexed | Phân loại retention: `USER` (Xoá sau 6 tháng), `SYSTEM` (Lưu giữ vĩnh viễn). |

**Chính sách dọn rác đề xuất:** Sử dụng Cron job nội bộ chạy lệnh `DELETE FROM audit_logs WHERE log_category = 'USER' AND occurred_at < NOW() - INTERVAL '6 months';` để tự động dọn dẹp các log của người dùng đã quá hạn 6 tháng, tiết kiệm dung lượng lưu trữ cho Database.
