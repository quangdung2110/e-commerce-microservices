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

### 2.2. Activities
Mỗi Activity đóng vai trò như 1 Local Transaction đối với Order hoặc 1 Remote Call/gRPC sang Service khác:
*   `ReserveInventoryActivity`: Khóa Tồn kho theo kế hoạch cấp phát Smart Routing.
*   `ReservePromotionActivity`: Khóa ngân sách voucher trên Redis (Lua Script 2). Đây là điểm Reserve duy nhất — không xảy ra tại Preview để tránh lạm dụng (bot treo giỏ hàng làm cạn budget).
*   `CreateOrderActivity`: INSERT Master Order và Order Parcels xuống Database (Trạng thái thiết lập là `PENDING_PAYMENT`).
*   `InitiatePaymentActivity`: Tạo phiên giao dịch thanh toán và trả về payment URL.
*   `DeductInventoryActivity`: (Post-Payment) Trừ hẳn tồn kho đã bị giữ chỗ.
*   `ConfirmPromotionActivity`: (Post-Payment) Xác nhận sử dụng voucher (đổi trạng thái từ RESERVED sang REDEEMED).
*   `CreateShippingLabelsActivity`: (Post-Payment) Gửi thông tin sang 3PL để tạo mã vận đơn.
*   `UpdateOrderStatusActivity`: Chuyển trạng thái Order (FSM Update an toàn với Optimistic Locking).

### 2.3. Cặp Hàm Bù Trừ (Compensations)
*   `RollbackInventoryActivity`: Giải phóng số lượng giữ chỗ ở các kho.
*   `RollbackPromotionActivity`: Nhả ngân sách voucher đã được khóa bởi `ReservePromotionActivity` trong Saga (không liên quan đến Preview).
*   `CancelPaymentActivity`: Hủy phiên giao dịch thanh toán nếu đơn hàng timeout.

---

## 3. Luồng Thực Thi (Workflow Execution)

Dưới đây là mô phỏng luồng Activity trong đoạn code của `OrderCheckoutWorkflow`:

1.  **Chạy `ReserveInventoryActivity`**: Nếu lỗi (Kho đột ngột hết hàng) → Workflow văng lỗi ngay. Không có compensation cần thiết vì Promotion chưa được reserve ở bước này.
    *   *Thành công → Đăng ký `RollbackInventoryActivity` vào mảng Đền bù (Stack - LIFO).*
2.  **Chạy `ReservePromotionActivity`**: Gọi Promotion Service khóa ngân sách voucher. Nếu lỗi (voucher hết budget) → Chạy `RollbackInventoryActivity`. Workflow dừng và thông báo user: *"Voucher vừa hết lượt sử dụng, vui lòng chọn voucher khác."*
    *   *Thành công → Đăng ký `RollbackPromotionActivity` vào mảng Đền bù.*
3.  **Chạy `CreateOrderActivity`**: Lưu vào DB. Trạng thái `PENDING_PAYMENT`.
    *   Nếu lỗi Database nội bộ → Chạy `RollbackInventoryActivity` → Chạy `RollbackPromotionActivity`.
4.  **Chạy `InitiatePaymentActivity`**: Gọi Payment Service lấy URL thanh toán.
    *   Nếu lỗi → Chạy Đền bù `RollbackInventoryActivity` → `RollbackPromotionActivity`.
    *   *Thành công → Đăng ký `CancelPaymentActivity` vào mảng Đền bù.*
4.  **Tạm Ngủ (Sleep/Suspend) Chờ Thanh Toán**:
    *   Temporal không block Thread/CPU. Nó mở kênh `Selector` để nghe **Signal** `Payment_Success` từ Webhook Cổng Thanh Toán ping qua tên WorkflowID/OrderID.
    *   Cài đặt **Timer Timeout** chuẩn: 15 phút.
5.  **Biến Cố Kết Thúc**:
    *   *Nếu hết 15p mốc Timer nổ:* Temporal kích hoạt lại đoạn code, cập nhật Order DB -> `CANCELLED`. Chạy Stack Compensations (Hủy Payment, Nhả kho, Nhả voucher). Đóng Workflow là Failed/Cancelled.
    *   *Nếu nghe Signal `Payment_Success` trước 15 phút:* Bỏ qua Timer. Chạy các Post-Payment Activities: `DeductInventoryActivity`, `ConfirmPromotionActivity`, `CreateShippingLabelsActivity`. Cuối cùng chạy `UpdateOrderStatusActivity` để cập nhật Order thành `PROCESSING`. Workflow hoàn tất, bỏ qua Stack Compensations.

---

## 4. Ưu Điểm Thiết Kế
*   **Visibility (Minh Bạch):** Có thể mở xem chi tiết hàng nghìn workflow đang bị phong tỏa ở bước "Chờ Thanh Toán - Sleeping Timer" ngay trên giao diện Web UI của Temporal.
*   **Auto-Resume:** Server Order Service crash hoặc cập nhật Release Code giữa chừng không khiến đơn hàng đang thanh toán bị kẹt. Temporal sẽ ghim Timer và đền bù bình thường khi pod start lại.
*   **Tách rời FSM và Saga:** Temporal nhận việc điều phối luồng (retries, compensation fallback), còn FSM DB Layer bảo vệ chặn các request đổi status sai hoặc lóng ngóng (như khách gởi 2 signal liên tục). Hai tầng bổ trợ cực hiệu quả.
