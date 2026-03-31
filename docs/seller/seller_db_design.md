# Seller Service - Database Schema Design (Write Store)

**Liên kết nhanh (Related Documents):**
* [Workflows (API & Data Flow)](seller_workflow.md)
* [Background Workers & Jobs](seller_workers.md)
* [Caching Schema (Redis + L1)](seller_caching_schema.md)
* [Database Schema (Write Store)](seller_db_design.md)
* [State Machine & Lifecycle](seller_state_machine.md)
* [Events Structure & Kafka Topics](seller_events.md)
* [Architecture Recommendations](seller_architecture_recommendations.md)

---

Tài liệu này mô tả PostgreSQL Write Store cho Seller Service. Đây là **nguồn dữ liệu gốc** cho seller lifecycle, verification, commission, payout, performance và fulfillment settings.

## 1. Cấu trúc bảng

## 1.1 Nhóm Seller Core

### Bảng `sellers`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `seller_id` | VARCHAR(64) | Có | Định danh seller duy nhất. | PRIMARY KEY |
| `user_id` | VARCHAR(64) | Có | ID user chủ sở hữu seller account. | INDEX, unique logic 1 user -> 1 seller (nếu policy yêu cầu) |
| `company_name` | VARCHAR(255) | Có | Tên pháp lý của doanh nghiệp/hộ kinh doanh. | Không rỗng |
| `tax_id` | VARCHAR(128) | Có | Mã số thuế hoặc định danh pháp lý tương đương. | Mã hoá ở tầng ứng dụng nếu cần |
| `status` | VARCHAR(32) | Có | Trạng thái seller theo FSM. | CHECK enum |
| `verification_status` | VARCHAR(32) | Có | Trạng thái xác minh hồ sơ seller. | CHECK enum |
| `status_reason` | VARCHAR(255) | Không | Lý do đổi trạng thái (suspend/ban...). | Nullable |
| `is_deleted` | BOOLEAN | Có | Cờ xoá mềm seller record. | DEFAULT FALSE |
| `version` | BIGINT | Có | Version OCC cho aggregate seller. | DEFAULT 0 |
| `created_at` | TIMESTAMPTZ | Có | Thời điểm tạo seller. | UTC |
| `updated_at` | TIMESTAMPTZ | Có | Thời điểm cập nhật gần nhất. | UTC |

### Bảng `seller_profiles`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `seller_id` | VARCHAR(64) | Có | Seller owner của profile này. | PRIMARY KEY |
| `store_name` | VARCHAR(255) | Có | Tên hiển thị shop trên storefront. | Không rỗng |
| `description` | TEXT | Không | Mô tả shop. | Nullable |
| `logo_url` | TEXT | Không | URL logo shop. | Nullable |
| `contact_email` | VARCHAR(255) | Không | Email liên hệ hỗ trợ người mua. | Nullable, validate format |
| `contact_phone` | VARCHAR(32) | Không | Hotline của shop. | Nullable |
| `country_code` | VARCHAR(16) | Không | Mã quốc gia hoạt động. | Nullable |
| `city_code` | VARCHAR(32) | Không | Mã tỉnh/thành hoạt động chính. | Nullable |
| `updated_at` | TIMESTAMPTZ | Có | Mốc cập nhật profile gần nhất. | UTC |
| `profile_version` | BIGINT | Có | Version riêng cho profile events. | DEFAULT 0 |

### Bảng `seller_configs`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `seller_id` | VARCHAR(64) | Có | Seller owner của config. | PRIMARY KEY |
| `return_policy_json` | JSONB | Có | Quy tắc trả hàng/hoàn tiền của seller. | JSON schema validate |
| `shipping_tiers_json` | JSONB | Có | Cấu hình tầng vận chuyển, SLA và phí. | JSON schema validate |
| `commission_tiers_json` | JSONB | Có | Bảng phần trăm commission theo category/tier. | JSON schema validate |
| `fulfillment_settings_json` | JSONB | Có | Cấu hình xử lý đơn, tự động xác nhận, SLA nội bộ. | JSON schema validate |
| `updated_at` | TIMESTAMPTZ | Có | Mốc cập nhật config gần nhất. | UTC |

