# Review Service - Database Schema

**Liên kết nhanh (Related Documents):**
*   [Caching Schema (Redis & Caffeine)](review_caching_schema.md)
*   [Events Structure](review_events.md)
*   [Background Workers & Jobs](review_workers.md)

---
Database PostgreSQL đóng vai trò **Nguồn Sự Thật Chính (Source of Truth)** cho Review Service. Hệ thống ứng dụng kiến trúc **Event-Driven** để giao tiếp với Order Service (nhận quyền đánh giá) và AI Agent Service (kiểm duyệt).

Để đáp ứng lưu lượng tải (Read Throughput) cực kỳ lớn của việc xem đánh giá, schema được thiết kế với sự kết hợp **Phi chuẩn hóa (Denormalization)** và các **bộ đếm tổng hợp (Aggregates)**. Quá trình kiểm soát Anti-Spam (Rule 1-1-1: 1 Đơn - 1 Sản Phẩm - 1 Lượt đánh giá) được áp đặt ngay từ cấu trúc bảng.

## 1. Bảng `review_eligibilities` (Quyền Mở Khóa Đánh Giá)

Bảng này được ghi (Insert) thông qua một Kafka Consumer (Background Worker) khi **Order Service** phát ra sự kiện `OrderCompletedEvent`. Nó giải quyết bài toán: Chỉ những người đã mua sản phẩm VÀ đơn hàng ở trạng thái kết thúc thành công mới được phép tạo đánh giá. 

| Cột | Kiểu dữ liệu | Ràng buộc | Mô tả |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | PRIMARY KEY | Định danh quyền đánh giá |
| `user_id` | `BIGINT` | NOT NULL | ID người dùng (Khách hàng) |
| `order_id` | `BIGINT` | NOT NULL | ID đơn hàng |
| `product_id` | `BIGINT` | NOT NULL | ID sản phẩm trong đơn hàng |
| `sku_id` | `VARCHAR(255)`| NOT NULL | ID biến thể (SKU) thực tế khách đã mua (Ví dụ: Váy màu Đỏ, Size M) |
| `status` | `VARCHAR(30)`| DEFAULT 'PENDING' | Trạng thái: `PENDING`, `PROCESSING` (Khóa Submit), `REVIEWED`, `EXPIRED`, `REJECTED_RETRY_PENDING` (chờ nộp lại do AI từ chối), `REJECTED_FINAL` (Bị khóa do vi phạm hoặc Hậu kiểm Report) |
| `attempt_count` | `SMALLINT` | DEFAULT 0 | Số lượt đã nộp đánh giá (Tối đa 2 lượt. Cấp 1 cơ hội làm lại nếu AI báo vi phạm) |
| `expires_at` | `TIMESTAMP` | NOT NULL | Thời hạn tối đa cho phép đánh giá (VD: +30 ngày sau khi nhận hàng) |
| `created_at` | `TIMESTAMP` | NOT NULL | Thời điểm đơn hàng chuyển sang Completed |
| `updated_at` | `TIMESTAMP` | NOT NULL | |

**Mở rộng & Tối ưu:**
*   **Unique Index:** `UNIQUE(order_id, product_id, sku_id, user_id)` (Bắt buộc) - Ràng buộc cứng đảm bảo Rule 1-1-1 ở cấp độ CSDL cho từng phân loại nhận hàng.
*   **Index cho API List Pending Reviews:** `B-Tree Index(user_id, status)` - Giúp gọi API "Chưa đánh giá" của khách hàng cực nhanh.
*   **Giải quyết Race Condition (Rule 1-1-1):** Khi nộp bài, API Handler **BẮT BUỘC** dùng Atomic SQL Update để khóa Row thay vì SELECT kiểm tra. Toàn bộ thao tác phải được bọc trong một **Local Database Transaction** (`BEGIN ... COMMIT`) để tránh lỗi trạng thái treo lửng:
    *   **Bước 1:** `UPDATE review_eligibilities SET status = 'PROCESSING', attempt_count = attempt_count + 1 WHERE id = $id AND status IN ('PENDING', 'REJECTED_RETRY_PENDING') RETURNING attempt_count;`. 
    *   **Bước 2:** Chỉ khi lệnh UPDATE trả về thành công mới tiến hành `INSERT` vào bảng `reviews`. 
    *   **Bước 3:** Commit Transaction. Điều này chặn đứng hoàn toàn việc bắn HTTP Concurrent Spam và ngăn lỗi rác dữ liệu ở trạng thái `PROCESSING` nếu App Server crash giữa chừng. Lời khuyên: Cần có một cronjob chạy 15 phút/lần tự động update những record kẹt ở trạng thái `PROCESSING` quá 30 phút về lại `PENDING` để chống rác do hệ thống broker quá tải.

