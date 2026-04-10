# Seller Service - Event Structure and Kafka Topics

**Liên kết nhanh (Related Documents):**
* [Workflows (API & Data Flow)](seller_workflow.md)
* [Background Workers & Jobs](seller_workers.md)
* [Caching Schema (Redis + L1)](seller_caching_schema.md)
* [Database Schema (Write Store)](seller_db_design.md)
* [State Machine & Lifecycle](seller_state_machine.md)
* [Events Structure & Kafka Topics](seller_events.md)
* [Architecture Recommendations](seller_architecture_recommendations.md)

---

Tài liệu này chuẩn hoá contract sự kiện của Seller Service cho toàn bộ downstream critical services.

> **Identity contract (bắt buộc):** `seller_id` là định danh do Seller domain sở hữu và được downstream xử lý như opaque string. Các service không được tự convert format `seller_id`.

## 1. Bản đồ Kafka topics

## 1.1 Topics Seller Service produce

| Topic | Partition Key | Consumers chính | Mô tả |
|---|---|---|---|
| `t.seller.events` | `seller_id` | Product, Search, Order, Payment, Analytics | Sự kiện lifecycle, profile, status, commission, payout, performance |

## 1.2 Topics Seller Service consume

| Topic | Consumer Group | Producer | Mô tả |
|---|---|---|---|
| `t.order.events` | `seller-commission-group` | Order Service | Dữ liệu đầu vào tính commission và accrual |
| `t.review.events` | `seller-performance-group` | Review Service | Dữ liệu đầu vào tính rating/response |
| `t.payment.events` | `seller-payout-status-group` | Payment Service | Kết quả payout callback |
| `t.inventory.events` | `seller-fulfillment-group` | Inventory Service | Tín hiệu năng lực fulfillment |

## 2. CloudEvents envelope chuẩn

```json
{
  "event_id": "223e4567-e89b-12d3-a456-426614174000",
  "event_type": "SELLER_PROFILE_UPDATED",
  "aggregate_type": "SELLER",
  "aggregate_id": "s-9001",
  "correlation_id": "req-901",
  "timestamp": "2026-03-31T10:00:00Z",
  "source": "seller-service",
  "payload": {}
}
```

### Ý nghĩa các field header

| Field | Mô tả |
|---|---|
| `event_id` | ID duy nhất của event để dedup và tracing. |
| `event_type` | Loại sự kiện cụ thể. |
| `aggregate_type` | Loại aggregate (SELLER, SELLER_PROFILE, SELLER_PAYOUT...). |
| `aggregate_id` | ID aggregate bị thay đổi. |
| `correlation_id` | ID xuyên suốt workflow liên dịch vụ. |
| `timestamp` | Thời điểm event phát sinh theo UTC ISO-8601. |
| `source` | Service phát event (seller-service). |
| `payload` | Dữ liệu nghiệp vụ theo từng event type. |

## 3. Chi tiết produced events theo từng type

## 3.1 `SELLER_ONBOARDED`

**Khi phát sinh:** Tạo seller onboarding thành công.

| Field | Bắt buộc | Mô tả |
|---|---|---|
| `seller_id` | Có | Seller mới tạo. |
| `user_id` | Có | User sở hữu seller. |
| `status` | Có | Trạng thái khởi tạo. |
| `state_version` | Có | Version ban đầu của aggregate seller. |
| `created_at` | Có | Mốc tạo seller. |

## 3.2 `SELLER_PROFILE_UPDATED`

**Khi phát sinh:** Cập nhật profile shop.

| Field | Bắt buộc | Mô tả |
|---|---|---|
| `seller_id` | Có | Seller bị cập nhật profile. |
| `store_name` | Có | Tên shop mới. |
| `seller_name` | Không | Alias tương thích ngược cho downstream cũ; nếu có thì phải cùng giá trị với `store_name`. |
| `logo_url` | Không | Logo mới. |
| `country_code` | Không | Mã quốc gia hoạt động. |
| `city_code` | Không | Mã khu vực hoạt động. |
| `profile_version` | Có | Version profile sau update. |
| `updated_at` | Có | Mốc cập nhật profile. |

```json
{
  "event_id": "evt_seller_profile_001",
  "event_type": "SELLER_PROFILE_UPDATED",
  "aggregate_type": "SELLER_PROFILE",
  "aggregate_id": "s-9001",
  "correlation_id": "req-901",
  "timestamp": "2026-03-31T10:20:00Z",
  "source": "seller-service",
  "payload": {
    "seller_id": "s-9001",
    "store_name": "Apple Flagship Store",
    "seller_name": "Apple Flagship Store",
    "logo_url": "https://cdn.example.com/s9001-logo.png",
    "country_code": "VN",
    "city_code": "HCM",
    "profile_version": 12,
    "updated_at": "2026-03-31T10:20:00Z"
  }
}
```

## 3.3 `SELLER_VERIFICATION_STARTED`

## 3.4 `SELLER_VERIFICATION_COMPLETED`

**Khi phát sinh:** Seller nộp hồ sơ hoặc hoàn tất review verification.

| Field | Bắt buộc | Mô tả |
|---|---|---|
| `seller_id` | Có | Seller được xác minh. |
| `verification_id` | Có | ID phiên verification. |
| `old_status` | Không | Trạng thái cũ (thường có ở COMPLETED). |
| `new_status` | Có | Trạng thái mới sau xử lý. |
| `reviewed_at` | Không | Mốc review xong. |
| `rejection_reason` | Không | Lý do reject (nếu có). |
| `state_version` | Có | Version aggregate sau transition. |

