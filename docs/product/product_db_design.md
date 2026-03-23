# Product Service - Database Schema Design (Write Store)

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](product_workflow.md)
*   [Background Workers & Jobs](product_workers.md)
*   [Caching Schema (Redis & Caffeine)](product_caching_schema.md)
*   [Database Schema (Write Store)](product_db_design.md)
*   [State Machine & Lifecycle](product_state_machine.md)
*   [Events Structure & Kafka Topics](product_events.md)
*   [Architecture Recommendations](product_architecture_recommendations.md)

---
Tài liệu này mô tả thiết kế cơ sở dữ liệu PostgreSQL cho Product Service. Trong kiến trúc CQRS, database này đóng vai trò là **Write Store (Command Database - Source of Truth)**. Dữ liệu từ đây sẽ được phát qua sự kiện (Kafka) để đồng bộ sang Redis Cache và Elasticsearch (Search Service) phục vụ việc đọc.

## 1. Cấu Trúc Bảng (ERD & Entities)

Mô hình dữ liệu bao gồm các nhóm: Quản lý Danh mục (Catalog), Sản phẩm & Biến thể (Product & SKU), Tích hợp Giá Khuyến mãi (Markdown Integration), và các bảng hạ tầng (Infrastructure). 
**Lưu ý:** Thực thể `Brands` đã được loại bỏ do yêu cầu nghiệp vụ hiện tại không quản lý theo Brand độc lập.

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
    *   `idempotency_key`: VARCHAR(255) UNIQUE (Khóa chống trùng lặp, dùng để phục hồi state/product_id nếu Application văng lỗi/crash giữa chừng không kịp ghi vào Redis).
    *   `seller_id`: VARCHAR(255) INDEX.
    *   `category_id`: VARCHAR(255) INDEX.
    *   `name`: VARCHAR(255).
    *   `description`: TEXT.
    *   `status`: VARCHAR(50) DEFAULT 'DRAFT'. Quản lý bởi [State Machine](product_state_machine.md). (Các trạng thái: DRAFT, PENDING_REVIEW, ACTIVE, HIDDEN, REJECTED, BANNED. Tuyệt đối KHÔNG có trạng thái DELETED).
    *   `seller_intention_hidden`: BOOLEAN DEFAULT FALSE (Cờ độc lập ghi nhận ý muốn chủ quan của Seller là ẩn sản phẩm. Dùng để vượt mặt State Machine tự động bật hàng khi Inventory > 0).
    *   `is_deleted`: BOOLEAN DEFAULT FALSE (Cờ Soft Delete, dùng chung với Annotation `@SQLRestriction("is_deleted = false")` ở tầng Entity để tách biệt vòng đời dữ liệu khỏi vòng đời nghiệp vụ. Trạng thái cũ được bảo lưu giúp Restore dễ dàng).
    *   `attributes_json`: JSONB (Lưu trữ các giá trị thuộc tính dùng chung của toàn bộ sản phẩm, nhưng không phải variant. VD: Chất liệu, Xuất xứ).
    *   `version`: BIGINT DEFAULT 0 (Dùng cho Optimistic Locking, tăng tự động khi update).
    *   `created_at`: TIMESTAMPTZ.
    *   `updated_at`: TIMESTAMPTZ.
    *   **Partitioning**: Yêu cầu **Hash Partitioning** theo `product_id` phân thành 32/64 bảng vật lý để chia nhỏ tải Write IOPS khổng lồ, ngăn chặn Row Lock toàn cục.

