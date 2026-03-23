# Review Service - Danh sách Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
*   [Database Schema (Cold Store)](review_db_schema.md)
*   [Caching Schema (Redis & Caffeine)](review_caching_schema.md)
*   [Events Structure](review_events.md)
*   [Background宣 Workers & Jobs](review_workers.md)

---
Với đặc thù hệ thống đọc nhiều, nhưng ghi/tính toán số liệu cực lớn, Review Service phải tách bạch hầu hết các tác vụ tính toán (Aggregations) và Đồng bộ (Sync) ra khỏi luồng gọi API chính. Luồng xử lý bất đồng bộ được chia cho các Worker phân lý.

## 1. Outbox Relay Producer (CDC/Goroutine)
**Loại hình:** Long-running Daemon Process.
**Sứ mệnh:** Chuyển đổi dữ liệu Insert trên bảng `review_outbox` thành Kafka Messages vào Topic `t.review.events` an toàn, tránh mất Message (Eventual Consistency với DB).

**Luồng hoạt động định kỳ (Polling / Tailing):**
1. **Khuyến nghị Bắt Buộc:** Sử dụng công cụ CDC (Change Data Capture) như **Debezium** đọc trực tiếp Binlog/WAL của PostgreSQL thay vì Polling SQL. Nếu vì giới hạn hạ tầng bắt buộc phải dùng lệnh Polling, **TUYỆT ĐỐI KHÔNG** dùng cú pháp `SELECT ... FOR UPDATE SKIP LOCKED` cho bảng Outbox nhiều tiến trình đọc, vì nó sẽ phá vỡ thứ tự xuất Event của cùng một sản phẩm (`product_id`). Bạn bắt buộc phải chạy 1 Worker duy nhất (Leader Elected) hoặc Partitioning theo ID.
2. Nạp dữ liệu trong block Try/Catch.
3. Push lên Kafka `t.review.events` một cách Đồng Bộ (Synchronous Send).
4. **Sửa lỗi Atomicity & Lỗ hổng Trạng thái Outbox Pattern:**
   - Chờ Kafka trả về tín hiệu `ACK` thành công thực sự (Future.get).
   - Nếu Retry chạm đáy Timeout hoặc ném ngoại lệ Kafka Not Available, lập tức Dừng Giao Dịch, rollback block Polling (không làm gì cả, bản ghi vẫn giữ trạng thái `PENDING` để lần sau quét lại).
   - Nếu nhận `ACK` thành công, thực hiện `UPDATE review_outbox SET status = 'PUBLISHED'` (Trạng thái cực kỳ chuẩn theo schema, tuyệt đối không dùng từ rác `PROCESSED`).
   - KHÔNG ĐƯỢC MẶC ĐỊNH XÓA (Delete) record Outbox để giảm tải như lời khuyên gốc. Xóa cứng sẽ làm mất Debug Trail. Hãy dùng thêm một CronJob chạy lúc nửa đêm để xóa các record `PUBLISHED` quá 7 ngày.

## 2. Order Completed Consumer (Cấp quyền đánh giá)
**Loại hình:** Kafka Consumer Worker.
**Consume Topic:** `t.order.events`
**Sứ mệnh:** Tự động mở quyền Review cho khách mua hàng thật, chống Review ảo (Spam/Fake).

**Luồng hoạt động:**
1. Lắng nghe event `ORDER_COMPLETED`.
2. Extract danh sách mảng các `items` trong đơn hàng (Mỗi item gồm `product_id`, `sku_id`).
3. Chạy lệnh Bulk Insert: `INSERT INTO review_eligibilities (user_id, order_id, product_id, sku_id, status, expires_at) VALUES (...) ON CONFLICT DO NOTHING`.
4. Sau tiến trình này, Khách hàng vào app phần "Chờ Đánh Giá" sẽ nhìn thấy đúng phân loại sản phẩm tương ứng.

## 3. AI Moderation Synchronizer (Kiểm duyệt Auto)
**Loại hình:** Kafka Consumer Worker.
**Consume Topic:** `t.ai_agent.events`
**Sứ mệnh:** Cập nhật trạng thái của Review dựa trên kết quả AI. Triển khai Rule: Nếu Vi Phạm, cấp cơ hội sửa đổi 1 lần.