## 3.5 `SELLER_STATUS_CHANGED`

**Khi phát sinh:** Seller đổi trạng thái business (active/suspended/banned...).

| Field | Bắt buộc | Mô tả |
|---|---|---|
| `seller_id` | Có | Seller đổi trạng thái. |
| `old_status` | Có | Trạng thái trước đó. |
| `new_status` | Có | Trạng thái mới. |
| `status_reason` | Không | Lý do đổi trạng thái. |
| `visibility_policy` | Có | Chính sách hiển thị listing cho downstream. |
| `status_version` | Có | Version trạng thái sau transition. |
| `changed_at` | Có | Mốc đổi trạng thái. |

```json
{
  "event_id": "evt_seller_status_001",
  "event_type": "SELLER_STATUS_CHANGED",
  "aggregate_type": "SELLER",
  "aggregate_id": "s-9001",
  "correlation_id": "req-902",
  "timestamp": "2026-03-31T11:00:00Z",
  "source": "seller-service",
  "payload": {
    "seller_id": "s-9001",
    "old_status": "ACTIVE",
    "new_status": "SUSPENDED",
    "status_reason": "QUALITY_VIOLATION",
    "visibility_policy": "HIDE_LISTINGS",
    "status_version": 7,
    "changed_at": "2026-03-31T11:00:00Z"
  }
}
```

## 3.6 `SELLER_COMMISSION_CALCULATED`

**Khi phát sinh:** Tính commission cho một order event thành công.

| Field | Bắt buộc | Mô tả |
|---|---|---|
| `seller_id` | Có | Seller chịu commission. |
| `order_id` | Có | Đơn hàng nguồn. |
| `commission_rate` | Có | Tỷ lệ commission tại thời điểm tính. |
| `gross_amount` | Có | Giá trị đơn trước khi trừ commission. |
| `commission_amount` | Có | Số tiền commission. |
| `currency` | Có | Đơn vị tiền tệ. |
| `event_sequence_id` | Có | Sequence để guard out-of-order theo luồng commission. |
| `calculated_at` | Có | Mốc tính commission. |

## 3.7 `SELLER_PAYOUT_INITIATED`

## 3.8 `SELLER_PAYOUT_COMPLETED`

**Khi phát sinh:** Bắt đầu hoặc hoàn tất payout kỳ quyết toán.

| Field | Bắt buộc | Mô tả |
|---|---|---|
| `seller_id` | Có | Seller nhận payout. |
| `payout_id` | Có | ID payout record. |
| `period_start` | Có | Đầu kỳ quyết toán. |
| `period_end` | Có | Cuối kỳ quyết toán. |
| `net_amount` | Có | Số tiền chuyển cho seller. |
| `currency` | Có | Đơn vị tiền tệ. |
| `provider_reference` | Không | Mã giao dịch từ provider. |
| `status` | Có | SUCCESS/FAILED/PROCESSING. |
| `processed_at` | Không | Thời điểm xử lý xong. |

## 3.9 `SELLER_PERFORMANCE_SCORE_UPDATED`

**Khi phát sinh:** Worker cập nhật score định kỳ hoặc theo ngưỡng.

| Field | Bắt buộc | Mô tả |
|---|---|---|
| `seller_id` | Có | Seller được chấm điểm. |
| `health_score` | Có | Điểm tổng hợp chất lượng seller. |
| `rating_avg` | Có | Điểm review trung bình. |
| `response_rate` | Có | Tỷ lệ phản hồi đúng SLA. |
| `cancellation_rate` | Có | Tỷ lệ huỷ đơn. |
| `performance_version` | Có | Version metrics để downstream guard. |
| `updated_at` | Có | Mốc cập nhật metrics. |

## 4. Hợp đồng consumed events quan trọng

1. `ORDER_PAID`: trigger tính commission và cập nhật accrual.
2. `ORDER_COMPLETED`: cập nhật performance fulfillment metrics.
3. `ORDER_CANCELLED`/`ORDER_REFUNDED`: điều chỉnh ledger và payout reserve.
4. `PAYOUT_SUCCESS`/`PAYOUT_FAILED`: cập nhật trạng thái payout.
5. Review events từ `t.review.events`:
  - `REVIEW_APPROVED`, `REVIEW_HIDDEN`: cập nhật rating inputs theo `seller_id`, `rating`, `product_id`.
  - `SELLER_REPLY_CREATED`: cập nhật response-related score theo `seller_id`.

## 5. Quy tắc ordering, idempotency, retry

1. Consumer bắt buộc dedup theo `event_id`.
2. Guard ordering theo từng loại event:
   - profile events dùng `profile_version`.
   - status events dùng `status_version`.
   - commission events dùng `event_sequence_id`.
   - performance events dùng `performance_version`.
3. Retry exponential backoff cho lỗi tạm thời.
4. Lỗi contract/schema vào DLQ.
5. Các event cùng `seller_id` phải luôn đi cùng partition.

## 6. Contract governance

1. Additive evolution only.
2. Không đổi nghĩa field cũ khi chưa version.
3. Breaking change tạo event type/version mới.
4. Bắt buộc contract tests với Product, Search, Order, Payment.
