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

Tài liệu này mô tả thiết kế Orchestration Saga Pattern sử dụng Temporal để xử lý luồng tạo Đơn Hàng (Order Creation Saga) bền bỉ, dễ bù trừ rủi ro và an toàn tuyệt đối. Việc kết hợp Temporal thay thế cho Kafka Message thuần túy giúp giảm tải boilerplate code khi quản lý trạng thái luồng, xử lý timeout và nghịch đảo thao tác bù trừ (compensation).

---

## 1. Vấn Đề với Kafka Saga Truyền Thống
Trong môi trường E-Commerce, 1 Order tác động đến nhiều Service (Cart, Promotion, Inventory, Order, Payment). Nếu dùng Choreography/Orchestration chay bằng Kafka:
*   Bị "Mù thông tin" (Khó theo dõi tình trạng của từng Saga).
*   Cần chủ động viết Job/Code xử lý Timeout vô cùng cực khổ (Ví dụ: Order chưa cập nhật Payment sau 15p thì trigger luồng Rollback gửi msg cho Inventory).
*   Rất sợ sập mạng hoặc crash server giữa chừng, gây lơ lửng State.

**Giải Pháp:** Temporal.io biến các bước phức tạp này thành "Code Tuần tự" hoàn toàn có thể phục hồi (Resilient Execution).

---

## 2. Các Thành Phần của Temporal Checkout Saga

### 2.1. Workflow: `OrderCheckoutWorkflow`
Hàm điều phối (Orchestrator). Hàm này được thiết kế là *Deterministic*. Nghĩa là nếu Service sập và khởi động lại, FSM của Temporal sẽ resume chính xác tại dòng code bị chết dở dang bằng cách tái thiết event history lưu trong DB của máy chủ Temporal.

**Workflow Identity Contract:**
*   WorkflowID chuẩn: `order-checkout-{checkout_token}`.
*   `order_id` được sinh ở bước đầu tiên `CreateOrderActivity`, không dùng để đặt WorkflowID lúc start.
*   Consumer nhận event theo `order_id` phải lookup `checkout_token` trước khi signal workflow.

### 2.2. Activities
Mỗi Activity đóng vai trò như 1 Local Transaction đối với Order hoặc 1 Remote Call/gRPC sang Service khác:
*   `CreateOrderActivity`: INSERT Master Order và Order Parcels xuống Database (Trạng thái thiết lập là `PENDING_PAYMENT`).
*   `ReserveInventoryActivity`: Khóa Tồn kho theo kế hoạch cấp phát Smart Routing.
*   `ReservePromotionActivity`: Khóa ngân sách voucher trên Redis (Lua Script 2B all-or-nothing cho multi-voucher). Đây là điểm Reserve duy nhất — không xảy ra tại Preview để tránh lạm dụng (bot treo giỏ hàng làm cạn budget).
*   `InitiatePaymentActivity`: Tạo phiên giao dịch thanh toán và trả về payment URL.
*   `DeductInventoryActivity`: (Post-Payment) Trừ hẳn tồn kho đã bị giữ chỗ.
*   `ConfirmPromotionActivity`: (Post-Payment) Commit reservation voucher trên Redis và ghi ledger `coupon_usages.status = REDEEMED` (bắt buộc truyền `idempotency_key` theo từng `promotion_id`).
*   `CreateShippingLabelsActivity`: (Post-Payment) Gửi thông tin sang 3PL để tạo mã vận đơn.
*   `UpdateOrderStatusActivity`: Chuyển trạng thái Order (FSM Update an toàn với Optimistic Locking).

