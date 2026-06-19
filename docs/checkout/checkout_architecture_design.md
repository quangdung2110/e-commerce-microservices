# Kiến trúc Tổng quan Checkout Service (BFF / Aggregator)

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](checkout_workflow.md)
*   [Caching Schema (Redis Session)](checkout_caching_schema.md)
*   [Idempotency & Anti-Tampering](checkout_idempotency.md)
*   [Security Analysis (Anti-Tampering & Session)](checkout_security_analysis.md)

---

Tài liệu này mô tả kiến trúc và vai trò của **Checkout Service**. Trong hệ thống E-Commerce quy mô lớn, tính năng "Thanh toán/Chốt đơn" được tách biệt hoàn toàn khỏi "Quản lý Đơn hàng" (Order Service) để tối ưu hóa hiệu năng, bảo vệ Sổ cái dữ liệu và xử lý hàng triệu lượt xem trước giỏ hàng (Checkout Preview) trong các kỳ Flash Sale.

---

## 1. Vai trò của Checkout Service (Backend for Frontend - BFF)

Checkout Service hoạt động như một **Aggregator** (Người gom dữ liệu) và **Orchestrator cấp cao** cho phía Frontend (Web/App). Nó KHÔNG SỞ HỮU database chứa dữ liệu bền vững nào (Stateless).

### 1.1. Vấn đề giải quyết
*   Khách hàng có thói quen thao tác liên tục trên màn hình Checkout: Đổi địa chỉ, thử áp dụng 5-7 mã giảm giá khác nhau, thay đổi phương thức thanh toán để so sánh số tiền phải trả.
*   Thao tác tăng/giảm số lượng sản phẩm thuộc phạm vi Cart Service; Checkout chỉ nhận snapshot SKU + quantity đã chuẩn hoá từ Cart để tính báo giá.
*   Nếu những tương tác "nháp" này gõ trực tiếp vào Order Service, Database của Order (PostgreSQL) sẽ bị vắt kiệt IOPS dẫn đến Timeout khi có khách hàng khác đang thực sự tạo đơn.
*   Frontend (Mobile App) nếu không có Checkout BFF sẽ phải gọi nhiều API riêng lẻ (Cart, Promo, Inventory, User) và tự xử lý dependency warehouse selection theo từng item -> Gây lỗi bảo mật (client bị hack đổi giá), logic App nặng nề, tốn pin và băng thông.

### 1.2. Trách nhiệm Cốt lõi
*   **Data Aggregation (Gom dữ liệu):** Nhận 1 request duy nhất từ Frontend, song song (`Goroutines` hoặc `CompletableFuture`) gọi sang Cart, Promotion, Inventory để lấy thông số và ráp lại thành bảng báo giá (Quotation).
*   **Idempotency & Rate Limiting (Chống spam/Trùng lặp):** Validation ban đầu (Check token, check JWT, chống spam click).
*   **Tính toán tập trung (Centralized Calculation):** Đảm bảo mọi phép trừ phần trăm giảm giá, tính tổng tiền đều được tính bởi Backend, ngăn chặn client truyền số sai lệch (Tampering).
*   **Boundary ghi dữ liệu rõ ràng (Explicit Write Boundary):** Checkout chỉ orchestration tính toán và commit đơn. Việc ghi mới vào ví voucher không được làm ngầm ở Submit; chỉ các luồng explicit như Save & Apply voucher hoặc worker campaign của Promotion mới được tạo thêm bản ghi ví.
*   **Conflict Recovery có chủ đích:** Khi reserve voucher fail trong Saga (all-or-nothing), Checkout gọi lại Promotion evaluate để trả `409 PROMOTION_RECALCULATION_NEEDED` với `dropped_promotions[]` và đề xuất tổng tiền mới; không dùng phép trừ cục bộ. Client bắt buộc gọi lại Preview để lấy token mới trước khi submit tiếp.
*   **Cô lập dội tải (Traffic Isolation):** Chặn đứng 99% lượng "Traffic xem thử" trước khi nó chạm vào CSDL của Order Service.

---

## 2. Kiến trúc Data & Caching (Stateless Design)

Vì tính chất Stateless, Checkout Service không dùng Database quan hệ:

### 2.1. In-memory Cache / Redis Memory
*   Khi chạy API `Preview`, Service ráp thành công cục JSON báo giá (gồm tổng tiền, danh sách voucher hợp lệ).
*   Nó sẽ sinh 1 `CheckoutToken` và lưu toàn bộ snapshot session vào **Redis** với TTL 20 phút.
*   **Redis Key chuẩn:** `checkout:session:{user_id}` (Hash). `token` là một field trong Hash (không nằm ở key path). Thể hiện một "Bản nháp báo giá được Server công nhận".

### 2.2. Ý nghĩa Bảo mật của `CheckoutToken`
1. Khách hàng thay đổi bất kỳ thứ gì (đổi mã, đổi địa chỉ), Checkout Service sinh `CheckoutToken` mới.
2. Khi khách bấm "Đặt Hàng" (Submit), họ BẮT BUỘC TRUYỀN `CheckoutToken` này lên.
3. Checkout Service đối chiếu `CheckoutToken` -> Tránh tình trạng khách đổi giá cước ở màn 1 nhưng bấm Submit với số tiền sai, cũng như loại bỏ việc phải tính lại toàn bộ phép cộng trừ phức tạp khi đập xuống Order Service.

---

## 3. Ranh giới Trách nhiệm (Checkout Service vs Order Service)

| Tiêu chí | Checkout Service | Order Service |
| :--- | :--- | :--- |
| **Bản chất** | BFF / API Aggregator / Stateless | Core Domain / RDBMS / Event-Driven / Stateful |
| **Nhiệm vụ chính** | Hiển thị bảng tính tiền, bắt lỗi logic trước khi chốt, tạo Token bảo mật | Quản lý vòng đời đơn (State Machine), Lưu Database, Xử lý giao dịch phân tán (Temporal Saga) |
| **Database** | Redis (Lưu Session Checkout) | PostgreSQL + CQRS Elasticsearch |
| **Tần suất gọi** | Rất cao (Thử mã, đổi kho) | Thấp hơn (Chỉ khi User xác nhận MUA HÀNG) |

Checkout Service là "Lá chắn giao tiếp" hoàn hảo, giúp hệ sinh thái Microservices đằng sau nó chuyên tâm giải quyết các tác vụ Ghi/Đọc lõi thay vì bận bịu phục vụ các thao tác nháp của User.
