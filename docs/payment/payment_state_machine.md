# Payment Service - State Machine (Payment + Refund FSM)

**Liên kết nhanh (Related Documents):**
* [Database Schema](payment_db_design.md)
* [Workflows (API & Data Flow)](payment_workflow.md)
* [Background Workers & Jobs](payment_workers.md)
* [Events Structure & Kafka Topics](payment_events.md)
* [Refund Boundary Decision (ADR)](refund_boundary_decision.md)

---

Tài liệu này mô tả state machine cho Payment Transaction và Refund Transaction, tập trung vào tính đúng đắn tài chính trong môi trường webhook bất đồng bộ, retry, duplicate và out-of-order.

## 1. Payment Transaction FSM

### 1.1. Trạng thái

| Status | Ý nghĩa |
|:---|:---|
| `PENDING` | Đã tạo giao dịch, chờ người dùng thanh toán hoặc webhook xác nhận. |
| `PROCESSING` | Đang xử lý callback/trạng thái trung gian từ provider. |
| `SUCCESS` | Thanh toán thành công, có thể trigger luồng fulfilment. |
| `FAILED` | Thanh toán thất bại, không trừ tiền thành công. |
| `EXPIRED` | Phiên thanh toán hết hạn, không còn hiệu lực. |
| `CANCELED` | Giao dịch bị hủy (user/admin/system). |
| `REFUNDING` | Đã gửi yêu cầu hoàn tiền, chờ kết quả cuối. |
| `REFUNDED` | Hoàn tiền hoàn tất theo tín hiệu cuối cùng từ provider/reconciliation. |

### 1.2. Bảng chi tiết transitions

| ID | From | Event/Trigger | Guard | To |
|:---|:---|:---|:---|:---|
| T1 | `PENDING` | `PAYMENT_WEBHOOK_RECEIVED` | Signature hợp lệ + amount/ref match | `PROCESSING` |
| T2 | `PROCESSING` | `PAYMENT_SUCCESS_CONFIRMED` | Provider xác nhận thành công | `SUCCESS` |
| T3 | `PROCESSING` | `PAYMENT_FAILED_CONFIRMED` | Provider xác nhận thất bại | `FAILED` |
| T4 | `PENDING`/`PROCESSING` | `PAYMENT_SESSION_EXPIRED` | `NOW() >= expires_at` | `EXPIRED` |
| T5 | `PENDING`/`PROCESSING` | `PAYMENT_CANCELED` | Hủy hợp lệ | `CANCELED` |
| T6 | `SUCCESS` | `REFUND_REQUEST_ACCEPTED` | Tổng refund hợp lệ | `REFUNDING` |
| T7 | `REFUNDING` | `REFUND_SUCCESS_CONFIRMED` | Provider/poller/reconcile xác nhận | `REFUNDED` |
| T8 | `REFUNDING` | `REFUND_FAILED_CONFIRMED` | Provider/poller/reconcile xác nhận refund thất bại | `FAILED` |
| T9 | `REFUNDED` | `REFUND_REVERSED_CONFIRMED`| Provider xác nhận Refund bị đảo chiều | `SUCCESS` |

> **Lưu ý T8 & T9:** Cả 2 transition này sẽ trigger việc bật cờ (flag) `requires_manual_review = TRUE` trên `payment_transactions` để nhân viên CSKH/Kế toán vào xử lý thủ công, do hệ thống không thể tự động rút tiền lần 2 nếu gateway lỗi hoàn hoặc lỗi tài khoản đích.

### 1.3. Bổ sung cho `REFUND_REVERSED` (provider-dependent)

Một số cổng có thể gửi tín hiệu đảo chiều refund sau khi trước đó đã báo success (ví dụ tài khoản user khóa).

Nguyên tắc xử lý:
1. Áp dụng Transition T9 để kéo `payment_transaction` về lại `SUCCESS` (Tiền lại nằm ở Merchant).
2. Ghi audit transition vào `payment_status_history`.
3. Phát event `REFUND_REVERSED` để Order Service cập nhật theo (VD `ORDER_REFUND_FAILED`).
4. Đưa case vào hàng chờ xử lý thủ công (incident/manual review queue).

## 2. Refund Transaction FSM

### 2.1. Trạng thái

| Status | Ý nghĩa |
|:---|:---|
| `PENDING` | Đã tạo yêu cầu refund, chưa gửi/hoặc vừa gửi sang provider. |
| `PROCESSING` | Provider đang xử lý bất đồng bộ. |
| `SUCCESS` | Hoàn tiền thành công. |
| `FAILED` | Hoàn tiền thất bại. |

### 2.2. Bảng chi tiết transitions

| ID | From | Event/Trigger | Guard | To |
|:---|:---|:---|:---|:---|
| R1 | `PENDING` | `REFUND_REQUEST_SENT` | Request hợp lệ + idempotency pass | `PROCESSING` |
| R2 | `PROCESSING` | `REFUND_SUCCESS_CONFIRMED` | Callback/poller/reconcile xác nhận | `SUCCESS` |
| R3 | `PROCESSING` | `REFUND_FAILED_CONFIRMED` | Callback/poller/reconcile xác nhận | `FAILED` |
| R4 | `PENDING` | `REFUND_REQUEST_REJECTED` | Validation/provider reject sớm | `FAILED` |

## 3. Guard Rules (bắt buộc)

### 3.1. Signature & Authenticity Guard (Stripe chuẩn)

1. Verify `Stripe-Signature` header theo cơ chế `t=<timestamp>,v1=<signature>` của Stripe. Tính lại HMAC-SHA256 với `signed_payload = t + "." + raw_body` dùng `whsec_...` (lưu trong Vault/Secret Manager).
2. Reject ngay callback không hợp lệ (`FAILED_SIGNATURE` trong webhook logs). Nếu `|now - t| > 300s` cũng reject (chống replay attack).

### 3.2. Amount & Reference Guard

1. `provider_transaction_id` phải map được về transaction nội bộ.
2. Amount/currency phải khớp dữ liệu đã ghi nhận.

### 3.3. Idempotency & Ordering Guard

1. Mọi transition phải idempotent theo `event_id` hoặc `provider_ref + event_type`.
2. Chỉ cho phép transition hợp lệ theo bảng trạng thái.
3. Webhook out-of-order phải được xử lý bằng rule state precedence, không override ngược trạng thái terminal hợp lệ.

### 3.4. Audit Trail: payment_status_history

Mọi state transition của Payment/Refund đều phải ghi vào bảng `payment_status_history` với:

- `transaction_id`
- `transaction_type`
- `old_status`
- `new_status`
- `triggered_by` (`WEBHOOK`, `CRONJOB`, `API`, `TEMPORAL`)
- `provider_response`
- `created_at`

## 4. Consistency Model

1. Source of truth là PostgreSQL transaction.
2. Event publication dùng Outbox sau commit.
3. Nếu provider và nội bộ lệch trạng thái, Reconciliation job có quyền phát transition bù để đưa hệ thống về trạng thái đúng.

## 5. Invariants (không được vi phạm)

1. Tổng tiền hoàn không vượt tiền đã thanh toán.
2. Không có transition bỏ qua guard signature/amount/reference.
3. Không publish event khi DB transaction chưa commit.
4. Với cùng `order_id`, event payment/refund phải giữ ordering theo partition key.
5. Mọi terminal transition đều phải có audit record.
