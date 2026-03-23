# Promotion Service - Định nghĩa Các Sự Kiện (Events Schema)

**Liên kết nhanh (Related Documents):**
*   [Database Schema](promotion_db_design.md)
*   [Redis Caching & Lua Scripts](redis_caching_design.md)
*   [Promotion Workflows](promotion_workflow.md)
*   [Outbox Pattern](promotion_outbox_pattern.md)
*   [Coupon Claims Deletion Strategy](coupon_claims_deletion_strategy.md)

---

Tài liệu này định nghĩa cấu trúc tiêu chuẩn của tất cả các luồng sự kiện (Events) được phát đi (Outbound) và lắng nghe (Inbound) bởi Promotion Service thông qua Kafka. Thiết kế này tuân theo cùng chuẩn format với các service khác trong hệ sinh thái (VD: [Cart Events](../cart/cart_events.md)).

---

## 1. Cấu trúc Message Header chung (Kafka Envelope)

Tất cả messages trên Kafka đều mang header chuẩn:

*   `event_id`: UUID v7 (Unique ID, dùng để dedup và trace log).
*   `event_type`: Loại sự kiện (VD: `MARKDOWN_CREATED`, `COUPON_REDEEMED`).
*   `aggregate_type`: Loại Aggregate Root (VD: `Promotion`, `MarkdownRule`).
*   `aggregate_id`: ID của entity chủ thể (Promotion ID hoặc Rule ID).
*   `correlation_id`: Temporal Workflow ID (vd: `wf-10001`) để truy vết chuỗi Saga xuyên suốt nhiều service.
*   `business_id`: `order_id` dùng cho nghiệp vụ đối soát.
*   `timestamp`: Thời điểm event được tạo (ISO 8601 UTC).
*   `source`: Định danh service gốc phát event (VD: `promotion-service`, `order-service`).
*   `payload`: JSON Object chứa nội dung chi tiết.

