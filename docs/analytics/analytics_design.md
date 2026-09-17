# Bản Thiết Kế Toàn Diện: Analytics Service

> **Trạng thái:** thiết kế tương lai, chưa có Analytics Service/runtime và chưa là
> consumer được đăng ký của Order hay Review event nào. Không cấp Kafka ACL, không
> tạo pipeline và không suy diễn schema Analytics từ `t.order.events` hoặc
> `t.review.events` cho tới khi có contract integration riêng.

Tài liệu này là bản đúc kết từ quá trình phân tích và thảo luận chuyên sâu, quy định mọi mặt về kiến trúc, luồng sự kiện (Data Pipeline), cơ chế bảo mật cô lập và Database Schema cho **Analytics Service** (Dịch vụ Phân tích Dữ liệu) của hệ thống E-commerce.

---

## 1. Vai trò của Analytics Service trong Hệ Sinh Thái E-commerce

Analytics Service là **Central Data Platform (Data Warehouse/Lakehouse)** trung tâm, đóng vai trò "chiếc phễu" lưu trữ, dọn dẹp và tính toán mọi sự kiện từ toàn bộ các Microservices khác sinh ra. Nó phục vụ hai nhóm đối tượng tách biệt hoàn toàn nhưng nằm trên cùng một hệ thống vật lý:

1.  **Dành cho platform Admin (admin - Vi mô):** Đóng vai trò là Engine tính toán Real-time cho admin Dashboard. Giúp admin xem tức thời các chỉ số kinh doanh như: Tổng doanh thu, Lượng đơn hàng, Chỉ số Sức khỏe cửa hàng (Giao trễ, Hủy đơn), Lượng Follower, và ROI Khuyến mãi.
2.  **Dành cho System Admin (Sàn - Vĩ mô):** Đóng vai trò là Data Lake để truy vết hệ thống. Cung cấp báo cáo phễu mua sắm (Funnel), Hành vi từ bỏ giỏ hàng (Cart Abandoned), Xu hướng tìm kiếm (Popular Keywords), và đặc biệt là Nhật ký Pháp lý/Bảo mật (Audit Log t.auth.events).

---

## 2. Quyết Định Kiến Trúc Tổng Quan: Modern Kappa Architecture

Đứng trước sự xung đột lịch sử giữa **Tốc độ (Real-time)** và **Sự Chính Xác Tuyệt Đối (Batch Processing)**, hệ thống quyết định loại bỏ hoàn toàn Batch Layer (Hadoop/Spark Batch truyền thống) vì chi phí bảo trì quá đắt đỏ. 

Kiến trúc được lựa chọn là: **Modern Kappa Architecture (Streaming + Real-time OLAP)**.

Mô hình này đi theo chuỗi cốt lõi: 
`[Apache Kafka] ---> [Apache Flink] ---> [Apache Doris (OLAP)]`

*   **Tại sao hệ thống này vượt qua được sai số của Real-time thuần túy?**
    Thay vì chạy Batch qua đêm để bù trừ lại các sai sót do "Sự kiện đến trễ nhảy sai ngày" hay "Người dùng Hủy hàng (Refund)", toàn bộ gánh nặng sửa sai (Mutation Data) được dồn cho tính năng **Merge-On-Read/Upsert của Apache Doris**. Flink chỉ việc đẩy bản ghi mới nhất mang dấu trừ (-), Doris tự tìm kiếm dòng lịch sử dưới ổ cứng và ghi đè tự động.

---

## 3. Kiến Trúc Cô Lập Dữ Liệu (Multi-Tenancy Isolation)

Bài toán nguy hiểm nhất: Làm sao để cùng một kho Database, truy vấn ngàn dòng/giây từ hàng ngàn admin không bao giờ bị lộ doanh thu chéo của nhau, và không làm chết hệ thống khi Admin Sàn đang chạy Báo cáo tháng?

Giải pháp áp dụng **4 Lớp Chốt Chặn (Defense in Depth)**:

1.  **Data Structure (Logical Isolation):** Mọi Database Schema liên quan đến giao dịch phải ép buộc field `platform_id` hoặc `merchant_id` là một phần của Primary Key. Nếu thiếu, Database báo lỗi.
2.  **Application Context Layer:** API Analytics tuyệt đối không tin tham số truy vấn `?platform_id=` từ URL. ID của Tenant được bóc tách tàng hình từ gói **JWT Token** hoặc Header bảo mật từ API Gateway đẩy xuống.
3.  **Row-Level Security (RLS ở tầng DB):** Dành cho lỗi con người (Dev code nhầm SQL quên WHERE). Apache Doris được thiết lập `ROW POLICY` tự động gắn bộ lọc ẩn `WHERE platform_id = current_tenant_id()` vào mọi truy vấn ra khỏi Cổng JDBC admin.
4.  **Resource Isolation (Chiến lược Cá Mập và Cá Con):** Tách Workload của Database. Nhóm lệnh truy vấn nặng trịch của Hệ thống Sàn bị giới hạn MAX CPU 30%. Dành phần CPU còn lại cho nhóm các admin có thể tải trang Dashboard mượt mà, không gặp hiện tượng "Hàng xóm ồn ào" (Noisy Neighbors).

---

## 4. Thiết Kế Data Pipeline & Điểm Nạp Doris

Analytics không chủ động moi móc Database của các Service. Tất cả đi qua **Transactional Outbox Event** đẩy vào Kafka.

### Bố cục các luồng Flink Pipelines (Job)
1.  **Pipeline giao dịch (Payment):** nguồn khả dụng là `t.payment.events.v2` theo
    Payment AsyncAPI. Analytics chưa phải consumer được đăng ký của Order Events v1:
    hai fact Order hiện tại chỉ dành cho Cart và Review. Mọi pipeline doanh thu/order
    mới phải có consumer contract riêng trước khi cấp ACL, thay vì suy diễn lifecycle
    từ `t.order.events`.
2.  **Pipeline Khách hàng & Phễu (Cart):** Mục tiêu tương lai là dùng `t.cart.events`
    để dựng phễu bỏ giỏ. Review không phải source đã đăng ký: Review Events v1 chỉ có
    consumer là Review, Search và Notification.
3.  **Pipeline Hạ tầng & Bảo mật (Auth/Security):** Nguồn `t.auth.events`. Đẩy nguyên thô mọi tác vụ Đăng Nhập Sai, Reset Pass làm Audit Log Pháp lý để Sàn truy vết Gian lận Tài khoản mà không làm nghẽn DB Auth gốc.
4.  **Pipeline Hành vi Traffic (View/Click/Search):** Thông lượng lớn. Flink Gom rác và chỉ đổi JSON sang Columns, xả thẳng Data Khổng lồ xuống dạng Append-Only của Doris. Doris sẽ tự Rollup.

### Cơ Chế Đổ Dữ Liệu (Doris Stream Load)
Thay vì dùng `INSERT INTO`, Flink sử dụng tính năng **Micro-Batching**. Flink thu gom sự kiện vào một gói lớn (vd: 50.000 events / 5 giây) rồi bắn 1 phát (Stream Load / HTTP Put) thẳng vào Backend Node của Doris. Điều này tăng cường khả năng chịu tải lên tới triệu dòng/s mà không sập DB.

---

## 5. Cơ chế Flink Watermark - Sự cứu rỗi của Sự Kiện Đến Trễ

Khắc phục bài toán: *Flash Sale kết thúc lúc 00:00, nhưng khách chốt đơn lúc 23:59 mà mạng rớt tới 00:05 sự kiện mới tới máy chủ.*

*   **Không tin đồng hồ Máy chủ (Processing Time):** Flink cấu hình sử dụng `Event Time` (thời điểm chốt đơn thật sự lưu ở payload JSON).
*   **Watermark & Bounded-Out-Of-Orderness:** Flink thiết lập độ trễ (Lateness Tolerance) là 3 phút. Tức là dù đồng hồ máy chủ Flink báo 00:02 ngày mới, nhưng `Watermark = 23:59` nên Cửa sổ tính doanh thu ngày hôm qua vẫn **mở toang** để chờ đơn hàng chậm trễ lết tới vào báo cáo đúng ngày.
*   **Allowed Lateness & Sức mạnh Upsert:** Kể cả khi có đơn rớt mạng tới 30 phút (Hố sâu của Watermark), Flink vẫn có thể đưa ra một Lệnh tính toán lại. Nó đẩy cục Data cập nhật Doanh Thu (Tăng từ 1 tỷ lên 1 tỷ + 500k) sang Apache Doris. Doris sẽ dùng Key `platformID + Window_11_11` để **đè (Update/Merge)** kết quả mới rập khuôn tức thì lên biểu đồ của admin.

