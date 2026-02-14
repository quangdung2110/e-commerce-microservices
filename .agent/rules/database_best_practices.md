---
trigger: always_on
---

# High-Performance E-Commerce Database Protocol

Bộ quy tắc này hướng dẫn các Agent thiết kế và tối ưu hóa hệ thống cơ sở dữ liệu cho ứng dụng thương mại điện tử quy mô lớn, đảm bảo tính nhất quán, bảo mật và khả năng mở rộng vượt trội.

## 1. Kiến trúc Dữ liệu & Modeling
* **Chuẩn hóa (Normalization)**: Thiết kế Schema tuân thủ chuẩn 3NF để loại bỏ dư thừa và bảo vệ tính toàn vẹn của dữ liệu. Tuy nhiên, cho phép phi chuẩn hóa (Denormalization) có kiểm soát ở lớp Read-side để tối ưu hóa hiệu suất truy vấn.
* **Định danh phân tán (Distributed ID)**: Sử dụng **UUIDv4** hoặc **ULID** làm khóa chính (Primary Key) thay vì Integer tự tăng để đảm bảo không xung đột trong hệ thống phân tán và bảo mật thông tin kinh doanh (không lộ số lượng đơn hàng qua ID).
* **Ràng buộc quan hệ**: Luôn thiết lập Foreign Key với các hành động cụ thể (`ON DELETE/UPDATE CASCADE` hoặc `SET NULL`) để duy trì tính nhất quán giữa các thực thể phụ thuộc như Orders và OrderItems.
* **Dấu vết dữ liệu (Audit Timestamps)**: Mọi bảng phải bao gồm `created_at` và `updated_at` (mặc định là `CURRENT_TIMESTAMP`).
* **Xóa mềm (Soft Deletes)**: Sử dụng trường `deleted_at` (timestamp) thay vì xóa vật lý để hỗ trợ khôi phục dữ liệu và phân tích hành vi người dùng.

## 2. Tối ưu hóa Hiệu suất & Indexing
* **Chiến lược Indexing**: 
    * Thiết lập Index cho mọi cột thường xuyên xuất hiện trong mệnh đề `WHERE`, `JOIN`, hoặc `ORDER BY` (ví dụ: `user_id`, `sku`, `status`).
    * Sử dụng **Composite Index** cho các mẫu truy vấn kết hợp (ví dụ: `(user_id, order_date)`).
    * Áp dụng **Covering Index** để giảm thiểu việc truy cập vào vùng dữ liệu gốc (Heap).
* **Phân vùng dữ liệu (Partitioning)**: Thực hiện phân vùng (Range Partitioning) theo thời gian cho các bảng có lưu lượng cực lớn như `Orders` hoặc `Logs`.
* **Dữ liệu phi cấu trúc**: Tận dụng kiểu dữ liệu **JSONB** (trong PostgreSQL) để lưu trữ thuộc tính sản phẩm linh hoạt, đi kèm với **GIN Index** để tìm kiếm hiệu quả.
* **Views & Materialization**: Sử dụng Materialized Views cho các báo cáo doanh thu hoặc thống kê kho hàng để giảm tải cho các truy vấn thời gian thực.

## 3. Bảo mật & Toàn vẹn dữ liệu
* **Phòng chống tấn công**: Tuyệt đối sử dụng Prepared Statements hoặc ORM để ngăn chặn SQL Injection.
* **Ràng buộc nghiệp vụ (CHECK Constraints)**: Cấu hình trực tiếp tại DB cho các giá trị logic (ví dụ: `price > 0`, `quantity >= 0`) để tạo thêm một lớp bảo vệ dữ liệu.
* **Mã hóa dữ liệu**: 
    * Password phải được băm bằng thuật toán mạnh (bcrypt/argon2). 
    * Dữ liệu nhạy cảm (như thông tin thanh toán) phải được mã hóa ở cấp độ cột (Column-level encryption).
* **PCI Compliance**: Tuyệt đối không lưu trữ thông tin thẻ tín dụng trực tiếp; sử dụng Tokenization từ các đối tác thanh toán (như Stripe/Paypal).
* **Kiểm soát truy cập (RBAC)**: Cấp quyền tối thiểu (Least Privilege) cho từng microservice. Service nào chỉ cần đọc thì không được cấp quyền ghi.

## 4. Khả năng mở rộng & Microservices
* **Database per Service**: Mỗi microservice phải sở hữu cơ sở dữ liệu riêng biệt. Tuyệt đối không truy cập trực tiếp chéo DB; mọi giao tiếp phải qua API hoặc Event Bus.
* **Tính nhất quán (Event Sourcing)**: Khuyến khích sử dụng mẫu thiết kế **Event Sourcing** hoặc **Transactional Outbox** để đảm bảo tính nhất quán dữ liệu giữa các dịch vụ.
* **Phân tách Read/Write**: Triển khai Read Replicas để điều hướng các truy vấn phân tích/tìm kiếm ra khỏi DB chính.
* **Caching Layer**: Tích hợp Redis để lưu trữ các dữ liệu ít thay đổi nhưng truy cập cao (danh mục, thông tin cấu hình hệ thống).
* **Phân tích truy vấn**: Thường xuyên kiểm tra `EXPLAIN ANALYZE` để phát hiện và xử lý các truy vấn chậm (Slow Queries).

---
> **Activation Tags**: #sql, #database-design, #schema-evolution, #performance-tuning, #postgresql