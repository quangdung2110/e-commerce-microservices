# Checkout Service - Security Analysis & Anti-Tampering

**Liên kết nhanh (Related Documents):**
*   [Architecture Design](checkout_architecture_design.md)
*   [Workflows (API & Data Flow)](checkout_workflow.md)
*   [Caching Schema (Redis Session)](checkout_caching_schema.md)
*   [Idempotency & Anti-Tampering](checkout_idempotency.md)

---

Tài liệu này tổng hợp các rủi ro bảo mật và giải pháp tương ứng được áp dụng tại Checkout Service, đảm bảo nền tảng an toàn trước các cuộc tấn công phổ biến như: Giả mạo giá trị (Data Tampering), Spam API tạo nhiều phiên (API Abuse/Concurrency), và Tái sử dụng phiên thanh toán đã cũ (Replay Attacks).

---

## 1. Zero-Trust Architecture (Chống Tampering Giá Trị)

Mô hình thương mại điện tử rất nhạy cảm với việc người dùng chỉnh sửa giá client-side. Checkout Service áp dụng nguyên tắc **Zero-Trust Client**:

* **Từ chối Input định lượng:** Bất kỳ POST request nào từ Frontend chứa thông tin về `price`, `discount_amount` đều sẽ bị máy chủ phớt lờ hoàn toàn.
* **Server-Side Aggregation:** Tại API Checkout Preview, Checkout Service đóng vai trò là Aggregator gọi sang Cart, Promotion, Inventory để **tự tính toán giá mới nhất**.
* **Boundary cứng với Cart:** Checkout không nhận nghiệp vụ mutate `quantity`; mọi thay đổi số lượng phải đi qua Cart API trước khi quay lại Preview.
* **Đóng dấu Snapshot:** Sau khi tính toán xong, toàn bộ Data (sản phẩm gì, giá bao nhiêu) được lưu thành Snapshot trên Mem-cache (Redis) có sinh kèm một `CheckoutToken` bảo mật (UUIDv4) để niêm phong với thuật toán `pricing_hash` (bao gồm `promotion_campaign_revision`). `promotion_budget_version` chỉ dùng cho telemetry/read-time diagnostics. Dữ liệu tính toán không bao giờ rời khỏi tầm kiểm soát của Backend.
* **Explicit Voucher Write Boundary:** Tray voucher cho phép hành động `save-and-apply` (ghi ví có chủ đích). Ngược lại, tray voucher Sàn chỉ đọc mã đã lưu trong ví; submit path không được tự động ghi thêm voucher.
* **Option A Budget Guard:** Promotion Discovery có bước guard read-time để hạ mã đã cạn ngân sách thành `BUDGET_EXHAUSTED`; nếu guard degraded thì hệ thống fail-open ở discovery nhưng vẫn giữ correctness ở submit.
* **No Local Arithmetic Fallback:** Nếu reserve voucher thất bại lúc submit, Checkout không được tự trừ/cộng lại discount. Checkout bắt buộc gọi lại Promotion evaluate để trả `409 PROMOTION_RECALCULATION_NEEDED` cùng `dropped_promotions[]`.
* **Token Lifecycle Cứng:** Khi đã vào nhánh submit conflict (`PROMOTION_RECALCULATION_NEEDED`), token hiện tại vẫn là `SUBMITTED` (one-time-use). Muốn đặt tiếp bắt buộc tạo token mới qua Preview.

---

## 2. Bảo mật Phiên Thanh Toán (Session Token Lifecycle)

### 2.1 Sinh Token Không Chứa Trạng Thái (Non-JWT)
Không dùng JWT để làm Checkout Token vì JWT mã hóa payload bên trong nó và rất khó để Revoke (hủy bỏ) ngay lập tức khi người dùng Submit thành công. Checkout sử dụng `UUIDv4` thuần túy làm mã định danh tham chiếu (Reference Token) trỏ về Redis Session.

### 2.2 Replay Attack Prevention (Chống vòng lặp token)
Mỗi Session Token chỉ có một trạng thái duy nhất.
Khi API Submit mang Token lên, hệ thống sẽ thực thi một Atomic Lua Script trên Redis để kiểm tra trạng thái và chuyển Token từ `PREVIEW_READY` sang `SUBMITTED`. 
Bất cứ một request nào tiếp theo dùng Token này đều bị từ chối với mã lỗi `-2 (ALREADY_SUBMITTED)`.

---

## 3. Quản lý Concurrency & Race Condition 

Để bảo vệ hệ thống trước sự càn quét của Hacker sử dụng tool (hoặc ngay cả lỗi vô tình từ client double-click):

### 3.1. Phân Lương Rate Limiting & Distributed Lock tại Preview
Đỉnh điểm các sự kiện Flash Sale, Hacker có thể viết bot mở 10 luồng thanh toán cùng lúc nhắm vào một món hàng với 10 mã giảm giá khác nhau để bào mòn khuyến mãi.
Checkout áp dụng:
* **Rate Limits:** Tối đa 10 Preview requests / Phút.
* **Preview Lock:** Sử dụng `checkout:lock:preview:{user_id}` (5s) chặn ngay tại Cửa Ngõ API. Tại 1 thời điểm ở 1 account, ĐẢM BẢO chỉ duy nhất 1 luồng được gọi vào các Service Backend (Promotion, Inventory). Chấm dứt triệt để Race Condition trên Session.

### 3.2. Lock tại Submit & Deadlock Prevention
Trước khi Lua Script khóa Token vĩnh viễn, hệ thống dựng màng bảo vệ vòng cung `checkout:lock:submit:{user_id}`.
Thứ tự thực thi cực kỳ cẩn trọng:
1. Đóng băng luồng hiện tại bằng Lock.
2. Kiểm tra nhanh sự thay đổi về giá bằng hàm so sánh Hash (giá SKU + version cứng promotion-rule). Dù giá có lệch hay mạng có chập chờn, ở bước này Session vẫn an toàn vì Lua Script chưa chạy.
3. Nếu giá hợp lệ, Lua Script giập chết Token và đẩy Request sang Order.
4. Xóa Lock.

Nhờ tách bạch cơ chế Read Validator và Write Commiter, hệ thống không bao giờ xảy ra tình trạng "Treo Phiên", đảm bảo trải nghiệm khách hàng ở mức xuất sắc kể cả khi hạ tầng mạng nội bộ bị rung rinh.

---

## 4. Giảm Bề mặt Abuse ở Luồng Voucher

Việc không auto-save voucher Sàn trong Submit mang lại lợi ích vận hành trực tiếp:

* **Loại bỏ hidden write tại Submit:** Luồng thanh toán chỉ tập trung vào reserve/confirm, dễ theo dõi audit hơn.
* **Giảm nguy cơ DB bloat:** Tránh việc user mở checkout nhiều lần làm phát sinh hàng loạt dòng ví không có chủ đích sử dụng.
* **Giảm áp lực cho Discovery API:** API gợi ý voucher Sàn chỉ đọc ví đã lưu + evaluate rule, không cần trộn thêm mã chưa lưu tại runtime.
