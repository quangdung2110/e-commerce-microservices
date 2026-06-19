# Giao dịch phân tán (Distributed Saga) với Temporal

**Liên kết nhanh (Related Documents):**
*   [Database Schema](order_db_design.md)
*   [State Machine & Lifecycle](order_state_machine.md)
*   [CQRS & Elasticsearch](cqrs_elasticsearch_design.md)
*   [Cold Data Archiving](cold_data_archiving_design.md)
*   [Workflows (API & Data Flow)](order_workflow.md)
*   [Background Workers & Jobs](order_workers.md)
*   [Events Structure & Kafka Topics](order_events.md)

---

Tài liệu này mô tả thiết kế Orchestration Saga Pattern sử dụng Temporal để xử lý ba lane nghiệp vụ cốt lõi của Order Service: checkout saga (tạo đơn + thanh toán), refund saga (return/admin force refund), và late-payment recovery saga (khôi phục đơn khi payment đến muộn sau timeout). Việc kết hợp Temporal thay thế cho Kafka Message thuần túy giúp giảm tải boilerplate code khi quản lý trạng thái luồng, xử lý timeout và nghịch đảo thao tác bù trừ (compensation).

---

## 1. Vấn Đề với Kafka Saga Truyền Thống
Trong môi trường E-Commerce, 1 Order tác động đến nhiều Service (Cart, Promotion, Inventory, Order, Payment). Nếu dùng Choreography/Orchestration chay bằng Kafka:
*   Bị "Mù thông tin" (Khó theo dõi tình trạng của từng Saga).
*   Cần chủ động viết Job/Code xử lý Timeout vô cùng cực khổ (Ví dụ: Order chưa cập nhật Payment sau 15p thì trigger luồng Rollback gửi msg cho Inventory).
*   Rất sợ sập mạng hoặc crash server giữa chừng, gây lơ lửng State.

**Giải Pháp:** Temporal.io biến các bước phức tạp này thành "Code Tuần tự" hoàn toàn có thể phục hồi (Resilient Execution).

---

## 2. Các Thành Phần của Temporal Workflows

### 2.1. Workflow: `OrderCheckoutWorkflow`
Hàm điều phối (Orchestrator). Hàm này được thiết kế là *Deterministic*. Nghĩa là nếu Service sập và khởi động lại, FSM của Temporal sẽ resume chính xác tại dòng code bị chết dở dang bằng cách tái thiết event history lưu trong DB của máy chủ Temporal.

**Workflow Identity Contract:**
*   WorkflowID chuẩn: `order-checkout-{checkout_token}`.
*   `order_id` được sinh ở bước đầu tiên `CreateOrderActivity`, không dùng để đặt WorkflowID lúc start.
*   Consumer nhận event theo `order_id` phải lookup `checkout_token` trước khi signal workflow.

### 2.2. Workflow: `OrderRefundWorkflow`
Workflow chuyên trách lane refund. Mọi request hoàn tiền (return sau giao hàng hoặc admin force refund khi đơn đã thu tiền) đều phải đi qua workflow này.

**Workflow Identity Contract:**
*   WorkflowID chuẩn: `order-refund-{order_id}`.
*   Chỉ cho phép 1 refund workflow active theo mỗi `order_id`.
*   Payment consumer route `REFUND_SUCCESS|REFUND_FAILED|REFUND_REVERSED` theo `payload.order_id` và signal `RefundResult` cho workflow này.
*   Theo policy B, workflow này chỉ được phép tạo **một** `refund_transaction_id` cho mỗi `order_id`; `refund_type` có thể `FULL` hoặc `PARTIAL` theo refund scope đã lock từ request đầu vào.
*   Guard này chỉ chống concurrent workflow instance, **không thay thế** policy one-shot của Buyer API.
*   Policy one-shot cho `POST /v1/orders/{orderId}/return` được enforce trước khi start workflow bằng `order_customer_return_guard` (first-write-wins theo `order_id`).

