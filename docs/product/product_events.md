# Product Service - Định Nghĩa Các Sự Kiện & Kafka Topics (Events Structure)

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](product_workflow.md)
*   [Background Workers & Jobs](product_workers.md)
*   [Caching Schema (Redis)](product_caching_schema.md)
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

Kiến trúc sự kiện này đảm nhậm nhiệm vụ đồng bộ CQRS từ [Write Store (PostgreSQL)](product_db_design.md) sang Elasticsearch (Search Service) và Redis Cache, đồng thời phục vụ các hệ thống downstream (Search, Notification, Promotion...) subscribe để phản ứng theo logic riêng.

> **Identity contract (bắt buộc):*
> **`rule_id` contract (bắt buộc):** Trong Product Service, `rule_id` được chuẩn hoá về string ở consumer boundary. Nếu upstream gửi số (legacy), worker phải convert sang string trước khi ghi DB/cache và trước khi so sánh.
>
> **Chuẩn hoá event tên:** Hệ thống chỉ dùng duy nhất event `SKU_UPDATED` cho mọi thay đổi cấp SKU (bao gồm cả thay đổi giá và thay đổi trạng thái). Các tên event cũ `SKU_PRICE_UPDATED` và `SKU_STATUS_CHANGED` đã bị loại bỏ hoàn toàn khỏi mọi tài liệu. Mọi reference trong các file khác phải thống nhất dùng `SKU_UPDATED`.

## 1. Bản Đồ Kafka Topics

### 1.1 Topics mà Product Service **Produce** (Phát đi)

| Topic Name | Partition Key | Consumers | Mô tả |
|---|---|---|---|
| `t.product.events` | `product_id` | Search Service, Notification Service, Promotion Service (allowlist: `PRODUCT_STATUS_CHANGED`, `PRODUCT_DELETED`, `SKU_DELETED`), Event Handler Worker (`product-event-handler-group`, riêng `SKU_UPDATED`) | Topic chính chứa mọi sự kiện thay đổi dữ liệu sản phẩm |
| `t.category.events` | `category_id` | Search Service, Product Service | Topic phát sự kiện thay đổi cấu trúc danh mục, tách biệt hoàn toàn để tránh xung đột partition key |

> **Ghi chú Partition Key:** Sử dụng `product_id` làm key cho `t.product.events` đảm bảo tất cả events của cùng 1 sản phẩm luôn vào đúng 1 Partition → **Strict Ordering** cho từng sản phẩm. Tương tự, dùng `category_id` cho `t.category.events`.

### 1.2 Topics mà Product Service **Consume** (Lắng nghe)

