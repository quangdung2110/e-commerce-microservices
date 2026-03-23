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

Kiến trúc sự kiện này đảm nhận nhiệm vụ đồng bộ CQRS từ [Write Store (PostgreSQL)](product_db_design.md) sang Elasticsearch (Search Service) và Redis Cache, đồng thời phục vụ các hệ thống downstream (Cart, Order, Inventory, Promotion...) subscribe để phản ứng theo logic riêng.

## 1. Bản Đồ Kafka Topics

### 1.1 Topics mà Product Service **Produce** (Phát đi)

| Topic Name | Partition Key | Consumers | Mô tả |
|---|---|---|---|
| `t.product.events` | `product_id` | Search Service, Cart Service, Order Service, Notification Service, Product Service (self-consume invalidation) | Topic chính chứa mọi sự kiện thay đổi dữ liệu sản phẩm |

> **Ghi chú Partition Key:** Sử dụng `product_id` làm key đảm bảo tất cả events của cùng 1 sản phẩm luôn vào đúng 1 Partition → **Strict Ordering** cho từng sản phẩm. Điều này cực kỳ quan trọng để Search Service index đúng thứ tự (không bao giờ index trạng thái cũ đè lên trạng thái mới).

### 1.2 Topics mà Product Service **Consume** (Lắng nghe)

