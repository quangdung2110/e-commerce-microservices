# User Service - Database Schema Design (Write Store)

**Liên kết nhanh (Related Documents):**
* [Workflows (API & Data Flow)](user_workflow.md)
* [Background Workers & Jobs](user_workers.md)
* [Caching Schema (Redis)](user_caching_schema.md)
* [Database Schema (Write Store)](user_db_design.md)
* [State Machine & Lifecycle](user_state_machine.md)
* [Events Structure & Kafka Topics](user_events.md)
* [Architecture Recommendations](user_architecture_recommendations.md)

---

Tài liệu này mô tả thiết kế PostgreSQL cho User Service. Trong kiến trúc CQRS, đây là **Write Store (Source of Truth)** cho các nghiệp vụ profile, địa chỉ, preferences, KYC và vòng đời xoá tài khoản.

## 1. Cấu trúc bảng (ERD và Entities)

## 1.1 Nhóm User Core

### Bảng `users`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `user_id` | VARCHAR(64) | Có | Định danh user duy nhất trong User Service. | PRIMARY KEY |
| `email` | VARCHAR(255) | Có | Email đăng ký hiện hành của user. | UNIQUE, chuẩn hoá lowercase trước khi ghi |
| `phone` | VARCHAR(32) | Không | Số điện thoại đã xác minh hoặc do user khai báo. | UNIQUE (nếu có), chuẩn E.164 |
| `display_name` | VARCHAR(255) | Có | Tên hiển thị công khai cho các bề mặt người dùng. | Không rỗng, có thể giới hạn ký tự đặc biệt |
| `first_name` | VARCHAR(128) | Không | Tên riêng của user. | Nullable để hỗ trợ tài khoản tối giản |
| `last_name` | VARCHAR(128) | Không | Họ và tên đệm của user. | Nullable để hỗ trợ tài khoản tối giản |
| `avatar_url` | TEXT | Không | URL ảnh đại diện đã upload. | Chỉ lưu URL đã qua sanitize |
| `bio` | TEXT | Không | Mô tả ngắn của user. | Giới hạn độ dài ở tầng API |
| `status` | VARCHAR(32) | Có | Trạng thái tài khoản: ACTIVE/SUSPENDED/BANNED/DELETED. | CHECK theo FSM |
| `kyc_status` | VARCHAR(32) | Có | Trạng thái KYC: NOT_SUBMITTED/PENDING_REVIEW/APPROVED/REJECTED. | CHECK theo KYC FSM |
| `is_legally_deleted` | BOOLEAN | Có | Đánh dấu user đã qua giai đoạn anonymize theo pháp lý. | DEFAULT FALSE |
| `version` | BIGINT | Có | Version OCC để chống ghi đè dữ liệu khi concurrent updates. | DEFAULT 0, tăng mỗi lần update nghiệp vụ |
| `created_at` | TIMESTAMPTZ | Có | Thời điểm tạo tài khoản User record. | UTC |
| `updated_at` | TIMESTAMPTZ | Có | Thời điểm cập nhật gần nhất. | UTC |

### Bảng `user_preferences`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `user_id` | VARCHAR(64) | Có | Khoá tham chiếu đến bảng users. | PRIMARY KEY, FK logic đến users |
| `newsletter_enabled` | BOOLEAN | Có | Bật/tắt nhận bản tin marketing. | DEFAULT FALSE |
| `sms_notifications_enabled` | BOOLEAN | Có | Bật/tắt nhận thông báo qua SMS. | DEFAULT FALSE |
| `currency` | VARCHAR(8) | Có | Đơn vị tiền tệ ưu tiên khi hiển thị. | DEFAULT VND |
| `preferred_language` | VARCHAR(8) | Có | Ngôn ngữ hiển thị ưu tiên. | DEFAULT vi |
| `marketing_consent_timestamp` | TIMESTAMPTZ | Không | Thời điểm user đồng ý điều khoản marketing. | Nullable |
| `marketing_consent_version` | INT | Có | Phiên bản consent policy mà user đã chấp nhận. | DEFAULT 1 |
| `updated_at` | TIMESTAMPTZ | Có | Thời điểm cập nhật preference gần nhất. | UTC |

