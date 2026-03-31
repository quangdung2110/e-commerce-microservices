# User Service - Event Structure and Kafka Topics

**Liên kết nhanh (Related Documents):**
* [Workflows (API & Data Flow)](user_workflow.md)
* [Background Workers & Jobs](user_workers.md)
* [Caching Schema (Redis)](user_caching_schema.md)
* [Database Schema (Write Store)](user_db_design.md)
* [State Machine & Lifecycle](user_state_machine.md)
* [Events Structure & Kafka Topics](user_events.md)
* [Architecture Recommendations](user_architecture_recommendations.md)

---

Tài liệu này chuẩn hoá contract sự kiện của User Service theo mô hình CloudEvents + partitioned Kafka.

## 1. Bản đồ Kafka topics

## 1.1 Topics User Service produce

| Topic | Partition Key | Consumers chính | Mô tả |
|---|---|---|---|
| `t.user.events` | `user_id` | Auth, Order, Notification, Review, Seller, Analytics | Sự kiện vòng đời user, profile, địa chỉ, KYC, compliance |

## 1.2 Topics User Service consume

| Topic | Consumer Group | Producer | Mô tả |
|---|---|---|---|
| `t.auth.events` | `user-auth-sync-group` | Auth Service | Đồng bộ đăng ký/khoá/mở khoá account |
| `t.order.events` | `user-order-stats-group` | Order Service | Cập nhật thống kê người dùng sau đơn hoàn tất |
| `t.payment.events` | `user-payment-sync-group` | Payment Service | Đồng bộ cờ thanh toán và metadata liên quan |