**Luồng xử lý (Thiết kế chặt chẽ Idempotency):**
1. Nhận kết quả từ AI, check tính lũy đẳng (Idempotency) để tránh xử lý 2 lần cùng một kết quả trả về từ bản tin Kafka:
   - Worker sử dụng bảng `ai_moderation_idempotency(event_id PK, processing_status, created_at)` để quản lý trạng thái xử lý độc lập, cho phép khóa (Lock) theo `event_id`.
   - **Xử lý Race-Condition:** Nhúng vào một Transaction `BEGIN`. Call `INSERT INTO ai_moderation_idempotency (event_id, processing_status, created_at) VALUES ($event_id, 'PROCESSING', NOW()) ON CONFLICT DO NOTHING`.
   - Nếu trả về `0 rows affected` (Đã có worker khác giữ hoặc đã xử lý xong), lập tức COMMIT, SKIP event này và trả `ACK` về Kafka Broker.
   - Nếu qua cửa (Mới toanh), tiếp tục Query trạng thái hiện tại của review ID. Nếu `status` khác `PROCESSING` (Nghĩa là Review này có thể đã được duyệt bởi Admin bằng tay hoặc bị huỷ trước đó do nguyên nhân khác), Update `processing_status = 'SKIPPED'` vào bảng Idempotency, COMMIT và SKIP chặn đứng luồng tiếp theo.
2. Mở Transaction cập nhật bản ghi trong DB (Kẹp chung với việc Cập nhật Idempotency thành công).
3. Nếu `is_safe = true` (Status mới = `APPROVED`): 
   - Update bảng `reviews` -> `APPROVED`.
   - Update bảng `review_eligibilities` -> `status = 'REVIEWED'`.
   - Đẩy 1 Event mới `REVIEW_APPROVED` vào bảng Outbox để châm ngòi Aggregate Job (Gửi kèm `has_media` và `rating`).
4. Nếu `is_safe = false` (Status mới = `REJECTED`):
   - Update bảng `reviews` -> `REJECTED` (Và lưu lại `moderation_reason` AI trả về).
   - Truy vấn mức `attempt_count` của user trong bảng `review_eligibilities`.
   - Nếu `attempt_count == 1` (Mới nộp lần 1, tính theo code default ++): Update `review_eligibilities(status = 'REJECTED_RETRY_PENDING')`. Đẩy Outbox Event `REVIEW_REJECTED` kẹp thêm `{can_retry: true}`.
   - Nếu `attempt_count >= 2`: Update `review_eligibilities(status = 'REJECTED_FINAL')`. Đẩy Outbox `REVIEW_REJECTED` với `{can_retry: false}`. Qua đó tước và khóa vĩnh viễn quyền Review của người dùng đối với cụm phân loại Sản phẩm/Lô hàng này.
5. Update chốt hạ `UPDATE ai_moderation_idempotency SET processing_status = 'COMPLETED'` và Commit Transaction cuối cùng. Trả `ACK` cứng cho Kafka.

*(Ghi chú: Nếu hệ thống ghi nhận có quá nhiều lượt Report, Admin duyệt (Hậu kiểm) sẽ trực tiếp Update `status = 'HIDDEN_BY_REPORT'` cho review, ĐỒNG THỜI Update `review_eligibilities(status = 'REJECTED_FINAL')` để chặn đứng khách hàng này được review lại (kể cả khi attempt_count=1) và push bảng Outbox Event `REVIEW_HIDDEN` vào Kafka mà KHÔNG lội qua Worker này).*


## 4. Rating Aggregator Consumer (Cập nhật Bộ Đếm)
**Loại hình:** Nội bộ hoặc Kafka Consumer. (Có thể gom chung vào Kafka Topic `t.review.events` hoặc chạy Goroutine nhận Local Channel).
**Sứ mệnh:** Tính toán lại điểm số trung bình của Sản Phẩm thật nhanh mà không block luồng submit review. Worker phải đón được cả luồng tăng điểm (Approve) và hạ điểm (Reject / Hidden).