## 1.2 Nhóm User Address

### Bảng `user_addresses`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `address_id` | VARCHAR(64) | Có | Định danh địa chỉ duy nhất. | PRIMARY KEY |
| `user_id` | VARCHAR(64) | Có | User sở hữu địa chỉ này. | INDEX, FK logic |
| `full_name` | VARCHAR(255) | Có | Người nhận hàng trên địa chỉ. | Không rỗng |
| `phone` | VARCHAR(32) | Có | Số điện thoại liên hệ nhận hàng. | Chuẩn hoá định dạng ở API |
| `street` | VARCHAR(512) | Có | Địa chỉ chi tiết (số nhà, đường). | Không rỗng |
| `ward_code` | VARCHAR(32) | Không | Mã phường/xã theo master data địa lý. | Nullable |
| `district_code` | VARCHAR(32) | Có | Mã quận/huyện. | Không rỗng |
| `province_code` | VARCHAR(32) | Có | Mã tỉnh/thành phố. | Không rỗng |
| `postal_code` | VARCHAR(16) | Không | Mã bưu chính. | Nullable |
| `address_type` | VARCHAR(16) | Có | Loại địa chỉ: HOME/OFFICE/OTHER. | DEFAULT HOME |
| `is_default_shipping` | BOOLEAN | Có | Có phải địa chỉ giao hàng mặc định. | Partial unique index theo user |
| `is_default_billing` | BOOLEAN | Có | Có phải địa chỉ thanh toán mặc định. | Partial unique index theo user |
| `is_deleted` | BOOLEAN | Có | Cờ xoá mềm địa chỉ. | DEFAULT FALSE |
| `created_at` | TIMESTAMPTZ | Có | Thời điểm tạo địa chỉ. | UTC |
| `updated_at` | TIMESTAMPTZ | Có | Thời điểm cập nhật địa chỉ gần nhất. | UTC |

## 1.3 Nhóm KYC và Compliance

### Bảng `kyc_documents`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `kyc_document_id` | VARCHAR(64) | Có | Định danh tài liệu KYC. | PRIMARY KEY |
| `user_id` | VARCHAR(64) | Có | User sở hữu bộ hồ sơ KYC. | INDEX |
| `document_type` | VARCHAR(32) | Có | Loại giấy tờ: NATIONAL_ID/PASSPORT/... | CHECK enum ở tầng DB hoặc API |
| `document_url` | TEXT | Có | URL object storage của file gốc. | Không rỗng |
| `document_checksum` | VARCHAR(128) | Không | Hash file để chống upload trùng và phục vụ audit. | Nullable |
| `status` | VARCHAR(32) | Có | PENDING/APPROVED/REJECTED/... | CHECK theo KYC FSM |
| `rejection_reason` | TEXT | Không | Lý do từ chối KYC. | Chỉ có giá trị khi status=REJECTED |
| `submitted_at` | TIMESTAMPTZ | Có | Thời điểm user nộp hồ sơ. | UTC |
| `reviewed_at` | TIMESTAMPTZ | Không | Thời điểm reviewer xử lý xong. | Nullable |
| `reviewed_by` | VARCHAR(64) | Không | Reviewer ID hoặc hệ thống tự động. | Nullable |
| `version` | BIGINT | Có | Version cho update cạnh tranh khi worker xử lý lại. | DEFAULT 0 |