## 1.2 Nhóm Verification và Legal

### Bảng `seller_verifications`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `verification_id` | VARCHAR(64) | Có | ID phiên xác minh seller. | PRIMARY KEY |
| `seller_id` | VARCHAR(64) | Có | Seller cần xác minh. | INDEX |
| `status` | VARCHAR(32) | Có | PENDING/APPROVED/REJECTED. | CHECK enum |
| `submitted_docs_json` | JSONB | Có | Danh sách hồ sơ pháp lý nộp lên. | JSON schema validate |
| `reviewed_by` | VARCHAR(64) | Không | Reviewer ID hoặc hệ thống tự động. | Nullable |
| `reviewed_at` | TIMESTAMPTZ | Không | Thời điểm reviewer xử lý xong. | Nullable |
| `rejection_reason` | TEXT | Không | Lý do từ chối nếu status=REJECTED. | Nullable |
| `created_at` | TIMESTAMPTZ | Có | Thời điểm tạo verification record. | UTC |

### Bảng `seller_verification_history`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `history_id` | BIGSERIAL | Có | Khoá lịch sử tự tăng. | PRIMARY KEY |
| `seller_id` | VARCHAR(64) | Có | Seller bị thay đổi trạng thái. | INDEX |
| `old_status` | VARCHAR(32) | Có | Trạng thái cũ. | Không rỗng |
| `new_status` | VARCHAR(32) | Có | Trạng thái mới. | Không rỗng |
| `changed_by` | VARCHAR(64) | Không | ID actor đổi trạng thái. | Nullable |
| `changed_by_type` | VARCHAR(32) | Có | ADMIN/SYSTEM/SELLER. | CHECK enum |
| `reason` | TEXT | Không | Lý do đổi trạng thái. | Nullable |
| `created_at` | TIMESTAMPTZ | Có | Mốc thời gian thay đổi. | UTC |

## 1.3 Nhóm Finance và Payout

### Bảng `seller_commission_ledger`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `ledger_id` | BIGSERIAL | Có | Khoá ledger tự tăng. | PRIMARY KEY |
| `seller_id` | VARCHAR(64) | Có | Seller nhận doanh thu và bị trừ commission. | INDEX |
| `order_id` | VARCHAR(64) | Có | Đơn hàng nguồn của bản ghi commission. | Unique composite với seller_id |
| `category_id` | VARCHAR(64) | Không | Category dùng để áp tier commission. | Nullable |
| `commission_rate` | DECIMAL(8,4) | Có | Tỷ lệ commission tại thời điểm tính. | >= 0 |
| `gross_amount` | DECIMAL(18,2) | Có | Giá trị trước khi trừ commission. | >= 0 |
| `commission_amount` | DECIMAL(18,2) | Có | Số tiền commission thực tế. | >= 0 |
| `currency` | VARCHAR(8) | Có | Đơn vị tiền tệ. | Mã ISO |
| `event_timestamp_ms` | BIGINT | Có | Timestamp sự kiện nguồn để ordering guard. | Milliseconds epoch |
| `created_at` | TIMESTAMPTZ | Có | Thời điểm ghi ledger. | UTC |

### Bảng `seller_payouts`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `payout_id` | VARCHAR(64) | Có | ID payout batch hoặc payout item. | PRIMARY KEY |
| `seller_id` | VARCHAR(64) | Có | Seller nhận payout. | INDEX |
| `period_start` | TIMESTAMPTZ | Có | Mốc bắt đầu kỳ quyết toán. | UTC |
| `period_end` | TIMESTAMPTZ | Có | Mốc kết thúc kỳ quyết toán. | UTC |
| `gross_amount` | DECIMAL(18,2) | Có | Tổng doanh thu hợp lệ trong kỳ. | >= 0 |
| `fee_amount` | DECIMAL(18,2) | Có | Tổng phí/khấu trừ. | >= 0 |
| `net_amount` | DECIMAL(18,2) | Có | Số tiền chuyển thực tế. | >= 0 |
| `status` | VARCHAR(32) | Có | PENDING/PROCESSING/SUCCESS/FAILED. | CHECK enum |
| `provider_reference` | VARCHAR(128) | Không | Mã tham chiếu giao dịch từ provider. | Nullable |
| `processed_at` | TIMESTAMPTZ | Không | Thời điểm xử lý xong payout. | Nullable |
| `created_at` | TIMESTAMPTZ | Có | Thời điểm tạo payout record. | UTC |