## 2. Bảng `reviews` (Nội Dung Nhận Xét)

Bảng cốt lõi chứa dữ liệu text do người dùng sinh ra (User-Generated Content). 

| Cột | Kiểu dữ liệu | Ràng buộc | Mô tả |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | PRIMARY KEY | Định danh đánh giá |
| `eligibility_id` | `UUID` | NOT NULL| Khóa ngoại tham chiếu 1-N tới `review_eligibilities(id)` (Bỏ UNIQUE để hỗ trợ nộp lại khi AI duyệt Rejects) |
| `product_id` | `BIGINT` | NOT NULL | ID sản phẩm (Giúp query nhanh tất cả review của 1 SP) |
| `sku_id` | `VARCHAR(255)`| NOT NULL | ID phân loại / biến thể của sản phẩm |
| `variant_name` | `VARCHAR(255)`| NULLABLE | Tên của biến thể (Ví dụ: "Màu Đỏ, Size M"). **Phi chuẩn hoá** lưu thẳng vào đây để UI hiển thị dòng "Phân loại hàng đã mua: ..." cực nhanh mà không cần Join Product DB. |
| `user_id` | `BIGINT` | NOT NULL | ID người tạo |
| `user_name` | `VARCHAR(100)`| NOT NULL | Tên người dùng tại thời điểm đánh giá (**Phi chuẩn hóa - Denormalized** để giảm bớt Join sang User DB) |
| `rating` | `SMALLINT` | NOT NULL | Số sao đánh giá (1 tới 5) |
| `content` | `TEXT` | NULLABLE | Nội dung nhận xét bằng chữ (có thể để trống nếu chỉ ấn rate sao) |
| `status` | `VARCHAR(30)`| DEFAULT 'PENDING_MODERATION' | Trạng thái: `PENDING_MODERATION` (Hiển thị ngay cho Author, chờ AI), `APPROVED`, `REJECTED`, `HIDDEN_BY_REPORT` (Bị report nhiều, chờ Admin) |
| `moderation_reason`| `TEXT` | NULLABLE | Lý do hoặc chi tiết điểm số độc hại từ AI Agent trả về nếu status = REJECTED |
| `helpful_count` | `INT` | DEFAULT 0 | Tổng số lượt khách hàng khác Vote "Hữu ích" |
| `report_count` | `INT` | DEFAULT 0 | Tổng số lượt khách hoặc Seller báo cáo (Dùng mốc >= 3 để Auto-hide lúc chờ xử lý) |
| `has_media` | `BOOLEAN` | DEFAULT FALSE | Cờ đánh dấu (Flag) review có chứa ảnh/video (Khóa chính phục vụ tính năng "Lọc theo Hình Ảnh") |
| `is_seller_replied`| `BOOLEAN` | DEFAULT FALSE | Người bán đã phản hồi bình luận này chưa? |
| `created_at` | `TIMESTAMP` | NOT NULL | |
| `updated_at` | `TIMESTAMP` | NOT NULL | |