### Bảng `user_deletion_requests`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `deletion_request_id` | VARCHAR(64) | Có | Định danh yêu cầu xoá tài khoản. | PRIMARY KEY |
| `user_id` | VARCHAR(64) | Có | User yêu cầu xoá. | INDEX |
| `request_source` | VARCHAR(32) | Có | Nguồn yêu cầu: USER_SELF/ADMIN/LEGAL. | CHECK enum |
| `reason` | VARCHAR(255) | Không | Lý do xoá tài khoản. | Nullable |
| `retention_window_expires_at` | TIMESTAMPTZ | Có | Mốc hết hạn retention để anonymize. | INDEX |
| `status` | VARCHAR(32) | Có | PENDING_RETENTION/ANONYMIZED/CANCELLED. | CHECK enum |
| `created_at` | TIMESTAMPTZ | Có | Thời điểm tạo yêu cầu. | UTC |
| `updated_at` | TIMESTAMPTZ | Có | Thời điểm cập nhật trạng thái yêu cầu. | UTC |

### Bảng `user_compliance_audit`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `audit_id` | BIGSERIAL | Có | Khoá audit tự tăng. | PRIMARY KEY |
| `user_id` | VARCHAR(64) | Có | User bị tác động bởi hành vi compliance. | INDEX |
| `action_type` | VARCHAR(64) | Có | Loại hành động: PROFILE_UPDATED/KYC_APPROVED/ACCOUNT_DELETED... | Không rỗng |
| `actor_id` | VARCHAR(64) | Không | ID người/hệ thống thực hiện. | Nullable |
| `actor_type` | VARCHAR(32) | Có | ADMIN/SYSTEM/USER. | CHECK enum |
| `details_json` | JSONB | Có | Chi tiết trước-sau thay đổi phục vụ audit pháp lý. | Không rỗng |
| `created_at` | TIMESTAMPTZ | Có | Thời điểm ghi audit. | UTC |

## 1.4 Nhóm hạ tầng (Infrastructure)

### Bảng `user_outbox_events`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `event_id` | UUID | Có | ID duy nhất của event. | PRIMARY KEY |
| `event_type` | VARCHAR(128) | Có | Tên event type chuẩn hoá. | Không rỗng |
| `aggregate_type` | VARCHAR(64) | Có | Loại aggregate: USER/USER_ADDRESS/KYC_DOCUMENT... | Không rỗng |
| `aggregate_id` | VARCHAR(64) | Có | ID của aggregate bị thay đổi. | INDEX |
| `partition_key` | VARCHAR(64) | Có | Khoá partition Kafka, chuẩn = user_id. | NOT NULL |
| `payload` | JSONB | Có | Dữ liệu nghiệp vụ gửi downstream. | Không rỗng |
| `created_at` | TIMESTAMPTZ | Có | Thời điểm insert vào outbox. | UTC |

### Bảng `user_idempotency`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `idempotency_key` | VARCHAR(128) | Có | Khoá idempotency từ request header. | PRIMARY KEY |
| `request_hash` | VARCHAR(128) | Có | Hash payload để chặn key reuse sai payload. | Không rỗng |
| `response_json` | JSONB | Không | Response đã trả cho request đầu tiên. | Nullable |
| `status` | VARCHAR(32) | Có | PROCESSING/COMPLETED/FAILED. | CHECK enum |
| `expires_at` | TIMESTAMPTZ | Có | Mốc hết hạn token idempotency. | INDEX |
| `created_at` | TIMESTAMPTZ | Có | Thời điểm tạo token. | UTC |

### Bảng `user_consumer_dedup`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `event_id` | VARCHAR(128) | Có | ID event đã xử lý. | PRIMARY KEY |
| `consumer_group` | VARCHAR(128) | Có | Consumer group đã xử lý event. | Composite index |
| `processed_at` | TIMESTAMPTZ | Có | Thời điểm xử lý thành công. | UTC |

## 2. Chiến lược đánh index