### 2.3. Workflow: `OrderLatePaymentRecoveryWorkflow`
Workflow chuyên trách khôi phục đơn khi checkout workflow đã timeout/cancel nhưng Payment Service xác nhận `PAYMENT_SUCCESS` đến muộn.

**Workflow Identity Contract:**
*   WorkflowID chuẩn: `order-late-payment-recovery-{order_id}`.
*   Chỉ cho phép 1 recovery workflow active theo mỗi `order_id`.
*   Chỉ được start khi guard hợp lệ: order đang `CANCELLED` với `reason = PAYMENT_TIMEOUT` và nhận payment success hợp lệ từ Payment stream.
*   Recovery workflow là owner của quyết định cuối: khôi phục về lane đã thu tiền và chạy post-payment, hoặc chuyển auto-refund nếu không thể phục hồi vận hành.

### 2.4. Activities
Mỗi Activity đóng vai trò như 1 Local Transaction đối với Order hoặc 1 Remote Call/gRPC sang Service khác:
*   `CreateOrderActivity`: INSERT Master Order và Order Items xuống Database (Trạng thái thiết lập là `PENDING_PAYMENT`).
*   `ReserveInventoryActivity`: Khóa Tồn kho theo kế hoạch cấp phát Smart Routing.
*   `ReservePromotionActivity`: Khóa ngân sách voucher trên Redis (Lua Script 2B all-or-nothing cho multi-voucher). Đây là điểm Reserve duy nhất — không xảy ra tại Preview để tránh lạm dụng (bot treo giỏ hàng làm cạn budget).
*   `InitiatePaymentActivity`: Tạo phiên giao dịch thanh toán và trả về payment URL.
*   `DeductInventoryActivity`: (Post-Payment) Trừ hẳn tồn kho đã bị giữ chỗ.
*   `ConfirmPromotionActivity`: (Post-Payment) Commit reservation voucher trên Redis và ghi ledger `coupon_usages.status = REDEEMED` (bắt buộc truyền `idempotency_key` theo từng `promotion_id`).
*   `CancelConfirmedPromotionActivity`: (Post-Payment compensation) Undo trạng thái đã `REDEEMED` theo từng `promotion_id` khi post-payment saga fail.
*   `UpdateOrderStatusActivity`: Chuyển trạng thái Order (FSM Update an toàn với Optimistic Locking).
*   `MarkRefundRequestedActivity`: Verify `trigger_source` + policy guard, commit transition vào lane refund (`PAID/PROCESSING/COMPLETED` -> `RETURN_REQUESTED`) và insert outbox `ORDER_STATUS_CHANGED` với metadata `refund_waiting_inventory_confirmation = true`.
*   `LockRefundScopeActivity`: Chốt refund scope từ request đầu vào (`FULL` hoặc `PARTIAL + items_to_return[]`) để tính `refund_type`/`refund_amount` một lần duy nhất cho mỗi order.
*   `RegisterRefundReturnExpectationActivity`: Đăng ký expected return scope với Inventory theo `order_id + refund_request_id`.
*   `WaitInventoryReturnReceivedAllSignal`: Chờ signal từ Inventory khi đã nhận đủ toàn bộ item trong refund scope (timeout 30 ngày, quá hạn chuyển lane manual verification).
*   `InitiateRefundActivity`: Gọi Payment Service `POST /v1/payments/{txn_id}/refund` với idempotency key ổn định.
*   `FinalizeRefundResultActivity`: Commit `REFUND_SUCCESS|REFUND_FAILED|REFUND_REVERSED` thành FSM/payment_status + outbox (`ORDER_REFUNDED` hoặc `ORDER_REFUND_FAILED`).
*   `RecoverPaidOrderFromTimeoutActivity`: Áp lane khôi phục payment muộn, đưa order từ `CANCELLED(PAYMENT_TIMEOUT)` về lane đã thu tiền trước khi chạy post-payment.