**Mở rộng & Tối ưu:**
*   **Kiểu dữ liệu ID:** Khuyến nghị chuẩn định dạng `UUID v7` lưu trữ timestamp tại các byte đầu tiên, giúp việc phân trang Cursor Pagination theo thời gian `(id < last_id)` trơn tru như dùng auto-increment, tránh đứt gãy thuật toán.
*   **Index cho Lọc & Sắp xếp Review:** Cần tạo các Composite Index để hỗ trợ bộ Lọc Review (Lọc theo sao, Lọc có Media, Lọc theo Phân loại/SKU) nhân chéo với Sắp xếp ("Mới nhất" và "Đánh giá hữu ích nhất"):
    *   `B-Tree Index(user_id)` (Bắt buộc thiết lập vì khi luồng Update GDPR đổi tên / Ẩn danh user chạy mà thiếu index này, Database sẽ bị Full Table Scan và sập hoàn toàn).
    *   `B-Tree Index(product_id, status, created_at DESC)` (Tối ưu Default sort "Mới nhất").
    *   `B-Tree Index(product_id, status, helpful_count DESC, id DESC)` (Tối ưu sort theo "Hữu ích nhất").
    *   `B-Tree Index(product_id, sku_id, status, created_at DESC)` (Lọc SKU + sort Mới nhất).
    *   `B-Tree Index(product_id, sku_id, status, helpful_count DESC, id DESC)` (Lọc SKU + sort Hữu ích nhất).
    *   `B-Tree Index(product_id, rating, created_at DESC)` (Lọc lọc số sao + sort Mới nhất).
    *   `B-Tree Index(product_id, status, created_at DESC) WHERE has_media = TRUE` (Chỉ Có hình/video + sort Mới nhất).
    *   `B-Tree Index(product_id, status, helpful_count DESC, id DESC) WHERE has_media = TRUE` (Chỉ Có hình/video + sort Hữu ích nhất).
*   Ràng buộc `CHECK(rating >= 1 AND rating <= 5)`.

## 3. Bảng `review_media` (Ảnh/Video Của Đánh Giá)

| Cột | Kiểu dữ liệu | Ràng buộc | Mô tả |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | PRIMARY KEY | |
| `review_id` | `UUID` | FOREIGN KEY, NOT NULL| ID của bản ghi review cha |
| `media_type` | `VARCHAR(10)` | NOT NULL | `IMAGE` hoặc `VIDEO` |
| `media_url` | `VARCHAR(255)`| NOT NULL | URL gốc lưu file trên Cloud (S3/GCS) |
| `thumbnail_url` | `VARCHAR(255)`| NULLABLE | URL ảnh thu nhỏ (Worker xử lý để load trên mobile web nhanh hơn, hoặc trích xuất khung hình đầu của Video) |
| `sort_order` | `SMALLINT` | DEFAULT 0 | Vị trí thứ tự hiển thị của hình ảnh |
| `created_at` | `TIMESTAMP` | NOT NULL | |

**Index:** `B-Tree Index(review_id)` 

## 4. Bảng `product_rating_aggregates` (Thống Kê Tổng Hợp)

Để phục vụ truy vấn ở "Trang Chi Tiết Sản Phẩm" và cung cấp điểm số cho **Product Service** hay **Search Service**. Bảng này KHÔNG bao giờ bị `UPDATE` trực tiếp mỗi khi có review mới mà sẽ được cập nhật gián tiếp nhờ Event hoặc Background Consumer (bắt sự kiện `ReviewApprovedEvent`) để tránh nghẽn DB.

| Cột | Kiểu dữ liệu | Ràng buộc | Mô tả |
| :--- | :--- | :--- | :--- |
| `product_id` | `BIGINT` | PRIMARY KEY | ID sản phẩm trùng khớp với Product Service |
| `average_rating` | `DECIMAL(3, 2)`| DEFAULT 0.00 | Điểm trung bình (Ví dụ: 4.85) |
| `total_score` | `INT` | DEFAULT 0 | Tổng điểm số (Được dùng làm tử số chính xác khi tính TB) |
| `total_reviews` | `INT` | DEFAULT 0 | Tổng số lượt nhận xét (Chỉ tính `APPROVED`) |
| `total_1_star` | `INT` | DEFAULT 0 | Số lượt rate 1 sao |
| `total_2_star` | `INT` | DEFAULT 0 | Số lượt rate 2 sao |
| `total_3_star` | `INT` | DEFAULT 0 | Số lượt rate 3 sao |
| `total_4_star` | `INT` | DEFAULT 0 | Số lượt rate 4 sao |
| `total_5_star` | `INT` | DEFAULT 0 | Số lượt rate 5 sao |
| `total_with_media`| `INT` | DEFAULT 0 | Tổng số lượng đánh giá có gắn kèm Media (Video/Image) |
| `updated_at` | `TIMESTAMP` | NOT NULL | Thời gian tính toán lại gần nhất |