**Luồng xử lý (Hỗ trợ Data Cập nhật Thêm / Bớt):**
1. Lắng nghe các Event: `REVIEW_APPROVED`, `REVIEW_HIDDEN`, `REVIEW_REJECTED`.
  2. **Kiểm tra nghiệp vụ cơ bản & Định Tuyến Idempotency (Sửa lỗi Race Condition):**
     - Mở Transaction PostgreSQL (`BEGIN`).
     - Ghi nhận `event_id` vào bảng `rating_aggregator_idempotency(event_id PK)` bằng cờ hiệu `INSERT ... ON CONFLICT DO NOTHING`. Nếu trả về 0 (duplicate), lập tức COMMIT và BỎ QUA event. (Điều này chặn đứng lỗi Worker Crash / Rebalance Kafka gây cộng điểm 2 lần đẩy `total_score` lên trời).
   - Nếu Data tới từ `REVIEW_REJECTED` hay `REVIEW_HIDDEN` mà `previous_status` đang *không phải* là `APPROVED`, thì BỎ QUA (Nghĩa là nó chưa bao giờ được cộng vào Database, không có gì để trừ).
3. Thi công phép toán SQL Database (PostgreSQL):
   Sử dụng **Raw SQL Update Atomic** để khóa Row kết hợp với cú pháp `UPSERT` tránh lỗi im lặng khi Review đầu tiên xuất hiện. Đoạn worker tuyệt đối không tự tính toán rating ngoài Code mà đẩy thẳng vào một Transaction gồm 2 lệnh cho bảng Product và bảng SKU:
   - **Tăng (Cộng) Điểm:** Khi nhận `REVIEW_APPROVED`.
     ```sql
     -- Query 1: Upsert cho product_rating_aggregates
     INSERT INTO product_rating_aggregates (product_id, average_rating, total_reviews, total_score, total_1_star, total_2_star, total_3_star, total_4_star, total_5_star, total_with_media, updated_at)
     VALUES ($product_id, $new_rating, 1, $new_rating, CASE WHEN $new_rating=1 THEN 1 ELSE 0 END, CASE WHEN $new_rating=2 THEN 1 ELSE 0 END, CASE WHEN $new_rating=3 THEN 1 ELSE 0 END, CASE WHEN $new_rating=4 THEN 1 ELSE 0 END, CASE WHEN $new_rating=5 THEN 1 ELSE 0 END, $media_count, NOW())
     ON CONFLICT (product_id) DO UPDATE SET
         total_1_star = product_rating_aggregates.total_1_star + CASE WHEN $new_rating = 1 THEN 1 ELSE 0 END,
         total_2_star = product_rating_aggregates.total_2_star + CASE WHEN $new_rating = 2 THEN 1 ELSE 0 END,
         total_3_star = product_rating_aggregates.total_3_star + CASE WHEN $new_rating = 3 THEN 1 ELSE 0 END,
         total_4_star = product_rating_aggregates.total_4_star + CASE WHEN $new_rating = 4 THEN 1 ELSE 0 END,
         total_5_star = product_rating_aggregates.total_5_star + CASE WHEN $new_rating = 5 THEN 1 ELSE 0 END,
         total_reviews = product_rating_aggregates.total_reviews + 1,
         total_score = product_rating_aggregates.total_score + $new_rating,
         total_with_media = product_rating_aggregates.total_with_media + CASE WHEN $has_media THEN 1 ELSE 0 END,
         average_rating = (product_rating_aggregates.total_score + $new_rating)::DECIMAL / (product_rating_aggregates.total_reviews + 1),
         updated_at = NOW();

     -- Query 2: Upsert cho sku_rating_aggregates
     INSERT INTO sku_rating_aggregates (product_id, sku_id, average_rating, total_reviews, total_score, total_1_star, total_2_star, total_3_star, total_4_star, total_5_star, total_with_media, updated_at)
     VALUES ($product_id, $sku_id, $new_rating, 1, $new_rating, CASE WHEN $new_rating=1 THEN 1 ELSE 0 END, CASE WHEN $new_rating=2 THEN 1 ELSE 0 END, CASE WHEN $new_rating=3 THEN 1 ELSE 0 END, CASE WHEN $new_rating=4 THEN 1 ELSE 0 END, CASE WHEN $new_rating=5 THEN 1 ELSE 0 END, $media_count, NOW())
     ON CONFLICT (product_id, sku_id) DO UPDATE SET
     -- logic update tương tự như Query 1 nhưng áp dụng cho sku_rating_aggregates...
     ```
   - **Giảm (Trừ) Điểm:** Khi nhận `REVIEW_HIDDEN` hoặc `REVIEW_REJECTED` (có `previous_status='APPROVED'`).
     - Thực thi câu lệnh UPDATE trừ đi ngược lại trên cả 2 khối bảng, lưu ý phòng tránh lỗi chia cho 0:
       ```sql
       -- Trừ cho bảng Product
       UPDATE product_rating_aggregates
       SET total_1_star = GREATEST(total_1_star - CASE WHEN $old_rating = 1 THEN 1 ELSE 0 END, 0),
           total_2_star = GREATEST(total_2_star - CASE WHEN $old_rating = 2 THEN 1 ELSE 0 END, 0),
           total_3_star = GREATEST(total_3_star - CASE WHEN $old_rating = 3 THEN 1 ELSE 0 END, 0),
           total_4_star = GREATEST(total_4_star - CASE WHEN $old_rating = 4 THEN 1 ELSE 0 END, 0),
           total_5_star = GREATEST(total_5_star - CASE WHEN $old_rating = 5 THEN 1 ELSE 0 END, 0),
           total_reviews = GREATEST(total_reviews - 1, 0),
           total_score = GREATEST(total_score - $old_rating, 0),
           total_with_media = GREATEST(total_with_media - CASE WHEN $has_media THEN 1 ELSE 0 END, 0),
           average_rating = CASE WHEN (total_reviews - 1) <= 0 THEN 0 ELSE (total_score - $old_rating)::DECIMAL / (total_reviews - 1) END,
           updated_at = NOW()
       WHERE product_id = $product_id AND total_reviews > 0;

       -- Cần chạy truy vấn Trừ độc lập tương tự cho bảng sku_rating_aggregates
       ```