### 2.5. Cặp Hàm Bù Trừ (Compensations)
*   `RollbackInventoryActivity`: Giải phóng số lượng giữ chỗ ở các kho.
*   `RollbackPromotionActivity`: Nhả ngân sách voucher đã được khóa bởi `ReservePromotionActivity` trong Saga (không liên quan đến Preview, bắt buộc truyền `idempotency_key` theo từng `promotion_id`). Activity này chỉ release runtime reservation, không đảo trạng thái ví DB `USED -> SAVED` ở lane pre-payment cancel.
*   `CancelConfirmedPromotionActivity`: Hoàn ngược commit promotion ở lane post-payment (`REDEEMED` -> refund/cancel allocation) bằng API sync riêng, không dùng chung semantics với `RollbackPromotionActivity`.
*   `CancelOrderActivity`: Đánh dấu order `CANCELLED` nếu pre-payment saga thất bại giữa chừng.
*   `CancelPaymentActivity`: Hủy phiên giao dịch thanh toán nếu đơn hàng timeout.
*   `RollbackDeductedInventoryActivity`: Bù trừ phần stock đã deduct ở post-payment khi step sau lỗi.
*   `RevertOrderStatusToPaidActivity`: Trả trạng thái từ `PROCESSING` về `PAID` nếu post-payment rollback.

### 2.6. Contract `idempotency_key` với Promotion (Bắt buộc)
*   Order Service phải sinh key ở phía Order và giữ ổn định xuyên retry của cùng activity.
*   Key phải là duy nhất theo từng `promotion_id` cho mỗi action.
*   Format chuẩn hóa:
    *   `REDEEM_FINAL:{order_id}:{promotion_id}`
    *   `CANCEL_FINAL:{order_id}:{promotion_id}`
    *   `REFUND_FULL:{order_id}:{promotion_id}:{refund_request_id}`
    *   `REFUND_PARTIAL:{order_id}:{promotion_id}:{refund_request_id}`
*   Promotion Service dùng trực tiếp key này để ghi `promotion_event_idempotency.event_id`, không được tự sinh lại.
*   Với fallback async qua Kafka, Promotion dùng `event_id` từ envelope làm `event_id` trong DB idempotency.

---

## 3. Luồng Thực Thi (Workflow Execution)

Dưới đây là mô phỏng luồng Activity trong đoạn code của `OrderCheckoutWorkflow`:

1.  **Chạy `CreateOrderActivity`**: Lưu vào DB. Trạng thái `PENDING_PAYMENT` để sinh `order_id` chuẩn dùng xuyên suốt Saga.
	*   Bắt buộc persist snapshot phục vụ CQRS search (`order_items.store_name_snapshot`, `order_items.snapshot_name`, `order_items.snapshot_image_url`) ngay ở bước này để reindex từ DB không mất ngữ cảnh hiển thị.
    *   *Thành công → Đăng ký `CancelOrderActivity` vào mảng Đền bù (Stack - LIFO).*
    *   Nếu lỗi Database nội bộ → Workflow dừng trước khi gọi dịch vụ ngoài.
2.  **Chạy `ReserveInventoryActivity`**: Nếu lỗi (Kho đột ngột hết hàng) → Chạy `CancelOrderActivity`.
    *   *Thành công → Đăng ký `RollbackInventoryActivity` vào mảng Đền bù.*
3.  **Chạy `ReservePromotionActivity`**: Gọi Promotion Service khóa ngân sách voucher. Nếu lỗi (ví dụ 1 mã fail budget trong batch all-or-nothing) → Chạy rollback theo LIFO: `RollbackInventoryActivity` rồi `CancelOrderActivity`. Workflow dừng với lỗi nghiệp vụ có cấu trúc (`failed_promotion_id`, `failed_reason`, `reserve_mode`) để Checkout trả `409 PROMOTION_RECALCULATION_NEEDED`. Re-evaluation discount và dựng `dropped_promotions[]` là trách nhiệm của Checkout (qua Promotion `/calculate-discount`), không nằm trong Temporal workflow của Order.
    *   *Thành công → Đăng ký `RollbackPromotionActivity` vào mảng Đền bù.*
