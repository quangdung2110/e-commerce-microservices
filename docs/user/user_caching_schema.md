# User Service - Caching Schema Design

**Liên kết nhanh:**
*   [Workflows (API & Data Flow)](user_workflows.md)
*   [Caching Schema (Redis)](user_caching_schema.md)
*   [Database Schema](user_db_design.md)
*   [Events Structure & Kafka Topics](user_events.md)

---
Tài liệu này mô tả chiến lược bộ đệm (Caching Plan) của User Service. **Hiện tại, hệ thống chỉ áp dụng caching cho danh sách địa chỉ** nhằm tối ưu tốc độ cho luồng Checkout và giảm tải cho Database.

> **Lưu ý:** Profile và Preferences được đọc trực tiếp từ Database mà không qua cache vì tần suất truy cập thấp và không mang lại lợi ích hiệu năng đáng kể.

## 1. Cấp độ Caching
- **L2 Cache (Redis Cluster Global):** Lưu trữ toàn cục trên Memory. Độ trễ 1-2ms. Làm lớp khiên chắn rác trước khi Query tuột xuống Database cho các dữ liệu có tần suất truy cập cao nhưng ít thay đổi như địa chỉ.

## 2. Chuỗi Khóa (Redis Keys & TTL)

| Key Pattern | Cấu Trúc Data | TTL | Miêu tả (Description) |
| :--- | :--- | :--- | :--- |
| `user:caches:addresses:{user_id}` | `String JSON` (Mảng List) | 12 Giờ | Danh sách Full của tất cả Địa chỉ phục vụ trang Checkout và Info User. |

## 3. Chính sách Xóa Và Đồng Bộ (Invalidation Strategy)

Với mô hình Cache Aside:

1.  Client gửi yêu cầu thay đổi địa chỉ (Thêm/Sửa/Xóa/Set Default).
2.  **User Service** xử lý thay đổi vào DB Postgres. Transaction Commit OK.
3.  Server gọi lệnh xóa Cache L2 Redis: `DEL user:caches:addresses:{user_id}`.
4.  *Kết quả*: Khi Client truy cập lại danh sách địa chỉ, hệ thống gặp Cache Miss -> Query Database lấy data MỚI -> Chép ngược lại vào Redis.

## 4. Chống Xuyên Thủng Lưới (Cache Penetration / Thundering Herd)

1. **Khách hàng chưa có địa chỉ**: 
    Nếu DB trả về danh sách rỗng, hệ thống vẫn cache một mảng `[]` với TTL ngắn (ví dụ 15-30 phút) để tránh việc liên tục truy vấn Database khi người dùng chưa khai báo địa chỉ.

