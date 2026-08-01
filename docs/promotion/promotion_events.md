# Promotion Service - Định nghĩa Các Sự Kiện (Events Schema)

**Liên kết nhanh (Related Documents):**
*   [Database Schema](promotion_db_design.md)
*   [Redis Caching & Lua Scripts](redis_caching_design.md)
*   [Promotion Workflows](promotion_workflow.md)

---

Tài liệu này định nghĩa cấu trúc tiêu chuẩn của tất cả các luồng sự kiện (Events) được phát đi (Outbound) và lắng nghe (Inbound) bởi Promotion Service thông qua Kafka. Thiết kế này tuân theo cùng chuẩn format với các service khác trong hệ sinh thái (VD: [Cart Events](../cart/cart_events.md)).

---

## 1. Cấu trúc Message Header chung (Kafka Envelope)

Tất cả messages trên Kafka đều mang header chuẩn:

*   `event_id`: UUID v7 (Unique ID, dùng để dedup và trace log).
*   `event_type`: Loại sự kiện (VD: `MARKDOWN_CREATED`, `COUPON_REDEEMED`).
*   `aggregate_type`: Loại Aggregate Root (VD: `Promotion`, `MarkdownRule`).
*   `aggregate_id`: ID của entity chủ thể (Promotion ID hoặc Rule ID).
*   `correlation_id`: ID truy vết xuyên service. Với luồng Saga bắt buộc dùng Temporal Workflow ID (vd: `wf-10001`), với luồng non-saga dùng request_id/job_run_id.
*   `business_id`: ID nghiệp vụ chính theo từng event (vd: `claim_id` cho `COUPON_CLAIMED`, `order_id` cho `COUPON_REDEEMED/CANCELLED/REFUNDED`, `rule_id` cho `MARKDOWN_*`).
*   `schema_version`: Phiên bản schema event ở top-level (hiện tại cố định = `1`).
*   `timestamp`: Thời điểm event được tạo (ISO 8601 UTC).
*   `source`: Định danh service gốc phát event (VD: `promotion-service`, `order-service`).
*   `payload`: JSON Object chứa nội dung chi tiết.

> [!IMPORTANT]
> Promotion dùng top-level `timestamp` làm chuẩn thời gian event. Các consumer dùng envelope `occurred_at` (vd Notification) phải map tương thích `timestamp -> occurred_at`.
> Với hệ dùng `event_schema_version`, map tương thích `schema_version -> event_schema_version` ở tầng adapter canonical.
>
> Với event inbound từ Kafka (`ORDER_REFUNDED`), Promotion bắt buộc dùng đúng `event_id` ở envelope để ghi `promotion_event_idempotency.event_id`. Không được tự sinh lại ID mới ở consumer.
>