4.  **Chạy `InitiatePaymentActivity`**: Gọi Payment Service lấy URL thanh toán.
    *   Nếu lỗi → Chạy rollback theo LIFO: `RollbackPromotion` → `RollbackInventory` → `CancelOrder`.
    *   *Thành công → Đăng ký `CancelPaymentActivity` vào mảng Đền bù.*
5.  **Tạm Ngủ (Sleep/Suspend) Chờ Thanh Toán**:
    *   Temporal không block Thread/CPU. Nó mở kênh `Selector` để nghe **Signal** payment từ Payment Consumer.
    *   Cài đặt **Timer Timeout** chuẩn: 15 phút.
    *   Chỉ chấp nhận signal khi workflow state = `WAITING_PAYMENT`.
    *   Signal `PAYMENT_SUCCESS` đến sau timeout/complete không apply vào checkout workflow đã đóng; worker phải route sang `OrderLatePaymentRecoveryWorkflow`.
6.  **Biến Cố Kết Thúc**:
    *   *Nếu hết 15p mốc Timer nổ:* Temporal chạy rollback theo LIFO pre-payment: `CancelPayment` → `RollbackPromotion` → `RollbackInventory` → `CancelOrder`. Đóng Workflow là Cancelled.
    *   *Nếu nghe Signal `PAYMENT_SUCCESS` trước 15 phút:* Bỏ qua Timer và xử lý theo 2 mốc:
        1) **Mốc tài chính:** `UpdateOrderStatusActivity` commit `PENDING_PAYMENT` -> `PAID` và insert outbox `ORDER_PAID` trong cùng transaction.
2) **Mốc vận hành (post-payment):** reset compensation stack cho post-payment và chạy tuần tự:
           - `DeductInventoryActivity` (register `RollbackDeductedInventoryActivity`)
            - `ConfirmPromotionActivity` (register `CancelConfirmedPromotionActivity`, gọi với `idempotency_key` theo từng `promotion_id`)
            - `UpdateOrderStatusActivity` (`PAID` -> `PROCESSING`, register `RevertOrderStatusToPaidActivity`)
        *   Nếu lỗi ở bất kỳ bước post-payment nào, rollback theo LIFO post-payment: `RevertOrderStatusToPaid` → `CancelConfirmedPromotion` → `RollbackDeductedInventory`.
    *   Nếu thành công toàn bộ post-payment, workflow complete; mốc vận hành được phản ánh bởi `ORDER_STATUS_CHANGED` tại transition `PAID` -> `PROCESSING`.

## 3.1. Luồng `OrderLatePaymentRecoveryWorkflow`

1. Trigger khi Payment consumer nhận `PAYMENT_SUCCESS` nhưng checkout workflow đã timeout/close và order đang `CANCELLED(PAYMENT_TIMEOUT)`.
2. `RecoverPaidOrderFromTimeoutActivity` verify guard + payment evidence, sau đó khôi phục order về lane đã thu tiền.
3. Workflow phát lại mốc tài chính theo contract recovery (`ORDER_PAID` với recovery metadata).
4. Trước khi vào deduct, workflow bắt buộc đảm bảo reservation còn hiệu lực:
    - gọi `ReserveInventoryActivity` lại theo route plan hợp lệ, hoặc
    - `RecoverPaidOrderFromTimeoutActivity` phải tái lập reservation tương đương và trả evidence cho workflow.
5. Sau khi reservation hợp lệ, workflow chạy post-payment saga (`DeductInventory` -> `ConfirmPromotion` -> `UpdateOrderStatus`).
6. Nếu post-payment recovery thất bại ở bước bắt buộc, workflow chuyển sang auto-refund lane để đưa hệ thống về trạng thái tài chính an toàn.

