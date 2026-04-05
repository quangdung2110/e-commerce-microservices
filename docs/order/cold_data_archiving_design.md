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

## 2. Tiêu chí dữ liệu lạnh

- Đơn hàng trạng thái kết thúc (`COMPLETED`, `CANCELLED`, `REFUNDED`).
- Tuổi dữ liệu vượt ngưỡng (ví dụ 180 ngày).

## 3. Kiến trúc lưu trữ

- Nguồn nóng: PostgreSQL OLTP.
- Lưu trữ lạnh: Object Storage (Parquet) hoặc Data Warehouse.
- Metadata lookup: bảng chỉ mục archive trong PostgreSQL.

## 4. Quy trình archive

1. Batch job chọn dữ liệu đủ điều kiện theo window nhỏ.
2. Serialize snapshot order + parcel + item.
3. Ghi vào kho lạnh, xác thực checksum.
4. Đánh dấu archived trong DB nóng.
5. Xóa/partition drop theo chính sách retention.

## 5. Quy trình phục hồi truy vấn

- API ưu tiên đọc DB nóng.
- Nếu không có dữ liệu nóng nhưng có `archived=true`, trả về dữ liệu từ kho lạnh qua adapter đọc riêng.

## 6. An toàn dữ liệu

- Idempotent theo `archive_batch_id`.
- Có replay job cho batch thất bại.
- Theo dõi số bản ghi archive/delete bằng đối soát tự động.
