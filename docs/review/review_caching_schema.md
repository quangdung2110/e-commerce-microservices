# Review Service - Caching Schema

**Liên kết nhanh (Related Documents):**
*   [Database Schema (Cold Store)](review_db_schema.md)
*   [Events Structure](review_events.md)
*   [Background Workers & Jobs](review_workers.md)

Đặc thù của Review Service là **Read-Heavy** (Tải đọc cực cao) vì người dùng nào vào xem chi tiết sản phẩm cũng sẽ kéo xuống xem đánh giá. Tuy nhiên dữ liệu nội dung đánh giá của một sản phẩm ít khi thay đổi liên tục một cách nhanh chóng (chỉ có lượt Like là thay đổi). 
Việc sử dụng Caching đóng vai trò "sống còn" để bảo vệ CSDL (PostgreSQL). 

Kiến trúc dưới đây chia thành 3 lớp Cache chiến lược:

## 1. Cụm Cache 1: Tổng Hợp Điểm Số Sản Phẩm & Phân Loại (Aggregates Cache)
Khi người dùng mở trang sản phẩm, phần tử Header hiển thị ngay là Điểm trung bình (Vd: 4.9 Sao) và Phân loại thanh tiến trình 5 sao, 4 sao... Đây là dữ liệu được load đầu tiên. Khi bấm thẻ theo nhãn SKU trên header, gọi thêm thống kê lẻ.

- **Redis Key Pattern (Sản Phẩm):** `review:prod:agg:{product_id}`
- **Redis Key Pattern (Theo SKU):** `review:sku:agg:{product_id}:{sku_id}`
- **Data Type:** `String` (Chứa chuỗi JSON)
- **Ví dụ Payload:**
  ```json
  {
    "product_id": 9991238,
    "sku_id": "optional-color-red",
    "average_rating": 4.85,
    "total_reviews": 1250,
    "total_with_media": 300,
    "star_distribution": { "5": 1000, "4": 150, "3": 50, "2": 25, "1": 25 }
  }
  ```
- **TTL (Thời gian tồn tại):** 24 giờ.
- **Cơ chế Update / Invalidate:** Proactive Push (Write-Through). Nhằm chống lại hiện tượng Cache Stampede (Bão truy vấn đồng loạt đâm vào DB nếu bị Cache Miss), hệ thống sẽ không sử dụng lệnh `DEL` để hard invalidate cache. Thay vào đó, khi có Event làm thay đổi các bảng điểm của `product_rating_aggregates` hoặc `sku_rating_aggregates` (`REVIEW_APPROVED`, `REVIEW_HIDDEN`, `REVIEW_REJECTED_FROM_APPROVED`), **Background Worker xử lý Event sẽ tự đâm truy vấn vào DB để tính lại điểm, sau đó chủ động nạp JSON mới vào Redis** thông qua lệnh `SET`. Request từ hệ thống Load Web luôn Hit Cache 100%.

## 2. Cụm Cache 2: Trang Đánh Giá Đầu Tiên (First-Page Pagination Cache)
Theo số liệu hành vi, hơn 90% người dùng chỉ lướt xem danh sách nhận xét ở "Trang 1" để đưa ra quyết định mua hàng. Việc liên tục đập query lấy danh sách 10 mẫu đâm thẳng vào DB là tối kị. Để cho phép linh động xóa bộ lọc hay xếp trang, thiết kế sử dụng **Pattern Cache Versioning**.

- **Redis Key Pattern:** 
  - Version Key: `review:prod_ver:{product_id}` -> Lưu Integer ID phiên bản.
  - Mặc định: `review:prod:{product_id}:v:{version}:page:1:sort:{sort_type}`
  - Có lọc (Filter): `review:prod:{product_id}:v:{version}:filter:{filter_type}:page:1:sort:{sort_type}`
  *(Trong đó `filter_type` có thể là: `sku_{id}`, `rating_5`, `media_true`... và `sort_type` có thể là `newest` hoặc `top_helpful`)*.