### 2.3. Cặp Hàm Bù Trừ (Compensations)
*   `RollbackInventoryActivity`: Giải phóng số lượng giữ chỗ ở các kho.
*   `RollbackPromotionActivity`: Nhả ngân sách voucher đã được khóa bởi `ReservePromotionActivity` trong Saga (không liên quan đến Preview, bắt buộc truyền `idempotency_key` theo từng `promotion_id`).
*   `CancelOrderActivity`: Đánh dấu order `CANCELLED` nếu pre-payment saga thất bại giữa chừng.
*   `CancelPaymentActivity`: Hủy phiên giao dịch thanh toán nếu đơn hàng timeout.
*   `RollbackDeductedInventoryActivity`: Bù trừ phần stock đã deduct ở post-payment khi step sau lỗi.
*   `RevertOrderStatusToPaidActivity`: Trả trạng thái từ `PROCESSING` về `PAID` nếu post-payment rollback.
*   `CancelShippingActivity`: Hủy vận đơn đã tạo khi post-payment rollback.

### 2.4. Contract `idempotency_key` với Promotion (Bắt buộc)
*   Order Service phải sinh key ở phía Order và giữ ổn định xuyên retry của cùng activity.
*   Key phải là duy nhất theo từng `promotion_id` cho mỗi action (`REDEEM_FINAL`, `CANCEL_FINAL`, `REFUND_PARTIAL/FULL`).
*   Promotion Service dùng trực tiếp key này để ghi `promotion_event_idempotency.event_id`, không được tự sinh lại.
*   Với fallback async qua Kafka, Promotion dùng `event_id` từ envelope làm `event_id` trong DB idempotency.

---

## 3. Luồng Thực Thi (Workflow Execution)

Dưới đây là mô phỏng luồng Activity trong đoạn code của `OrderCheckoutWorkflow`:

1.  **Chạy `CreateOrderActivity`**: Lưu vào DB. Trạng thái `PENDING_PAYMENT` để sinh `order_id` chuẩn dùng xuyên suốt Saga.
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
    *   Chỉ chấp nhận signal khi workflow state = `WAITING_PAYMENT`; signal đến sau khi timeout/complete sẽ bị reject.
6.  **Biến Cố Kết Thúc**:
    *   *Nếu hết 15p mốc Timer nổ:* Temporal chạy rollback theo LIFO pre-payment: `CancelPayment` → `RollbackPromotion` → `RollbackInventory` → `CancelOrder`. Đóng Workflow là Cancelled.
    *   *Nếu nghe Signal `PAYMENT_SUCCESS` trước 15 phút:* Bỏ qua Timer, reset compensation stack cho post-payment và chạy tuần tự:
        1) `DeductInventoryActivity` (register `RollbackDeductedInventoryActivity`)
        2) `ConfirmPromotionActivity` (register `RollbackPromotionActivity`, gọi với `idempotency_key` theo từng `promotion_id`)
        3) `UpdateOrderStatusActivity` (`PAID` -> `PROCESSING`, register `RevertOrderStatusToPaidActivity`)
        4) `CreateShippingLabelsActivity` (register `CancelShippingActivity`)
    *   Nếu lỗi ở bất kỳ bước post-payment nào, rollback theo LIFO post-payment: `CancelShipping` → `RevertOrderStatusToPaid` → `RollbackPromotion` → `RollbackDeductedInventory`.
    *   Nếu thành công toàn bộ post-payment, workflow complete và phát `ORDER_PAID`.

---

## 4. Ưu Điểm Thiết Kế
*   **Visibility (Minh Bạch):** Có thể mở xem chi tiết hàng nghìn workflow đang bị phong tỏa ở bước "Chờ Thanh Toán - Sleeping Timer" ngay trên giao diện Web UI của Temporal.
*   **Auto-Resume:** Server Order Service crash hoặc cập nhật Release Code giữa chừng không khiến đơn hàng đang thanh toán bị kẹt. Temporal sẽ ghim Timer và đền bù bình thường khi pod start lại.
*   **Tách rời FSM và Saga:** Temporal nhận việc điều phối luồng (retries, compensation fallback), còn FSM DB Layer bảo vệ chặn các request đổi status sai hoặc lóng ngóng (như khách gởi 2 signal liên tục). Hai tầng bổ trợ cực hiệu quả.
