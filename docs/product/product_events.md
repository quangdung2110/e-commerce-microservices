# Product Service - Định Nghĩa Các Sự Kiện & Kafka Topics (Events Structure)

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](product_workflow.md)
*   [Background Workers & Jobs](product_workers.md)
*   [Caching Schema (Redis & Caffeine)](product_caching_schema.md)
*   [Database Schema (Write Store)](product_db_design.md)
*   [State Machine & Lifecycle](product_state_machine.md)
*   [Events Structure & Kafka Topics](product_events.md)
*   [Architecture Recommendations](product_architecture_recommendations.md)

**Cross-Service (Search Service — Read Model / Query):**
*   [Search Service - Events Consumed](../search/search_events.md) — Chi tiết cách Search Service xử lý events từ Product
*   [Search Service - Elasticsearch Schema](../search/search_elasticsearch_schema.md) — Document mapping trong Elasticsearch
*   [Search Service - Workers](../search/search_workers.md) — Product Indexing Worker

---

Tài liệu này định nghĩa cấu trúc tiêu chuẩn của tất cả các luồng sự kiện (Events) được **phát đi (Produce)** từ Product Service thông qua Transactional Outbox Pattern, cũng như các sự kiện mà Product Service **tiêu thụ (Consume)** từ các service khác.

Kiến trúc sự kiện này đảm nhận nhiệm vụ đồng bộ CQRS từ [Write Store (PostgreSQL)](product_db_design.md) sang Elasticsearch (Search Service) và Redis Cache, đồng thời phục vụ các hệ thống downstream (Search, Notification, Promotion...) subscribe để phản ứng theo logic riêng.

> **Identity contract (bắt buộc):*
> **`rule_id` contract (bắt buộc):** Trong Product Service, `rule_id` được chuẩn hoá về string ở consumer boundary. Nếu upstream gửi số (legacy), worker phải convert sang string trước khi ghi DB/cache và trước khi so sánh.

## 1. Bản Đồ Kafka Topics

### 1.1 Topics mà Product Service **Produce** (Phát đi)

| Topic Name | Partition Key | Consumers | Mô tả |
|---|---|---|---|
| `t.product.events` | `product_id` | Search Service, Notification Service, Promotion Service (allowlist: `PRODUCT_STATE_CHANGED`, `PRODUCT_DELETED`, `PRODUCT_SKU_DELETED`), Product Service (self-consume invalidation) | Topic chính chứa mọi sự kiện thay đổi dữ liệu sản phẩm |
| `t.category.events` | `category_id` | Search Service, Product Service | Topic phát sự kiện thay đổi cấu trúc danh mục, tách biệt hoàn toàn để tránh xung đột partition key |

> **Ghi chú Partition Key:** Sử dụng `product_id` làm key cho `t.product.events` đảm bảo tất cả events của cùng 1 sản phẩm luôn vào đúng 1 Partition → **Strict Ordering** cho từng sản phẩm. Tương tự, dùng `category_id` cho `t.category.events`.

### 1.2 Topics mà Product Service **Consume** (Lắng nghe)