## 4.2. Bảng `sku_rating_aggregates` (Thống Kê Theo Phân Loại SKU)

Giống hệt cấu trúc của `product_rating_aggregates` nhưng chia nhỏ cấp độ xuống SKU để hỗ trợ UI: "Lọc theo Màu Đỏ (45 đánh giá)".

| Cột | Kiểu dữ liệu | Ràng buộc | Mô tả |
| :--- | :--- | :--- | :--- |
| `product_id` | `BIGINT` | NOT NULL | ID sản phẩm cha |
| `sku_id` | `VARCHAR(255)`| NOT NULL | ID phân loại/biến thể (Ví dụ: Đỏ-Size M) |
| `average_rating` | `DECIMAL(3, 2)`| DEFAULT 0.00 | Điểm trung bình riêng của SKU |
| `total_score` | `INT` | DEFAULT 0 | Tổng điểm của SKU |
| `total_reviews` | `INT` | DEFAULT 0 | Tổng số lượt nhận xét |
| `total_1_star` | `INT` | DEFAULT 0 | Số lượt rate 1 sao |
| `total_5_star` | `INT` | DEFAULT 0 | ...Tương tự tới 5 sao |
| `total_with_media`| `INT` | DEFAULT 0 | Tổng lượt có đính media |
| `updated_at` | `TIMESTAMP` | NOT NULL | |

*(Lưu ý: Bảng này dùng MKhóa Chính là Composite Key `PRIMARY KEY (product_id, sku_id)`)*

## 5. Bảng `review_seller_replies` (Phản hồi từ Người Bán)

Vì quy tắc là 1 Review chỉ cho phép 1 Phản hồi từ cửa hàng (seller), nên thiết kế bảng gióng riêng giúp quản lý gọn gàng phân hệ Admin của người bán.

| Cột | Kiểu dữ liệu | Ràng buộc | Mô tả |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | PRIMARY KEY | |
| `review_id` | `UUID` | UNIQUE, FOREIGN KEY | Khóa chính tham chiếu (1-1) với `reviews` |
| `seller_id` | `UUID` | NOT NULL | Định danh Seller/Nhân viên CSKH trả lời |
| `content` | `TEXT` | NOT NULL | Nội dung Text phản hồi (Thường không cho đính kèm hình) |
| `created_at` | `TIMESTAMP` | NOT NULL | |
| `updated_at` | `TIMESTAMP` | NOT NULL | |

*(Ghi chú: Đã loại bỏ lưu trữ bảng `review_likes` truyền thống. Việc thả "Hữu ích" cho một đánh giá được theo dõi trên Memory nhờ sử dụng cấu trúc dữ liệu **Redis Bitmaps**. Một tiến trình Worker chạy ngầm sẽ đếm số Lượt Like bằng bit (BITCOUNT) rồi cộng dồn cập nhật trực tiếp vào cột `helpful_count` của bảng `reviews` định kỳ.)*

## 6. Bảng `review_outbox` (Outbox Pattern)
Đảm bảo tính nhất quán dứt khoát giữa Review DB Service và Message Broker (Kafka) theo nguyên tắc bù đắp Transaction (Chống rách dữ liệu nếu Kafka rớt).