*   `product_skus` (Bảng Biến thể vật lý - Stock Keeping Unit)
    *   `sku_id`: VARCHAR(255) PRIMARY KEY.
    *   `product_id`: VARCHAR(255) INDEX (Tham chiếu tới `products`).
    *   `sku_code`: VARCHAR(255) UNIQUE (Mã định danh nội bộ từ Shop/Seller).
    *   `original_price`: DECIMAL(15,2) (Giá gốc / Giá niêm yết do Seller đặt).
    *   `promotional_price`: DECIMAL(15,2) NULL (Giá sau khi áp dụng Markdown Rule từ [Promotion Service](../promotion/promotion_db_design.md#21-markdown_rules-luật-giảm-giá-gạch-chéo). Khi NULL = không có chương trình giảm giá nào đang active. Xem [Workflow xử lý Markdown](product_workflow.md#6-workflow-xử-lý-markdown-event-từ-promotion-service)).
    *   `status`: VARCHAR(50) DEFAULT 'ACTIVE' (Trạng thái riêng của SKU: `ACTIVE`, `INACTIVE`, `OUT_OF_STOCK`. Cho phép quản lý lifecycle SKU độc lập với Product. VD: Product đang ACTIVE nhưng SKU Size XXL bị tạm ngưng bán).
    *   `display_quantity`: INT DEFAULT 0 (Số lượng tồn kho **hiển thị** — đây là bản sao cached đồng bộ từ [Inventory Service](../inventory/inventory_db_design.md). **Source of Truth thực sự** về tồn kho nằm ở Inventory Service. Field này được cập nhật bởi [Inventory Sync Worker](product_workers.md#5-inventory-sync-worker)).
    *   `variant_attributes_json`: JSONB (Cấu hình riêng của SKU này, vd: `{"color": "Xanh", "size": "M"}`).
    *   `created_at`: TIMESTAMPTZ.
    *   `updated_at`: TIMESTAMPTZ.

> **Lưu ý quan trọng về Versioning (Optimistic Locking & Search Event Sourcing):**
> Bảng `product_skus` KHÔNG ĐƯỢC PHÉP có cột `version` riêng. Theo thiết kế Aggregate Root, mọi thao tác tạo, sửa, xóa bất kỳ SKU nào cũng BẮT BUỘC phải làm tăng cột `version` của bảng cha `products`. Chuỗi `version` đồng bộ duy nhất này sẽ được dùng làm căn cứ thứ tự tuần tự (Optimistic Concurrency Control) cho Search Service, nhằm triệt tiêu hoàn toàn sự cố lỗi từ chối Event (Out-of-Order Event Reject) do Elasticsearch version clash.
    *   **Partitioning / Data Routing**: Mặc dù đồng bộ **Hash Partitioning** theo khóa `product_id` (để tối ưu hóa query JOIN), nhưng Endpoint gRPC Point-Read cực kì nhạy cảm về độ trễ lại truy vấn thuần túy theo `sku_id`. Để giải quyết triệt để lỗi Scatter-Gather (Full Partition Scan), **bắt buộc phải nhúng mã định tuyến `product_id` vào trong chuỗi sinh `sku_id`**. Ví dụ format: `SKU_{product_id}_{random_hash}`. Nhờ vậy, ngay tại tầng Repository, Application có thể bóc tách tiền tố chuỗi `sku_id` để biết đích xác Record này nằm ở Partition nào và chỉ định tuyến `SELECT` thẳng vào Partition đó thay vì mở bung tất cả các Partition.

*   `product_status_history` (Bảng Ghi nhận Lịch sử Đổi trạng thái)
    *   `history_id`: VARCHAR(255) PRIMARY KEY.
    *   `product_id`: VARCHAR(255) INDEX.
    *   `old_status`: VARCHAR(50).
    *   `new_status`: VARCHAR(50).
    *   `changed_by`: VARCHAR(255) (Ai là người thực hiện đổi: System, Admin, Seller).
    *   `changed_by_type`: VARCHAR(50) (Loại đối tượng: SYSTEM, ADMIN, SELLER).
    *   `reason`: TEXT (Lý do thay đổi, đặc biệt khi REJECTED hoặc BANNED).
    *   `changed_at`: TIMESTAMPTZ.

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

Khi Promotion Service phát event `MARKDOWN_CREATED` / `MARKDOWN_EXPIRED`, Product Service cần biết chính xác rule nào đang tác động lên SKU nào để:
- **Tính toán** `promotional_price` khi rule mới được tạo.
- **Reset** `promotional_price` về NULL khi rule hết hạn hoặc bị huỷ.

> **Tại sao cần bảng này?** Nếu chỉ lưu `promotional_price` trên `product_skus` mà không tracking rule_id, khi một rule hết hạn (event `MARKDOWN_EXPIRED`), Product Service sẽ không biết cần reset SKU nào. Bảng này chính là "sổ ghi nợ" cho Markdown lifecycle. Xem chi tiết luồng tại [Promotion DB Design - Markdown](../promotion/promotion_db_design.md#2-thiết-kế-giảm-giá-trực-tiếp-markdowns--price-slashes).

*   `product_markdown_applied` (Bảng theo dõi Markdown Rule đang Active trên SKU)
    *   `id`: VARCHAR(255) PRIMARY KEY.
    *   `product_id`: VARCHAR(255) INDEX (FK ảo tới `products`).
    *   `rule_id`: BIGINT INDEX (ID của `markdown_rules` từ Promotion Service).
    *   `sku_id`: VARCHAR(255) INDEX (FK ảo tới `product_skus`).
    *   `original_price_snapshot`: DECIMAL(15,2) (Giá gốc tại thời điểm áp dụng rule — dùng để audit & rollback).
    *   `discount_type`: VARCHAR(50) (Loại giảm giá: PERCENTAGE, FIXED_AMOUNT).
    *   `discount_value`: DECIMAL(15,2) (Giá trị cấu hình giảm).
    *   `max_discount_amount`: DECIMAL(15,2) NULL (Giới hạn mức giảm tối đa trên mỗi sản phẩm - chỉ dùng khi PERCENTAGE).
    *   `calculated_price`: DECIMAL(15,2) (Giá sau khi tính toán = `original_price * (1 - discount_percentage)` hoặc `original_price - fixed_amount`).
    *   `applied_at`: TIMESTAMPTZ.
    *   `expires_at`: TIMESTAMPTZ (Copy từ `end_time` của rule, dùng để tracking và hiển thị Countdown Timer trên UI, không dùng để quét dọn vì Event Expiry do Promotion Service đẩy sang).
    *   **Unique Index (`rule_id`, `sku_id`)**: Đảm bảo một rule chỉ áp dụng 1 lần trên 1 SKU.

### 1.4. Nhóm Hạ tầng Cốt lõi (Infrastructure)

*   `product_outbox_events` (Bảng Ghi nhận Event Phát đi cho Outbox Pattern kết hợp Debezium)
    *   `event_id`: UUID PRIMARY KEY.
    *   `event_type`: VARCHAR(100) (VD: ProductCreatedEvent, ProductStateChangedEvent, ProductPriceUpdatedEvent). Xem danh sách đầy đủ tại [Product Events](product_events.md).
    *   `aggregate_type`: VARCHAR(50) (VD: Product, ProductSku).
    *   `aggregate_id`: VARCHAR(255).
    *   `partition_key`: VARCHAR(255) NOT NULL (Chứa `product_id`. Được chèn vào bởi Application lúc Insert để Debezium định tuyến Kafka Message Partition Key nguyên bản, không cần tốn CPU parse JSON ở Connect). Ràng buộc `NOT NULL` là lá chắn sống còn bảo vệ CQRS: Nếu quên truyền, Event rơi lung tung sang các Partition khác nhau làm trật tự đồng bộ (Ordering) của Elasticsearch bị phá vỡ hoàn toàn.
    *   `payload`: JSONB.
    *   `created_at`: TIMESTAMPTZ.

## 2. Chiến Lược Đánh Index (Indexing Strategy)

Các Index được thiết kế để tối ưu cho các truy vấn phổ biến nhất của Product Service:

| Bảng | Index | Loại | Mục đích |
|---|---|---|---|
| `products` | `idx_products_seller_id` | B-tree | Query tất cả sản phẩm của 1 Seller |
| `products` | `idx_products_category_id` | B-tree | Query sản phẩm theo danh mục (phục vụ Markdown batch) |
| `products` | `idx_products_status` | B-tree | Lọc sản phẩm theo trạng thái (ACTIVE, DRAFT...) |
| `product_skus` | `idx_skus_product_id` | B-tree | JOIN product → SKUs |
| `product_skus` | `idx_skus_status` | B-tree | Lọc SKU theo trạng thái |
| `product_markdown_applied` | `idx_markdown_expires` | B-tree (expires_at) | Quét dọn markdown hết hạn |
| `product_status_history` | `idx_history_product_id` | B-tree | Tra cứu lịch sử trạng thái 1 SP |

## 3. Ràng Buộc & Quy Tắc Ghi Dữ Liệu

1.  **Tính nguyên vẹn (Validation):**
    *   Dữ liệu ghi vào bảng `products` (kể cả thuộc tính động) phải được validate chống chéo với nhóm cấu hình (`categories`, `category_attributes`). Tham khảo [Workflow Ghi](product_workflow.md) để biết cơ chế ứng dụng Redis Cache cho khâu kiểm tra này.
2.  **Cơ chế Khoá (Locking Strategy):** 
    *   **Lock-Free với Khoá lạc quan (Optimistic Locking):** Bảng `products` sử dụng field `version` để bảo vệ **tính toàn vẹn (Row-level integrity)** cho Aggregate Root. Bất kể thay đổi ở `products` hay `product_skus`, `version` của bảng cha `products` đều phải được tăng lên. Khác với Distributed Lock chậm chạp, Optimistic Lock nhẹ và tăng throughput. Mọi luồng API sẽ tự check `version` và Reject Transaction bẩn. Tránh việc sử dụng 2 chuỗi version độc lập gây xung đột ở Search Service.
    *   **Khoá bi quan (Pessimistic Locking) cho Strict Consistency:** Riêng đối với thao tác có tính ganh đua cao và yêu cầu nhất quán lượng tồn kho tuyệt đối (Workflow xử lý `INVENTORY_QUANTITY_CHANGED`), hệ thống áp dụng `FOR NO KEY UPDATE`. Điều này chặn triệt để hiện tượng Stale Read tại DB thay vì sử dụng Redis Redlock ở App.
3.  **Outbox Phát Sự Kiện:**
    *   Mọi Operation làm thay đổi dữ liệu của `products` hoặc `product_skus` TRONG CÙNG TX DATABASE phải sinh thêm 1 record tại bảng `product_outbox_events` với status `PENDING`. Job định kỳ sẽ kéo dữ liệu chuyển lên Kafka. (Xem [Product Workers](product_workers.md)). Cấu trúc payload chi tiết xem tại [Product Events](product_events.md).
4.  **Markdown Price Integrity:**
    *   `promotional_price` trên `product_skus` chỉ được cập nhật bởi [Markdown Price Worker](product_workers.md#5-markdown-price-calculator-worker), KHÔNG phải bởi API trực tiếp từ Seller. Seller chỉ có quyền sửa `original_price`.
    *   Khi `original_price` thay đổi (Seller sửa giá), nếu SKU đang có `product_markdown_applied` active → cần re-calculate `promotional_price` tương ứng.
