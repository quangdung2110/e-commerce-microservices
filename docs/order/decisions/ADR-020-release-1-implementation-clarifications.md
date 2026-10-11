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


> **Cập nhật ADR-021 (2026-10-11):** không có Delivery service; "verified Delivery proof" trong ADR này nay là
> Admin delivery confirmation trên Order ([ADR-021](./ADR-021-admin-delivery-confirmation.md)). Quyết định còn lại giữ nguyên.

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
6. ~~**Delivery `source`** là `/services/delivery`~~ — bị thay thế bởi ADR-021: không có Delivery fact.

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

### Bổ sung khi implement (cùng ngày)

16. **`external_settlement`.** `CLOSE_WITH_EXTERNAL_FINANCIAL_REMEDIATION` trên
    `CUSTOMER_PAYOUT_REMEDIATION`/`REFUND_REVERSAL` bắt buộc
    `external_settlement {amount, customer_user_id, settled_at, settlement_reference}`;
    contract cũ không có trường nào để Order kiểm "đúng customer, đúng VND, đủ locked
    amount" và lấy `refunded_at = settled_at` như ADR-019 yêu cầu. Order còn gọi Payment
    `GET /v1/payments/{captured}`; refund provider `SUCCESS` thắng race thì decision bị `409`.
17. **`final_amount ≥ 1 VND`.** Payment chỉ tạo attempt với amount dương, nên submission có
    `final_amount = 0` bị `422 INVALID_CHECKOUT_SNAPSHOT` thay vì tạo order không thể trả.
18. **Unique bridge theo order.** `order_item_lines` unique `(order_id,
    allocation_plan_line_reference)` thay cho `(inventory_allocation_plan_id, line_reference)`:
    Inventory không lưu plan, nên cùng `allocation_plan_id` có thể được Checkout gửi lại với
    token mới sau khi submission trước thất bại.
19. **Request identity của payment attempt** nằm trên `order_operations` (unique theo
    principal/type/key + digest có `order_id`); `order_payment_attempts.operation_id` trỏ tới
    operation đã tạo attempt. Bảng attempt không lặp các cột request.
20. **Admin case.** Lifetime case Admin chỉ được tạo sau receipt `RELEASED`, nên trạng thái
    chờ release được biểu diễn bởi `order_admin_refund_attempts.status=PENDING`; case Admin
    đi `INITIATED -> WAITING_PAYMENT_REFUND_RESULT -> SUCCEEDED|FAILED`.
    `WAITING_INVENTORY_RELEASE` giữ trong enum để tương thích nhưng không được ghi.
21. **Evidence của decision.** `ORDER_CHECKPOINT` tham chiếu `checkpoint/{command_id}` với
    digest request/response đã đóng băng; `refund-scope-query/...` được Order query lại và
    checkpoint dưới decision; `PAYMENT_RECONCILIATION` là settlement record bất biến được
    append. Tham chiếu khác phải đã có trên obligation, nếu không trả `422`.
22. **Resume return thủ công.** `RESUME_FORWARD_REPAIR` trên `RETURN_MANUAL_VERIFICATION`
    yêu cầu full completion evidence, đưa case về `WAITING_INVENTORY_SCOPE_INSPECTION`,
    đóng obligation và gửi `RESUME_REFUND` cho refund workflow; Payment gate cũ được dùng lại.
23. **Consumer Kafka không có DLQ phía Order.** Lỗi hạ tầng không ACK và redeliver với
    backoff mũ (tối đa 60 giây); fact sai schema/identity được lưu `REJECTED` trong inbox rồi
    ACK, nên inbox là nơi quarantine và audit.
24. **Operation mapping.** Cancellation `SUCCEEDED` khi order `CANCELLED`, `REJECTED` khi
    capture thắng race; payment-attempt `REJECTED` nếu deadline đã qua lúc Payment trả kết quả,
    `MANUAL_REVIEW_REQUIRED` nếu outcome không xác định.

## Hệ quả

Contract Order public/internal và Delivery v1 thay đổi ở trạng thái `proposed`, chưa có
consumer đã adopt nên không cần version mới. Inventory giữ endpoint
`recover-timed-out-payment` nhưng Order Release 1 không gọi (ADR-014); tài liệu Inventory
được đánh dấu tương ứng.

## Xác minh

`java/services/order-service` (`DomainRulesTest`, `CheckoutRulesTest`, `CheckoutFlowIT`,
`RefundFlowIT`, `QueryIT`) kiểm toàn bộ các điểm trên; response HTTP được validate với schema
OpenAPI và outbox với JSON Schema của contracts. Unit/integration test của `java/services/order-service` bao phủ bảng cancel reason, line
limit, `variant_name` trong `order.completed.v1`, mapping `ui_primary_status`, submission
state, obligation `RETURN_MANUAL_VERIFICATION` và normalizer có `đ`.
