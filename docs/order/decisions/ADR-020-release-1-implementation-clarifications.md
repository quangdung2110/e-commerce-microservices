---
doc_id: order-adr-020
service: order
status: accepted
date: 2026-10-05
decision_owners:
  - Order Service team
supersedes: null
refines:
  - ADR-012
  - ADR-013
  - ADR-014
  - ADR-015
  - ADR-017
  - ADR-018
evidence:
  - docs/order/domain.md
  - docs/order/data.md
  - docs/order/interfaces.md
  - docs/order/workflows.md
  - contracts/http/order/v1/openapi.yaml
  - contracts/http/order/internal/v1/openapi.yaml
  - contracts/events/delivery/v1/asyncapi.yaml
  - java/services/order-service
---

# ADR-020 — Các điểm làm rõ trước implementation Release 1

## Trạng thái

`Accepted` — 2026-10-05. ADR này không đổi quyết định kiến trúc của ADR-001…019; nó chốt
các điểm còn mở hoặc mâu thuẫn mà implementation đầu tiên phát hiện khi đối chiếu
tài liệu Order với contract Inventory, Payment, Promotion, Delivery và Notification.

## Bối cảnh

Bộ tài liệu Order được viết trước khi có code. Khi đọc cùng contract đã implement của
Inventory/Payment/Promotion, một số chỗ không khớp (giới hạn số dòng, enum cancel reason
của Payment), một số field/state chưa có nơi lưu (trạng thái submission, `variant_name`,
actor của quyết định) và một số trigger chưa xác định (`PAYMENT_FAILED`, obligation cho
return chờ xác minh thủ công).

## Quyết định

### Runtime

Order Service là Java 21 + Spring Boot 3.3 (JDBC + Flyway, không JPA), Spring Kafka và
Temporal Java SDK, đặt tại `java/services/order-service`, cùng convention với Inventory
và Payment.

### Wire/contract

1. **Giới hạn dòng = 100.** Inventory Preview/Reserve/RefundScope (đã implement) nhận tối
   đa 100 line. Order internal `items[]`/`allocation_plan.lines[]` và public
   `items[]`/`lines[]`/return `lines[]`/refund-case `lines[]` giảm từ 200 xuống 100.
2. **`variant_name`.** Checkout `CheckoutItem` có thêm `variant_name` optional/nullable.
   Order lưu immutable ở `order_item_lines.snapshot_variant_name`; đây là nguồn duy nhất của
   `order.completed.v1 items[].variant_name` và `variant_name` trong notification. Không
   suy diễn từ `snapshot_attributes`.
3. **Lỗi submit terminal.** Inventory Reserve `REJECT_ALL` trả
   `409 INVENTORY_RESERVATION_REJECTED`; Payment initiation bị từ chối bền vững trả
   `409 PAYMENT_INITIATION_REJECTED`. Recovery read trả `state=FAILED` cùng problem đó.
   Promotion reserve fail vẫn là `409 PROMOTION_RECALCULATION_NEEDED`
   (`state=RECALCULATION_REQUIRED`).
4. **`X-Actor-Id`.** Decision API bắt buộc header UUID của người Finance/Ops; Order lưu
   cùng mesh workload principal. Header chỉ là attribution, không phải authorization.
5. **`ui_primary_status`** là enum: `PENDING_PAYMENT=AWAITING_PAYMENT`,
   `CANCELLING=CANCELLING`, `PAID/PROCESSING` tab `TO_SHIP=PREPARING`, tab
   `TO_RECEIVE=IN_DELIVERY`, `COMPLETED`, `CANCELLED`,
   `RETURN_REQUESTED=RETURN_IN_PROGRESS`, `REFUNDED`,
   `FINANCIAL_RECONCILIATION_REQUIRED=UNDER_REVIEW`. `ui_status_rank` là rank của tab.
6. **Delivery `source`** là `/services/delivery` theo convention CloudEvents chung.

### Domain