- **Data Type:** `String` (Chứa Mảng JSON)
- **Nội dung Payload:** Array chứa 5 hoặc 10 Object Review với thông tin phi chuẩn hóa hiển thị trực tiếp (Tên khách hàng, Rating, Content, Thumbnail URL).
- **TTL:** 1 - 2 Giờ + Tính năng Add **Random Jitter**.
  *(Giải thích: Cộng thêm số giây ngẫu nhiên từ 1-300s vào TTL để tránh hiện tượng **Cache Stampede** - hiện tượng hàng ngàn request cùng miss cache và ào ạt đâm vào DB tìm Trang 1 vào cùng một tích tắc).*
- **Cơ chế Update & Phân Trang:** 
  - **Cache Invalidation:** Khi có bất kỳ Event nào làm vỡ mặt tiền (như `REVIEW_APPROVED`, `REVIEW_HIDDEN`, `REVIEW_REJECTED` khi gỡ, hay `SELLER_REPLY_CREATED`), hệ thống CHỈ CẦN gọi lệnh `INCR review:prod_ver:{product_id}`. Các key danh sách phiên bản cũ nằm lại Redis sẽ không ai truy cập nữa và tự động chết êm ái nhờ TTL. Cơ chế này chặn đứng triệt để nghẽn cổ chai nếu dùng `DEL / SCAN` trên Redis Cluster.
  - **Cursor Pagination:** Từ trang 2 trở đi đâm thẳng vào DB, nhưng BẮT BUỘC dùng Database Cursor Pagination thay vì `OFFSET/LIMIT` để tránh 100% rủi ro Pagination Drift (Lặp / Trôi đánh giá khi duyệt trang):
    - Khi Sort `newest`: Truy vấn bằng Time UUID `WHERE id < $last_id_v7`.
    - Khi Sort `top_helpful`: Vô cùng dễ trùng số lượt like, bắt buộc dùng khái niệm Tuple Cursor kép `WHERE (helpful_count, id) < ($last_likes, $last_id_v7)`.

## 3. Cụm Cache 3: Redis Bitmaps (Siêu Tiết Kiệm Trí Nhớ Chống Bão Like)
Khách hàng có tính "bầy đàn" và rất dễ nảy sinh hành vi spam đập liên tục nút Like vào 1 bài Review. Để vừa giải quyết bài toán chống Spam, vừa tối ưu hóa dung lượng lưu trữ (xóa bỏ hoàn toàn bảng `review_likes` dư thừa dưới Database), hệ thống sử dụng cấu trúc Redis Bitmaps.

Lưu ý: Giải pháp này yêu cầu `user_id` phải là số nguyên dương (`BIGINT`).

- **Key Pattern A - Quản lý Bit trạng thái Đã Vote:** `review:liked_bits:{review_id}`
  - **Data Type:** `Bitmap` (Bản chất là String thao tác theo bit)
  - **Mô tả:** Ánh xạ mỗi `user_id` (BIGINT) vào một bit (0 hoặc 1). 
  - **Kích thước:** 1 triệu người dùng vote cho 1 review chỉ tốn vỏn vẹn 125 KB RAM.
  - **Cơ Chế An Toàn Backup (Sửa lỗi Data Loss từ TTL):** Chấp nhận Eventual Consistency nhưng tuyệt đối không chấp nhận System Trade-off gây Data Loss (cho phép người dùng lạm dụng lặp Like liên tục sau 6 tháng do Bitmap bốc hơi). Cách giải quyết chuẩn kĩ thuật:
    - Tại tầng Application/Database, cấu hình quy tắc "Chặn mọi thao tác Like/Unlike đối với Review có `created_at` > 6 tháng".
    - Khi đó, Application sẽ Reject trực tiếp ở vòng ngoài.
    - Lúc này, ta MỚI ĐƯỢC PHÉP cài đặt **TTL = 6 tháng** trên các key Bitmap Redis để dọn dẹp RAM mà không sợ Race Condition do user spam vòng lặp mất dữ liệu. 
  - **Hành vi Nút Bấm (Xử lý Race Condition API):** 
    - Tuyệt đối không dùng `GETBIT` rồi gọi `SETBIT`, rồi mới gọi `INCR` ở Application Layer vì sẽ dính Race Condition. VD: 2 user spam click, 2 thread cùng gọi lệnh `SETBIT`, trả về 0, và cùng gọi `INCR` (Gây mất bộ đếm, "Nuốt like" bão traffic).
    - **Click Like & Giải quyết Race Condition:** Sử dụng 1 **Lệnh LUA Script độc nhất** bọc cả SETBIT và INCR vào 1 cục (Atomicity Level CSDL). Lua script check `SETBIT review:liked_bits:{review_id} {user_id} 1`. Chỉ khi trả về `0`, Lua mới tự động gọi tiếp lệnh `INCR review:{review_id}:delta` và `SADD review:dirty_delta_keys {review_id}` nội bộ trong Redis.
    - **Click Unlike:** Tương tự như trên, dùng một Lua script gom `SETBIT 0` và check kết quả, chỉ giảm trừ nếu kết quả về `1`: `DECR review:{review_id}:delta`. Đóng gói logic Atomic chống thất thoát hoàn toàn.