| Topic Name | Consumer Group | Producer | Mô tả |
|---|---|---|---|
| `t.promotion.markdown.events` | `product-markdown-group` | Promotion Service | Lắng nghe đúng allowlist `MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED` để tính [promotional_price](product_db_design.md#13-nhóm-tích-hợp-giá-khuyến-mãi-markdown-integration)|
| `t.inventory.events` | `product-inventory-fast-sync-group` | Inventory Service | Phase 1: Lock-Free Fast Sync (Two-Phase). Xem [Inventory Sync Optimization](inventory_sync_optimization.md) |
| `t.product.events` | `product-event-handler-group` | Product Service (chính nó) | Self-consume `SKU_UPDATED` từ Markdown Worker để refresh `product:sku:{sku_id}` (cache refresh sau DB commit). Xem [Event Handler Worker](product_workers.md#2-event-handler-worker) |
| `t.category.events` | `product-event-handler-group` | Product Service (chính nó) | Self-consume `CATEGORY_DELETED` để cập nhật Write Model (chuyển sản phẩm liên quan sang `HIDDEN`) |

## 2. Cấu Trúc Message Header Chung (CloudEvents Wrapper)

Tất cả các tin nhắn phát lên Kafka đều tuân theo chuẩn [CloudEvents](https://cloudevents.io/) và được pipeline Debezium Outbox bổ sung header khi forward từ bảng `product_outbox_events`:

*   `event_id`: UUID của sự kiện (lấy từ `product_outbox_events.event_id`).
*   `event_type`: Loại sự kiện (VD: `PRODUCT_CREATED`, `PRODUCT_STATUS_CHANGED`...).
*   `aggregate_type`: Loại Aggregate Root (VD: `Product`, `ProductSku`, `Category`).
*   `aggregate_id`: ID của entity chủ thể theo `aggregate_type` (Product events dùng `product_id`, ProductSku events dùng `sku_id`, Category events dùng `category_id`).
*   `partition_key`: Khóa định tuyến Kafka lấy từ cột `product_outbox_events.partition_key` (`product_id` cho `t.product.events`, `category_id` cho `t.category.events`). **Application-level validation bắt buộc phải kiểm tra NOT NULL + NOT EMPTY trước khi insert outbox** để đảm bảo event không rơi lung tung sang các partition khác nhau làm trật tự đồng bộ (Ordering) bị phá vỡ.
*   `correlation_id`: ID để truy vết chuỗi Saga xuyên suốt nhiều service (thường lấy từ upstream hoặc log tracing).
*   `timestamp`: Thời điểm event được tạo (ISO 8601 UTC).
*   `source`: Định danh service gốc phát event (Cố định: `product-service`).
*   `schema_version`: Phiên bản schema bắt buộc của message (hiện tại cố định = `1`).
*   `payload`: JSON Object chứa nội dung chi tiết — cấu trúc thay đổi theo từng `event_type`.

> **Quy ước hiển thị mẫu JSON:** Các mẫu bên dưới đều bao gồm `schema_version: 1` ở top-level message.

## 3. Chi Tiết Các Loại Event Phát Đi (Produced Events)

### 3.1. PRODUCT_CREATED
**Ngữ cảnh phát sinh:** admin tạo mới một sản phẩm ở trạng thái DRAFT thành công. Xem [Endpoint Tạo Sản Phẩm](product_endpoints.md#11-tạo-sản-phẩm-bản-nháp).
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
    "created_at": "2026-02-28T03:51:00Z",
    "updated_at": "2026-02-28T03:51:00Z"
  }
}
```
*(Ghi chú: Event chứa toàn bộ snapshot của sản phẩm tại thời điểm tạo, bao gồm cả danh sách SKUs. Downstream consumer không cần gọi API lại để lấy thêm dữ liệu.)*

> **Contract giá trong SKU snapshot (BẮT BUỘC):** Với `PRODUCT_CREATED`/`PRODUCT_UPDATED`, mỗi phần tử `skus[]` phải có đủ `original_price`, `promotional_price`, `best_price`, `has_promotion` để Search phản chiếu đúng giá mà không cần tự tính lại.
>
> **Contract trạng thái SKU (chuẩn hoá):** Producer mới nên phát `skus[].sku_status` làm field canonical. Trường `skus[].status` chỉ duy trì như alias tương thích ngược trong giai đoạn chuyển đổi.
>
> **Contract phạm vi snapshot (BẮT BUỘC):** Payload `PRODUCT_UPDATED` phải là snapshot đầy đủ của Product tại thời điểm commit (không phải delta patch từ FE). Nếu producer tạm thời chưa phát được full snapshot, consumer invalidation phải coi đó là payload không đầy đủ và fallback đọc snapshot từ PostgreSQL.

### 3.2. PRODUCT_UPDATED
**Ngữ cảnh phát sinh:** admin cập nhật thông tin cấp sản phẩm (tên, mô tả, danh mục, thuộc tính, hình ảnh). Xem [Endpoint Cập Nhật Sản Phẩm](product_endpoints.md#14-cập-nhật-sản-phẩm).
**Consumers chính:** [Search Service](../search/search_events.md#22-product_updated) (re-index product-level fields trên các SKU documents thuộc product), Product Service (self-invalidate cache).

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
    "category_id": "cat-dien-thoai",
    "status": "ACTIVE",
    "name": "iPhone 15 Pro Max 256GB (Updated)",
    "description": "Mô tả cập nhật...",
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
    "sku_ids": ["sku-uuid-001", "sku-uuid-002"],
    "updated_at": "2026-02-28T04:15:00Z"
  }
}
```
> **Contract `sku_ids[]` (BẮT BUỘC):** Payload bắt buộc phải chứa `sku_ids[]` — danh sách tất cả SKU thuộc product tại thời điểm commit. Backend truy vấn trong cùng transaction trước khi insert outbox. Search Service dùng `sku_ids[]` để xác định chính xác các document cần update trên Elasticsearch (mỗi document có `_id = sku_id`).


> **Source-selection contract cho `product-invalidation-group` (BẮT BUỘC):**
> - Ưu tiên nguồn payload khi payload là hợp lệ (chứa product-level fields + `sku_ids[]`).
> - Payload KHÔNG chứa SKU-level fields (`skus[]`). Cache refresh cho từng SKU được thực hiện dựa trên `sku_ids[]` — Event Handler query DB để lấy full snapshot (bao gồm SKU data) rồi ghi đè cache.
> - Trong cùng 1 event `PRODUCT_UPDATED`, không được trộn một phần SKU từ payload và phần còn lại từ DB.
> - Không fallback sang Search/Elasticsearch.

### 3.3. PRODUCT_STATUS_CHANGED
**Ngữ cảnh phát sinh:** Trạng thái sản phẩm thay đổi qua [State Machine](product_state_machine.md). VD: DRAFT → ACTIVE, ACTIVE → HIDDEN, HIDDEN → ACTIVE.
**Consumers chính:** [Search Service](../search/search_events.md#23-PRODUCT_STATUS_CHANGED) (ẩn/hiện sản phẩm trên Elasticsearch), Notification Service (gửi mail cho admin), Promotion Service (Lắng nghe để bắt các Sản phẩm mới được duyệt lên `ACTIVE`, qua đó áp bù giá Markdown nếu chúng thuộc Category đang có Flash Sale).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174002",
  "event_type": "PRODUCT_STATUS_CHANGED",
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
    "old_status": "DRAFT",
    "new_status": "ACTIVE",
    "changed_by": "admin-uuid-007",
    "changed_by_type": "ADMIN",
    "reason": null,
    "updated_at": "2026-02-28T05:00:00Z",
    "changed_at": "2026-02-28T05:00:00Z"
  }
}
```
*(Ghi chú: Sự kiện này bắt buộc phải nạp thêm mảng `sku_ids` vào payload để **Worker Invalidation Cache** không bị mù, nó có thể xoá Cache cho các SKU đó ngay lập tức mà không phải tốn thêm Query xuống DB mò mẫm.)*

> **Nguồn dữ liệu `sku_ids` (BẮT BUỘC):** FE không gửi `sku_ids` khi gọi API đổi trạng thái Product. Backend phải tự truy vấn danh sách SKU liên đới trong cùng transaction và đóng gói vào payload `PRODUCT_STATUS_CHANGED` trước khi insert outbox.

### 3.4. SKU_UPDATED
**Ngữ cảnh phát sinh:** Bất kỳ thay đổi nào cấp SKU — bao gồm: (1) Admin sửa `original_price`, `variant_attributes`, `media` qua [Endpoint Cập Nhật SKU](product_endpoints.md#18-cập-nhật-sku); (2) [Markdown Worker](product_workers.md#4-markdown-price-calculator-worker) tính lại `promotional_price` khi rule được tạo/cập nhật/kết thúc; (3) [Inventory Sync Phase 2](product_workers.md#52-phase-2-inventory-async-db-sync-worker) đổi `sku_status` qua FSM auto-hide/reactivate hoặc Admin đổi trạng thái SKU.

**Consumers chính:** [Search Service](../search/search_events.md) (cập nhật Elasticsearch), [Event Handler Worker](product_workers.md#2-event-handler-worker) (refresh `product:sku:{sku_id}`).

> **Partition Key contract (BẮT BUỘC):** Vì `aggregate_type = ProductSku`, `partition_key` trong outbox phải là `sku_id` (không phải `product_id`) để đảm bảo event được định tuyến đúng partition. Quy tắc thống nhất: toàn bộ SKU-level events (`SKU_CREATED`, `SKU_UPDATED`, `SKU_DELETED`) dùng `sku_id` làm `partition_key`; toàn bộ Product-level events dùng `product_id`.

> **`change_source` (BẮT BUỘC khi `sku_status` trong `changed_fields`):** Nguồn phát của thay đổi SKU. Giá trị bắt buộc nằm trong payload, KHÔNG nằm trong header CloudEvents.

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174003",
  "event_type": "SKU_UPDATED",
  "aggregate_type": "ProductSku",
  "aggregate_id": "sku-uuid-001",
  "partition_key": "sku-uuid-001",
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
    "sku_status": "ACTIVE",
    "variant_attributes_json": {"color": "Đen", "storage": "256GB"},
    "product_status_changed": false,
    "new_product_status": null,
    "change_source": "ADMIN_UPDATE",
    "updated_at": "2026-02-28T06:00:00Z",
    "changed_fields": ["original_price", "promotional_price", "best_price", "has_promotion"]
  }
}
```

> **Field `changed_fields` (BẮT BUỘC):** Array liệt kê các field thực sự thay đổi giá trị so với trước đó. Downstream dùng `changed_fields` để xác định hành động xử lý phù hợp — không phải so sánh payload với DB. Các giá trị hợp lệ:
> - `original_price` — Admin sửa giá gốc
> - `promotional_price` — Markdown Worker tính lại giá khuyến mãi
> - `best_price` — Luôn đi kèm khi price thay đổi
> - `has_promotion` — Luôn đi kèm khi price thay đổi
> - `sku_status` — Inventory Sync / Admin đổi trạng thái SKU
> - `variant_attributes` — Admin sửa thuộc tính biến thể
> - `media` — Admin sửa hình ảnh SKU
> - `product_status` — FSM trigger đổi cả product_status (khi AUTO_HIDE_OUT_OF_STOCK / AUTO_REACTIVATE_IN_STOCK)
>
> Payload luôn chứa **toàn bộ snapshot SKU** (tất cả field, không phải delta). `changed_fields` chỉ dùng để consumer phân biệt loại thay đổi.

> **Field `best_price` (BẮT BUỘC):** Giá thấp nhất đã được Product Service tính sẵn: `best_price = LEAST(original_price, COALESCE(promotional_price, original_price))`. Kết quả làm tròn 2 chữ số thập phân, clamp tối thiểu về `0`.
>
> **Trường hợp `promotional_price = NULL`:** `best_price = original_price`. Downstream chỉ được đọc và phản chiếu giá trị này, không tự tính lại. Khi `promotional_price` được set: `best_price = promotional_price` (nếu `promotional_price < original_price`), ngược lại giữ nguyên `original_price`.

> **Trường hợp FSM trigger đổi cả `product_status`:** Khi `change_source` là `AUTO_HIDE_OUT_OF_STOCK` hoặc `AUTO_REACTIVATE_IN_STOCK`, payload bắt buộc mang thêm `product_status_changed: true`, `new_product_status: "HIDDEN"` (hoặc `"ACTIVE"`), và `changed_fields` phải chứa `"product_status"`. Search Service xử lý cả SKU-level và Product-level update trong cùng 1 event mà không cần thêm `PRODUCT_STATUS_CHANGED` riêng.

> **`change_source` (BẮT BUỘC khi `sku_status` trong `changed_fields`):**
> - `AUTO_HIDE_OUT_OF_STOCK`: Inventory Sync Phase 2 detect tất cả SKU hết hàng → product bị auto-hide.
> - `AUTO_REACTIVATE_IN_STOCK`: Inventory Sync Phase 2 detect có SKU có hàng trở lại → product được auto-reactivate.
> - `ADMIN_SET_INACTIVE`: Admin chủ động set SKU sang `INACTIVE`.
> - `ADMIN_SET_ACTIVE`: Admin chủ động set SKU sang `ACTIVE`.
> - `ADMIN_UPDATE`: Admin sửa SKU qua [Endpoint Cập Nhật SKU](product_endpoints.md#18-cập-nhật-sku).
> - `MARKDOWN_CREATED` / `MARKDOWN_UPDATED` / `MARKDOWN_ENDED`: Markdown Worker tính lại giá.
> - `INVENTORY_SYNC_PHASE2`: Chỉ được phát khi Phase 2 thực sự thay đổi `sku_status`.

> **Contract phát event cho Inventory Phase 1 vs Phase 2:**
> - **Phase 1 (Lua CAS):** KHÔNG phát `SKU_UPDATED` vào Kafka. Phase 1 chỉ cập nhật Redis cache. Việc phát event được giao cho **Phase 2** sau khi DB commit.
> - **Phase 2 (DB Sync):** PHÁT `SKU_UPDATED` (với `changed_fields = ["sku_status", ...]`) vào Kafka sau khi batch update + FSM trigger thành công.
> - **Admin API (Cập Nhật SKU):** PHÁT `SKU_UPDATED` ngay trong API transaction.
> - **Markdown Worker:** PHÁT `SKU_UPDATED` (với `changed_fields = ["original_price", "promotional_price", "best_price", "has_promotion"]`) cho từng SKU bị ảnh hưởng.

> **Ownership Cache Refresh (BẮT BUỘC):** Với thay đổi giá do Markdown, key `product:sku:{sku_id}` phải do `product-event-handler-group` ghi đè dựa trên `SKU_UPDATED`. Markdown Worker chỉ quản lý key phụ `product:markdown:active:{sku_id}` để tránh double-write bất đồng bộ.

### 3.5. SKU_CREATED
**Ngữ cảnh phát sinh:** Admin thêm một SKU mới vào sản phẩm đã tồn tại qua [Endpoint Thêm SKU](product_endpoints.md#17-thêm-sku-vào-sản-phẩm). Event này phát ra thay cho `PRODUCT_UPDATED` — không phải thay đổi product-level fields mà là việc tạo một document SKU mới trên Search Service.
**Consumers chính:** [Search Service](../search/search_events.md#26-sku_created) (tạo document SKU mới trên Elasticsearch), Product Service Event Handler (refresh `product:sku:{sku_id}`).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174099",
  "event_type": "SKU_CREATED",
  "aggregate_type": "ProductSku",
  "aggregate_id": "sku-uuid-003",
  "correlation_id": "req-123499",
  "timestamp": "2026-06-10T21:00:00Z",
  "source": "product-service",
  "schema_version": 1,
  "payload": {
    "product_id": "prod-uuid-001",
    "product_name": "iPhone 15 Pro Max 256GB",
    "category_id": "cat-dien-thoai",
    "sku_id": "sku-uuid-003",
    "sku_code": "IP15PM-256-WHT",
    "original_price": 34990000.00,
    "promotional_price": null,
    "best_price": 34990000.00,
    "has_promotion": false,
    "sku_status": "ACTIVE",
    "product_status": "DRAFT",
    "variant_attributes_json": {"color": "Trắng", "storage": "256GB"},
    "media": [
      {
        "media_type": "IMAGE",
        "url": "https://cdn.example.com/ip15-white.jpg",
        "sort_order": 1
      }
    ],
    "updated_at": "2026-06-10T21:00:00Z",
    "created_at": "2026-06-10T21:00:00Z"
  }
}
```

> **Partition Key contract (BẮT BUỘC):** Vì `aggregate_type = ProductSku`, `partition_key` trong outbox phải là `sku_id` (không phải `product_id`) để đảm bảo event được định tuyến đúng partition khi Search Service cần xử lý.
>
> **Contract enrichment fields (BẮT BUỘC):** Backend phải bổ sung `product_name`, `category_id`, `sku_code` trong cùng transaction trước khi insert outbox. Đây là dữ liệu cần thiết để Search Service index document đầy đủ mà không cần gọi lại API.
>
> **Contract giá trong SKU snapshot (BẮT BUỘC):** Payload phải có đủ `original_price`, `promotional_price`, `best_price`, `has_promotion` để Search phản chiếu đúng giá mà không cần tự tính lại.
>
> **Phân biệt với PRODUCT_CREATED:** Khi tạo sản phẩm mới ở endpoint 1.1, toàn bộ SKU được tạo đồng thời trong cùng transaction và phát qua `PRODUCT_CREATED` (payload chứa `skus[]` đầy đủ). Search Service loop qua `payload.skus[]` để index N documents. Không cần phát thêm `SKU_CREATED` cho trường hợp này. `SKU_CREATED` chỉ dùng khi thêm SKU vào sản phẩm đã tồn tại.

### 3.6. SKU_DELETED
**Ngữ cảnh phát sinh:** Một SKU cụ thể bị Soft Delete khỏi Sản phẩm bởi admin (các SKU khác trong sản phẩm vẫn tồn tại).
**Consumers chính:** Search Service (nhận Event để xóa hoặc đánh dấu DELETED cho SKU Document, tránh việc User search ra kết quả lỗi).
**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "evt_skudel_xxx",
  "event_type": "SKU_DELETED",
  "aggregate_type": "ProductSku",
  "aggregate_id": "sku-uuid-002",
  "partition_key": "sku-uuid-002",
  "correlation_id": "req-123492",
  "source": "product-service",
  "schema_version": 1,
  "timestamp": "2026-03-24T10:00:00Z",
  "payload": {
    "product_id": "prod-uuid-001",
    "product_name": "iPhone 15 Pro Max 256GB",
    "category_id": "cat-dien-thoai",
    "category_name": "Điện thoại",
    "sku_id": "sku-uuid-002",
    "sku_code": "IP15PM-256-BLK",
    "updated_at": "2026-03-24T10:00:00Z",
    "deleted_at": "2026-03-24T10:00:00Z"
  }
}
```

> **Contract guard cho tombstone (BẮT BUỘC):** `SKU_DELETED` phải luôn có `payload.updated_at` để downstream guard out-of-order khi scripted-upsert tombstone.
>
> **Partition Key (BẮT BUỘC):** `partition_key = sku_id` — tuân thủ quy tắc chuẩn hóa SKU-level events.
>
> **Enrichment fields bắt buộc:** Backend phải bổ sung `product_name`, `category_id`, `category_name`, `sku_code` trong cùng transaction trước khi insert outbox. Đây là dữ liệu cần thiết để Search Service cleanup Suggestion Cache và audit log mà không phải query ES document riêng.

### 3.7. PRODUCT_DELETED
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
    "updated_at": "2026-02-28T08:00:00Z",
    "deleted_at": "2026-02-28T08:00:00Z"
  }
}
```

> **Nguồn dữ liệu `sku_ids` cho xóa mềm (BẮT BUỘC):** FE chỉ gửi `product_id`. Backend phải tự lấy danh sách SKU thuộc Product trong transaction xóa mềm và đóng gói vào payload `PRODUCT_DELETED` để invalidation refresh đầy đủ các key SKU liên quan.

### 3.8. CATEGORY_CREATED
**Ngữ cảnh phát sinh:** Danh mục (Category) được tạo mới bởi Admin. Xem [Endpoint Tạo Danh Mục](product_endpoints.md#24-tạo-danh-mục).
**Consumers chính:** Search Service (nhận Event để index danh mục mới vào Elasticsearch phục vụ category tree/suggestion).

**Cấu trúc Payload (JSON):**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174012",
  "event_type": "CATEGORY_CREATED",
  "aggregate_type": "Category",
  "aggregate_id": "cat_001",
  "correlation_id": "req-123495",
  "source": "product-service",
  "schema_version": 1,
  "timestamp": "2026-06-10T10:00:00Z",
  "payload": {
    "category_id": "cat_001",
    "name": "Điện thoại thông minh",
    "slug": "dien-thoai-thong-minh",
    "parent_id": "cat-dien-tu",
    "level": 2,
    "is_leaf": true,
    "path": "dien-tu/dien-thoai-thong-minh"
  }
}
```

> **Enrichment fields bắt buộc:** Backend phải bổ sung `path` (full breadcrumb path từ root đến category) trong cùng transaction trước khi insert outbox. Đây là dữ liệu cần thiết để Search Service build category tree và facet filter mà không phải query lại PostgreSQL.

### 3.9. CATEGORY_UPDATED
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
    "new_path": "dien-tu/dien-thoai-thong-minh"
  }
}
```

### 3.10. CATEGORY_DELETED
**Ngữ cảnh phát sinh:** Danh mục bị Xóa Cứng hoặc Xóa Mềm bởi Admin (System Constraint: thông thường Admin chỉ được xoá khi danh mục không còn Product nào, nhưng đề phòng trường hợp Force Delete).
**Consumers chính:** Product Service (`product-event-handler-group`) và Search Service (`search-category-indexer`).

**Mô tả luồng xử lý:**
- Product Service xử lý Write Model (đổi `product.status` thành `HIDDEN` tại PostgreSQL) — **KHÔNG phát thêm event `PRODUCT_STATUS_CHANGED`** vì không cần thiết.
- Cả Product Service (DB) và Search Service (ES) nhận `CATEGORY_DELETED` qua Kafka cùng lúc. Thứ tự không xác định, nhưng Search Service guard bằng `event.updated_at` (chỉ update khi mới hơn). Đây là **acceptable eventual consistency**: Cart/Checkout không phụ thuộc Elasticsearch cho luồng mua hàng.

**Payload contract:**
- Payload nên mang `descendant_category_ids` khi xoá cả cây danh mục. Nếu field này thiếu (legacy), consumer tối thiểu phải xử lý `category_id` gốc và có cơ chế self-heal subtree theo snapshot category tree nội bộ.
- Payload bắt buộc mang `updated_at` (ISO 8601 UTC) để downstream dùng làm guard timestamp ordering.
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
    "updated_at": "2026-03-24T10:00:00Z"
  }
}
```

