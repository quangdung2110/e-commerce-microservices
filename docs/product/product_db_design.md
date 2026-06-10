# Product Service - Database Schema Design (Write Store)

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](product_workflow.md)
*   [Background Workers & Jobs](product_workers.md)
*   [Caching Schema (Redis)](product_caching_schema.md)
*   [Database Schema (Write Store)](product_db_design.md)
*   [State Machine & Lifecycle](product_state_machine.md)
*   [Events Structure & Kafka Topics](product_events.md)
*   [Architecture Recommendations](product_architecture_recommendations.md)

---
Tài liệu này mô tả thiết kế cơ sở dữ liệu PostgreSQL cho Product Service. Trong kiến trúc CQRS, database này đóng vai trò là **Write Store (Command Database - Source of Truth)**. Dữ liệu từ đây sẽ được phát qua sự kiện (Kafka) để đồng bộ sang Redis Cache và Elasticsearch (Search Service) phục vụ việc đọc.

## 1. Cấu Trúc Bảng (ERD & Entities)

Mô hình dữ liệu bao gồm các nhóm: Quản lý Danh mục (Catalog), Sản phẩm & Biến thể (Product & SKU), Tích hợp Giá Khuyến mãi (Markdown Integration), và các bảng hạ tầng (Infrastructure). 

### 1.1. Nhóm cấu hình Danh mục & Thuộc tính (Catalog)

*   `categories` (Bảng Danh mục phân cấp)
    *   `category_id`: VARCHAR(255) PRIMARY KEY.
    *   `parent_id`: VARCHAR(255) INDEX (Reference ảo đến `category_id` cha, cho phép tạo cây danh mục đệ quy).
    *   `name`: VARCHAR(255) (Tên danh mục, vd: Điện thoại, Quần áo).
    *   `slug`: VARCHAR(255) UNIQUE (Dùng cho SEO URL).
    *   `level`: INT (Cấp độ phân nhánh, vd: Thư mục gốc = 1).
    *   `is_leaf`: BOOLEAN (Đánh dấu có phải danh mục lá không, sản phẩm chỉ được gán vào danh mục lá).
    *   `created_at`: TIMESTAMPTZ.
    *   `updated_at`: TIMESTAMPTZ.

*   `attributes` (Bảng Thuộc tính định nghĩa sẵn)
    *   `attribute_id`: VARCHAR(255) PRIMARY KEY.
    *   `name`: VARCHAR(255) (Tên thuộc tính, vd: Màu sắc, Kích thước, RAM).
    *   `type`: VARCHAR(50) (Loại dữ liệu: TEXT, NUMBER, DROPDOWN).
    *   `created_at`: TIMESTAMPTZ.

*   `attribute_values` (Bảng Giá trị của thuộc tính - Dành cho loại DROPDOWN)
    *   `value_id`: VARCHAR(255) PRIMARY KEY.
    *   `attribute_id`: VARCHAR(255) INDEX (FK ảo tới `attributes`).
    *   `value`: VARCHAR(255) (Giá trị thực: Xanh, Đỏ, 8GB, 16GB).

*   `category_attributes` (Bảng Mapping Danh mục - Thuộc tính)
    *   `id`: VARCHAR(255) PRIMARY KEY.
    *   `category_id`: VARCHAR(255) INDEX.
    *   `attribute_id`: VARCHAR(255) INDEX.
    *   `is_required`: BOOLEAN (Bắt buộc phải có khi tạo sản phẩm thuộc danh mục này).
    *   `is_variant`: BOOLEAN (Thuộc tính này có dùng để tạo phân loại/SKU hay không. VD: Size quần áo -> True).

### 1.2. Nhóm Sản phẩm & Biến thể (Product & SKU)

*   `products` (Bảng Master Sản phẩm)
    *   `product_id`: VARCHAR(255) PRIMARY KEY.
    *   `category_id`: VARCHAR(255) INDEX.
    *   `name`: VARCHAR(255).
    *   `description`: TEXT.
    *   `status`: VARCHAR(50) DEFAULT 'DRAFT'. Quản lý bởi [State Machine](product_state_machine.md). (Các trạng thái: DRAFT, ACTIVE, HIDDEN.).
    *   `is_deleted`: BOOLEAN DEFAULT FALSE (Cờ Soft Delete).
    *   `admin_hidden`: BOOLEAN DEFAULT FALSE (Cờ Admin chủ động ẩn sản phẩm. Dùng để phân biệt giữa việc Admin ẩn thủ công và System tự động ẩn do hết hàng. Chỉ được set = TRUE khi Admin chủ động thực hiện lệnh HIDE. Khi System chạy AUTO_HIDE_OUT_OF_STOCK, cờ này giữ nguyên giá trị.)
    *   `deleted_at`: TIMESTAMPTZ NULL (Thời điểm bị xóa mềm).
    *   `attributes_json`: JSONB (Lưu trữ các giá trị thuộc tính dùng chung của toàn bộ sản phẩm, nhưng không phải variant. VD: Chất liệu, Xuất xứ). **Đây là dữ liệu thô (raw key-value), không phải token search.** Việc transform sang token `k:v` cho Elasticsearch thuộc trách nhiệm Search Worker.
    *   `created_at`: TIMESTAMPTZ.
    *   `updated_at`: TIMESTAMPTZ.