- **Key Pattern B - Tracking Delta Lượt Like:** `review:{review_id}:delta`
  - **Data Type:** `String Integer` (Bộ đếm INCR/DECR)
  - **Mô tả:** Chỉ lưu trữ lượng tăng/giảm (Delta) của Like từ thời điểm Worker gom số lần trước. Số Like thật được duy trì an toàn và cập nhật dần ở Database.


### Tiến trình Đồng bộ `helpful_count` xuống Database (Bảo đảm Crash Recovery & Chống Race Condition)
Tiến trình phải sử dụng vùng đệm (Processing Hash) để tránh Data Loss nếu Worker bị Crash giữa chừng (khi lấy đứt delta ra khỏi Redis mà chưa kịp lưu vào PostgreSQL):
1.  **Crash Recovery Check**: Đầu mỗi chu kỳ, Worker luôn kiểm tra Hash `review:processing_deltas` trước. Nếu có dữ liệu (HGETALL trả về keys), chứng tỏ ca làm việc trước bị crash hoặc chưa commit xong DB. Worker sẽ lấy lượng delta tồn đọng này để đi cập nhập DB ngay (Đi thẳng sang bước 3). Nếu không có, đi sang bước 2.
2.  **Đẩy vào Processing Hash (Atomic)**: Worker chạy một **Lua Script** để quét lấy list `review_id` từ Set `review:dirty_delta_keys`. Với mỗi ID, lấy giá trị delta (`GET`), đưa vào một Hash tạm thao tác (`HSET review:processing_deltas {id} {delta}`), sau đó xóa key delta cũ (`DEL`) và xóa ID khỏi Set dirty (`SREM`).
3.  **Cập nhật Database**: Đọc toàn bộ danh sách ID và số delta định biên từ vùng đệm (`HGETALL review:processing_deltas`), chạy lệnh Batch Update cộng dồn đè vào cột `helpful_count` của bảng `reviews` ở PostgreSQL (`UPDATE reviews SET helpful_count = GREATEST(helpful_count + $delta, 0)`).
4.  **Dọn dẹp**: **Chỉ khi và sau khi** câu lệnh SQL trả về thông báo thành công (Commit Transaction), Worker mới chạy Pipeline gọi lệnh `HDEL review:processing_deltas {id1} {id2}...` để dọn dẹp các delta đã xử lý an toàn.
5.  Hoàn tất tiến trình. DB không phải lưu trữ các bảng `review_likes` nối quan hệ tốn dung lượng nữa, và hệ thống miễn nhiễm với Worker Crash.

## Tóm Lược 

| Yêu Cầu | Giải Pháp Redis | Tác Dụng |
| :--- | :--- | :--- |
| Load Điểm Uy Tín Nhanh | Local DB (Caffeine) / Redis Strings | Giữ Trang sản phẩm nhạy & mượt |
| 10 Review Chưng Mặt Tiền | Json Array String có Jitter TTL | Bảo vệ máy chủ PostgreSQL tuyệt đối |
| Chống Lún DB Khi Bão Like | Bitmaps (`SETBIT`/`BITCOUNT`) | Chống spam triệt để, RAM siêu gọn (1 bit/User), tiết kiệm hàng GB dữ liệu vô nghĩa trên Postgres |
