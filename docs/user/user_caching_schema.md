# User Service - Caching Schema Design

**Liên kết nhanh:**
*   [Workflows (API & Data Flow)](user_workflows.md)
*   [Caching Schema (Redis)](user_caching_schema.md)
*   [Database Schema](user_db_design.md)
*   [Events Structure & Kafka Topics](user_events.md)

---
Tài liệu này mô tả chiến lược bộ đệm (Caching Plan) của User Service. Do Profile của người dùng thường được rất nhiều Service gọi đến với tần suất (thông lượng đọc) lên tới hàng chục nghìn TPS (như API Gateway gắn Header, Chat Service định danh người dùng), nên việc cấu hình caching bài bản là cực kì quan trọng. Trong thiết kế này, chúng ta sử dụng kiến trúc L2 Cache (Redis).

## 1. Các cấp độ Caching
- **L2 Cache (Redis Cluster Global):** Lưu trữ toàn cục trên Memory. Độ trễ 1-2ms. Làm lớp khiên chắn rác trước khi Query tuột xuống Database.

## 2. Chuỗi Khóa (Redis Keys & TTL)

Thông tin Profile sẽ chia Keys rõ ràng thay vì gộp chung một cục nhằm tránh phình to kích cỡ Byte đẩy cho băng thông Redis.

| Key Pattern | Cấu Trúc Data | TTL | Miêu tả (Description) |
| :--- | :--- | :--- | :--- |
| `user:caches:profile:{id}` | `String JSON` | 2 Ngày | Lưu thông tin cơ bản: Tên, Phone Number, Ngày sinh|
| `user:caches:addresses:{user_id}` | `String JSON` (Mảng List) | 12 Giờ | Danh sách Full của tất cả Địa chỉ phục vụ trang Checkout và Info User. |
| `user:caches:preferences:{user_id}` | `String JSON` | 1 Tuần | Tùy chọn quyền riêng tư, tiếng ngôn ngữ, email marketing... Ít khi sửa đổi nhất. |

## 3. Chính sách Xóa Và Đồng Bộ (Invalidation Strategy)

Với mô hình Cache Aside:

1.  Client gửi yêu cầu `PUT /api/v1/users/me/profile` (Thay đổi Avatar URL mới).
2.  **User Service (Pod A)** xử lý việc thay Avatar vào DB Postgres. Transaction Commit OK.
3.  Pod A gọi lệnh hạ sát Cache L2 Redis: `DEL user:caches:profile:{uuid}` (double-delete trước và sau commit nếu cần).
4.  *Kết quả*: Khi Client F5 trang web và đi vào lấy ảnh đại diện, rớt tìm xuống L2 Redis Miss, xuống Database tóm được data MỚI -> Chép ngược lại vào Redis.

*Ghi chú thêm: Logic tương tự (DEL Redis L2) được áp dụng bắt buộc cho cả nhánh dữ liệu **Addresses** và **Preferences**.*

## 4. Chống Xuyên Thủng Lưới (Cache Penetration / Thundering Herd)

1. **Khách hàng Cố Tính Trộm ID Không tồn Tại**: 
    Hacker truyền UUID giả liên tục. L2 Miss, Database Miss. Tạo vòng lặp DoS vỡ ống DB RDBMS. 
    -> **Phương thuốc**: Áp dụng cắm cờ `Null Value Cache`. Profile tìm không ra sẽ nhét 1 JSON Rỗng Cắm cờ "Not Found" vô Redis có thời hạn chết (TTL = 15 phút) ngay lập tức dập tắt cú Query Database ở đợt thứ 2.
2. **Danh sách rỗng và Preferences chưa khởi tạo**:
    * Với **Addresses**, nếu DB trả về danh sách rỗng, vẫn cache một mảng `[]` với TTL ngắn để tránh cache miss lặp lại.
    * Với **Preferences**, nếu chưa có bản ghi, cache một object mặc định (hoặc sentinel) để tránh xuyên thủng.
3. **Kẹp Ổ Khóa Singleflight (Concurrency Guard):**
    Dùng thư viện hoặc cơ chế ngắt Singleflight của Go / Redisson để ép 50 Call song song trùng 1 ID UUID phải Wait lẫn nhau. Chỉ một luồng xuyên mạng chọc xuống Redis L2/DB, 49 thằng còn lại sẽ xài ké luồng đi cống nạp Data của gã số một.