7. **Cancel reason.** `cancel_reason` được gán như sau:

   | Tình huống | `cancel_reason` |
   |---|---|
   | Buyer cancel | `USER_CANCEL` |
   | Admin cancel pre-payment | `ADMIN_FORCE_CANCEL` |
   | Deadline tới khi attempt cuối còn `PENDING`, hoặc attempt `EXPIRED` | `PAYMENT_TIMEOUT` |
   | Deadline tới khi attempt cuối là `FAILED`, hoặc Payment `CANCELED` với `PROVIDER_CANCELED` | `PAYMENT_FAILED` |
   | Lỗi trước `PAYMENT_READY` (reserve/promotion/payment initiation/deadline cap) | `PRE_PAYMENT_SAGA_FAILED` |

   Payment cancel `reason`: `USER_CANCEL -> BUYER_CANCELLED`,
   `PAYMENT_TIMEOUT|PAYMENT_FAILED -> PAYMENT_TIMEOUT`,
   `ADMIN_FORCE_CANCEL|PRE_PAYMENT_SAGA_FAILED -> ORDER_CANCELLED_PRE_CAPTURE`. Inventory
   release reason giữ bảng trong `interfaces.md` §3.
8. **Không có cửa sổ thời gian trả hàng** ở Release 1: Buyer return chỉ cần master
   `COMPLETED`, mọi line `DELIVERED` bằng verified proof và guard one-shot chưa dùng.
9. **Obligation `RETURN_MANUAL_VERIFICATION`.** 30-day inspection timeout hoặc
   RegisterRefundScope bị Inventory từ chối bền vững mở obligation này (resource =
   `refund_request_id`). Resolution hợp lệ: `RESUME_FORWARD_REPAIR` (chỉ khi đã có full
   completion evidence), `CLOSE_RETURN_WITHOUT_REFUND`, `ESCALATE_TO_ACCOUNTING`.
10. **Promotion grace.** Order không có activity "gia hạn reservation 60 giây": Promotion
    tự gia hạn grace trong Confirm-Prepare (Promotion `cleanup-grace`). Câu cũ bị xoá.

### Data và orchestration

11. **`order_checkout_submissions`** lưu canonical request/digest, `state`
    (`PREPARING/PAYMENT_READY/RECALCULATION_REQUIRED/FAILED`), `order_id` và problem. Đây
    là nguồn của recovery read. Workflow checkout chỉ nhận `checkout_token`; snapshot có
    PII (địa chỉ, số điện thoại) không vào Temporal history.
12. **`payment_deadline_at`** được ghi khi tạo order bằng business ceiling
    (`created_at + 15 phút`), rồi khoá một lần tại `PAYMENT_READY` bằng minimum trong
    ADR-014 (chỉ có thể giảm) và đánh dấu `payment_deadline_locked_at`. Sau khi khoá nó
    immutable.
13. **Workflow ID của Admin pre-dispatch attempt** là
    `order-admin-refund-{attempt_id}` trên `order-refund-queue`; Buyer return giữ
    `order-refund-{order_id}`. Refund result route theo `order_refund_cases.workflow_id`.
    Nhờ đó một attempt Admin bị từ chối không chặn Workflow ID của Buyer return sau này.
    Reuse policy: checkout/refund/admin attempt `REJECT_DUPLICATE`; scope finalization và
    financial reconciliation `ALLOW_DUPLICATE` vì chỉ replay command đã đóng băng và activity
    DB-idempotent.
14. **Scope close luôn qua durable dispatch.** Transaction financial result/no-payout
    decision ghi intent + checkpoint + dispatch `OrderRefundScopeFinalizationWorkflow`;
    refund saga không gọi Inventory close inline. Như vậy chỉ có một owner thực thi.
15. **Search normalizer** = NFKD, bỏ combining marks, fold `đ/Đ -> d` (NFKD không tách
    `đ`), lowercase `Locale.ROOT`. Fold này là một phần của version pinned của normalizer.

## Hệ quả

Contract Order public/internal và Delivery v1 thay đổi ở trạng thái `proposed`, chưa có
consumer đã adopt nên không cần version mới. Inventory giữ endpoint
`recover-timed-out-payment` nhưng Order Release 1 không gọi (ADR-014); tài liệu Inventory
được đánh dấu tương ứng.

## Xác minh

Unit/integration test của `java/services/order-service` bao phủ bảng cancel reason, line
limit, `variant_name` trong `order.completed.v1`, mapping `ui_primary_status`, submission
state, obligation `RETURN_MANUAL_VERIFICATION` và normalizer có `đ`.