4. Cuối cùng, thực hiện Update L2 Cache Redis bằng logic **Soft Invalidation** (Không xoá bằng `DEL` mà chủ động nhúng Data để tránh Cache Stampede):
   - Thay vì Cache-Evict phá sập request của Client tiếp theo, Worker ngay sau Transaction Commit thành công sẽ đẩy song song lệnh SET (ghi đè kết quả vừa query ra) thẳng vào chìa khoá `review:prod:agg:{product_id}` và `review:sku:agg:{product_id}:{sku_id}` hiện tại đang tồn tại trước khi nó hết hạn (kèm tái khởi tạo Time-To-Live).
5. Push thêm Record `REVIEW_METRICS_UPDATED` vào Outbox để ElasticSearch bên Search Service cập nhật điểm hiển thị nhanh của Product Listing Page.

## 5. Helpful Votes Sync Worker (Cron Job dồn Like)
**Loại hình:** Lịch biểu chạy `1 giờ / Lần`.
**Sứ mệnh:** Đồng bộ lượt Hữu Ích (Helpful/Like) thông qua bộ đếm Delta từ Redis vào Database Persistent. Xử lý triệt để bài toán Crash Recovery và Race Condition.

**Luồng hoạt động Batch Update (Sử dụng luồng Processing Hash):**
1. **Kiểm tra Crash Recovery:** Khởi đầu chu kỳ, Worker lập tức ưu tiên gọi `HGETALL review:processing_deltas` để lấy toàn bộ dữ liệu đang mắc kẹt chưa kịp đồng bộ (Nếu ca làm việc trước bị Crash). Nếu có dữ liệu, nhảy thẳng sang bước 4 để cứu hộ. Nếu không có đâm sang bước 2.
2. **Kéo dữ liệu Delta (Atomic Lua Script):** Nếu không có rác, Worker quét danh sách ID các review vừa có thay đổi Like trong 1 giờ qua vào khu vực đang xử lý: 
   - Quét từ `SMEMBERS review:dirty_delta_keys`.
   - Với mỗi mục, lấy `GET review:{review_id}:delta` và chuyển vào `HSET review:processing_deltas {id} {delta}`, sau đó gọi `DEL` trên key delta cũ và `SREM` khỏi dirty. Hoạt động này được gói bằng LUA Script để đảm bảo Atomic trong Redis.