### Bảng `seller_payout_accounts`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `account_id` | VARCHAR(64) | Có | ID tài khoản payout. | PRIMARY KEY |
| `seller_id` | VARCHAR(64) | Có | Seller sở hữu tài khoản payout. | INDEX |
| `bank_name` | VARCHAR(255) | Có | Tên ngân hàng nhận tiền. | Không rỗng |
| `account_masked` | VARCHAR(64) | Có | Số tài khoản đã mask để hiển thị. | Không lưu plaintext |
| `account_token_ref` | VARCHAR(128) | Có | Token tham chiếu vault/provider. | Không rỗng |
| `is_default` | BOOLEAN | Có | Cờ tài khoản payout mặc định. | Partial unique theo seller |
| `status` | VARCHAR(32) | Có | ACTIVE/INACTIVE/LOCKED. | CHECK enum |
| `created_at` | TIMESTAMPTZ | Có | Thời điểm liên kết tài khoản payout. | UTC |

## 1.4 Nhóm Performance và Fulfillment

### Bảng `seller_performance_metrics`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `seller_id` | VARCHAR(64) | Có | Seller sở hữu bản ghi metrics. | PRIMARY KEY |
| `health_score` | DECIMAL(5,2) | Có | Điểm sức khoẻ tổng hợp. | 0 - 100 |
| `rating_avg` | DECIMAL(3,2) | Có | Điểm đánh giá trung bình. | 0 - 5 |
| `response_rate` | DECIMAL(5,2) | Có | Tỷ lệ phản hồi trong SLA. | 0 - 100 |
| `cancellation_rate` | DECIMAL(5,2) | Có | Tỷ lệ huỷ đơn. | 0 - 100 |
| `updated_at` | TIMESTAMPTZ | Có | Mốc cập nhật metrics gần nhất. | UTC |
| `performance_version` | BIGINT | Có | Version để downstream guard ordering. | DEFAULT 0 |

### Bảng `seller_warehouses`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `warehouse_id` | VARCHAR(64) | Có | ID kho của seller. | PRIMARY KEY |
| `seller_id` | VARCHAR(64) | Có | Seller sở hữu kho. | INDEX |
| `name` | VARCHAR(255) | Có | Tên kho nội bộ. | Không rỗng |
| `address_json` | JSONB | Có | Địa chỉ kho chi tiết. | JSON schema validate |
| `capacity` | INT | Có | Sức chứa khai báo. | >= 0 |
| `is_primary` | BOOLEAN | Có | Cờ kho chính. | Tối đa 1 kho chính mỗi seller |
| `status` | VARCHAR(32) | Có | ACTIVE/INACTIVE/MAINTENANCE. | CHECK enum |
| `created_at` | TIMESTAMPTZ | Có | Thời điểm tạo kho. | UTC |

## 1.5 Nhóm hạ tầng (Infrastructure)

### Bảng `seller_outbox_events`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `event_id` | UUID | Có | ID event duy nhất. | PRIMARY KEY |
| `event_type` | VARCHAR(128) | Có | Tên event type. | Không rỗng |
| `aggregate_type` | VARCHAR(64) | Có | SELLER/SELLER_PROFILE/... | Không rỗng |
| `aggregate_id` | VARCHAR(64) | Có | ID aggregate bị thay đổi. | INDEX |
| `partition_key` | VARCHAR(64) | Có | Partition key Kafka, chuẩn = seller_id. | NOT NULL |
| `payload` | JSONB | Có | Payload nghiệp vụ. | Không rỗng |
| `created_at` | TIMESTAMPTZ | Có | Thời điểm insert outbox. | UTC |

### Bảng `seller_idempotency`