---

## 6. Thiết Kế Database Schema Cho Apache Doris

Doris sử dụng 3 mô hình lưu trữ xử lý khác nhau, ánh xạ đúng nhu cầu của Big Data E-commerce.

### Lớp dữ liệu Lịch sử (Bronze Layer) - Duplicate Key Model
Chỉ ghi thêm (Append-Only), phục vụ System Admin xem History, Audit Log.
```sql
CREATE TABLE raw_user_behaviors (
    event_time DATETIME NOT NULL COMMENT 'Có gắn Watermark',
    user_id BIGINT NOT NULL,
    platform_id BIGINT COMMENT 'Tenant isolation',
    item_id BIGINT,
    event_type VARCHAR(50) NOT NULL COMMENT 'VIEW, CLICK, ADD_TO_CART',
    ip_address VARCHAR(50)
)
ENGINE=OLAP
DUPLICATE KEY(event_time, user_id, platform_id)
PARTITION BY RANGE(event_time) (...) -- Tự động xoá/tống lên S3 sau 30 ngày
DISTRIBUTED BY HASH(user_id) BUCKETS 32;
```

### Lớp dữ liệu Thực thi Hành động (Silver Layer) - Unique Key Model
Ghi đè, tự khắc phục và lui doanh thu các loại đơn móp méo/Hủy/Đổi trả.
```sql
CREATE TABLE rt_order_transactions (
    platform_id BIGINT NOT NULL COMMENT 'Tenant ID Primary',
    order_id BIGINT NOT NULL COMMENT 'ID Core (Merge theo 2 fields này)',
    event_time DATETIME NOT NULL,
    order_status VARCHAR(50) REPLACE NOT NULL COMMENT 'PAID, CANCELLED',
    total_amount DECIMAL(15,2) REPLACE DEFAULT "0", 
    is_admin_fault BOOLEAN REPLACE DEFAULT "false"
)
ENGINE=OLAP
UNIQUE KEY(platform_id, order_id)
DISTRIBUTED BY HASH(platform_id, order_id) BUCKETS 64
PROPERTIES (
    "enable_unique_key_merge_on_write" = "true"
);
```

### Lớp Dữ liệu Tốc độ cao (Gold Layer) - Aggregate Key Model
Hàng triệu dòng đã được cộng gộp tự động trước khi người dùng truy vấn. Front-end gọi là có ngay (O(1)).
```sql
CREATE TABLE agg_platform_hourly_revenue (
    platform_id BIGINT NOT NULL COMMENT 'Tenant ID',
    date_hour DATETIME NOT NULL,
    total_revenue DECIMAL(20,2) SUM DEFAULT "0" COMMENT 'Doanh thu thuần tự cộng dồn',
    total_orders BIGINT SUM DEFAULT "0",
    unique_buyers BITMAP BITMAP_UNION COMMENT 'Đếm nhanh High-cardinality độc nhất'
)
ENGINE=OLAP
AGGREGATE KEY(platform_id, date_hour)
DISTRIBUTED BY HASH(platform_id) BUCKETS 16;
```
*Ghi chú: Lớp Gold Layer này được tổng hợp định kỳ từ Lớp Silver Layer qua Materialized Views tạo sẵn bên trong Doris.* 

---
**Tổng kết Toàn diện:** 
Hệ thống Analytics Service sử dụng kiến trúc **Stream-to-OLAP** loại bỏ rào cản cồng kềnh của Batch Layer. Với Kafka chịu tải ngõ vào, Flink chống lặp đếm xử lý sai thời gian mạng yếu, và Cụm Apache Doris phân quyền chặt chẽ đè dữ liệu siêu tốc... Nó đủ sức phục vụ độ sâu dữ liệu kinh doanh cho cả hệ thống Sàn và Hàng vạn Cửa Hàng cùng một lúc!