3. Nếu danh sách thu về trống rỗng, kết thúc Job.
4. Mở Transaction. Phóng câu lệnh Batch Update SQL vào PostgreSQL Database với lệnh cộng dồn an toàn từ toàn bộ Hash `processing_deltas`:
   - `UPDATE reviews SET helpful_count = GREATEST(helpful_count + $delta, 0) WHERE id IN (...)`
   - Commit Transaction DB.
5. Sau khi Update DB thành công 100%, Worker mới gọi Pipeline `HDEL review:processing_deltas {id1} {id2}...` để dọn dẹp các ID đã đồng bộ xong.
6. Đảm nhiệm Update Caching L1/L2 Paging nếu danh sách thuộc Trang 1.

## 6. L1 Cache Invalidator Consumer (Tránh Hiển Thị Sai Trang Đánh Giá)
**Loại hình:** Pub-sub In-memory Consumer trên các Gateway/Application Pods.
**Sứ mệnh:** Lắng nghe Event để tự xóa Cache RAM Caffeine (L1) đối với Trang Danh sách review hoặc Tổng hợp review cho chính Pod Web Server đó, đảm bảo 0% Blind Window.
**Cảnh báo Kiến trúc:** TUYỆT ĐỐI KHÔNG dùng cơ chế Consumer Group tĩnh của Kafka cho Worker này nếu bạn deploy nhiều Pods trên K8s (vì Kafka Load Balancer sẽ chỉ định 1 Pod nhận tin). Cần sử dụng kiến trúc **Broadcast**:
- *Cách 1:* Kafka với mỗi Pod sinh ra một UUID ngẫu nhiên làm `group.id` lúc khởi động.
- *Cách 2 (Khuyến nghị):* Dùng trực tiếp **Redis Pub/Sub** bắn lệnh xóa L1 đính kèm Message Payload qua một channel chung thay vì qua Kafka.

## 7. Profile Snapshot Updater (Consumer đồng bộ Tên User - Rời rạc)
**Loại hình:** Background Job/Kafka Consumer cấu hình Queue rảnh rỗi (Low Priority).
**Consume Topic:** `t.user.events`
**Sứ mệnh:** Do Review Service dùng cấu trúc Phi chuẩn hóa lưu cứng `user_name` để tăng tốc đọc, khi User đổi tên (nghiệp vụ Avatar bị loại bỏ để đơn giản hóa hệ thống), cần cập nhật đè chậm vào các review cổ để giao diện khỏi thấy tên cũ.
**Luồng xử lý:** Chạy ngầm túc tắc cấu trúc lệnh SQL `UPDATE reviews SET user_name = ? WHERE user_id = ?`. Lưu ý trên Database đã thiết lập sẵn `B-Tree Index(user_id)` để ngăn chặn tình trạng Table Scan / Table Lock làm sập db trong quá trình quét.

## 8. Reconciliation Cronjob (Quét dọn kẹt trạng thái)
**Loại hình:** Lịch biểu chạy `15 phút / Lần`.
**Sứ mệnh:** Xử lý tình trạng kẹt quyền đánh giá ở trạng thái `PROCESSING` do Message Broker (Kafka) hoặc AI Service bị sập/đứt kết nối, giúp khách hàng có thể gửi lại form.
**Luồng xử lý:** 
Chạy script tự động Update trả lại trạng thái `PENDING` cho các Record bị kẹt quá 30 phút.
```sql
UPDATE review_eligibilities 
SET status = 'PENDING' 
WHERE status = 'PROCESSING' 
AND updated_at < NOW() - INTERVAL '30 minutes';
```