## 4. Luồng Refund bằng `OrderRefundWorkflow`

1. Workflow được start từ một trong hai trigger: `POST /orders/{id}/return` hoặc admin force refund lane từ API cancel khi đơn đang `PAID/PROCESSING`.
2. Với trigger `API_RETURN`, API layer phải pass one-shot guard (`order_customer_return_guard`) và precondition toàn bộ item đã `DELIVERED` trước khi start workflow. Trigger `ADMIN_FORCE_REFUND_REQUESTED` không dùng one-shot guard này.
3. `MarkRefundRequestedActivity` verify lại policy theo `trigger_source`, commit `RETURN_REQUESTED`, ghi `order_refund_cases.case_status = WAITING_INVENTORY_RETURN_CONFIRMATION`, insert `ORDER_STATUS_CHANGED` với reason phù hợp (`RETURN_API_ACCEPTED` hoặc `ADMIN_FORCE_REFUND_REQUESTED`).
4. `LockRefundScopeActivity` chốt tập item refund từ request (`FULL` hoặc `PARTIAL`) để tính `refund_type` (`FULL|PARTIAL`) và `refund_amount`.
5. `RegisterRefundReturnExpectationActivity` đăng ký expected return scope với Inventory, sau đó workflow chờ `WaitInventoryReturnReceivedAllSignal`.
6. `WaitInventoryReturnReceivedAllSignal` timeout sau 30 ngày -> phát cảnh báo ops + chuyển trạng thái `MANUAL_VERIFICATION_REQUIRED`; chỉ tiếp tục khi có admin override signal.
7. Chỉ khi nhận signal Inventory xác nhận đã nhận đủ toàn bộ item trong refund scope (hoặc admin override hợp lệ), workflow mới chạy `InitiateRefundActivity` và gọi Payment API refund đúng một lần cho mỗi `order_id`.
8. Payment consumer nhận `REFUND_SUCCESS|REFUND_FAILED|REFUND_REVERSED` rồi signal `RefundResult` cho `order-refund-{order_id}`.
9. `FinalizeRefundResultActivity` commit trạng thái cuối:
    - `REFUND_SUCCESS` -> `RETURN_REQUESTED` -> `REFUNDED` + outbox `ORDER_REFUNDED`.
    - `REFUND_FAILED`/`REFUND_REVERSED` -> giữ `RETURN_REQUESTED`, cập nhật `payment_status`, + outbox `ORDER_REFUND_FAILED`.
10. Với lane bắt đầu từ `API_RETURN`, one-shot guard không được reset sau `REFUND_FAILED`/`REFUND_REVERSED`; mọi submit Buyer API tiếp theo bị từ chối.
11. Voucher restore ở downstream Promotion là best-effort non-blocking và validity-aware: chỉ restore `USED -> SAVED` khi voucher còn hạn (`expired_at > NOW()`), quá hạn thì chuyển `EXPIRED`/giữ `USED`; nếu voucher unavailable thì workflow tiền vẫn không bị rollback.
12. API layer không được commit terminal state ở lane refund; workflow là source-of-truth duy nhất.

---

## 5. Ưu Điểm Thiết Kế
*   **Visibility (Minh Bạch):** Có thể mở xem chi tiết hàng nghìn workflow đang bị phong tỏa ở bước "Chờ Thanh Toán - Sleeping Timer" ngay trên giao diện Web UI của Temporal.
*   **Auto-Resume:** Server Order Service crash hoặc cập nhật Release Code giữa chừng không khiến đơn hàng đang thanh toán bị kẹt. Temporal sẽ ghim Timer và đền bù bình thường khi pod start lại.
*   **Tách rời FSM và Saga:** Temporal nhận việc điều phối luồng (retries, compensation fallback), còn FSM DB Layer bảo vệ chặn các request đổi status sai hoặc lóng ngóng (như khách gởi 2 signal liên tục). Hai tầng bổ trợ cực hiệu quả.