| Topic Name | Consumer Group | Producer | Mô tả |
|---|---|---|---|
| `t.promotion.markdown.events` | `product-markdown-group` | Promotion Service | Lắng nghe `MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED` để tính [promotional_price](product_db_design.md#13-nhóm-tích-hợp-giá-khuyến-mãi-markdown-integration) |
| `t.inventory.events` | `product-inventory-sync-group` | Inventory Service | Lắng nghe `INVENTORY_QUANTITY_CHANGED` để cập nhật `display_quantity` và auto-hide sản phẩm hết hàng |
| `t.product.events` | `product-invalidation-group` | Product Service (chính nó) | Self-consume để xoá Cache Redis. Xem [Invalidation Consumer Worker](product_workers.md#2-invalidation-consumer-worker-xoá-cache-ngầm---optional) |

## 2. Cấu Trúc Message Header Chung (CloudEvents Wrapper)

Tất cả các tin nhắn phát lên Kafka đều tuân theo chuẩn [CloudEvents](https://cloudevents.io/) và được pipeline Debezium Outbox bổ sung header khi forward từ bảng `product_outbox_events`:

*   `event_id`: UUID của sự kiện (lấy từ `product_outbox_events.event_id`).
*   `event_type`: Loại sự kiện (VD: `PRODUCT_CREATED`, `PRODUCT_STATE_CHANGED`...).
*   `aggregate_type`: Loại Aggregate Root (VD: `Product`, `ProductSku`).
*   `product_id`: ID của entity chủ thể (Thay thế cho aggregate_id để luôn dùng chung partition key).
*   `correlation_id`: ID để truy vết chuỗi Saga xuyên suốt nhiều service (thường lấy từ upstream hoặc log tracing).
*   `timestamp`: Thời điểm event được tạo (ISO 8601 UTC).
*   `source`: Định danh service gốc phát event (Cố định: `product-service`).
*   `payload`: JSON Object chứa nội dung chi tiết — cấu trúc thay đổi theo từng `event_type`.

## 3. Chi Tiết Các Loại Event Phát Đi (Produced Events)

### 3.1. PRODUCT_CREATED
**Ngữ cảnh phát sinh:** Seller tạo mới một sản phẩm bản nháp (DRAFT) thành công. Xem [Workflow Tạo Sản Phẩm](product_workflow.md#2-api-tạo-mới-sản-phẩm-bản-nháp-create-concept).
**Consumers chính:** [Search Service](../search/search_events.md#21-product_created) (index sản phẩm mới, dù DRAFT chưa hiển thị public nhưng cần để Admin search).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174000",
  "event_type": "PRODUCT_CREATED",
  "aggregate_type": "Product",
  "product_id": "prod-uuid-001",
  "correlation_id": "req-123456",
  "timestamp": "2026-02-28T03:51:00Z",
  "source": "product-service",
  "payload": {
    "product_id": "prod-uuid-001",
    "seller_id": "seller-uuid-001",
    "category_id": "cat-dien-thoai",
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
        "variant_attributes_json": {"color": "Đen", "storage": "256GB"},
        "status": "ACTIVE",
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
        "variant_attributes_json": {"color": "Trắng", "storage": "256GB"},
        "status": "ACTIVE",
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
    "created_at": "2026-02-28T03:51:00Z"
  }
}
```
*(Ghi chú: Event chứa toàn bộ snapshot của sản phẩm tại thời điểm tạo, bao gồm cả danh sách SKUs. Downstream consumer không cần gọi API lại để lấy thêm dữ liệu.)*

### 3.2. PRODUCT_UPDATED
**Ngữ cảnh phát sinh:** Seller cập nhật thông tin sản phẩm (tên, mô tả, attributes, giá SKU, thêm/xoá SKU). Xem [Workflow Sửa Sản Phẩm](product_workflow.md#3-api-sửa-đổi-thông-tin-sản-phẩm).
**Consumers chính:** [Search Service](../search/search_events.md#22-product_updated) (re-index), Cart Service (invalidate product snapshot trong giỏ), Product Service (self-invalidate cache).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174001",
  "event_type": "PRODUCT_UPDATED",
  "aggregate_type": "Product",
  "product_id": "prod-uuid-001",
  "correlation_id": "req-123457",
  "timestamp": "2026-02-28T04:15:00Z",
  "source": "product-service",
  "payload": {
    "product_id": "prod-uuid-001",
    "seller_id": "seller-uuid-001",
    "category_id": "cat-dien-thoai",
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
        "variant_attributes_json": {"color": "Đen", "storage": "256GB"},
        "status": "ACTIVE",
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
> **Quan trọng về Idempotency:** Downstream consumers PHẢI kiểm tra `version` của event. Nếu `version` trong event ≤ `version` đã lưu local, event này là stale (cũ hơn) và phải bỏ qua. Đây là cơ chế chống sai thứ tự (Out-of-order) tự nhiên, tương tự `updated_at` check trong [Cart Service Events](../cart/cart_events.md#22-cart_item_updated).

### 3.3. PRODUCT_STATE_CHANGED
**Ngữ cảnh phát sinh:** Trạng thái sản phẩm thay đổi qua [State Machine](product_state_machine.md). VD: DRAFT → PENDING_REVIEW, PENDING_REVIEW → ACTIVE, ACTIVE → BANNED.
**Consumers chính:** [Search Service](../search/search_events.md#23-product_state_changed) (ẩn/hiện sản phẩm trên Elasticsearch), Notification Service (gửi mail cho Seller).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174002",
  "event_type": "PRODUCT_STATE_CHANGED",
  "aggregate_type": "Product",
  "product_id": "prod-uuid-001",
  "correlation_id": "req-123458",
  "timestamp": "2026-02-28T05:00:00Z",
  "source": "product-service",
  "payload": {
    "product_id": "prod-uuid-001",
    "seller_id": "seller-uuid-001",
    "old_status": "PENDING_REVIEW",
    "new_status": "ACTIVE",
    "changed_by": "admin-uuid-007",
    "changed_by_type": "ADMIN",
    "reason": null,
    "version": 4,
    "changed_at": "2026-02-28T05:00:00Z"
  }
}
```
*(Ghi chú: Khi `new_status = BANNED`, field `reason` là bắt buộc có giá trị. Notification Service sẽ dùng field này để gửi mail giải thích cho Seller.)*

### 3.4. PRODUCT_PRICE_UPDATED
**Ngữ cảnh phát sinh:** Giá sản phẩm thay đổi — bao gồm cả khi Seller sửa `original_price` LẪN khi [Markdown Worker](product_workers.md#5-markdown-price-calculator-worker) tính lại `promotional_price`. Xem [Promotion DB Design - Cơ chế Zero-Latency](../promotion/promotion_db_design.md#22-xử-lý-zero-latency-tính-toán-best-deal-mà-không-cần-discount_items-mapping).
**Consumers chính:** [Search Service](../search/search_events.md#24-product_price_updated) (cập nhật giá hiển thị trên Elasticsearch), Cart Service (cảnh báo giá đã đổi cho user).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174003",
  "event_type": "PRODUCT_PRICE_UPDATED",
  "aggregate_type": "ProductSku",
  "product_id": "prod-uuid-001",
  "correlation_id": "req-123459",
  "timestamp": "2026-02-28T06:00:00Z",
  "source": "product-service",
  "payload": {
    "product_id": "prod-uuid-001",
    "sku_id": "sku-uuid-001",
    "sku_code": "IP15PM-256-BLK",
    "original_price": 33990000.00,
    "promotional_price": 28891500.00,
    "previous_original_price": 34990000.00,
    "previous_promotional_price": null,
    "change_source": "MARKDOWN_APPLIED",
    "rule_id": 42,
    "version": 4,
    "updated_at": "2026-02-28T06:00:00Z"
  }
}
```
> **Field `change_source`:** Giúp downstream phân biệt nguồn thay đổi giá: `SELLER_UPDATE` (Seller tự sửa giá gốc), `MARKDOWN_APPLIED` (Promotion rule mới áp dụng), `MARKDOWN_EXPIRED` (Promotion rule hết hạn, `promotional_price` reset về null).

### 3.5. PRODUCT_SKU_STATUS_CHANGED
**Ngữ cảnh phát sinh:** Trạng thái của một SKU cụ thể thay đổi (VD: `ACTIVE` → `OUT_OF_STOCK` do Inventory sync, hoặc `ACTIVE` → `INACTIVE` do Seller tạm ngưng).
**Consumers chính:** Cart Service (cảnh báo user SKU không còn khả dụng), Search Service (cập nhật availability).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174004",
  "event_type": "PRODUCT_SKU_STATUS_CHANGED",
  "aggregate_type": "ProductSku",
  "product_id": "prod-uuid-001",
  "correlation_id": "req-123460",
  "timestamp": "2026-02-28T07:00:00Z",
  "source": "product-service",
  "payload": {
    "product_id": "prod-uuid-001",
    "sku_id": "sku-uuid-001",
    "sku_code": "IP15PM-256-BLK",
    "old_status": "ACTIVE",
    "new_status": "OUT_OF_STOCK",
    "change_source": "INVENTORY_SYNC",
    "version": 5,
    "updated_at": "2026-02-28T07:00:00Z"
  }
}
```

### 3.6. PRODUCT_DELETED
**Ngữ cảnh phát sinh:** Sản phẩm bị Soft Delete (cập nhật cờ `is_deleted = true`, trạng thái nghiệp vụ cũ được giữ nguyên).
**Consumers chính:** [Search Service](../search/search_events.md#26-product_deleted) (xoá khỏi Elasticsearch index), Cart Service (xoá sản phẩm khỏi giỏ hàng active).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174005",
  "event_type": "PRODUCT_DELETED",
  "aggregate_type": "Product",
  "product_id": "prod-uuid-001",
  "correlation_id": "req-123461",
  "timestamp": "2026-02-28T08:00:00Z",
  "source": "product-service",
  "payload": {
    "product_id": "prod-uuid-001",
    "seller_id": "seller-uuid-001",
    "sku_ids": ["sku-uuid-001", "sku-uuid-002"],
    "version": 5,
    "deleted_at": "2026-02-28T08:00:00Z"
  }
}
```

### 3.7. CATEGORY_UPDATED
**Ngữ cảnh phát sinh:** Tên, thuộc tính, hoặc đường dẫn của Danh mục (Category) bị thay đổi bởi Admin.
**Consumers chính:** Search Service (nhận Event để update tên và thông tin danh mục cho hàng loạt sản phẩm bằng lệnh Update By Query trên Elasticsearch).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "evt_abc124...",
  "aggregate_id": "cat_001",
  "aggregate_type": "Category",
  "event_type": "CATEGORY_UPDATED",
  "category_version": 4,
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

### 3.8. PRODUCT_SKU_DELETED
**Ngữ cảnh phát sinh:** Một SKU cụ thể bị Soft Delete khỏi Sản phẩm bởi Seller (các SKU khác trong sản phẩm vẫn tồn tại).
**Consumers chính:** Search Service (nhận Event để xóa hoặc đánh dấu DELETED cho SKU Document, tránh việc User search ra kết quả lỗi). Cart Service (xoá SKU này khỏi giỏ hàng).
**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "evt_skudel_xxx",
  "aggregate_id": "prod-uuid-001",
  "aggregate_type": "Product",
  "event_type": "PRODUCT_SKU_DELETED",
  "version": 6,
  "timestamp": "2026-03-24T10:00:00Z",
  "payload": {
    "product_id": "prod-uuid-001",
    "sku_id": "sku-uuid-002",
    "deleted_at": "2026-03-24T10:00:00Z"
  }
}
```

### 3.9. CATEGORY_DELETED
**Ngữ cảnh phát sinh:** Danh mục bị Xóa Cứng hoặc Xóa Mềm bởi Admin (System Constraint: thông thường Admin chỉ được xoá khi danh mục không còn Product nào, nhưng đề phòng trường hợp Force Delete).
**Consumers chính:** Search Service (cập nhật trạng thái HIDDEN cho các Product mồ côi nếu có và xoá Cache).
**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "evt_catdel_xxx",
  "aggregate_id": "cat_001",
  "aggregate_type": "Category",
  "event_type": "CATEGORY_DELETED",
  "category_version": 5,
  "timestamp": "2026-03-24T10:00:00Z",
  "payload": {
    "category_id": "cat_001",
    "deleted_at": "2026-03-24T10:00:00Z",
    "category_version": 5
  }
}
```

### 3.10. PRODUCT_VISIBILITY_REEVALUATE_REQUESTED (Internal)
**Ngữ cảnh phát sinh:** Seller đổi ý mở lại cờ `seller_intention_hidden = false` trong khi Product vẫn `HIDDEN` và chưa có inventory event mới.
**Consumers chính:** Chỉ Product Service internal worker (không public cho service khác).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "evt_visibility_recheck_xxx",
  "event_type": "PRODUCT_VISIBILITY_REEVALUATE_REQUESTED",
  "aggregate_type": "Product",
  "product_id": "prod-uuid-001",
  "timestamp": "2026-03-24T10:10:00Z",
  "source": "product-service",
  "payload": {
    "product_id": "prod-uuid-001",
    "trigger": "SELLER_UNHIDE_INTENTION_CHANGED",
    "version": 7
  }
}
```
**Logic xử lý:** Worker chạy guard trong 1 transaction: `status = HIDDEN` + `seller_intention_hidden = false` + `EXISTS sku còn hàng` -> nếu đạt điều kiện thì trigger `AUTO_REACTIVATE_IN_STOCK`.

## 4. Chi Tiết Các Loại Event Tiêu Thụ (Consumed Events)

### 4.1. MARKDOWN_CREATED (Từ Promotion Service)
**Topic:** `t.promotion.markdown.events`
**Consumer Group:** `product-markdown-group`
**Xử lý bởi:** [Markdown Price Calculator Worker](product_workers.md#5-markdown-price-calculator-worker)

**Cấu trúc Payload kỳ vọng (JSON):**
```json
{
  "event_type": "MARKDOWN_CREATED",
  "payload": {
    "rule_id": 42,
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
**Logic xử lý:** Product Service query DB nội bộ lấy tất cả SKU thuộc `category_id = ref_id` → tính `promotional_price` → batch update `product_skus` + insert `product_markdown_applied` → phát `PRODUCT_PRICE_UPDATED` events cho từng SKU bị ảnh hưởng.

### 4.2. MARKDOWN_UPDATED (Từ Promotion Service)
**Topic:** `t.promotion.markdown.events`
**Consumer Group:** `product-markdown-group`
**Xử lý bởi:** [Markdown Price Calculator Worker](product_workers.md#5-markdown-price-calculator-worker)

**Cấu trúc Payload kỳ vọng (JSON):**
```json
{
  "event_type": "MARKDOWN_UPDATED",
  "payload": {
    "rule_id": 42,
    "changes": {
      "discount_value": 20.00,
      "end_time": "2026-03-14T23:59:59Z"
    }
  }
}
```
**Logic xử lý:** Product Service query `product_markdown_applied WHERE rule_id = 42` → lấy danh sách `sku_id` bị ảnh hưởng → tính lại `promotional_price` với các thông số thay đổi được áp dụng → batch update `product_skus` và `product_markdown_applied` → phát `PRODUCT_PRICE_UPDATED` events cho từng SKU bị ảnh hưởng.

### 4.3. MARKDOWN_ENDED (Từ Promotion Service)
**Topic:** `t.promotion.markdown.events`
**Consumer Group:** `product-markdown-group`

**Cấu trúc Payload kỳ vọng (JSON):**
```json
{
  "event_type": "MARKDOWN_ENDED",
  "payload": {
    "rule_id": 42,
    "scope_type": "CATEGORY",
    "ref_id": "cat-dien-thoai",
    "reason": "EXPIRED"
  }
}
```

> **Thống nhất tên event:** Event này tên chính thức là `MARKDOWN_ENDED` (không phải `MARKDOWN_EXPIRED`). Tên `MARKDOWN_ENDED` bao gồm cả "hết hạn" lẫn "Admin tắt thủ công", phân biệt qua field `reason`:
> - `reason: "EXPIRED"` → CronJob [Markdown Expiry Scanner](../promotion/promotion_workflow.md#6-markdown-expiry-scanner-cronjob) phát hiện `end_time < NOW()`
> - `reason: "ADMIN_DISABLED"` → Admin tắt thủ công trên dashboard
>
> Xem định nghĩa event gốc tại [Promotion Events - MARKDOWN_ENDED](../promotion/promotion_events.md#23-markdown_ended).

**Logic xử lý:** Query `product_markdown_applied WHERE rule_id = 42` → lấy danh sách `sku_id` bị ảnh hưởng → reset `product_skus.promotional_price = NULL` → delete records từ `product_markdown_applied` → phát `PRODUCT_PRICE_UPDATED` events (với `change_source = MARKDOWN_EXPIRED`).

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
  "product_id": "prod-uuid-001",
  "correlation_id": "sys-abc",
  "timestamp": "2026-02-28T08:00:00Z",
  "source": "inventory-service",
  "payload": {
    "sku_id": "sku-uuid-001",
    "available_quantity": 0,
    "previous_available_quantity": 15,
    "change_type": "ORDER_DEDUCTED"
  }
}
```
**Logic xử lý:** Update `product_skus.display_quantity = available_quantity`. Nếu `available_quantity = 0` và SKU đang `ACTIVE` → chuyển SKU status sang `OUT_OF_STOCK` → phát `PRODUCT_SKU_STATUS_CHANGED`. Nếu `available_quantity > 0` và SKU đang `OUT_OF_STOCK` → chuyển về `ACTIVE`. Xem thêm [State Machine - Auto Hide](product_state_machine.md#2-các-lệnh-chuyển-tiếp-events--transitions).

## 5. Schema Versioning & Backward Compatibility

*   **Event Schema Version:** Mỗi event payload nên chứa field ẩn `schema_version: 1` (hiện tại v1). Khi schema thay đổi trong tương lai, version tăng lên.
*   **Backward Compatibility Rule:** Chỉ được phép **thêm field mới** (additive changes). Không được xoá hoặc đổi tên field hiện có. Nếu cần breaking change, phải tạo event_type mới (VD: `PRODUCT_CREATED_V2`).
*   **Consumer Resilience:** Tất cả consumers PHẢI ignore unknown fields (sử dụng `@JsonIgnoreProperties(ignoreUnknown = true)` trong Java/Spring hoặc tương đương).