### 3.11. Contract Nạp Payload Từ FE -> BE -> Outbox

| Event | Dữ liệu từ FE | Dữ liệu BE bắt buộc tự bổ sung | Mục tiêu downstream |
|---|---|---|---|
| `SKU_CREATED` | `sku_code`, `original_price`, `variant_attributes_json`, `media` | Truy vấn `product_name`, `category_id`, `sku_code` trong cùng transaction trước khi insert outbox (payload chứa đầy đủ SKU snapshot, không bao gồm product-level fields) | Search tạo document SKU mới trên Elasticsearch (index document đầy đủ từ payload, không cần gọi API). Event Handler refresh `product:sku:{sku_id}`. Cache refresh do In-band API đã xử lý |
| `SKU_UPDATED` | `original_price` (nếu sửa), `variant_attributes_json` (nếu sửa), `media` (nếu sửa) — partial, chỉ field thay đổi | Truy vấn để tính `changed_fields[]`, tính `best_price`, re-calculate `promotional_price` nếu SKU có markdown active, bổ sung các field không thay đổi để payload đầy đủ snapshot | Search partial update theo `changed_fields`. Event Handler refresh cache theo `changed_fields`. Markdown re-calculate trong cùng transaction |
| `PRODUCT_CREATED` | Product-level fields + `skus[]` | Tính toán `path` (full breadcrumb path), không cần bổ sung gì khác | Search index product document + loop qua `payload.skus[]` để tạo N SKU documents |
| `PRODUCT_UPDATED` | Product-level fields (`name`, `description`, `category_id`, `attributes_json`, `media`) | Truy vấn `sku_ids[]` thuộc product trong cùng transaction trước khi insert outbox (không bao gồm SKU-level fields) | Search bulk partial update product-level fields trên các SKU documents thuộc product (loop theo `sku_ids[]`). Cache refresh do In-band API đã xử lý |
| `PRODUCT_STATUS_CHANGED` | Command đổi trạng thái (`SUBMIT`, `HIDE`, `SHOW`) + `product_id` | Truy vấn danh sách `sku_ids[]` liên đới trong cùng transaction FSM | Event Handler refresh cache `product:sku:{sku_id}` (In-band fallback) |
| `CATEGORY_CREATED` | `name`, `slug`, `parent_id`, `level`, `is_leaf` | Tính toán `path` (full breadcrumb path từ root đến category) trong cùng transaction trước khi insert outbox | Search index category mới vào Elasticsearch (category tree/suggestion/facet) |
| `CATEGORY_DELETED` | `category_id` | Truy vấn `descendant_category_ids[]` trong cùng transaction, update `product.status = 'HIDDEN'` tại PostgreSQL (không phát thêm `PRODUCT_STATUS_CHANGED`) | Search nullify category metadata + update `product_status = HIDDEN` |
| `PRODUCT_DELETED` | `product_id` | Truy vấn danh sách `sku_ids[]` thuộc Product trước/sát thời điểm soft-delete | Search xóa khỏi index. Cache refresh do In-band API đã xử lý |