| Bảng | Index | Loại | Mục đích |
|---|---|---|---|
| `users` | `idx_users_email` | B-tree unique | Lookup user theo email nhanh và không trùng |
| `users` | `idx_users_phone` | B-tree unique | Lookup user theo phone nhanh và không trùng |
| `users` | `idx_users_status` | B-tree | Lọc user theo trạng thái cho moderation/compliance |
| `user_addresses` | `idx_user_addresses_user_id` | B-tree | Truy vấn danh sách địa chỉ theo user |
| `user_addresses` | `idx_user_addresses_default_shipping` | Partial index | Lấy địa chỉ giao hàng mặc định nhanh |
| `user_addresses` | `idx_user_addresses_default_billing` | Partial index | Lấy địa chỉ thanh toán mặc định nhanh |
| `kyc_documents` | `idx_kyc_documents_user_id` | B-tree | Lấy lịch sử KYC theo user |
| `kyc_documents` | `idx_kyc_documents_status` | B-tree | Worker quét hàng đợi theo trạng thái |
| `user_deletion_requests` | `idx_deletion_retention_expiry` | B-tree | Worker quét retention window hết hạn |
| `user_outbox_events` | `idx_user_outbox_created_at` | B-tree | Theo dõi outbox lag và troubleshooting |
| `user_consumer_dedup` | `idx_consumer_dedup_group_time` | B-tree | Dọn dedup records theo nhóm và thời gian |

## 3. Ràng buộc và quy tắc ghi dữ liệu

1. `users.status` chỉ nhận giá trị thuộc FSM: ACTIVE, SUSPENDED, BANNED, DELETED.
2. Mỗi user chỉ có tối đa 1 default shipping và 1 default billing (enforce bằng partial unique index).
3. KYC status transition phải đi qua luồng hợp lệ, không update tuỳ ý bằng script tay.
4. Mọi thay đổi nghiệp vụ chính phải ghi outbox trong cùng transaction.
5. Luồng xoá tài khoản là soft-delete trước, anonymize sau retention.
6. `request_hash` trong bảng idempotency phải trùng payload đầu tiên; nếu khác thì trả lỗi conflict.

## 4. Concurrency và locking strategy

1. Profile update dùng optimistic locking qua `users.version`.
2. Address default switch dùng lock thứ tự: lock `users` trước, lock `user_addresses` sau.
3. KYC decision dùng `FOR UPDATE` trên row `kyc_documents` để tránh double-process.
4. Delete request lock row `users`, update status + insert deletion request + outbox atomically.

## 5. Partitioning và retention

1. `user_outbox_events`: range partition theo tháng khi throughput tăng.
2. `user_compliance_audit`: range partition theo quý để dễ archive theo policy pháp lý.
3. `user_consumer_dedup`: retention 7-30 ngày theo Kafka retention và nhu cầu replay.

## 6. SQL snippets tham khảo

### 6.1 Update profile với optimistic lock

```sql
UPDATE users
SET display_name = :display_name,
    first_name = :first_name,
    last_name = :last_name,
    avatar_url = :avatar_url,
    version = version + 1,
    updated_at = NOW()
WHERE user_id = :user_id
  AND version = :expected_version;
```

### 6.2 Switch default shipping address atomically

```sql
BEGIN;

SELECT 1 FROM users WHERE user_id = :user_id FOR UPDATE;

UPDATE user_addresses
SET is_default_shipping = FALSE,
    updated_at = NOW()
WHERE user_id = :user_id
  AND is_default_shipping = TRUE
  AND is_deleted = FALSE;

UPDATE user_addresses
SET is_default_shipping = TRUE,
    updated_at = NOW()
WHERE address_id = :address_id
  AND user_id = :user_id
  AND is_deleted = FALSE;

COMMIT;
```

### 6.3 Insert outbox event trong cùng transaction

```sql
INSERT INTO user_outbox_events (
  event_id,
  event_type,
  aggregate_type,
  aggregate_id,
  partition_key,
  payload,
  created_at
) VALUES (
  :event_id,
  'USER_PROFILE_UPDATED',
  'USER',
  :user_id,
  :user_id,
  :payload_json,
  NOW()
);
```