| Trường | Kiểu dữ liệu | Bắt buộc | Mô tả | Ràng buộc |
|---|---|---|---|---|
| `idempotency_key` | VARCHAR(128) | Có | Idempotency key từ request. | PRIMARY KEY |
| `request_hash` | VARCHAR(128) | Có | Hash payload request. | Không rỗng |
| `response_json` | JSONB | Không | Response cache cho duplicate requests. | Nullable |
| `status` | VARCHAR(32) | Có | PROCESSING/COMPLETED/FAILED. | CHECK enum |
| `expires_at` | TIMESTAMPTZ | Có | TTL logic của token. | INDEX |
| `created_at` | TIMESTAMPTZ | Có | Thời điểm tạo token. | UTC |

## 2. Chiến lược index

| Bảng | Index | Loại | Mục đích |
|---|---|---|---|
| `sellers` | `idx_sellers_user_id` | B-tree | Map từ user sang seller nhanh |
| `sellers` | `idx_sellers_status` | B-tree | Lọc theo trạng thái seller lifecycle |
| `seller_profiles` | `idx_seller_profiles_store_name` | B-tree | Search nội bộ/admin theo tên shop |
| `seller_verifications` | `idx_seller_verifications_status` | B-tree | Queue review verification |
| `seller_commission_ledger` | `idx_commission_seller_time` | B-tree | Statement theo seller và thời gian |
| `seller_commission_ledger` | `idx_commission_order_id` | Unique | Chặn ghi trùng theo order |
| `seller_payouts` | `idx_payouts_seller_status` | B-tree | Dashboard payout theo trạng thái |
| `seller_performance_metrics` | `idx_perf_health_score` | B-tree | Phát hiện seller điểm thấp |
| `seller_warehouses` | `idx_warehouses_seller_id` | B-tree | Liệt kê kho theo seller |
| `seller_outbox_events` | `idx_seller_outbox_created_at` | B-tree | Theo dõi relay/outbox lag |

## 3. Ràng buộc và quy tắc ghi dữ liệu

1. Mỗi seller có duy nhất một profile record.
2. Mỗi seller chỉ có tối đa một payout account mặc định.
3. Ledger commission phải dedup theo `seller_id + order_id`.
4. Mọi transition trạng thái seller phải tăng `sellers.version`.
5. Mọi cập nhật profile phải tăng `profile_version`.
6. Mọi cập nhật performance phải tăng `performance_version`.
7. Mọi write nghiệp vụ quan trọng phải đi kèm outbox event trong cùng transaction.

## 4. Partitioning và retention

1. `seller_commission_ledger`: range partition theo tháng để tối ưu truy vấn theo kỳ.
2. `seller_outbox_events`: range partition theo tháng khi event throughput cao.
3. `seller_verification_history`: retention theo policy compliance/audit.

## 5. Transaction snippets tham khảo

### 5.1 Atomic commission write

```sql
BEGIN;

INSERT INTO seller_commission_ledger (
  seller_id,
  order_id,
  category_id,
  commission_rate,
  gross_amount,
  commission_amount,
  currency,
  event_timestamp_ms,
  created_at
) VALUES (
  :seller_id,
  :order_id,
  :category_id,
  :commission_rate,
  :gross_amount,
  :commission_amount,
  :currency,
  :event_timestamp_ms,
  NOW()
);

UPDATE seller_performance_metrics
SET updated_at = NOW(),
    performance_version = performance_version + 1
WHERE seller_id = :seller_id;

INSERT INTO seller_outbox_events (
  event_id,
  event_type,
  aggregate_type,
  aggregate_id,
  partition_key,
  payload,
  created_at
) VALUES (
  :event_id,
  'SELLER_COMMISSION_CALCULATED',
  'SELLER',
  :seller_id,
  :seller_id,
  :payload_json,
  NOW()
);

COMMIT;
```

### 5.2 Status transition with optimistic lock

```sql
UPDATE sellers
SET status = :new_status,
    status_reason = :status_reason,
    version = version + 1,
    updated_at = NOW()
WHERE seller_id = :seller_id
  AND version = :expected_version;
```