| Topic Name | Consumer Group | Producer | Mô tả |
|---|---|---|---|
| `t.promotion.markdown.events` | `product-markdown-group` | Promotion Service | Lắng nghe đúng allowlist `MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED` để tính [promotional_price](product_db_design.md#13-nhóm-tích-hợp-giá-khuyến-mãi-markdown-integration); event ngoài allowlist phải skip + emit metric + chuyển DLQ |
| `t.inventory.events` | `product-inventory-sync-group` | Inventory Service | Lắng nghe `INVENTORY_QUANTITY_CHANGED` để cập nhật `display_quantity` và auto-hide sản phẩm hết hàng |
| `t.product.events` | `product-event-handler-group` | Product Service (chính nó) | Self-consume để cập nhật lại Cache Redis và xử lý category lifecycle (bắt buộc gồm `PRODUCT_PRICE_UPDATED`, `PRODUCT_STATE_CHANGED`, `PRODUCT_UPDATED`, `PRODUCT_DELETED`, `PRODUCT_SKU_DELETED`). Khi ghi `product:master`, phải dedupe theo `product_id` trong poll-batch và áp dụng stale guard `version/updated_at`. Xem [Event Handler Worker](product_workers.md) |
| `t.category.events` | `product-event-handler-group` | Product Service (chính nó) | Self-consume `CATEGORY_DELETED` để cập nhật Write Model (chuyển sản phẩm liên quan sang `REJECTED`) trước khi downstream phản chiếu |

## 2. Cấu Trúc Message Header Chung (CloudEvents Wrapper)

Tất cả các tin nhắn phát lên Kafka đều tuân theo chuẩn [CloudEvents](https://cloudevents.io/) và được pipeline Debezium Outbox bổ sung header khi forward từ bảng `product_outbox_events`:

*   `event_id`: UUID của sự kiện (lấy từ `product_outbox_events.event_id`).
*   `event_type`: Loại sự kiện (VD: `PRODUCT_CREATED`, `PRODUCT_STATE_CHANGED`...).
*   `aggregate_type`: Loại Aggregate Root (VD: `Product`, `ProductSku`, `Category`).
*   `aggregate_id`: ID của entity chủ thể theo `aggregate_type` (Product events dùng `product_id`, ProductSku events dùng `sku_id`, Category events dùng `category_id`).
*   `partition_key`: Khóa định tuyến Kafka lấy từ cột `product_outbox_events.partition_key` (`product_id` cho `t.product.events`, `category_id` cho `t.category.events`).
*   `correlation_id`: ID để truy vết chuỗi Saga xuyên suốt nhiều service (thường lấy từ upstream hoặc log tracing).
*   `timestamp`: Thời điểm event được tạo (ISO 8601 UTC).
*   `source`: Định danh service gốc phát event (Cố định: `product-service`).
*   `schema_version`: Phiên bản schema bắt buộc của message (hiện tại cố định = `1`).
*   `payload`: JSON Object chứa nội dung chi tiết — cấu trúc thay đổi theo từng `event_type`.

> **Quy ước hiển thị mẫu JSON:** Các mẫu bên dưới đều bao gồm `schema_version: 1` ở top-level message.

## 3. Chi Tiết Các Loại Event Phát Đi (Produced Events)

### 3.1. PRODUCT_CREATED
**Ngữ cảnh phát sinh:** admin tạo mới một sản phẩm bản nháp (DRAFT) thành công. Xem [Workflow Tạo Sản Phẩm](product_workflow.md#2-api-tạo-mới-sản-phẩm-bản-nháp-create-concept).
**Consumers chính:** [Search Service](../search/search_events.md#21-product_created) (index sản phẩm mới, dù DRAFT chưa hiển thị public nhưng cần để Admin search).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174000",
  "event_type": "PRODUCT_CREATED",
  "aggregate_type": "Product",
  "aggregate_id": "prod-uuid-001",
  "correlation_id": "req-123456",
  "timestamp": "2026-02-28T03:51:00Z",
  "source": "product-service",
  "schema_version": 1,
  "payload": {
    "product_id": "prod-uuid-001",
        "category_id": "cat-dien-thoai",
    "category_version": 4,
    "name": "iPhone 15 Pro Max 256GB",
    "description": "Mô tả sản phẩm...",
    "status": "DRAFT",
    "attributes_json": {
      "material": "Titanium",
      "origin": "Vietnam"
    },
    "media": [
      {
        "media_type": "IMAGE",
        "url": "https://cdn.example.com/ip15-main.jpg",
        "thumbnail_url": "https://cdn.example.com/ip15-main-thumb.jpg",
        "sort_order": 1,
        "sku_id": null
      }
    ],
    "skus": [
      {
        "sku_id": "sku-uuid-001",
        "sku_code": "IP15PM-256-BLK",
        "original_price": 34990000.00,
        "promotional_price": null,
        "best_price": 34990000.00,
        "has_promotion": false,
        "variant_attributes_json": {"color": "Đen", "storage": "256GB"},
        "sku_status": "ACTIVE",
        "media": [
           {
             "media_type": "IMAGE",
             "url": "https://cdn.example.com/ip15-black.jpg",
             "thumbnail_url": "https://cdn.example.com/ip15-black-thumb.jpg",
             "sort_order": 1
           }
        ]
      },
      {
        "sku_id": "sku-uuid-002",
        "sku_code": "IP15PM-256-WHT",
        "original_price": 34990000.00,
        "promotional_price": null,
        "best_price": 34990000.00,
        "has_promotion": false,
        "variant_attributes_json": {"color": "Trắng", "storage": "256GB"},
        "sku_status": "ACTIVE",
        "media": [
           {
             "media_type": "IMAGE",
             "url": "https://cdn.example.com/ip15-white.jpg",
             "thumbnail_url": null,
             "sort_order": 1
           }
        ]
      }
    ],
    "version": 0,
    "created_at": "2026-02-28T03:51:00Z",
    "updated_at": "2026-02-28T03:51:00Z"
  }
}
```
*(Ghi chú: Event chứa toàn bộ snapshot của sản phẩm tại thời điểm tạo, bao gồm cả danh sách SKUs. Downstream consumer không cần gọi API lại để lấy thêm dữ liệu.)*

> **Khuyến nghị contract cho downstream Search:** `category_version` nên được phát ngay trong `PRODUCT_CREATED`/`PRODUCT_UPDATED` khi có sẵn trong transaction. Nếu thiếu vì lý do tương thích ngược, consumer được phép fallback snapshot API category nhưng không được hard-code `0`.
>
> **Contract giá trong SKU snapshot (BẮT BUỘC):** Với `PRODUCT_CREATED`/`PRODUCT_UPDATED`, mỗi phần tử `skus[]` phải có đủ `original_price`, `promotional_price`, `best_price`, `has_promotion` để Search phản chiếu đúng giá mà không cần tự tính lại.
>
> **Contract trạng thái SKU (chuẩn hoá):** Producer mới nên phát `skus[].sku_status` làm field canonical. Trường `skus[].status` chỉ duy trì như alias tương thích ngược trong giai đoạn chuyển đổi.
>
> **Contract phạm vi snapshot (BẮT BUỘC):** Payload `PRODUCT_UPDATED` phải là snapshot đầy đủ của Product tại thời điểm commit (không phải delta patch từ FE). Nếu producer tạm thời chưa phát được full snapshot, consumer invalidation phải coi đó là payload không đầy đủ và fallback đọc snapshot từ PostgreSQL.

### 3.2. PRODUCT_UPDATED
**Ngữ cảnh phát sinh:** admin cập nhật thông tin sản phẩm (tên, mô tả, attributes, giá SKU, thêm/xoá SKU). Xem [Workflow Sửa Sản Phẩm](product_workflow.md#3-api-sửa-đổi-thông-tin-sản-phẩm).
**Consumers chính:** [Search Service](../search/search_events.md#22-product_updated) (re-index), Product Service (self-invalidate cache).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174001",
  "event_type": "PRODUCT_UPDATED",
  "aggregate_type": "Product",
  "aggregate_id": "prod-uuid-001",
  "correlation_id": "req-123457",
  "timestamp": "2026-02-28T04:15:00Z",
  "source": "product-service",
  "schema_version": 1,
  "payload": {
    "product_id": "prod-uuid-001",
        "old_category_id": "cat-dien-thoai",
    "category_id": "cat-dien-thoai",
    "category_version": 4,
    "old_status": "ACTIVE",
    "name": "iPhone 15 Pro Max 256GB (Updated)",
    "description": "Mô tả cập nhật...",
    "status": "ACTIVE",
    "attributes_json": {
      "material": "Titanium Grade 5",
      "origin": "Vietnam"
    },
    "media": [
      {
        "media_type": "IMAGE",
        "url": "https://cdn.example.com/ip15-main-updated.jpg",
        "thumbnail_url": "https://cdn.example.com/ip15-main-updated-thumb.jpg",
        "sort_order": 1,
        "sku_id": null
      }
    ],
    "skus": [
      {
        "sku_id": "sku-uuid-001",
        "sku_code": "IP15PM-256-BLK",
        "original_price": 33990000.00,
        "promotional_price": 28891500.00,
        "best_price": 28891500.00,
        "has_promotion": true,
        "variant_attributes_json": {"color": "Đen", "storage": "256GB"},
        "sku_status": "ACTIVE",
        "media": [
           {
             "media_type": "IMAGE",
             "url": "https://cdn.example.com/ip15-black.jpg",
             "thumbnail_url": "https://cdn.example.com/ip15-black-thumb.jpg",
             "sort_order": 1
           }
        ]
      }
    ],
    "version": 3,
    "updated_at": "2026-02-28T04:15:00Z"
  }
}
```
> **Contract diff fields cho downstream cache/category invalidation:** khuyến nghị phát thêm `old_category_id` và `old_status` trong `PRODUCT_UPDATED`. Khi producer chưa phát được (legacy), downstream buộc dùng chiến lược invalidate bảo toàn đúng đắn (conservative invalidation).

> **Quan trọng về Idempotency:** Downstream consumers PHẢI kiểm tra `version` của event. Nếu `version` trong event ≤ `version` đã lưu local, event này là stale (cũ hơn) và phải bỏ qua. Đây là cơ chế chống sai thứ tự (Out-of-order) tự nhiên, tương tự `updated_at` check trong [Cart Service Events](../cart/cart_events.md#22-cart_item_updated).
> Với `product-invalidation-group` khi refresh `product:master:{product_id}`, guard bắt buộc dùng cặp `(version, updated_at)`: chỉ ghi nếu event mới hơn cache hiện tại.

> **Source-selection contract cho `product-invalidation-group` (BẮT BUỘC):**
> - Ưu tiên nguồn payload khi payload là full snapshot hợp lệ.
> - Nếu payload thiếu trường bắt buộc (`status`, `category_id`, `version`, `updated_at`, hoặc `skus[]` không đầy đủ field canonical), consumer phải DB re-fetch snapshot đầy đủ Product + SKUs rồi mới ghi cache.
> - Trong cùng 1 event `PRODUCT_UPDATED`, không được trộn một phần SKU từ payload và phần còn lại từ DB.
> - Không fallback sang Search/Elasticsearch.

### 3.3. PRODUCT_STATE_CHANGED
**Ngữ cảnh phát sinh:** Trạng thái sản phẩm thay đổi qua [State Machine](product_state_machine.md). VD: DRAFT → PENDING_REVIEW, PENDING_REVIEW → ACTIVE, ACTIVE → BANNED.
**Consumers chính:** [Search Service](../search/search_events.md#23-product_state_changed) (ẩn/hiện sản phẩm trên Elasticsearch), Notification Service (gửi mail cho admin), Promotion Service (Lắng nghe để bắt các Sản phẩm mới được duyệt lên `ACTIVE`, qua đó áp bù giá Markdown nếu chúng thuộc Category đang có Flash Sale).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174002",
  "event_type": "PRODUCT_STATE_CHANGED",
  "aggregate_type": "Product",
  "aggregate_id": "prod-uuid-001",
  "correlation_id": "req-123458",
  "timestamp": "2026-02-28T05:00:00Z",
  "source": "product-service",
  "schema_version": 1,
  "payload": {
    "product_id": "prod-uuid-001",
    "product_name": "iPhone 15 Pro Max 256GB",
        "sku_ids": ["sku-uuid-001", "sku-uuid-002"],
    "old_status": "PENDING_REVIEW",
    "new_status": "ACTIVE",
    "changed_by": "admin-uuid-007",
    "changed_by_type": "ADMIN",
    "reason": null,
    "version": 4,
    "updated_at": "2026-02-28T05:00:00Z",
    "changed_at": "2026-02-28T05:00:00Z"
  }
}
```
*(Ghi chú: Sự kiện này bắt buộc phải nạp thêm mảng `sku_ids` vào payload để **Worker Invalidation Cache** không bị mù, nó có thể xoá Cache cho các SKU đó ngay lập tức mà không phải tốn thêm Query xuống DB mò mẫm.)*

> **Nguồn dữ liệu `sku_ids` (BẮT BUỘC):** FE không gửi `sku_ids` khi gọi API đổi trạng thái Product. Backend phải tự truy vấn danh sách SKU liên đới trong cùng transaction và đóng gói vào payload `PRODUCT_STATE_CHANGED` trước khi insert outbox.

### 3.4. PRODUCT_PRICE_UPDATED
**Ngữ cảnh phát sinh:** Giá sản phẩm thay đổi — bao gồm cả khi admin sửa `original_price` LẪN khi [Markdown Worker](product_workers.md#5-markdown-price-calculator-worker) tính lại `promotional_price`. Xem [Promotion DB Design - Cơ chế Zero-Latency](../promotion/promotion_db_design.md#22-xử-lý-zero-latency-tính-toán-best-deal-mà-không-cần-discount_items-mapping).
**Consumers chính:** [Search Service](../search/search_events.md#24-product_price_updated) (cập nhật giá hiển thị trên Elasticsearch).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174003",
  "event_type": "PRODUCT_PRICE_UPDATED",
  "aggregate_type": "ProductSku",
  "aggregate_id": "sku-uuid-001",
  "correlation_id": "req-123459",
  "timestamp": "2026-02-28T06:00:00Z",
  "source": "product-service",
  "schema_version": 1,
  "payload": {
    "product_id": "prod-uuid-001",
    "sku_id": "sku-uuid-001",
    "sku_code": "IP15PM-256-BLK",
    "original_price": 33990000.00,
    "promotional_price": 28891500.00,
    "best_price": 28891500.00,
    "has_promotion": true,
    "previous_original_price": 34990000.00,
    "previous_promotional_price": null,
    "version": 4,
    "updated_at": "2026-02-28T06:00:00Z"
  }
}
```
> **Field `best_price` (BẮT BUỘC):** Đây là giá thấp nhất đã được Product Service tính sẵn theo công thức chuẩn:
> `best_price = LEAST(original_price, original_price - discount_amount)` khi `discount_type = PERCENTAGE`, trong đó `discount_amount = original_price * discount_value / 100` nếu không có `max_discount_amount`, hoặc `discount_amount = LEAST(original_price * discount_value / 100, max_discount_amount)` nếu có cap;
> `best_price = LEAST(original_price, original_price - discount_value)` khi `discount_type = FIXED_AMOUNT`;
> kết quả làm tròn 2 chữ số thập phân, clamp tối thiểu về `0` và cuối cùng đồng bộ vào `promotional_price`/`best_price` theo transaction. Downstream (Search/Checkout/Analytics) chỉ được đọc và phản chiếu, không tự tính lại.
>
> **Ownership Cache Refresh (BẮT BUỘC):** Với thay đổi giá do Markdown, key `product:sku:{sku_id}` và `product:master:{product_id}` phải do `product-event-handler-group` ghi đè dựa trên `PRODUCT_PRICE_UPDATED`. Markdown Worker chỉ quản lý key phụ `product:markdown:active:{sku_id}` để tránh double-write bất đồng bộ.
> Khi nhiều event cùng `product_id` xuất hiện liên tiếp, Event Handler phải coalesce để giới hạn số lần ghi `product:master:{product_id}` và chỉ apply bản mới nhất theo guard `version/updated_at`.

### 3.5. PRODUCT_DELETED
**Ngữ cảnh phát sinh:** Sản phẩm bị Soft Delete (cập nhật cờ `is_deleted = true`, trạng thái nghiệp vụ cũ được giữ nguyên).
**Consumers chính:** [Search Service](../search/search_events.md#26-product_deleted) (xoá khỏi Elasticsearch index).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174005",
  "event_type": "PRODUCT_DELETED",
  "aggregate_type": "Product",
  "aggregate_id": "prod-uuid-001",
  "correlation_id": "req-123461",
  "timestamp": "2026-02-28T08:00:00Z",
  "source": "product-service",
  "schema_version": 1,
  "payload": {
    "product_id": "prod-uuid-001",
        "sku_ids": ["sku-uuid-001", "sku-uuid-002"],
    "version": 5,
    "updated_at": "2026-02-28T08:00:00Z",
    "deleted_at": "2026-02-28T08:00:00Z"
  }
}
```

> **Nguồn dữ liệu `sku_ids` cho xóa mềm (BẮT BUỘC):** FE chỉ gửi `product_id`. Backend phải tự lấy danh sách SKU thuộc Product trong transaction xóa mềm và đóng gói vào payload `PRODUCT_DELETED` để invalidation refresh đầy đủ các key SKU liên quan.

### 3.6. CATEGORY_UPDATED
**Ngữ cảnh phát sinh:** Tên, thuộc tính, hoặc đường dẫn của Danh mục (Category) bị thay đổi bởi Admin.
**Consumers chính:** Search Service (nhận Event để update tên và thông tin danh mục cho hàng loạt sản phẩm bằng lệnh Update By Query trên Elasticsearch).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174008",
  "event_type": "CATEGORY_UPDATED",
  "aggregate_type": "Category",
  "aggregate_id": "cat_001",
  "correlation_id": "req-123490",
  "source": "product-service",
  "schema_version": 1,
  "timestamp": "2026-03-20T10:00:00Z",
  "payload": {
    "category_id": "cat_001",
    "old_name": "Điện thoại",
    "new_name": "Điện thoại thông minh",
    "new_path": "dien-tu/dien-thoai-thong-minh",
    "category_version": 4
  }
}
```

### 3.7. PRODUCT_SKU_DELETED
**Ngữ cảnh phát sinh:** Một SKU cụ thể bị Soft Delete khỏi Sản phẩm bởi admin (các SKU khác trong sản phẩm vẫn tồn tại).
**Consumers chính:** Search Service (nhận Event để xóa hoặc đánh dấu DELETED cho SKU Document, tránh việc User search ra kết quả lỗi).
**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "evt_skudel_xxx",
  "aggregate_id": "sku-uuid-002",
  "aggregate_type": "ProductSku",
  "event_type": "PRODUCT_SKU_DELETED",
  "correlation_id": "req-123492",
  "source": "product-service",
  "schema_version": 1,
  "timestamp": "2026-03-24T10:00:00Z",
  "payload": {
    "product_id": "prod-uuid-001",
    "sku_id": "sku-uuid-002",
    "version": 6,
    "updated_at": "2026-03-24T10:00:00Z",
    "deleted_at": "2026-03-24T10:00:00Z"
  }
}
```

> **Contract guard cho tombstone (BẮT BUỘC):** `PRODUCT_SKU_DELETED` phải luôn có `payload.version` để downstream guard out-of-order khi scripted-upsert tombstone.

### 3.8. CATEGORY_DELETED
**Ngữ cảnh phát sinh:** Danh mục bị Xóa Cứng hoặc Xóa Mềm bởi Admin (System Constraint: thông thường Admin chỉ được xoá khi danh mục không còn Product nào, nhưng đề phòng trường hợp Force Delete).
**Consumers chính:** Product Service (`product-event-handler-group`) và Search Service (`search-category-indexer`). Product Service phải xử lý Write Model trước: đổi `status` thành `REJECTED` cho toàn bộ sản phẩm thuộc danh mục bị xoá ngay tại PostgreSQL và sinh tiếp event `PRODUCT_STATE_CHANGED`. Search Service chỉ phản chiếu projection (xóa/đổi metadata category, invalidate category tree), tuyệt đối KHÔNG tự ý đổi state sản phẩm nếu chưa nhận `PRODUCT_STATE_CHANGED`.

> **Scope contract:** Payload nên mang `descendant_category_ids` khi xoá cả cây danh mục. Nếu field này thiếu (legacy), consumer tối thiểu phải xử lý `category_id` gốc và có cơ chế self-heal subtree theo snapshot category tree nội bộ.
**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174009",
  "event_type": "CATEGORY_DELETED",
  "aggregate_type": "Category",
  "aggregate_id": "cat_001",
  "correlation_id": "req-123491",
  "source": "product-service",
  "schema_version": 1,
  "timestamp": "2026-03-24T10:00:00Z",
  "payload": {
    "category_id": "cat_001",
    "descendant_category_ids": ["cat_001_a", "cat_001_b"],
    "deleted_at": "2026-03-24T10:00:00Z",
    "category_version": 5
  }
}
```

### 3.10. Contract Nạp Payload Từ FE -> BE -> Outbox

| Event | Dữ liệu từ FE | Dữ liệu BE bắt buộc tự bổ sung | Mục tiêu downstream |
|---|---|---|---|
| `PRODUCT_UPDATED` | Payload chỉnh sửa (có thể là delta theo SKU) | Chuẩn hóa thành snapshot đầy đủ Product + `skus[]` tại thời điểm commit (từ projection trong transaction hoặc re-read DB nhất quán) | Search re-index và Invalidation refresh cache không cần đoán thêm dữ liệu |
| `PRODUCT_STATE_CHANGED` | Command đổi trạng thái (`APPROVE`, `HIDE_PRODUCT`, ...) + `product_id` | Truy vấn danh sách `sku_ids[]` liên đới trong cùng transaction FSM | Invalidation refresh toàn bộ `product:sku:{sku_id}` và `product:master:{product_id}` ngay sau event |
| `PRODUCT_DELETED` | `product_id` | Truy vấn danh sách `sku_ids[]` thuộc Product trước/sát thời điểm soft-delete | Invalidation dọn/refresh đầy đủ các cache key SKU của product bị xóa mềm |

> **Nguyên tắc nhất quán:** Payload outbox là hợp đồng backend phát hành, không phải bản sao thô 1:1 từ request FE.



## 4. Chi Tiết Các Loại Event Tiêu Thụ (Consumed Events)

### 4.1. MARKDOWN_CREATED (Từ Promotion Service)
**Topic:** `t.promotion.markdown.events`
**Consumer Group:** `product-markdown-group`
**Xử lý bởi:** [Markdown Price Calculator Worker](product_workers.md#5-markdown-price-calculator-worker)

> **Compatibility rule:** Ưu tiên đọc `schema_version`. Nếu upstream legacy thiếu field này, consumer tạm map mặc định `schema_version = 1` và ghi metric compatibility để theo dõi rollout.

**Cấu trúc Payload kỳ vọng (JSON):**
```json
{
  "event_type": "MARKDOWN_CREATED",
  "schema_version": 1,
  "payload": {
    "rule_id": "42",
    "scope_type": "CATEGORY",
    "ref_id": "cat-dien-thoai",
    "discount_type": "PERCENTAGE",
    "discount_value": 15.00,
    "max_discount_amount": 5000000.00,
    "start_time": "2026-03-01T00:00:00Z",
    "end_time": "2026-03-07T23:59:59Z"
  }
}
```
**Logic xử lý:** Product Service xác định tập SKU bị ảnh hưởng theo `scope`, sau đó group theo `product_id` và xử lý transaction-per-product theo lock order **Product -> SKU**. Với mỗi `product_id`, worker lock Product cha `FOR NO KEY UPDATE` (kèm `lock_timeout` + retry policy), lock SKU con `FOR UPDATE` để lấy `original_price` live rồi mới tính `promotional_price`. Cuối transaction batch update `product_skus`, upsert `product_markdown_applied`, insert outbox `PRODUCT_PRICE_UPDATED` cho từng SKU bị ảnh hưởng.

> **Start-time gate bắt buộc:** Nếu `now < payload.start_time`, worker không được apply markdown ngay mà phải persist job vào Redis ZSET `product:markdown:pending` (score=`start_time_epoch_ms`, member=`{rule_id}:{scope_type}:{ref_id}`) để delayed scheduler thực thi đúng thời điểm. Retry/rebalance không được làm mất pending job.

### 4.2. MARKDOWN_UPDATED (Từ Promotion Service)
**Topic:** `t.promotion.markdown.events`
**Consumer Group:** `product-markdown-group`
**Xử lý bởi:** [Markdown Price Calculator Worker](product_workers.md#5-markdown-price-calculator-worker)

**Cấu trúc Payload kỳ vọng (JSON):**
```json
{
  "event_type": "MARKDOWN_UPDATED",
  "schema_version": 1,
  "payload": {
    "rule_id": "42",
    "changes": {
      "discount_value": 20.00,
      "end_time": "2026-03-14T23:59:59Z"
    }
  }
}
```
**Logic xử lý (Kèm Sửa Lỗi C2):** Product Service query `product_markdown_applied WHERE rule_id = '42'` → lấy danh sách `sku_id` bị ảnh hưởng → tính lại `promotional_price` với các thông số thay đổi. **Lưu ý tối cao:** `original_price` dùng để tính toán BẮT BUỘC phải được query trực tiếp từ field tĩnh `product_skus.original_price` tính tại thời điểm Worker đang xử lý, TUYỆT ĐỐI KHÔNG dùng lại `original_price_snapshot` của lịch sử; đồng thời worker cập nhật lại `original_price_snapshot` = `original_price` tại thời điểm đó → batch update `product_skus` và `product_markdown_applied` → phát `PRODUCT_PRICE_UPDATED` events cho từng SKU bị ảnh hưởng.

### 4.3. MARKDOWN_ENDED (Từ Promotion Service)
**Topic:** `t.promotion.markdown.events`
**Consumer Group:** `product-markdown-group`

**Cấu trúc Payload kỳ vọng (JSON):**
```json
{
  "event_type": "MARKDOWN_ENDED",
  "schema_version": 1,
  "payload": {
    "rule_id": "42",
    "scope_type": "CATEGORY",
    "ref_id": "cat-dien-thoai",
    "reason": "EXPIRED"
  }
}
```

> **Thống nhất tên event:** Event này tên chính thức là `MARKDOWN_ENDED` (không phải `MARKDOWN_EXPIRED`). Tên `MARKDOWN_ENDED` bao gồm cả "hết hạn" lẫn "Admin tắt thủ công", phân biệt qua field `reason`:
> - `reason: "EXPIRED"` → [Markdown Expiry Scheduler](../promotion/promotion_workers.md#34-markdown-expiry-scheduler-redis-zset-va-cron-fallback) (Redis ZSET loop + cron fallback) phát hiện `end_time < NOW()`
> - `reason: "ADMIN_DISABLED"` → Admin tắt thủ công trên dashboard
>
> Xem định nghĩa event gốc tại [Promotion Events - MARKDOWN_ENDED](../promotion/promotion_events.md#23-markdown_ended).

**Logic xử lý:** Query `product_markdown_applied WHERE rule_id = '42'` → lấy danh sách `sku_id` bị ảnh hưởng → reset `product_skus.promotional_price = NULL` → delete records từ `product_markdown_applied` → phát `PRODUCT_PRICE_UPDATED` events (với `change_source = MARKDOWN_ENDED` hoặc `MARKDOWN_EXPIRED_FALLBACK` nếu đến từ job self-healing).

> **Idempotency guard bắt buộc cho `MARKDOWN_ENDED`:** Nếu không còn bản ghi nào trong `product_markdown_applied` cho `rule_id`, consumer phải ACK và no-op (không phát `PRODUCT_PRICE_UPDATED` rỗng, không coi là lỗi).

### 4.4. INVENTORY_QUANTITY_CHANGED (Từ Inventory Service)
**Topic:** `t.inventory.events`
**Consumer Group:** `product-inventory-sync-group`
**Xử lý bởi:** [Inventory Sync Worker](product_workers.md#6-inventory-sync-worker)

**Cấu trúc Payload kỳ vọng (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174006",
  "event_type": "INVENTORY_QUANTITY_CHANGED",
  "aggregate_type": "Inventory",
  "aggregate_id": "sku-uuid-001",
  "correlation_id": "sys-abc",
  "timestamp": "2026-02-28T08:00:00Z",
  "source": "inventory-service",
  "schema_version": 1,
  "payload": {
    "product_id": "prod-uuid-001",
    "sku_id": "sku-uuid-001",
    "available_quantity": 0,
    "previous_available_quantity": 15,
    "change_type": "ORDER_DEDUCTED",
    "event_timestamp": 1709420000000,
    "timestamp_iso": "2026-02-28T08:00:00Z"
  }
}
```
**Logic xử lý:** Update `product_skus.display_quantity = available_quantity`. Product Service lock theo thứ tự **Product -> SKU** và validate cặp `product_id/sku_id` ngay trong transaction trước khi apply update. **Rào cản Out-Of-Order (X6 Fix):** Chỉ thực hiện apply update nếu `event_timestamp > product_skus.last_inventory_sync_at`. Nếu `available_quantity = 0` và SKU đang `ACTIVE` → chuyển SKU status sang `OUT_OF_STOCK`; nếu `available_quantity > 0` và SKU đang `OUT_OF_STOCK` → chuyển về `ACTIVE` và chỉ auto-reactivate khi thỏa predicate `status = HIDDEN AND admin_hidden = false AND is_deleted = false` trong cùng transaction (dùng row đã lock hoặc query guard trong cùng transaction). Nếu SKU status thay đổi, phải insert outbox `PRODUCT_SKU_STATUS_CHANGED` ngay trong transaction trước commit. Cache `product:sku:{sku_id}` bắt buộc lưu `last_inventory_sync_at` để Lua CAS fence chống stale write. Xem thêm [State Machine - Auto Hide](product_state_machine.md#2-các-lệnh-chuyển-tiếp-events--transitions).

## 5. Schema Versioning & Backward Compatibility

*   **Event Schema Version:** Mỗi message event bắt buộc chứa top-level field `schema_version` (hiện tại = `1`). Khi schema thay đổi trong tương lai, version tăng lên.
*   **Backward Compatibility Rule:** Chỉ được phép **thêm field mới** (additive changes). Không được xoá hoặc đổi tên field hiện có. Nếu cần breaking change, phải tạo event_type mới (VD: `PRODUCT_CREATED_V2`).
*   **Consumer Resilience:** Tất cả consumers PHẢI ignore unknown fields (sử dụng `@JsonIgnoreProperties(ignoreUnknown = true)` trong Java/Spring hoặc tương đương).
