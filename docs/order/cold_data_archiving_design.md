# Order Service - Cold Data Archiving Design

**Liên kết nhanh (Related Documents):**
*   [Database Schema](order_db_design.md)
*   [Events Structure](order_events.md)
*   [Workers](order_workers.md)
*   [CQRS Elasticsearch Design](cqrs_elasticsearch_design.md)

---

Tài liệu này mô tả chiến lược lưu trữ dữ liệu đơn hàng cũ (cold data) để giảm tải OLTP.

## 1. Mục tiêu

- Giảm kích thước bảng nóng trong PostgreSQL.
- Giữ truy vấn nghiệp vụ hiện tại nhanh và ổn định.
- Đảm bảo truy xuất lịch sử phục vụ đối soát/audit.


## 2. Kiến trúc lưu trữ

- Nguồn nóng: PostgreSQL OLTP.
- Lưu trữ lạnh: Object Storage (S3) hoặc Data Warehouse.
- Metadata lookup: bảng chỉ mục archive trong PostgreSQL.

## 3. Quy trình archive

1. Hệ thống không bốc toàn bộ dữ liệu ra cùng một lúc để tránh làm treo Database. CronJob sẽ dùng một câu lệnh SQL để quét và gom một lô nhỏ (khoảng 10000 đơn hàng mỗi lần).
    Điều kiện để một đơn hàng bị "bế" đi là:
    - Trạng thái đã chốt sổ: COMPLETED, CANCELLED, hoặc REFUNDED.
    - Đã "đóng bụi" đủ lâu: Thời gian cập nhật cuối cùng (updated_at) vượt qua một ngưỡng nhất định, ví dụ như 6 tháng (180 ngày).
2. Trong PostgreSQL, dữ liệu của một đơn hàng nằm rải rác ở 4 bảng: orders (Thông tin chung), order_parcels (Các kiện hàng), order_items (Chi tiết món hàng) và order_promotions (Khuyến mãi).
    Hệ thống sẽ gom (Denormalize) tất cả các mảnh ghép này lại thành một Snapshot duy nhất.
    Sau đó, nó dịch (Serialize) cụm dữ liệu này sang định dạng JSON hoặc Parquet. File Parquet đặc biệt hữu ích cho dữ liệu lạnh vì nó nén dung lượng cực tốt và hỗ trợ truy vấn phân tích (Data Warehouse) rất nhanh.
3. Tải lên và Xác thực (Upload & Verify)
    Hệ thống tải file Parquet/JSON đó lên Object Storage (như Amazon S3).
    Bước sinh tử: Sau khi upload, hệ thống BẮT BUỘC phải thực hiện một phép kiểm tra toàn vẹn dữ liệu (Xác thực Checksum). Bước này để chắc chắn rằng file trên S3 giống y hệt dữ liệu dưới DB, không bị rớt mạng làm hỏng file giữa chừng. Nếu checksum không khớp, hệ thống sẽ tự động retry upload lại cho đến khi thành công.
4. Dọn dẹp và Chốt sổ (Hard Delete & Cascade)
    Chỉ khi bước 3 báo thành công, hệ thống mới quay lại PostgreSQL để làm các thao tác dọn dẹp:
    - Hệ thống tiến hành xóa vĩnh viễn (Hard Delete) đơn hàng đó khỏi bảng orders.
    - Nhờ thiết lập khóa ngoại CASCADE, khi Master Order bị xóa, PostgreSQL sẽ tự động quét sạch các dòng dữ liệu rác ăn theo ở bảng order_parcels, order_items và order_promotions.
    Tùy thuộc vào chính sách lưu giữ, hệ thống cũng có thể giữ lại một bảng chỉ mục (metadata lookup) và đánh dấu `archived=true` để làm dấu.


## 4. Quy trình phục hồi truy vấn

- Khi Frontend gọi API lấy chi tiết đơn hàng, hệ thống luôn ưu tiên tìm trong Database nóng trước.
- Nếu không thấy, nhưng phát hiện đơn này có cờ archived=true (hoặc tra thấy ID nằm trong dải thời gian dữ liệu lạnh), API sẽ rẽ nhánh đi qua một Adapter đọc riêng (Read Adapter). Adapter này sẽ chọc thẳng lên S3, đọc file Parquet, dịch ngược lại thành JSON và trả về cho màn hình điện thoại của khách hàng. Quá trình này có thể mất vài giây, nhưng vì đây là dữ liệu lịch sử nên vẫn chấp nhận được.

## 5. An toàn dữ liệu

- Idempotent theo Batch ID: Mỗi lần chạy gom lô sẽ có một archive_batch_id duy nhất. Nếu Job bị đứt gánh giữa chừng và chạy lại, nó sẽ không tạo ra các file trùng lặp trên S3
- Replay Job: Có cơ chế cho phép chạy lại (replay) đối với những batch bị thất bại.
- Theo dõi số bản ghi archive/delete bằng đối soát tự động.
