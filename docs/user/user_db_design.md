# User Service - Database Schema Design

**Liên kết nhanh:**
*   [Workflows (API & Data Flow)](user_workflows.md)
*   [Caching Schema (Redis)](user_caching_schema.md)
*   [Database Schema](user_db_design.md)
*   [Events Structure & Kafka Topics](user_events.md)

---
Tài liệu này mô tả chi tiết Schema Cơ sở dữ liệu (PostgreSQL) cho **User Service** (hay Profile Service). Trách nhiệm cốt lõi của Database này là lưu trữ thông tin cá nhân của Khách hàng (Buyer), số điện thoại hợp lệ, địa chỉ nhận hàng và phân hạng khách hàng. 
Mật khẩu và thông tin đăng nhập hoàn toàn không tồn tại ở đây (nắm giữ bởi Auth Service).

## 1. Bảng `user_profiles` (Lưu Hồ Sơ Khách Hàng)

Bảng này được tạo ra đồng bộ theo kiến trúc Event-Driven. Mỗi khi một account đăng ký hoặc xác thực thành công ở Auth Service, hệ thống sẽ tự chèn (upsert) một bản ghi vào đây có `id` trùng khớp 1-1 với `id` của bảng `users` bên Auth.

| Column Name | Data Type | Constraints / Indexes | Description |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | Primary Key | Ánh xạ trực tiếp (1-1 mapping) với `auth.users.id`. |
| `first_name` | `VARCHAR(100)` | Nullable | Tên chính của người dùng. |
| `last_name` | `VARCHAR(100)` | Nullable | Họ đệm của người dùng. |
| `phone_number` | `VARCHAR(20)` | Unique, Indexed, Nullable | Số điện thoại liên lạc. Có thể Null nếu người dùng đăng ký bằng Email và chưa điền hồ sơ. |
| `date_of_birth` | `DATE` | Nullable | Ngày sinh (phục vụ chiến dịch chúc mừng/giảm giá tự động). |
| `gender` | `VARCHAR(10)` | Default `'UNKNOWN'` | Giới tính: `MALE`, `FEMALE`, `OTHER`, `UNKNOWN`. |
| `created_at` | `TIMESTAMP` | Default NOW() | |
| `updated_at` | `TIMESTAMP` | Mapped qua Trigger | |

## 2. Bảng `user_addresses` (Sổ Địa Chỉ Giao Hàng)

Quản lý danh sách địa chỉ nhận hàng của User.
> **Quyết định thiết kế (Hard Delete):** Áp dụng xóa vĩnh viễn (Hard delete) thay vì Soft delete. Khi người dùng xóa địa chỉ ở đây, data mất hoàn toàn để tiết kiệm dung lượng DB. Lịch sử Order gốc không bị mất vì Order Service đã bắt snapshot toàn bộ địa chỉ sang bảng `orders` lúc Checkout.

| Column Name | Data Type | Constraints / Indexes | Description |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | Primary Key, Auto Gen | Định danh địa chỉ. |
| `user_id` | `UUID` | Indexed, FK -> `user_profiles(id)` | Khách hàng sở hữu địa chỉ này. |
| `is_default` | `BOOLEAN` | Default `FALSE` | Cờ nhận biết địa chỉ mặc định. Dùng *Partial Unique Index* để đảm bảo mỗi User chỉ có duy nhất 1 địa chỉ Default = TRUE. |
| `recipient_name` | `VARCHAR(200)` | Not Null | Tên người nhận hàng (có thể nhận giùm). |
| `phone_number` | `VARCHAR(20)` | Not Null | SĐT người nhận hàng. |
| `address_line` | `VARCHAR(500)` | Not Null | Địa chỉ chi tiết (Tòa nhà, số nhà, tên đường). |
| `ward_id` | `VARCHAR(20)` | Indexed, Not Null | ID Phường/Xã (liên kết hệ thống Shipping Master Data). |
| `district_id` | `VARCHAR(20)` | Indexed, Not Null | ID Quận/Huyện. |
| `city_id` | `VARCHAR(20)` | Indexed, Not Null | ID Tỉnh/Thành phố. |
| `created_at` | `TIMESTAMP` | Default NOW() | |
| `updated_at` | `TIMESTAMP` | Mapped qua Trigger | |

### Constraint đặc biệt:
```sql
-- Đảm bảo chỉ có 1 địa chỉ mặc định trên mỗi user
CREATE UNIQUE INDEX idx_unique_default_address 
ON user_addresses (user_id) 
WHERE is_default = TRUE;
```

## 3. Bảng `user_preferences` (Tùy Chọn Cá Nhân)

Quản lý các thiết lập ứng dụng của Người dùng. Dữ liệu từ bảng này sẽ được dùng làm Master Data để sinh event đồng bộ sang `notification_user_preferences` của Notification Service.