*   `product_skus` (Bảng Biến thể vật lý - Stock Keeping Unit)
    *   `sku_id`: VARCHAR(255) PRIMARY KEY.
    *   `product_id`: VARCHAR(255) INDEX (Tham chiếu tới `products`).
    *   `sku_code`: VARCHAR(255) UNIQUE (Mã định danh nội sản phẩm).
    *   `original_price`: DECIMAL(15,2) (Giá gốc / Giá niêm yết do Admin đặt).
    *   `promotional_price`: DECIMAL(15,2) NULL (Giá sau khi áp dụng Markdown Rule từ [Promotion Service](../promotion/promotion_db_design.md#21-markdown_rules-luật-giảm-giá-gạch-chéo). Khi NULL = không có chương trình giảm giá nào đang active. Xem [Workflow xử lý Markdown](product_workflow.md#1-workflow-xử-lý-markdown-event-từ-promotion-service)).
    *   `status`: VARCHAR(50) DEFAULT 'ACTIVE' (Trạng thái riêng của SKU: `ACTIVE`, `INACTIVE`, `OUT_OF_STOCK`. Cho phép quản lý lifecycle SKU độc lập với Product. VD: Product đang ACTIVE nhưng SKU Size XXL bị tạm ngưng bán. **Tên canonical trong event payload là `sku_status`** — đây là tên chuẩn dùng trong mọi API response và Kafka payload; trường DB giữ nguyên là `status` để tương thích implementation).
    *   `is_deleted`: BOOLEAN DEFAULT FALSE (Cờ xoá mềm riêng biệt cấp độ SKU).
    *   `deleted_at`: TIMESTAMPTZ NULL (Thời điểm SKU bị xóa mềm).
    *   `display_quantity`: INT DEFAULT 0 (Số lượng tồn kho **hiển thị** — đây là bản sao cached đồng bộ ở [Inventory Service](../inventory/inventory_db_design.md). **Source of Truth thực sự** về tồn kho nằm ở Inventory Service. Field này được cập nhật bởi [Inventory Sync Worker](product_workers.md#5-inventory-sync-worker-two-phase-lock-free-architecture).
    *   `last_inventory_sync_at`: BIGINT DEFAULT 0 (Lưu lại `event_timestamp` từ Kafka Event cuối cùng của Inventory Service để chặn lùi version do lỗi Out-of-order Kafka message).
    *   `variant_attributes_json`: JSONB (Cấu hình riêng của SKU này, vd: `{"color": "Xanh", "size": "M"}`). **Giữ nguyên dạng object tại Write Model; Search Worker sẽ chuyển đổi sang `variant_attributes` token array khi index.**
    *   `created_at`: TIMESTAMPTZ.
    *   `updated_at`: TIMESTAMPTZ.

> **Lưu ý quan trọng về Concurrency Control (Loại bỏ Optimistic Locking):**
> Bảng `products` và `product_skus` KHÔNG còn sử dụng cột `version` cho optimistic locking. Thay vào đó, hệ thống áp dụng:
> - **Pessimistic Locking (Database Row Lock)** cho các thao tác trong cùng Product Service: lock theo thứ tự **Product -> SKU** (`SELECT ... FOR NO KEY UPDATE` / `SELECT ... FOR UPDATE`) để đảm bảo atomicity và chống deadlock.
> - **Distributed Lock (Redis)** cho Markdown Worker khi nhiều instance worker cùng xử lý, tránh race condition trên cùng một product.
> - **Out-of-Order Guard cho Search Service** sử dụng `updated_at` timestamp (TIMESTAMPTZ) thay vì version number. Elasticsearch document guard so sánh `event.updated_at > current_doc.updated_at` để chặn event đến trễ.
    *   **Quy tắc định danh SKU**: Không ép định dạng `sku_id` phải nhúng `product_id` trong chuỗi mã nghiệp vụ. Point-read theo `sku_id` phải dựa vào index truy vấn trực tiếp và resolve `product_id` từ DB trước khi thao tác lock/cập nhật bảng cha.

*   `product_media` (Bảng Quản lý Hình ảnh/Video Sản phẩm)
    *   `media_id`: VARCHAR(255) PRIMARY KEY.
    *   `product_id`: VARCHAR(255) INDEX (Tất cả media đều thuộc về một Product).
    *   `sku_id`: VARCHAR(255) NULL INDEX (Nếu ảnh này là ảnh riêng biệt của 1 SKU cụ thể, ví dụ: Ảnh chụp chiếc váy lúc nó màu Đỏ. Nếu ảnh dùng chung cho sản phẩm, trường này để NULL).
    *   `media_type`: VARCHAR(50) (IMAGE, VIDEO).
    *   `url`: VARCHAR(255) (Đường dẫn gốc đến file ảnh/video lưu trên S3/Cloudinary).
    *   `thumbnail_url`: VARCHAR(255) NULL (Đường dẫn đến bản thu nhỏ của ảnh/video).
    *   `sort_order`: INT DEFAULT 0 (Thứ tự ưu tiên hiển thị trên UI).
    *   `created_at`: TIMESTAMPTZ.
    *   `updated_at`: TIMESTAMPTZ.

### 1.3. Nhóm Tích Hợp Giá Khuyến Mãi (Markdown Integration)

Khi Promotion Service phát event `MARKDOWN_CREATED` / `MARKDOWN_ENDED`, Product Service cần biết chính xác rule nào đang tác động lên SKU nào để:
- **Tính toán** `promotional_price` khi rule mới được tạo.
- **Reset** `promotional_price` về NULL khi rule hết hạn hoặc bị huỷ.

> **Tại sao cần bảng này?** Nếu chỉ lưu `promotional_price` trên `product_skus` mà không tracking rule_id, khi một rule kết thúc (event `MARKDOWN_ENDED`), Product Service sẽ không biết cần reset SKU nào. Bảng này chính là "sổ ghi nợ" cho Markdown lifecycle. Xem chi tiết luồng tại [Promotion DB Design - Markdown](../promotion/promotion_db_design.md#2-thiết-kế-giảm-giá-trực-tiếp-markdowns--price-slashes).

*   `product_markdown_applied` (Bảng theo dõi Markdown Rule đang Active trên SKU)
    *   `id`: VARCHAR(255) PRIMARY KEY.
    *   `product_id`: VARCHAR(255) INDEX (FK ảo tới `products`).
    *   `rule_id`: VARCHAR(255) INDEX NOT NULL (ID markdown rule từ Promotion Service, luôn lưu ở dạng string để đồng nhất contract liên service và hỗ trợ tương lai khi rule_id đổi sang UUID).
    *   `sku_id`: VARCHAR(255) INDEX (FK ảo tới `product_skus`). Admin thao tác thay đổi `original_price` lúc Rule đang chạy để tránh lỗi Data Drift khi Audit).
    *   `discount_type`: VARCHAR(50) (Loại giảm giá: PERCENTAGE, FIXED_AMOUNT).
    *   `discount_value`: DECIMAL(15,2) (Giá trị cấu hình giảm).
    *   `max_discount_amount`: DECIMAL(15,2) NULL (Giới hạn mức giảm tối đa trên mỗi sản phẩm - chỉ dùng khi PERCENTAGE).
    *   `calculated_price`: DECIMAL(15,2) NOT NULL (Giá đã giảm lưu nháp để check sai số nếu đổi công thức).
    *   `applied_at`: TIMESTAMPTZ.
    *   `expires_at`: TIMESTAMPTZ INDEX NOT NULL (Thời điểm hết hạn. Index này KHÔNG PHỤC VỤ Flow mua hàng chính, nó DÀNH RIÊNG cho [Markdown Expiry Cronjob(Self-Healing)](product_workers.md#6-markdown-expiry-cronjob-self-healing), hỗ trợ quét dọn dữ liệu hết hạn cực nhẹ.).
    *   **Unique Index (`rule_id`, `sku_id`)**: Đảm bảo một rule chỉ áp dụng 1 lần trên 1 SKU.


### 1.4. Nhóm Hạ tầng Cốt lõi (Infrastructure)

*   `product_outbox_events` (Bảng Ghi nhận Event Phát đi cho Outbox Pattern kết hợp Debezium)
    *   `event_id`: UUID PRIMARY KEY.
    *   `event_type`: VARCHAR(100) (VD: `PRODUCT_CREATED`, `PRODUCT_STATUS_CHANGED`, `SKU_PRICE_UPDATED`). Xem danh sách đầy đủ tại [Product Events](product_events.md).
    *   `aggregate_type`: VARCHAR(50) (VD: Product, ProductSku).
    *   `aggregate_id`: VARCHAR(255) (Bắt buộc theo `aggregate_type`: Product -> `product_id`, ProductSku -> `sku_id`, Category -> `category_id`).
    *   `partition_key`: VARCHAR(255) NOT NULL (Chứa `product_id` cho `t.product.events` hoặc `category_id` cho `t.category.events`). Được chèn vào bởi Application lúc Insert để Debezium định tuyến Kafka Message Partition Key nguyên bản, không cần tốn CPU parse JSON ở Connect. **Application-level validation bắt buộc trước INSERT:** kiểm tra `partition_key IS NOT NULL AND partition_key <> ''`. Ngoài ràng buộc `NOT NULL`, nên thêm `CHECK (LENGTH(partition_key) > 0)` để phòng ngừa edge case. Nếu quên truyền, Event rơi lung tung sang các Partition khác nhau làm trật tự đồng bộ (Ordering) của Elasticsearch bị phá vỡ hoàn toàn.
    *   `payload`: JSONB (Payload bắt buộc kèm `schema_version` ở top-level message envelope).
    *   `created_at`: TIMESTAMPTZ.

## 2. Chiến Lược Đánh Index (Indexing Strategy)

Các Index được thiết kế để tối ưu cho các truy vấn phổ biến nhất của Product Service:

| Bảng | Index | Loại | Mục đích |
|---|---|---|---|
| `products` | `idx_products_category_id` | B-tree | Query sản phẩm theo danh mục (phục vụ Markdown batch) |
| `products` | `idx_products_status` | B-tree | Lọc sản phẩm theo trạng thái (ACTIVE, DRAFT...) |
| `product_skus` | `idx_skus_product_id` | B-tree | JOIN product → SKUs |
| `product_skus` | `idx_skus_status` | B-tree | Lọc SKU theo trạng thái |
| `product_markdown_applied` | `idx_markdown_expires` | B-tree (expires_at) | Quét dọn markdown hết hạn |

## 3. Ràng Buộc & Quy Tắc Ghi Dữ Liệu

1.  **Tính nguyên vẹn (Validation):**
    *   Dữ liệu ghi vào bảng `products` (kể cả thuộc tính động) phải được validate chống chéo với nhóm cấu hình (`categories`, `category_attributes`). Tham khảo [Workflow Ghi](product_workflow.md) để biết cơ chế ứng dụng Redis Cache cho khâu kiểm tra này.
2.  **Cơ chế Khoá (Locking Strategy):**
    *   **Khoá bi quan (Pessimistic Locking) cho mọi thao tác:** Tất cả thao tác ghi trong Product Service sử dụng database row-level locking theo thứ tự chuẩn **Product -> SKU** (`SELECT ... FOR NO KEY UPDATE` trước, `SELECT ... FOR UPDATE` sau). Điều này chặn deadlock AB-BA và triệt tiểu Stale Read tại DB.
    *   **Markdown Worker: Distributed Lock (Redis):** Khi nhiều instance worker cùng xử lý, sử dụng Redis distributed lock `SETNX product:lock:markdown:{product_id}` với TTL + retry policy để tránh race condition. Lock order: Product -> SKU giữ nguyên trong transaction.
    *   **Outbox Phát Sự Kiện:** Mọi Operation làm thay đổi dữ liệu của `products` hoặc `product_skus` TRONG CÙNG TX DATABASE phải sinh thêm 1 record tại bảng `product_outbox_events`. Debezium CDC sẽ đọc WAL và đẩy record sang Kafka. (Xem [Product Workers](product_workers.md)). Cấu trúc payload chi tiết xem tại [Product Events](product_events.md).
3.  **Markdown Price Integrity:**
    *   Admin chỉ có quyền sửa `original_price`; API admin không được tự ý set giá markdown thủ công.
    *   `promotional_price` được cập nhật bởi Pricing Domain Module của Product Service, có thể chạy từ hai đường hợp lệ: (1) Markdown Worker async, (2) nhánh đồng bộ trong API update khi admin đổi `original_price` và SKU đang có markdown active.
    *   Khi `original_price` thay đổi (admin sửa giá), nếu SKU đang có `product_markdown_applied` active -> phải re-calculate `promotional_price` trong cùng transaction.