> **Phase 21 (Schema Simplification):** `promotion_refund_allocations[]` giờ chỉ còn **2 field** (`promotion_id`, `refunded_discount_amount`) — bỏ `admin_funding_refund` vì luôn = `refunded_discount_amount` ở PLATFORM-only system. Column `order_promotions.admin_funding` cũng đã DROP. Promotion xử lý tương tự Phase 20: không suy luận allocation từ field bị bỏ; mọi voucher logic dựa trên `refunded_discount_amount`. Xem [CHANGELOG Phase 21](../_contracts/CHANGELOG.md#phase-21--order-service-schema-simplification).

> [!NOTE]
> Event outbound của Promotion được publish trực tiếp lên Kafka sau khi DB transaction commit, theo cơ chế **Best-Effort**. Downstream phải tự idempotent theo `event_id`/`business_id`.

---

## 2. Events Phát Đi (Outbound — Promotion → Other Services)

### 2.1. MARKDOWN_CREATED
**Ngữ cảnh:** Admin tạo mã giảm giá gạch chéo (Markdown Rule) mới. Xem [Workflow Luồng 1](promotion_workflow.md#1-luồng-tạo-mới-và-cập-nhật-khuyến-mãi-admin-workflow).
**Consumer chính:** Product Service (Tính `promotional_price`).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174000",
  "event_type": "MARKDOWN_CREATED",
  "aggregate_type": "MarkdownRule",
  "aggregate_id": "42",
  "correlation_id": "campaign-15-creation-saga",
  "business_id": "42",
  "timestamp": "2026-02-28T00:00:05Z",
  "source": "promotion-service",
  "payload": {
    "rule_id": 42,
    "campaign_id": 15,
    "owner_id": null,
    "scope_type": "CATEGORY",
    "ref_id": "cat-dien-thoai",
    "discount_type": "PERCENTAGE",
    "discount_value": 15.00,
    "max_discount_amount": 200000.00,
    "start_time": "2026-02-28T00:00:00Z",
    "end_time": "2026-03-07T23:59:59Z"
  }
}
```

### 2.2. MARKDOWN_UPDATED
**Ngữ cảnh:** Admin chỉnh sửa giá trị giảm, hoặc thời gian áp dụng.
**Consumer chính:** Product Service (Tính lại `promotional_price`).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174001",
  "event_type": "MARKDOWN_UPDATED",
  "aggregate_type": "MarkdownRule",
  "aggregate_id": "42",
  "correlation_id": "user-12-edit-action",
  "business_id": "42",
  "timestamp": "2026-03-01T08:30:00Z",
  "source": "promotion-service",
  "payload": {
    "rule_id": 42,
    "changes": {
      "discount_value": 20.00,
      "end_time": "2026-03-14T23:59:59Z"
    }
  }
}
```

### 2.3. MARKDOWN_ENDED
**Ngữ cảnh:** Markdown rule hết hạn (do Markdown Expiry Scheduler dùng Redis ZSET + Cron fallback kích hoạt) hoặc bị Admin tắt (`status → ENDED/`).
**Consumer chính:** Product Service (Xóa `promotional_price`, khôi phục giá gốc).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174002",
  "event_type": "MARKDOWN_ENDED",
  "aggregate_type": "MarkdownRule",
  "aggregate_id": "42",
  "correlation_id": "markdown-expiry-scheduler-run-543",
  "business_id": "42",
  "timestamp": "2026-03-07T23:59:59Z",
  "source": "promotion-service",
  "payload": {
    "rule_id": 42,
    "ref_id": "cat-dien-thoai",
    "reason": "EXPIRED"
  }
}
```

> `payload.reason` enum chuẩn: `EXPIRED` | `ADMIN_DISABLED`.

> Với `reason = EXPIRED`, Producer phải chốt `UPDATE markdown_rules` (DB) rồi publish `MARKDOWN_ENDED` lên Kafka. Nếu crash sau DB commit trước khi Kafka send, event bị mất — chấp nhận best-effort.

### 2.4. COUPON_CLAIMED
**Ngữ cảnh:** User giành được mã Flash Sale (SPOP). Xem [Workflow Luồng 2](promotion_workflow.md#2-luồng-tranh-mã-flash-sale-spop-claiming).
**Consumer chính:** Notification Service (Gửi thông báo "Chúc mừng bạn đã lưu mã thành công!").
**Publish path:** Direct-produce từ API/Rescue Job (không đi qua outbox DB).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174003",
  "event_type": "COUPON_CLAIMED",
  "aggregate_type": "Promotion",
  "aggregate_id": "100",
  "correlation_id": "req-987654",
  "business_id": "549",
  "timestamp": "2026-02-28T09:46:40Z",
  "source": "promotion-service",
  "payload": {
    "promotion_id": 100,
    "claim_id": 549,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "production_source": "API_DIRECT",
    "claimed_at": "2026-02-28T09:46:40Z"
  }
}
```

> [!IMPORTANT]
> **Contract bắt buộc cho `COUPON_CLAIMED`:**
> 1. `business_id` phải bằng `claim_id` để thống nhất khóa nghiệp vụ xuyên producer/consumer.
> 2. `event_id` là khóa delivery-level và phải mới ở mỗi lần publish/republish.
> 3. `claim_id` là khóa business-level bất biến cho cùng một vé claim.
> 4. Downstream phải dedup hai tầng theo thứ tự: `event_id` -> `claim_id`.
>
> `payload.production_source` enum chuẩn:
> - `API_DIRECT`: phát từ API ngay sau Lua Script SPOP.
> - `SWEEPER_RESCUE`: phát lại từ Pending Claims Sweeper để cứu crash window.

### 2.5. COUPON_REDEEMED
**Ngữ cảnh:** Đơn hàng thanh toán thành công, ngân sách xác nhận trừ. Xem [Workflow Luồng 4.B](promotion_workflow.md#bước-b-thanh-toán-thành-công-confirm).
**Consumer chính:** Analytics.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174004",
  "event_type": "COUPON_REDEEMED",
  "aggregate_type": "Promotion",
  "aggregate_id": "100",
  "correlation_id": "wf-5001",
  "business_id": "5001",
  "timestamp": "2026-02-28T10:16:40Z",
  "source": "promotion-service",
  "payload": {
    "promotion_id": 100,
    "order_id": 5001,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "discount_amount": 50000.00,
    "redeemed_at": "2026-02-28T10:16:40Z"
  }
}
```

### 2.6. COUPON_CANCELLED
**Ngữ cảnh:** Đơn hàng bị hủy hoặc TTL Reservation hết hạn. Xem [Workflow Luồng 4.C/4.D](promotion_workflow.md#bước-c-thanh-toán-thất-bại--tự-huỷ-cancelrollback).
**Consumer:** Analytics.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174005",
  "event_type": "COUPON_CANCELLED",
  "aggregate_type": "Promotion",
  "aggregate_id": "100",
  "correlation_id": "wf-5001",
  "business_id": "5001",
  "timestamp": "2026-02-28T10:33:20Z",
  "source": "promotion-service",
  "payload": {
    "promotion_id": 100,
    "order_id": 5001,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "discount_amount": 50000.00,
    "reason": "ORDER_CANCELLED",
    "cancelled_at": "2026-02-28T10:33:20Z"
  }
}
```
*(`reason` có thể là `ORDER_CANCELLED` hoặc `RESERVATION_TTL_EXPIRED`)*