| Column Name | Data Type | Constraints / Indexes | Description |
| :--- | :--- | :--- | :--- |
| `user_id` | `UUID` | Primary Key, FK -> `user_profiles(id)` | Mối quan hệ 1-1 với bảng Profile. |
| `language` | `VARCHAR(10)` | Default `'vi'` | Cài đặt ngôn ngữ hiển thị gốc cho User (`vi`, `en`). Notification Service sẽ dùng trường này để load template. |
| `currency` | `VARCHAR(3)` | Default `'VND'` | Cài đặt tiền tệ ưu tiên. |
| `email_enabled` | `BOOLEAN` | Default `TRUE` | Bật/tắt nhận thông báo qua kênh Email. |
| `in_app_enabled` | `BOOLEAN` | Default `TRUE` | Bật/tắt nhận thông báo qua kênh In-App (Push Notification). |
| `marketing_enabled` | `BOOLEAN` | Default `TRUE` | Có đồng ý nhận tin nhắn Quảng cáo/Khuyến mãi không? |
| `updated_at` | `TIMESTAMP` | Mapped qua Trigger | Thời gian sửa đổi preferences gần nhất. |

## 5. Bảng `outbox_events` (Transactional Outbox Pattern qua Debezium)

User Service dùng Outbox để đảm bảo Transaction SQL (update profile/preferences) và Publish Event là ALL-OR-NOTHING.

| Column Name | Data Type | Constraints / Indexes | Description |
| :--- | :--- | :--- | :--- |
| `event_id` | `UUID` | Primary Key, Auto Gen | Định danh Event |
| `event_type` | `VARCHAR(100)` | Not Null | Tên luồng Event (Ex: `USER_PROFILE_UPDATED`) |
| `aggregate_type` | `VARCHAR(100)` | Indexed | Tên kiểu Aggregate (VD: `User`) |
| `aggregate_id` | `UUID` | Indexed | UUID của `user_id` |
| `partition_key` | `UUID` | Indexed | Partition Key của Kafka để đảm bảo thứ tự gửi của user |
| `payload` | `JSONB` | Not Null | Data bắn qua Kafka (Không chứa thông tin nhạy cảm) |
| `created_at` | `TIMESTAMP` | Default NOW() | Debezium không tự xóa bản ghi nếu không cấu hình rõ ràng. Cần cron dọn rác định kỳ. |

**Chính sách dọn rác:** Định kỳ chạy cron xóa event cũ (ví dụ giữ 7-14 ngày) để tránh phình bảng. Debezium không tự xóa bản ghi nếu không cấu hình rõ ràng.

## 6. Bảng `user_deletions` (GDPR Compliance - Event Store)

Bảng này lưu trữ thông tin xóa tài khoản để hỗ trợ luồng GDPR 2 giai đoạn:
- **Giai đoạn 1 (Anonymize):** Ngay khi nhận `USER_DELETED` event, PII được anonymize nhưng bản ghi vẫn tồn tại để đảm bảo referential integrity.
- **Giai đoạn 2 (Hard Delete):** Sau `retention_window_expires_at` (90 ngày), Compliance Worker sẽ xóa vĩnh viễn dữ liệu và DELETE bản ghi này.

> **Tại sao cần bảng này thay vì đọc từ Kafka?**
> Kafka không phải permanent storage (mặc định retention 7 ngày). Bảng `user_deletions` là **Event Store** để Compliance Worker có thể truy vấn và xóa vĩnh viễn sau 90 ngày.

| Column Name | Data Type | Constraints / Indexes | Description |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | Primary Key, Auto Gen | Định danh bản ghi |
| `user_id` | `UUID` | Unique, Indexed | User ID bị xóa |
| `deleted_at` | `TIMESTAMP` | Not Null | Thời điểm nhận event xóa |
| `retention_window_expires_at` | `TIMESTAMP` | Indexed, Not Null | Thời điểm hard delete (deleted_at + 90 ngày) |
| `reason` | `VARCHAR(50)` | Not Null | Lý do xóa: `GDPR_REQUEST`, `ADMIN_DELETION` |
| `created_at` | `TIMESTAMP` | Default NOW() | |

### Index đặc biệt cho Compliance Worker:
```sql
-- Nhanh chóng tìm các bản ghi cần hard delete
CREATE INDEX idx_retention_expires_pending
ON user_deletions (retention_window_expires_at);
```

### Lifecycle của bản ghi:
| Thời điểm | Hành động | Trạng thái |
|:-----------|:-----------|:-----------|
| Giai đoạn 1 (Ngay) | INSERT bản ghi | Chờ hard delete |
| Giai đoạn 2 (90 ngày sau) | DELETE `user_profiles`, rồi DELETE bản ghi này | Hoàn tất |