## 2. CloudEvents envelope chuẩn

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174000",
  "event_type": "USER_PROFILE_UPDATED",
  "aggregate_type": "USER",
  "aggregate_id": "u-10001",
  "correlation_id": "req-789",
  "timestamp": "2026-03-31T09:00:00Z",
  "source": "user-service",
  "payload": {}
}
```

### Ý nghĩa các field header

| Field | Mô tả |
|---|---|
| `event_id` | ID duy nhất của event để dedup phía consumer. |
| `event_type` | Tên loại event. |
| `aggregate_type` | Loại aggregate nghiệp vụ (USER, USER_ADDRESS, KYC_DOCUMENT...). |
| `aggregate_id` | ID aggregate bị thay đổi; dùng cùng partition key logic. |
| `correlation_id` | ID truy vết xuyên dịch vụ và workflow. |
| `timestamp` | Thời điểm phát sinh event theo UTC ISO-8601. |
| `source` | Service phát event (luôn là user-service). |
| `payload` | Dữ liệu nghiệp vụ của event type cụ thể. |

## 3. Chi tiết produced events theo từng type

## 3.1 `USER_PROFILE_UPDATED`

**Khi phát sinh:** User cập nhật profile thành công.

**Payload fields:**

| Field | Bắt buộc | Mô tả |
|---|---|---|
| `user_id` | Có | ID user bị cập nhật. |
| `display_name` | Có | Tên hiển thị mới. |
| `first_name` | Không | Tên riêng mới (nếu có). |
| `last_name` | Không | Họ/tên đệm mới (nếu có). |
| `avatar_url` | Không | URL avatar mới. |
| `status` | Có | Trạng thái tài khoản tại thời điểm event. |
| `state_version` | Có | Version của aggregate user sau update. |
| `updated_at` | Có | Thời điểm cập nhật profile. |

```json
{
  "event_id": "evt_user_profile_001",
  "event_type": "USER_PROFILE_UPDATED",
  "aggregate_type": "USER",
  "aggregate_id": "u-10001",
  "correlation_id": "req-1001",
  "timestamp": "2026-03-31T09:00:00Z",
  "source": "user-service",
  "payload": {
    "user_id": "u-10001",
    "display_name": "nguyen_van_a",
    "first_name": "Nguyen",
    "last_name": "Van A",
    "avatar_url": "https://cdn.example.com/u10001.jpg",
    "status": "ACTIVE",
    "state_version": 17,
    "updated_at": "2026-03-31T09:00:00Z"
  }
}
```

## 3.2 `USER_PREFERENCES_UPDATED`

**Khi phát sinh:** User đổi cài đặt preferences.

**Payload fields:**

| Field | Bắt buộc | Mô tả |
|---|---|---|
| `user_id` | Có | ID user bị ảnh hưởng. |
| `newsletter_enabled` | Có | Bật/tắt bản tin marketing. |
| `sms_notifications_enabled` | Có | Bật/tắt SMS notification. |
| `currency` | Có | Currency hiện hành. |
| `preferred_language` | Có | Ngôn ngữ ưu tiên. |
| `state_version` | Có | Version sau cập nhật. |
| `updated_at` | Có | Mốc thời gian cập nhật. |

```json
{
  "event_id": "evt_user_pref_001",
  "event_type": "USER_PREFERENCES_UPDATED",
  "aggregate_type": "USER_PREFERENCES",
  "aggregate_id": "u-10001",
  "correlation_id": "req-1002",
  "timestamp": "2026-03-31T09:05:00Z",
  "source": "user-service",
  "payload": {
    "user_id": "u-10001",
    "newsletter_enabled": true,
    "sms_notifications_enabled": false,
    "currency": "VND",
    "preferred_language": "vi",
    "state_version": 18,
    "updated_at": "2026-03-31T09:05:00Z"
  }
}
```

## 3.3 `ADDRESS_CREATED`

**Khi phát sinh:** Thêm mới địa chỉ user.

## 3.4 `ADDRESS_UPDATED`

**Khi phát sinh:** Cập nhật địa chỉ user.

## 3.5 `ADDRESS_DELETED`

**Khi phát sinh:** Xoá mềm địa chỉ user.

**Payload fields dùng chung cho nhóm `ADDRESS_*`:**

| Field | Bắt buộc | Mô tả |
|---|---|---|
| `user_id` | Có | ID user sở hữu địa chỉ. |
| `address_id` | Có | ID địa chỉ bị tác động. |
| `full_name` | Không | Người nhận tại địa chỉ (không bắt buộc cho DELETE). |
| `phone` | Không | SĐT nhận hàng (không bắt buộc cho DELETE). |
| `street` | Không | Địa chỉ chi tiết. |
| `district_code` | Không | Mã quận/huyện. |
| `province_code` | Không | Mã tỉnh/thành phố. |
| `is_default_shipping` | Không | Cờ mặc định giao hàng. |
| `is_default_billing` | Không | Cờ mặc định thanh toán. |
| `state_version` | Có | Version user sau thay đổi địa chỉ. |
| `updated_at` | Có | Thời điểm cập nhật. |

```json
{
  "event_id": "evt_addr_updated_001",
  "event_type": "ADDRESS_UPDATED",
  "aggregate_type": "USER_ADDRESS",
  "aggregate_id": "addr-9001",
  "correlation_id": "req-1003",
  "timestamp": "2026-03-31T09:10:00Z",
  "source": "user-service",
  "payload": {
    "user_id": "u-10001",
    "address_id": "addr-9001",
    "full_name": "Nguyen Van A",
    "phone": "0901000111",
    "street": "123 Nguyen Hue",
    "district_code": "D1",
    "province_code": "HCM",
    "is_default_shipping": true,
    "is_default_billing": false,
    "state_version": 19,
    "updated_at": "2026-03-31T09:10:00Z"
  }
}
```

## 3.6 `KYC_VERIFICATION_INITIATED`

**Khi phát sinh:** User nộp hồ sơ KYC thành công.

| Field | Bắt buộc | Mô tả |
|---|---|---|
| `user_id` | Có | ID user nộp hồ sơ. |
| `kyc_document_id` | Có | ID tài liệu KYC. |
| `document_type` | Có | Loại giấy tờ. |
| `submitted_at` | Có | Thời điểm nộp. |
| `state_version` | Có | Version user sau khi chuyển KYC sang pending. |

```json
{
  "event_id": "evt_kyc_init_001",
  "event_type": "KYC_VERIFICATION_INITIATED",
  "aggregate_type": "KYC_DOCUMENT",
  "aggregate_id": "kyc-7001",
  "correlation_id": "req-1004",
  "timestamp": "2026-03-31T09:20:00Z",
  "source": "user-service",
  "payload": {
    "user_id": "u-10001",
    "kyc_document_id": "kyc-7001",
    "document_type": "NATIONAL_ID",
    "submitted_at": "2026-03-31T09:20:00Z",
    "state_version": 20
  }
}
```

## 3.7 `KYC_VERIFICATION_APPROVED`

## 3.8 `KYC_VERIFICATION_REJECTED`

**Payload fields dùng chung cho nhóm quyết định KYC:**

| Field | Bắt buộc | Mô tả |
|---|---|---|
| `user_id` | Có | User được duyệt/từ chối KYC. |
| `kyc_document_id` | Có | Hồ sơ KYC liên quan. |
| `kyc_status` | Có | APPROVED hoặc REJECTED. |
| `reviewed_at` | Có | Thời điểm có kết quả. |
| `rejection_reason` | Không | Lý do từ chối (chỉ có khi REJECTED). |
| `state_version` | Có | Version sau quyết định KYC. |

```json
{
  "event_id": "evt_kyc_approved_001",
  "event_type": "KYC_VERIFICATION_APPROVED",
  "aggregate_type": "KYC_DOCUMENT",
  "aggregate_id": "kyc-7001",
  "correlation_id": "req-1005",
  "timestamp": "2026-03-31T10:00:00Z",
  "source": "user-service",
  "payload": {
    "user_id": "u-10001",
    "kyc_document_id": "kyc-7001",
    "kyc_status": "APPROVED",
    "reviewed_at": "2026-03-31T10:00:00Z",
    "state_version": 21
  }
}
```

## 3.9 `ACCOUNT_BANNED`

**Khi phát sinh:** Account bị khoá mức nghiêm trọng.

| Field | Bắt buộc | Mô tả |
|---|---|---|
| `user_id` | Có | User bị banned. |
| `old_status` | Có | Trạng thái trước khi ban. |
| `new_status` | Có | Trạng thái sau ban (BANNED). |
| `reason` | Có | Lý do ban. |
| `state_version` | Có | Version sau transition. |
| `changed_at` | Có | Thời điểm đổi trạng thái. |

## 3.10 `ACCOUNT_DELETED`

**Khi phát sinh:** User yêu cầu xoá tài khoản, chuyển sang soft-delete.

| Field | Bắt buộc | Mô tả |
|---|---|---|
| `user_id` | Có | User đã yêu cầu xoá. |
| `deleted_at` | Có | Thời điểm xoá mềm. |
| `reason` | Có | USER_REQUEST/ADMIN/LEGAL... |
| `retention_window_expires_at` | Có | Mốc chạy anonymization. |
| `state_version` | Có | Version sau transition DELETED. |

```json
{
  "event_id": "evt_acc_deleted_001",
  "event_type": "ACCOUNT_DELETED",
  "aggregate_type": "USER",
  "aggregate_id": "u-10001",
  "correlation_id": "req-1006",
  "timestamp": "2026-03-31T11:00:00Z",
  "source": "user-service",
  "payload": {
    "user_id": "u-10001",
    "deleted_at": "2026-03-31T11:00:00Z",
    "reason": "USER_REQUEST",
    "retention_window_expires_at": "2026-06-29T11:00:00Z",
    "state_version": 23
  }
}
```

## 3.11 `ACCOUNT_DATA_RETENTION_EXPIRED`

**Khi phát sinh:** User đã qua retention window và dữ liệu đã được anonymize.

| Field | Bắt buộc | Mô tả |
|---|---|---|
| `user_id` | Có | User đã anonymize. |
| `anonymized_at` | Có | Thời điểm hoàn tất anonymization. |
| `anonymization_mode` | Có | Cách anonymize (PII_MASKING/HARD_DELETE_SUBSETS). |
| `state_version` | Có | Version sau cập nhật compliance. |

## 4. Quy tắc ordering, idempotency, retry

1. Producer dùng outbox + CDC/relay để đạt at-least-once.
2. Consumer phải dedup theo `event_id` bằng Redis hoặc bảng dedup.
3. Stale guard theo `state_version` là ưu tiên; fallback theo `updated_at`.
4. Lỗi tạm thời retry exponential backoff; lỗi schema/payload đi vào DLQ.
5. Cùng một `user_id` phải luôn vào cùng partition để giữ đúng thứ tự.

## 5. Contract governance

1. Event schema chỉ được mở rộng theo hướng additive.
2. Không được xoá hoặc đổi nghĩa field đang dùng ở downstream.
3. Breaking change tạo event type mới, ví dụ `USER_PROFILE_UPDATED_V2`.
4. Bắt buộc có contract tests liên service trước khi deploy.