> [!NOTE]
> Event được ghi trước vào bảng [`promotion_outbox`](promotion_db_design.md#18-promotion_outbox-outbox-pattern--reliable-event-publishing) cùng Transaction PostgreSQL, sau đó [Outbox Relay Worker](promotion_outbox_pattern.md) đọc và publish lên Kafka. Cơ chế này đảm bảo **At-Least-Once** publishing; duplicate được xử lý bằng idempotency ở downstream consumers.

---

## 2. Events Phát Đi (Outbound — Promotion → Other Services)

### 2.1. MARKDOWN_CREATED
**Ngữ cảnh:** Admin tạo mã giảm giá gạch chéo (Markdown Rule) mới. Xem [Workflow Luồng 1](promotion_workflow.md#1-luồng-tạo-mới-và-cập-nhật-khuyến-mãi-admin-workflow).
**Consumer chính:** Product Service (Tính `promotional_price` cho sản phẩm thuộc scope).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174000",
  "event_type": "MARKDOWN_CREATED",
  "aggregate_type": "MarkdownRule",
  "aggregate_id": "42",
  "correlation_id": "campaign-15-creation-saga",
  "timestamp": "2026-02-28T00:00:05Z",
  "source": "promotion-service",
  "payload": {
    "rule_id": 42,
    "campaign_id": 15,
    "owner_type": "PLATFORM",
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
**Ngữ cảnh:** Admin chỉnh sửa giá trị giảm, scope, hoặc thời gian áp dụng.
**Consumer chính:** Product Service (Tính lại `promotional_price`).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174001",
  "event_type": "MARKDOWN_UPDATED",
  "aggregate_type": "MarkdownRule",
  "aggregate_id": "42",
  "correlation_id": "user-12-edit-action",
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
**Ngữ cảnh:** Markdown rule hết hạn hoặc bị Admin tắt (`status → ENDED/INACTIVE`).
**Consumer chính:** Product Service (Xóa `promotional_price`, khôi phục giá gốc).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174002",
  "event_type": "MARKDOWN_ENDED",
  "aggregate_type": "MarkdownRule",
  "aggregate_id": "42",
  "correlation_id": "markdown-expiry-scanner-run-543",
  "timestamp": "2026-03-07T23:59:59Z",
  "source": "promotion-service",
  "payload": {
    "rule_id": 42,
    "scope_type": "CATEGORY",
    "ref_id": "cat-dien-thoai",
    "reason": "EXPIRED"
  }
}
```

### 2.4. COUPON_CLAIMED
**Ngữ cảnh:** User giành được mã Flash Sale (SPOP). Xem [Workflow Luồng 2](promotion_workflow.md#2-luồng-tranh-mã-flash-sale-spop-claiming).
**Consumer chính:** Notification Service (Gửi thông báo "Chúc mừng bạn đã lưu mã thành công!"), Analytics.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174003",
  "event_type": "COUPON_CLAIMED",
  "aggregate_type": "Promotion",
  "aggregate_id": "100",
  "correlation_id": "req-987654",
  "timestamp": "2026-02-28T09:46:40Z",
  "source": "promotion-service",
  "payload": {
    "promotion_id": 100,
    "claim_id": 549,
    "user_id": 99,
    "claimed_at": "2026-02-28T09:46:40Z"
  }
}
```

### 2.5. COUPON_RESERVED
**Ngữ cảnh:** Budget được khóa khi user bấm "Xác nhận Đặt hàng". Xem [Workflow Luồng 4.A](promotion_workflow.md#bước-a-xác-nhận-giỏ-hàng-try).
**Consumer chính:** Internal (Audit log), Analytics.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174004",
  "event_type": "COUPON_RESERVED",
  "aggregate_type": "Promotion",
  "aggregate_id": "100",
  "correlation_id": "5001",
  "timestamp": "2026-02-28T10:00:00Z",
  "source": "promotion-service",
  "payload": {
    "promotion_id": 100,
    "order_id": 5001,
    "user_id": 99,
    "discount_amount": 50000.00,
    "platform_funding": 20000.00,
    "seller_funding": 30000.00,
    "reserved_at": "2026-02-28T10:00:00Z"
  }
}
```

### 2.6. COUPON_REDEEMED
**Ngữ cảnh:** Đơn hàng thanh toán thành công, ngân sách xác nhận trừ. Xem [Workflow Luồng 4.B](promotion_workflow.md#bước-b-thanh-toán-thành-công-confirm).
**Consumer chính:** Analytics, Seller Settlement Service (đối soát chi phí khuyến mãi).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174005",
  "event_type": "COUPON_REDEEMED",
  "aggregate_type": "Promotion",
  "aggregate_id": "100",
  "correlation_id": "5001",
  "timestamp": "2026-02-28T10:16:40Z",
  "source": "promotion-service",
  "payload": {
    "promotion_id": 100,
    "order_id": 5001,
    "user_id": 99,
    "discount_amount": 50000.00,
    "platform_funding": 20000.00,
    "seller_funding": 30000.00,
    "redeemed_at": "2026-02-28T10:16:40Z"
  }
}
```

### 2.7. COUPON_CANCELLED
**Ngữ cảnh:** Đơn hàng bị hủy hoặc TTL Reservation hết hạn. Xem [Workflow Luồng 4.C/4.D](promotion_workflow.md#bước-c-thanh-toán-thất-bại--tự-huỷ-cancelrollback).
**Consumer:** Analytics.

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174006",
  "event_type": "COUPON_CANCELLED",
  "aggregate_type": "Promotion",
  "aggregate_id": "100",
  "correlation_id": "5001",
  "timestamp": "2026-02-28T10:33:20Z",
  "source": "promotion-service",
  "payload": {
    "promotion_id": 100,
    "order_id": 5001,
    "user_id": 99,
    "discount_amount": 50000.00,
    "reason": "ORDER_CANCELLED",
    "cancelled_at": "2026-02-28T10:33:20Z"
  }
}
```
*(`reason` có thể là `ORDER_CANCELLED` hoặc `RESERVATION_TTL_EXPIRED`)*

### 2.8. COUPON_REFUNDED
**Ngữ cảnh:** Đơn hàng đã giao nhưng khách đổi trả hàng. Xem [Workflow Luồng 5](promotion_workflow.md#5-luồng-đổi-trả-hàng--hoàn-tiền-refund-flow).
**Consumer:** Analytics, Seller Settlement Service (hoàn lại chi phí khuyến mãi cho Seller).

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174007",
  "event_type": "COUPON_REFUNDED",
  "aggregate_type": "Promotion",
  "aggregate_id": "100",
  "correlation_id": "5001",
  "timestamp": "2026-02-28T23:53:20Z",
  "source": "promotion-service",
  "payload": {
    "promotion_id": 100,
    "order_id": 5001,
    "user_id": 99,
    "discount_amount": 50000.00,
    "platform_funding": 20000.00,
    "seller_funding": 30000.00,
    "refunded_at": "2026-02-28T23:53:20Z"
  }
}
```

### 2.9. BUDGET_EXHAUSTED
**Ngữ cảnh:** Ngân sách (Quantity hoặc Budget Amount) của một promotion đã cạn kiệt hoàn toàn (`available = 0`).
**Consumer:** Notification Service (Báo cho Admin/Seller), Frontend (Disable nút "Lưu mã").

```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174008",
  "event_type": "BUDGET_EXHAUSTED",
  "aggregate_type": "Promotion",
  "aggregate_id": "100",
  "correlation_id": "5001",
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

### 3.1. ORDER_PAID (Inbound)
**Producer:** Order Service (sau khi nhận `PAYMENT_SUCCESS` từ Payment → Signal Temporal → FSM `PAID`).
**Topic:** `t.order.events`
**Ngữ cảnh:** Khách hàng thanh toán đơn hàng thành công. Event này dùng cho đối soát/audit/fallback recovery; đường xử lý chính của Confirm Redeem là gọi đồng bộ `ConfirmPromotionActivity` trong Temporal Saga. Xem [Order Events - ORDER_PAID](../order/order_events.md#32-order_paid).

```json
{
  "event_type": "ORDER_PAID",
  "payload": {
    "order_id": 5001,
    "user_id": 99,
    "promotions_applied": [
      {"promotion_id": 100, "discount_amount": 50000.00, "platform_funding": 20000.00, "seller_funding": 30000.00}
    ],
    "paid_at": "2026-02-28T10:05:00Z"
  }
}
```

### 3.2. ORDER_CANCELLED (Inbound)
**Producer:** Order Service.
**Topic:** `t.order.events`
**Ngữ cảnh:** Đơn hàng bị hủy (user hủy, hoặc Temporal timeout 15 phút). Event này dùng cho đối soát/audit/fallback recovery; đường xử lý chính của rollback budget là gọi đồng bộ `RollbackPromotionActivity` trong Temporal Compensation Stack. Xem [Order Events - ORDER_CANCELLED](../order/order_events.md#33-order_cancelled).

```json
{
  "event_type": "ORDER_CANCELLED",
  "payload": {
    "order_id": 5001,
    "user_id": 99,
    "promotions_applied": [
      {"promotion_id": 100, "discount_amount": 50000.00, "platform_funding": 20000.00, "seller_funding": 30000.00}
    ],
    "cancelled_at": "2026-02-28T10:20:00Z",
    "reason": "PAYMENT_TIMEOUT"
  }
}
```

### 3.3. ORDER_REFUNDED (Inbound)
**Producer:** Order Service.
**Topic:** `t.order.events`
**Ngữ cảnh:** Đơn hàng đã giao thành công nhưng khách đổi trả hàng hoàn tiền. Trigger [Workflow 5](promotion_workflow.md#5-luồng-đổi-trả-hàng--hoàn-tiền-refund-flow). Xem [Order Events - ORDER_REFUNDED](../order/order_events.md#36-order_refunded).

```json
{
  "event_type": "ORDER_REFUNDED",
  "payload": {
    "order_id": 5001,
    "user_id": 99,
    "promotions_applied": [
      {"promotion_id": 100, "discount_amount": 50000.00, "platform_funding": 20000.00, "seller_funding": 30000.00}
    ],
    "refunded_at": "2026-03-05T10:00:00Z"
  }
}
```

---

## 4. Kafka Topic Mapping

| Topic | Event Types | Partitioning Key |
|:---|:---|:---|
| `t.promotion.markdown.events` | `MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED` | `rule_id` |
| `t.promotion.coupon.events` | `COUPON_CLAIMED`, `COUPON_RESERVED`, `COUPON_REDEEMED`, `COUPON_CANCELLED`, `COUPON_REFUNDED` | `promotion_id` |
| `t.promotion.budget.events` | `BUDGET_EXHAUSTED` | `promotion_id` |
| `t.order.events` | `ORDER_PAID`, `ORDER_CANCELLED`, `ORDER_REFUNDED` (Inbound) | `order_id` |

> [!NOTE]
> **Partitioning Key** đảm bảo mọi event liên quan đến cùng 1 promotion/order luôn rơi vào cùng 1 Kafka Partition, giữ đúng thứ tự xử lý (Ordering Guarantee).