| Cột | Kiểu dữ liệu | Ràng buộc | Mô tả |
| :--- | :--- | :--- | :--- |
| `event_id` | `UUID` | PRIMARY KEY | ID định danh của sự kiện |
| `aggregate_type` | `VARCHAR(50)` | NOT NULL | VD: `REVIEW`, `REVIEW_METRIC` |
| `aggregate_id` | `VARCHAR(255)`| NOT NULL | ID của `review_id` hoặc `product_id` |
| `event_type` | `VARCHAR(100)`| NOT NULL | VD: `REVIEW_CREATED`, `REVIEW_APPROVED`, `REVIEW_METRICS_UPDATED` |
| `payload` | `JSONB` | NOT NULL | Data chuẩn hóa CloudEvent |
| `status` | `VARCHAR(20)`| DEFAULT 'PENDING'| Trạng thái: `PENDING`, `PUBLISHED`, `DEAD` |
| `created_at` | `TIMESTAMP` | DEFAULT NOW() | |

## 7. Bảng `review_reports` (Tier 2 Moderation - Cấm Túc / Báo Cáo)
Hỗ trợ chức năng Cộng đồng / Người Bán bấm "Report" để bảo vệ tính trong sạch của Review.

| Cột | Kiểu dữ liệu | Ràng buộc | Mô tả |
| :--- | :--- | :--- | :--- |
| `id` | `UUID` | PRIMARY KEY | |
| `review_id` | `UUID` | NOT NULL, FOREIGN KEY | Review bị báo cáo (1-N với `reviews`) |
| `reporter_id` | `BIGINT` | NOT NULL | ID người báo cáo (User hoặc Seller) |
| `reporter_role` | `VARCHAR(20)`| NOT NULL | `USER` hoặc `SELLER` |
| `reason_type` | `VARCHAR(50)`| NOT NULL | Loại vi phạm: `SPAM`, `OFFENSIVE`, `IRRELEVANT`, `FALSE_INFO`, `PRIVACY`... |
| `status` | `VARCHAR(30)`| DEFAULT 'PENDING_REVIEW' | Hàng đợi xử lý: `PENDING_REVIEW`, `RESOLVED_REJECTED` (Không xóa/bỏ qua), `RESOLVED_ACCEPTED` (Xóa review) |
| `created_at` | `TIMESTAMP` | NOT NULL | |
| `updated_at` | `TIMESTAMP` | NOT NULL | |

**Index & Ràng Buộc:** 
- `B-Tree Index(review_id, status)` (Để Dashboard Admin List lôi lên duyệt).
- `UNIQUE(review_id, reporter_id)` (Anti-Spam: Mỗi người chỉ report 1 bài tối đa 1 lần).

---

> **Quyết định Thiết kế Liên quan (Design Decision Logs):**
> 1.  **Vì sao không Elasticsearch?** Yêu cầu nghiệp vụ lọc thuần túy "Số sao", "Có Hình/Video", "Sắp xếp mới nhất" hoàn toàn có thể cân được bằng Composite/Partial Indexes trên Data Tiers Relational. Sử dụng Elasticsearch phát sinh thừa thãi độ phức tạp vận hành và phí triển khai.
> 2.  **AI Moderation Asynchronous:** Việc duyệt Auto bằng AI Agent không làm block flow phản hồi Client. Status mặc định `PENDING_MODERATION` giúp Frontend App vẫn hiện mảng Toast: "Cảm ơn bạn, đánh giá đang được kiểm duyệt".
> 3.  **Lỗ hổng Đổi Tên User:** Giá trị User Name được *Snapshot* thẳng cắm lấy vào Record. Trường hợp hi hữu sau 2 năm User Đổi tên, Data ở Review cũ vẫn dùng tên cũ. Chấp nhận Eventual Inconsistency mức này để đổi lấy Read tốc độ cao tuyệt đối cho Trang Chi Tiết Sản Phẩm (PDP - Product Detail Page) thay vì làm Table Join cực nhọc. (Để giảm tải độ phức tạp, hệ thống đã loại bỏ hoàn toàn việc lưu trữ Avatar). Tùy theo nhu cầu cập nhật Tên User đồng bộ ở Backend, có thể chạy bù Worker rà soát chậm Update sau qua Message Broker.