### 2.7. COUPON_REFUNDED
**Ngữ cảnh:** Đơn hàng đã giao nhưng khách đổi trả hàng. Xem [Workflow Luồng 5](promotion_workflow.md#5-luồng-đổi-trả-hàng--hoàn-tiền-refund-flow).
**Consumer:** Analytics.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174006",
  "event_type": "COUPON_REFUNDED",
  "aggregate_type": "Promotion",
  "aggregate_id": "100",
  "correlation_id": "wf-5001",
  "business_id": "5001",
  "timestamp": "2026-02-28T23:53:20Z",
  "source": "promotion-service",
  "payload": {
    "promotion_id": 100,
    "order_id": 5001,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "refund_transaction_id": "RFD-DEF456",
    "refund_type": "FULL",
    "discount_amount": 50000.00,
    "refunded_discount_amount": 50000.00,
    "restore_outcome": "RETAINED_USED",
    "refunded_at": "2026-02-28T23:53:20Z"
  }
}
```

> **Idempotency contract:** `refund_transaction_id` bắt buộc có để downstream settlement/analytics dedup đúng theo giao dịch hoàn tiền.

### 2.8. BUDGET_EXHAUSTED
**Ngữ cảnh:** Ngân sách (Quantity hoặc Budget Amount) của một promotion đã cạn kiệt hoàn toàn (`available = 0`).
**Consumer:** Notification Service (Báo cho Admin/admin), Frontend (Disable nút "Lưu mã").

> [!NOTE]
> Event Kafka `BUDGET_EXHAUSTED` là tín hiệu bất đồng bộ cho monitoring/UI. Ở submit path thời gian thực, Checkout nhận lỗi reserve có cấu trúc qua call đồng bộ (`ReservePromotionActivity`), không chờ Kafka event này để quyết định phản hồi `409 PROMOTION_RECALCULATION_NEEDED`.
> `failed_reason` chi tiết (`BUDGET_QTY_EXHAUSTED|BUDGET_AMOUNT_EXHAUSTED`) thuộc contract đồng bộ của API reserve; event Kafka chỉ dùng để broadcast trạng thái exhausted ở mức promotion.
>
> Theo Option A, Promotion **không fanout invalidate wallet ví toàn cục** theo `promotion_id` khi nhận/emit `BUDGET_EXHAUSTED`. Độ tươi của tray voucher được cải thiện bằng Budget Guard read-time trong Discovery; correctness cuối cùng vẫn do Reserve ở Submit.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174007",
  "event_type": "BUDGET_EXHAUSTED",
  "aggregate_type": "Promotion",
  "aggregate_id": "100",
  "correlation_id": "budget-monitor-100",
  "business_id": "100",
  "timestamp": "2026-02-28T10:50:00Z",
  "source": "promotion-service",
  "payload": {
    "promotion_id": 100,
    "exhausted_type": "QUANTITY",
    "total_quantity": 1000,
    "redeemed_quantity": 997,
    "reserved_quantity": 3,
    "exhausted_at": "2026-02-28T10:50:00Z"
  }
}
```

---

## 3. Events Lắng Nghe (Inbound — Other Services → Promotion)