> **Nguyên tắc nhất quán:** Payload outbox là hợp đồng backend phát hành, không phải bản sao thô 1:1 từ request FE.

## 4. Chi Tiết Các Loại Event Tiêu Thụ (Consumed Events)

### 4.1. MARKDOWN_CREATED (Từ Promotion Service)
**Topic:** `t.promotion.markdown.events`
**Consumer Group:** `product-markdown-group`
**Xử lý bởi:** [Markdown Price Calculator Worker](product_workers.md#4-markdown-price-calculator-worker)

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
**Logic xử lý:** Product Service xác định tập SKU bị ảnh hưởng theo `scope`, sau đó group theo `product_id` và xử lý transaction-per-product theo lock order **Product -> SKU**. Với mỗi `product_id`, worker lock Product cha `FOR NO KEY UPDATE` (kèm `lock_timeout` + retry policy), lock SKU con `FOR UPDATE` để lấy `original_price` live rồi mới tính `promotional_price`. Cuối transaction batch update `product_skus`, upsert `product_markdown_applied`, insert outbox `SKU_UPDATED` cho từng SKU bị ảnh hưởng (với `changed_fields = ["original_price", "promotional_price", "best_price", "has_promotion"]`).

> **Start-time gate bắt buộc:** Nếu `now < payload.start_time`, worker không được apply markdown ngay mà phải persist job vào Redis ZSET `product:markdown:pending` (score=`start_time_epoch_ms`, member=`{rule_id}:{scope_type}:{ref_id}`) để delayed scheduler thực thi đúng thời điểm. Retry/rebalance không được làm mất pending job.

### 4.2. MARKDOWN_UPDATED (Từ Promotion Service)
**Topic:** `t.promotion.markdown.events`
**Consumer Group:** `product-markdown-group`
**Xử lý bởi:** [Markdown Price Calculator Worker](product_workers.md#4-markdown-price-calculator-worker)

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
**Logic xử lý:** Product Service query `product_markdown_applied WHERE rule_id = '42'` → lấy danh sách `sku_id` bị ảnh hưởng → tính lại `promotional_price` với các thông số thay đổi. **Lưu ý tối cao:** `original_price` dùng để tính toán BẮT BUỘC phải được query trực tiếp từ field tĩnh `product_skus.original_price` tính tại thời điểm Worker đang xử lý → batch update `product_skus` và `product_markdown_applied` → phát `SKU_UPDATED` events cho từng SKU bị ảnh hưởng (với `changed_fields = ["original_price", "promotional_price", "best_price", "has_promotion"]`).

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

> **Thống nhất tên event:** Event này tên chính thức là `MARKDOWN_ENDED`. Tên `MARKDOWN_ENDED` bao gồm cả "hết hạn" lẫn "Admin tắt thủ công", phân biệt qua field `reason`:
> - `reason: "EXPIRED"` → [Markdown Expiry Scheduler](../promotion/promotion_workers.md#34-markdown-expiry-scheduler-redis-zset-va-cron-fallback) (Redis ZSET loop + cron fallback) phát hiện `end_time < NOW()`
> - `reason: "ADMIN_DISABLED"` → Admin tắt thủ công trên dashboard
>
> Xem định nghĩa event gốc tại [Promotion Events - MARKDOWN_ENDED](../promotion/promotion_events.md#23-markdown-ended).

**Logic xử lý:** Query `product_markdown_applied WHERE rule_id = '42'` → lấy danh sách `sku_id` bị ảnh hưởng → reset `product_skus.promotional_price = NULL` → delete records từ `product_markdown_applied` → phát `SKU_UPDATED` events (với `changed_fields = ["original_price", "promotional_price", "best_price", "has_promotion"]`, `change_source = MARKDOWN_ENDED` nếu đến từ event này hoặc `MARKDOWN_EXPIRED_FALLBACK` nếu đến từ job self-healing).

> **No-op guard cho `MARKDOWN_ENDED`:** Nếu không còn bản ghi nào trong `product_markdown_applied` cho `rule_id`, consumer phải ACK và no-op (không phát `SKU_UPDATED` rỗng, không coi là lỗi).

### 4.4. INVENTORY_STOCK_CHANGED (Từ Inventory Service)
**Topic:** `t.inventory.events`
**Consumer Group:** `product-inventory-fast-sync-group` (Phase 1: Lock-Free Fast Sync)
**Xử lý bởi:** [Inventory Sync Worker - Two-Phase Architecture](product_workers.md#5-inventory-sync-worker-two-phase-lock-free-architecture). Xem chi tiết tại [Inventory Sync Optimization](inventory_sync_optimization.md).

**Cấu trúc Payload kỳ vọng (JSON) — theo canonical Inventory spec:**
```json
{
  "event_id": "123e4567-e89b-12d3-a456-426614174003",
  "event_type": "INVENTORY_STOCK_CHANGED",
  "aggregate_type": "Inventory",
  "aggregate_id": "sku-001",
  "correlation_id": "sys-abc",
  "timestamp": "2026-02-28T10:05:01Z",
  "source": "inventory-service",
  "schema_version": 1,
  "payload": {
    "product_id": "prod-uuid-001",
    "sku_id": "sku-001",
    "available_quantity": 798,
    "change_type": "ORDER_RESERVED"
  }
}
```

> **Quan trọng — Nguồn gốc `event_timestamp` (epoch_ms) cho Staleness Guard:**
>
> Theo canonical Inventory contract (xem [inventory_events.md §4.2](../inventory/inventory_events.md#42-inventory_stock_changed)):
> - **Payload KHÔNG chứa** `event_timestamp`, `timestamp_iso`, `previous_available_quantity` hay bất kỳ trường thời gian nào.
> - Consumer Phase 1 **PHẢI convert** `timestamp` ở CloudEvents wrapper (ISO 8601 UTC) sang epoch milliseconds:
>   ```java
>   Instant ts = Instant.parse(event.getTimestamp());        // "2026-02-28T10:05:01Z"
>   long eventTimestamp = ts.toEpochMilli();                 // → 1772289901000
>   ```
> - Epoch_ms này được truyền vào Lua script làm `ARGV[1]` và dùng cho:
>   - **Staleness guard** Layer 1 (Lua CAS): `eventTimestamp > cached.last_inventory_sync_at`
>   - **ZSET member/score**: member = `{sku_id}:{eventTimestamp}`, score = `eventTimestamp`
> - Phase 2 DB Sync cũng dùng cùng epoch_ms để compare với `product_skus.last_inventory_sync_at`.

> **`sku_status` derivation (BẮT BUỘC cho Consumer Phase 1 Lua):**
> Payload không chứa `sku_status`. Consumer Phase 1 (Lua script) tự derive theo quy tắc:
> - `available_quantity = 0` → `sku_status = 'OUT_OF_STOCK'`
> - `available_quantity > 0` → `sku_status = 'ACTIVE'` (giữ nguyên nếu đang `INACTIVE` do admin set)
> Giá trị `sku_status` được truyền vào Lua qua `ARGV[4]` và ghi vào Redis cache. Việc phát event `SKU_UPDATED` (với `changed_fields = ["sku_status"]`) do **Phase 2** xử lý sau DB commit.

**Luồng xử lý Two-Phase:**
- **Phase 1 (Lua CAS):** Kafka Consumer nhận event → convert wrapper `timestamp` → epoch_ms → Lua CAS atomic update trên Redis → immediate cache warm + ZADD pending sync. Latency < 5ms, zero DB lock.
- **Phase 2 (DB Sync):** Scheduled Worker drain ZSET → batch update `product_skus` với Optimistic Locking → FSM triggers (AUTO_HIDE/AUTO_REACTIVATE) → outbox `SKU_UPDATED` (với `changed_fields`, `product_status_changed`, `new_product_status` nếu FSM đồng thời đổi product status). Latency 500ms-2s.
- **Staleness guard**: Layer 1 (Lua CAS epoch_ms check), Layer 2 (Phase 2 DB check), Layer 3 (outbox event guard). Chỉ apply update khi `event_timestamp > last_inventory_sync_at`.
- **Cache staleness**: `product:sku:{sku_id}` bắt buộc lưu `last_inventory_sync_at` (epoch_ms) để Lua CAS fence chống stale write.
- **Auto-hide/Reactivate & SKU_UPDATED**: SKU status `OUT_OF_STOCK`/`ACTIVE` được set ngay ở Phase 1 (Lua CAS). FSM triggers (AUTO_HIDE_OUT_OF_STOCK / AUTO_REACTIVATE_IN_STOCK) được trigger ở Phase 2 và phát event `SKU_UPDATED` (với `changed_fields = ["sku_status", "product_status"]` nếu FSM đổi cả product status) vào Kafka (không còn `PRODUCT_STATUS_CHANGED` cho luồng này nữa). Xem thêm [State Machine - Auto Hide](product_state_machine.md#2-các-lệnh-chuyển-tiếp-events--transitions).

## 5. Schema Versioning & Backward Compatibility

*   **Event Schema Version:** Mỗi message event bắt buộc chứa top-level field `schema_version` (hiện tại = `1`). Khi schema thay đổi trong tương lai, version tăng lên.
*   **Backward Compatibility Rule:** Chỉ được phép **thêm field mới** (additive changes). Không được xoá hoặc đổi tên field hiện có. Nếu cần breaking change, phải tạo event_type mới (VD: `SKU_UPDATED_V2`).
*   **Consumer Resilience:** Tất cả consumers PHẢI ignore unknown fields (sử dụng `@JsonIgnoreProperties(ignoreUnknown = true)` trong Java/Spring hoặc tương đương).