### 3.1. ORDER_REFUNDED (Inbound)
**Producer:** Order Service.
**Topic:** `t.order.events`
**Ngữ cảnh:** Đơn hàng đã giao thành công nhưng khách đổi trả hàng hoàn tiền. Trigger [Workflow 5](promotion_workflow.md#5-luồng-đổi-trả-hàng--hoàn-tiền-refund-flow). Xem [Order Events - ORDER_REFUNDED](../order/order_events.md#36-order_refunded).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174203",
  "event_type": "ORDER_REFUNDED",
  "aggregate_type": "Order",
  "aggregate_id": "5001",
  "correlation_id": "wf-5001",
  "timestamp": "2026-03-05T10:00:00Z",
  "source": "order-service",
  "schema_version": 1,
  "payload": {
    "order_id": 5001,
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "status": "REFUNDED",
    "last_event_sequence_id": 11,
    "refund_transaction_id": "RFD-DEF456",
    "refund_type": "FULL",
    "refund_amount": 995000,
    "refund_trigger_source": "API_RETURN",
    "customer_return_request_id": "fdf4d7d3-4d35-4a61-a55a-8d2b1a315b6a",
    "returned_item_ids": [2001],
    "promotion_refund_allocations": [
      {
        "promotion_id": 100,
        "refunded_discount_amount": 50000
      }
    ],
    "refunded_at": "2026-03-05T10:00:00Z"
  }
}
```

> **Refund rule (bắt buộc, Phase 20):** Promotion hoàn ngân sách theo `promotion_refund_allocations[]` — không suy luận từ `returned_item_ids[]` (Order đã lock scope từ request, chỉ publish allocation cho Promotion execute).
>

>
> **Partial voucher scope rule:** Với `refund_type = PARTIAL`, chỉ allocation PLATFORM đạt điều kiện `RESTORE_ONLY_IF_FULL_PLATFORM_COVERED` (internal rule) mới có `refunded_discount_amount > 0`; ngược lại phải = `0`.
>

> **Unavailable voucher handling:** Nếu voucher không còn khả dụng (row ví không tồn tại) ở thời điểm xử lý refund, Promotion ghi nhận `restore_outcome = VOUCHER_UNAVAILABLE` và không rollback tiền refund đã thành công ở Payment.
>
> **Idempotency key format (Promotion dùng cho Redis Lua + DB):** `REFUND_FULL:{order_id}:{promotion_id}:{refund_request_id}` hoặc `REFUND_PARTIAL:{order_id}:{promotion_id}:{refund_request_id}` — Promotion tự sinh từ `payload.refund_transaction_id` + `payload.customer_return_request_id` + `payload.promotion_refund_allocations[].promotion_id` (xem canonical [ADR Refund Voucher Policy §2.2](../order/refund_voucher_policy.md)).

### 3.2. USER_REGISTERED (Inbound - Optional)
**Producer:** Auth Service.
**Topic:** `t.auth.events`
**Ngữ cảnh:** Đồng bộ use-case welcome voucher/new-user promotion. Event này optional theo feature-flag, không ảnh hưởng luồng checkout cốt lõi.

```json
{
  "event_type": "USER_REGISTERED",
  "payload": {
    "user_id": "550e8400-e29b-41d4-a716-446655440000",
    "registered_at": "2026-03-05T10:00:00Z"
  }
}
```


> **Compatibility note:** Auth có thể phát envelope dạng `data` + `timestamp_ms` + `event_version` (xem [auth_events.md](../auth/auth_events.md)). Promotion consumer phải map adapter: `data -> payload`, `timestamp_ms -> payload.registered_at` (ISO-8601), rồi mới chạy business handler.

### 3.3. PRODUCT_STATE_CHANGED (Inbound)
**Producer:** Product Service.
**Topic:** `t.product.events`
**Ngữ cảnh:** Sản phẩm chuyển trạng thái qua FSM. Promotion cần bắt sự kiện này để apply markdown cho sản phẩm vừa chuyển `ACTIVE` khi rule vẫn còn hiệu lực.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174204",
  "event_type": "PRODUCT_STATE_CHANGED",
  "aggregate_type": "Product",
  "aggregate_id": "prod-001",
  "correlation_id": "req-123458",
  "timestamp": "2026-02-28T05:00:00Z",
  "source": "product-service",
  "payload": {
    "product_id": "prod-001",
        "old_status": "PENDING_REVIEW",
    "new_status": "ACTIVE",
    "version": 4,
    "updated_at": "2026-02-28T05:00:00Z"
  }
}
```

> **Rule xử lý bắt buộc:** Chỉ kích hoạt markdown re-apply khi `new_status = ACTIVE`. Các trạng thái khác được ACK + no-op có audit metric.

---

## 4. Kafka Topic Mapping

| Topic | Event Types | Partitioning Key |
|:---|:---|:---|
| `t.promotion.markdown.events` | `MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED` | `rule_id` |
| `t.promotion.coupon.events` | `COUPON_CLAIMED` | `claim_id` |
| `t.promotion.coupon.events` | `COUPON_REDEEMED`, `COUPON_CANCELLED`, `COUPON_REFUNDED` | `order_id` |
| `t.promotion.budget.events` | `BUDGET_EXHAUSTED` | `promotion_id` |
| `t.order.events` | `ORDER_REFUNDED` (Inbound) | `order_id` |
| `t.product.events` | `PRODUCT_STATE_CHANGED` (Inbound) | `product_id` |
| `t.auth.events` | `USER_REGISTERED` (Inbound, optional) | `user_id` |

> [!NOTE]
> **Partitioning Key** phải theo đúng ngữ nghĩa nghiệp vụ của từng event group để đảm bảo ordering cần thiết (theo order lifecycle hoặc theo promotion lifecycle).
