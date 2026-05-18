# Product Service - Danh sách Background Workers & Jobs

**Liên kết nhanh (Related Documents):**
*   [Workflows (API & Data Flow)](product_workflow.md)
*   [Background Workers & Jobs](product_workers.md)
*   [Caching Schema (Redis & Caffeine)](product_caching_schema.md)
*   [Database Schema (Write Store)](product_db_design.md)
*   [State Machine & Lifecycle](product_state_machine.md)
*   [Events Structure & Kafka Topics](product_events.md)
*   [Architecture Recommendations](product_architecture_recommendations.md)

---
Tai Product Service, du lieu la tinh (Command Model / Master Data) nen Service khong su dung Redis lam co so du lieu chinh nhu Cart Service. Ha het cac Workers trong Product Service dong vai tro phuc vu viec **Dong bo Du Lieu Phan Tan (Saga / CQRS)** de dam bao cac he thong xung quanh nhu Search Service, Notification, Promotion nhan duoc tin hieu cap nhat dung thoi diem.

Duoi day la danh sach Worker noi bo thuoc vong tay quan ly cua cum May Chu Product Service.

---

## Tong Hop Kafka Topics

| Huong | Topic | Consumer Group | Mo ta |
|---|---|---|---|
| **Produce** | `t.product.events` | - | Topic chinh chua moi event phat di |
| **Produce** | `t.category.events` | - | Topic category lifecycle events |
| **Consume** | `t.product.events` | `product-event-handler-group` | Self-consume de refresh cache va xu ly category lifecycle |
| **Consume** | `t.category.events` | `product-event-handler-group` | Self-consume `CATEGORY_DELETED` de dong bo lai Write Model |
| **Consume** | `t.promotion.markdown.events` | `product-markdown-group` | Nhan dung allowlist `MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED`; event la phai skip + metric + DLQ |
| **Consume** | `t.inventory.events` | `product-inventory-sync-group` | Nhan tin hieu ton kho tu Inventory |

Xem cau truc payload chi tiet cho tat ca events tai [Product Events](product_events.md).

---

## 1. Debezium CDC Connector (Thay the hoan toan Polling Worker)

**Loai hinh:** Kafka Connect Source Connector (Doc Real-time duoi tang Dia cung).

**Su menh:** Chuyen Event da sinh ra tu bang `product_outbox_events` sang Kafka Topic (`t.product.events` va `t.category.events`) voi do tre < 5ms thong qua luong bat Read-Log (Write-Ahead-Log). Thay the hoan toan vong lap CPU SELECT ton kem cua Outbox Polling cu. Xem [Product Workflow (API Sinh Outbox)](product_workflow.md#2-api-tao-moi-san-pham-ban-nhap-create-concept).

**Chi tiet luong hoat dong:**

1. Khi co tao moi/sua doi API, no dong thoi Insert Record vao bang Outbox (voi `event_id` la Khoa chinh (UUID)). DB cap nhat WAL duoi o cung.
2. Debezium Connector gan qua cong Logical_Replication_Slot cua Postgres tien hanh doc stream tu dong. Hanh vi nay Khong chieu du lieu 1 chut tai nguyen CPU/Connection Pool nao cua Request nguoi dung dap vao DB.
3. **[CRITICAL Kafka Routing]:** Debezium duoc cau hinh de trich xuat truc tiep gia tri tu cot `partition_key` (da duoc luu san `product_id`) lam Kafka Message Key. Dieu nay ghim chat tat ca Event thay doi cua cung 1 San pham se roi vao chung mot Kafka Partition, phu thuoc hoan hao nguyen tac CQRS cho Search Service (khong duoc dung SMT boc tach JSON o cot payload vi rui ro Crash va giam hieu nang).
4. Debezium doc ban ghi va Connector tu dong dinh dang thanh CloudEvents va phat len Topic Kafka mong muon.
5. **[Dead Letter Queue - DLQ]:** Moi cau hinh DLQ, Retry, Error Handling duoc thuc hien truc tiep tai Kafka Connect (Sink/Source Dead Letter Topic) thay vi dung cot `status`, `retry_count` rac trong Database. Table outbox duoc don dinh ky bang retention job theo cua so thoi gian (`created_at`).

## 2. Event Handler Worker (Cache Refresh & Category Lifecycle)

**Loai hinh:** Kafka Consumer Lang Nghe Topic Cua Chinh No.

**Su menh:** Xoa/Cap nhat Cache (Soft Invalidation) va xu ly Category Lifecycle thong qua Worker (Out-of-band). Day la co che **Du phong (Fallback & Self-Healing)** ket hop. Chuc nang cap nhat Cache ngay tuc thi luc admin Sua Du lieu bat buoc phai duoc dat **In-band** (cung luong voi lenh tra HTTP 200 OK) de dam bao quyen loi **Read-Your-Own-Writes**. Worker Kafka nay lang nghe Event ngam de don song, tu dong Overwrite lam muot lai he thong neu Node API rot mang khong kip SET sau In-band, hoac phuc vu luong Cache cap nhat do System Bots (VD: Markdown Pricing doi gia / Inventory). Thay vi API tu xoa (Hard DEL) Redis gay rui ro Thundering Herd, Worker nay luon dung chieu (SET de du lieu moi).

**Chi tiet luong hoat dong:**

1. Worker Kafka (`groupId="product-event-handler-group"`) lang nghe Topic `t.product.events` va `t.category.events`.
2. Moi vong `poll()` lay mot `poll-batch` records va chuan hoa CloudEvents (`event_type`, `payload`, `partition`, `offset`, `timestamp`) truoc khi xu ly.
3. Batch duoc tach thanh 2 lane xu ly:
   - **Lane SKU cache keys**: xu ly theo tung record de refresh `product:sku:{sku_id}` hoac cac `sku_id` trong mang.
   - **Lane Product master key**: gom record theo `product_id`, chi giu ban moi nhat theo comparator `(version, updated_at, kafka_offset)` de ghi `product:master:{product_id}` dung 1 lan.
4. Ma tran xu ly theo `event_type` va nguon du lieu:
   - `PRODUCT_PRICE_UPDATED`: dung payload event lam nguon chinh de xac dinh `sku_id`, `product_id`, sau do refresh cache.
   - `PRODUCT_SKU_DELETED`: dung payload tombstone (`sku_id`, `product_id`, `deleted_at`) de refresh key SKU ve trang thai da xoa va dong bo lai `product:master`.
   - `PRODUCT_STATE_CHANGED`, `PRODUCT_DELETED`: bat buoc co `payload.sku_ids[]`. Worker loop theo danh sach nay de refresh toan bo `product:sku:{sku_id}` va danh dau refresh `product:master:{product_id}`. Neu thieu `sku_ids[]` -> coi la contract violation, chuyen DLQ.
   - `PRODUCT_UPDATED`: dung chien luoc **payload-first, DB-fallback**:
     - Neu payload co **full snapshot** (`product_id`, `status`, `category_id`, `version`, `updated_at`, `skus[]` va moi SKU co du `sku_id`, `sku_code`, `original_price`, `promotional_price`, `best_price`, `has_promotion`, `sku_status`) thi refresh cache hoan toan tu payload.
     - Neu payload thieu bat ky truong bat buoc nao, worker phai DB re-fetch snapshot day du Product + SKUs trong mot lan doc roi ghi cache tu snapshot DB.
     - Trong cung mot event, **khong duoc tron nguon** (mot phan tu payload, mot phan tu DB). Chon dung mot nguon cho toan bo thao tac ghi.
     - Tuyet doi khong fallback sang Search/Elasticsearch vi khong dam bao freshness cho write model.
   - `CATEGORY_DELETED`: xu ly Write Model truoc khi downstream phan chieu:
     - Validate `category_id`, `category_version`.
     - Xac dinh pham vi tac dong: neu payload co `descendant_category_ids` thi phai xu ly ca subtree; neu khong co thi toi thieu xu ly `category_id` goc.
     - Xu ly theo batch nho (khuyen nghi 100 product/batch), transaction-per-batch: cap nhat cac product lien doi ve trang thai `REJECTED` (Ly do: Category deleted) tai PostgreSQL. Khong day ve `HIDDEN` de tran Inventory tu kich hoat lai san pham mo coi.
     - Insert outbox `PRODUCT_STATE_CHANGED` cho cac product bi tac dong trong cung transaction.
5. Quy tac ownership bat buoc: voi luong gia Markdown, `PRODUCT_PRICE_UPDATED` la tin hieu chuan de Event Handler ghi de `product:sku:{sku_id}` va `product:master:{product_id}`. Markdown Worker khong duoc ghi de 2 key nay truc tiep de tran double-write bat dong bo.
6. **[Giam Write Amplification cho `product:master`]**: trong moi lan `poll()` Kafka, phai gom event theo `product_id` va chi refresh `product:master:{product_id}` 1 lan duy nhat cho moi product. Neu co nhieu event cung product trong batch, chon event moi nhat theo comparator `(version, updated_at, kafka_offset)`.
7. **[Coalescing lien batch - khuyen nghi]**: co the bat cua so debounce ngan (50-100ms) theo `product_id`; cac event don trong cua so nay chi tao 1 DB re-fetch + 1 Redis write cho `product:master:{product_id}`.
8. **[Stale-Write Guard bat buoc]**: payload cache `product:master:{product_id}` phai luu `version` va `updated_at`. Truoc khi overwrite, chi cho ghi khi `incoming.version > cached.version` hoac (`incoming.version = cached.version` va `incoming.updated_at > cached.updated_at`); neu khong thi skip de chan stale/equal write.
9. **[Execution Contract bat buoc: Offset/Retry/DLQ/Pause-Resume]**
   - **Offset commit**: chi commit offset sau khi record da xu ly xong theo mot trong hai trang thai: (a) ghi cache thanh cong, hoac (b) da dua record sang DLQ theo nhanh non-retryable.
   - **Retry transient**: loi Redis timeout, loi DB tam thoi, loi network tam thoi -> khong commit offset record do, retry voi exponential backoff + jitter (khuyen nghi toi da 5 lan).
   - **DLQ non-retryable**: payload sai schema, thieu truong bat buoc, hoac loi nghiep vu khong the tu hoi phuc -> publish sang topic DLQ cua invalidation (kem `event_id`, `event_type`, `partition`, `offset`, `error_code`) roi commit offset de tran poison-message loop.
   - **Pause/Resume**: khi Redis/DB suy giam hoac backlog xu ly noi bo vuot nguong, worker phai `pause()` cac partitions dang gan; chi `resume()` khi dependency hoi phuc va backlog ve nguong an toan.
   - **Rebalance/Shutdown**: truoc khi revoke partition hoac shutdown pod, phai flush cac write dang in-flight va commit cac offset da xu ly thanh cong de giam duplicate processing.
10. **Canh Bao loi Circular Dependency (Loop Vo Tan):** Worker nay CHI DUOC PHEP thao tac read tu Database va write vao Redis. TUYET DOI KHONG goi bat ky ham Business Logic hay Entity Save (Write Database) nao trong luong xu ly nay. Neu goi nham, no se luu Database -> sinh ra Outbox Event de ra Kafka Message moi -> tu Worker lai nhan Message do va tiep tuc chay -> Vong lap huy diet toan bo he thong.
11. Voi cac event Product lam thay doi footprint category (tao/sua/xoa product hoac doi trang thai hien thi), gui Local L1 Cache Invalidate Command qua Pub/Sub cho tat ca Pods Product Service de xoa/cap nhat Caffeine key `product:categories:tree`.

## 3. Product Indexing Sync Job (Danh cho Search Service)

*Luu y: Job nay khong nam ben trong Code cua Product Service nua, ma thuoc ve **[Search Service](../search/search_workers.md#1-product-indexing-worker-kafka-consumer)**.*

Product Service la Command. Search Service la Query. Search Service co hai consumer groups chinh:

- `search-product-indexer` consume `t.product.events` (`PRODUCT_CREATED`, `PRODUCT_UPDATED`, `PRODUCT_STATE_CHANGED`, `PRODUCT_PRICE_UPDATED`, `PRODUCT_DELETED`, `PRODUCT_SKU_DELETED`).
- `search-category-indexer` consume `t.category.events` (`CATEGORY_UPDATED`, `CATEGORY_DELETED`).

Hai luong nay phoi hop de dam bao projection Elasticsearch luon dung ownership boundary. Xem chi tiet tai [Search Service - Workers](../search/search_workers.md) va [Elasticsearch Schema](../search/search_elasticsearch_schema.md).

## 4. Outbox Retention Trimmer (Time-Window Cleanup Cronjob)

**Loai hinh:** Job don dep chay luc it nguoi dung nhat (Vi du 3:00 Sang Chu Nhat Thoi Gian Cuc Bo Chau A).

**Su menh:** Du lieu trong bang `product_outbox_events` la log append-only phuc vu Debezium CDC. Can don du lieu cu theo retention window de tran phinh dung luong dia.

**Chi tiet luong hoat dong (Time-Window Cleanup):**

1. He thong Wake-up.
2. Don dep theo lo nho theo `created_at` de han che lock dai. Khong dung lenh `DELETE FROM ...` toan bang.

```sql
WITH to_delete AS (
  SELECT event_id
  FROM product_outbox_events
  WHERE created_at < NOW() - INTERVAL '30 days'
  ORDER BY created_at ASC
  LIMIT 10000
)
DELETE FROM product_outbox_events poe
USING to_delete
WHERE poe.event_id = to_delete.event_id;
```

3. Luu y: Vi Product dung Debezium CDC tu WAL (khong dung outbox polling trang thai `FAILED/DEAD`), retention duoc quan tri theo age cua record va trang thai Kafka Connect offset.

## 5. Markdown Price Calculator Worker

**Loai hinh:** Kafka Consumer lang nghe Topic `t.promotion.markdown.events`.

**Su menh:** Nhan event Markdown (Giam gia truc tiep) tu Promotion Service.

**Chi tiet luong hoat dong:**

1. Worker (`groupId="product-markdown-group"`) nhan event tu `t.promotion.markdown.events`.
2. Phan loai event_type (`MARKDOWN_CREATED`, `MARKDOWN_UPDATED`, `MARKDOWN_ENDED`).
   - Truy xuat danh sach SKU bi anh huong duoi dang danh sach phang co trom nhieu `product_id`.
   - **Group tai tang Application:** Worker phai gom theo `product_id` (map trong RAM hoac iterator grouping) de tao cac cum xu ly doc lap theo Product. Voi tap du lieu lon, uu tien streaming/chunk theo `ORDER BY product_id ASC, sku_id ASC`, tran giu toan bo map qua lon tren RAM.
3. **Transaction per Product + Batch DB Write:**
   - **CHONG DEADLOCK HANG LOAT (BATCH DEADLOCK):** BAT BUOC chi duoc mo `@Transactional` cho **TUNG `product_id`** mot (Transaction Size = 1 Product). CAM mo 1 Transaction boc ca ngan san pham.
   - Ben trong moi Transaction con (tren tung product):
   - **CHONG VA CHAM GIUA NHIEU INSTANCE WORKER:** Buoc dau tien trong transaction phai lock Product cha bang `FOR NO KEY UPDATE` voi `lock_timeout` ngan. Neu lock timeout, worker `nack/requeue` theo policy retry de tran xung dot am tham.
   - Buoc 1 - Lock Cha: `SELECT 1 FROM products WHERE product_id = :product_id FOR NO KEY UPDATE;`
   - Buoc 2 - Lock Con: `SELECT sku_id, original_price FROM product_skus WHERE sku_id IN (...) AND product_id = :product_id FOR UPDATE;`. Khi nhan `MARKDOWN_UPDATED`, bien `original_price` dung cho phep tinh `%` giam gia BAT BUOC phai lay tu DB hien tai (ket qua query nay), khong duoc lay tu `original_price` cu.
   - Buoc 2.1 - Cong thuc gia chuan (bat buoc):
     - `PERCENTAGE`: `discount_amount = MIN(original_price * discount_value / 100, max_discount_amount)` (neu co cap), `promotional_price = MAX(0, ROUND(original_price - discount_amount, 2))`.
     - `FIXED_AMOUNT`: `promotional_price = MAX(0, ROUND(original_price - discount_value, 2))`.
     - `best_price = LEAST(original_price, COALESCE(promotional_price, original_price))`.
   - Buoc 3 - Batch update `product_skus` (gia markdown), batch upsert `product_markdown_applied`, batch insert `product_outbox_events` ngay trong cung transaction.
   - Buoc 4 - Tang `products.version` va phat `PRODUCT_PRICE_UPDATED`.
4. **Cache Update sau Commit (Redis Pipeline - Ownership ro rang):**
   - Sau moi lan COMMIT cua tung Product, Markdown Worker KHONG ghi de truc tiep `product:sku:{sku_id}` de tran trung luong voi Event Handler.
   - Lam moi `product:sku:{sku_id}` va `product:master:{product_id}` duoc giao cho Event Handler khi self-consume `PRODUCT_PRICE_UPDATED` tu `t.product.events`.
   - Khi nhieu `PRODUCT_PRICE_UPDATED` cung `product_id` xuat hien trong cac chunk sat nhau, Event Handler phai coalesce de gioi han ghi `product:master:{product_id}` o muc toi thieu ma van giu dung trang thai moi nhat.
   - Markdown Worker chi quan ly key phu `product:markdown:active:{sku_id}` bang pipeline theo batch de phan anh rule markdown dang active.
   - Neu nhan `MARKDOWN_ENDED` do Admin tat thu cong (`reason = ADMIN_DISABLED`), phai `DEL product:markdown:active:{sku_id}` cho SKU bi anh huong, va nen dua cac lenh `DEL` nay vao cung pipeline batch.

> **Idempotency:** Su dung `rule_id` + `sku_id` Unique Index tren `product_markdown_applied` de chong apply trung.

> **No-op bat buoc cho `MARKDOWN_ENDED`:** neu query theo `rule_id` khong tra ve SKU nao, worker phai ACK va ket thuc (khong coi la loi, khong phat outbox rong).

## 6. Inventory Sync Worker

**Loai hinh:** Kafka Consumer lang nghe Topic `t.inventory.events`.

**Su menh:** Dong bo du lieu ton kho tu Inventory Service ve Product Service. Cap nhat `display_quantity` tren `product_skus` va trigger tu dong an/hien san pham khi het hang/co hang tro lai. Xem chi tiet tai [Workflow Inventory Sync](product_workflow.md#7-workflow-xu-ly-inventory-sync-event).

> **Chuan Lock Hierarchy bat buoc:** Voi moi transaction cung cham `products` va `product_skus`, Inventory Sync Worker phai lock theo thu tu **Product -> SKU** de tran vong deadlock AB-BA voi Markdown Worker va API Update Product.

**Chi tiet luong hoat dong:**

1. Worker (`groupId="product-inventory-sync-group"`) nhan `INVENTORY_QUANTITY_CHANGED` event.
2. `@Transactional`:
   - Buoc 1: Lock Product cha tu payload event: `SELECT product_id, status, admin_hidden, is_deleted FROM products WHERE product_id = :product_id FOR NO KEY UPDATE;`
   - Buoc 2: Lock SKU con va validate cap khoa: `SELECT sku_id, product_id, status, last_inventory_sync_at FROM product_skus WHERE sku_id = :sku_id AND product_id = :product_id FOR UPDATE;`
   - Buoc 2.1: Neu khong tim thay row o Buoc 2, coi la payload sai cap `product_id/sku_id` -> khong update DB, dua DLQ/parking-lot de dieu tra producer contract.
   - Buoc 3: So sanh thu tu su kien. Neu `event_timestamp <= last_inventory_sync_at` thi bo qua message (stale).
   - Buoc 4: Dung lenh `UPDATE product_skus SET display_quantity = :available_quantity, last_inventory_sync_at = :event_timestamp WHERE sku_id = :sku_id`.
   - Buoc 5: Quy tac versioning nhat quan: neu transaction chi chay SQL va khong goi FSM/JPA thi tang `products.version` dung 1 lan. Neu transaction co goi FSM/JPA (`@Version`) thi bo native increment de tran `OptimisticLockException`.
3. Logic auto-hide/reactivate (Dua tren du lieu da lock o Buoc 1 + Buoc 2, chan dung TOCTOU):
   - **[X6 Rao Can Out-Of-Order]**: Truoc tien, Worker phai so sanh `event_timestamp` tu Event voi truong `last_inventory_sync_at` cua SKU tren database (vua lock o buoc 2). Neu `event_timestamp <= last_inventory_sync_at`, vut Message va return bo qua de triet tieu loi Kafka Lag cap nhat ton kho lac hu. Neu hop le, gan `last_inventory_sync_at = event_timestamp`.
   - Tuyet doi khong duoc thuc hien them mot vong `SELECT` moi ben ngoai Transaction de kiem tra lai trang thai SKU. Toan bo chuoi **Doc Product -> Doc SKU -> Tinh toan auto-hide/reactivate -> Update Product/SKU** phai nam trong cung Transaction dang giu lock.
   - Neu can guard tuong minh truoc `AUTO_REACTIVATE_IN_STOCK`, duoc phep chay them query predicate trong CHINH transaction hien tai (vi du `SELECT 1 ... FOR NO KEY UPDATE`), hoac dung truc tiep row Product da lock o buoc 1. Ca hai cach deu hop le neu khong thoat transaction.
   - Neu `products.is_deleted = true`, worker van cap nhat `display_quantity/sku_status` theo inventory nhung **khong trigger** bat ky transition Product-level nao.
   - `available_quantity = 0` + SKU `ACTIVE` -> chuyen SKU sang `OUT_OF_STOCK`. Cap nhat ngay snapshot SKUs trong memory cua Transaction; neu 100% SKU deu het hang va `is_deleted = false` thi trigger `AUTO_HIDE_OUT_OF_STOCK` tren FSM. BAT BUOC KHONG CHAM vao `admin_hidden`.
   - `available_quantity > 0` + SKU `OUT_OF_STOCK` -> chuyen SKU ve `ACTIVE`. Cap nhat snapshot, kiem tra Product dang `HIDDEN` do thieu hang, `admin_hidden = false` **va** `is_deleted = false` thi trigger `AUTO_REACTIVATE_IN_STOCK` (JPA se tu tang version).
   - **Outbox boundary bat buoc:** Ngay trong transaction dang xu ly logic o buoc 2-3, neu SKU status thay doi thi phai `INSERT` `PRODUCT_STATE_CHANGED` vao `product_outbox_events` truoc khi commit. Khong phat event o ngoai transaction.
4. Commit transaction thanh cong roi moi ack offset; neu rollback thi khong ack de message duoc retry theo policy.
5. **Cache Update (Dong bo - Khong Queue):**
   - Sau khi Commit Database thanh cong, goi Redis SET dong bo ngay lap tuc voi retry ngan (2-3 lan).
   - Su dung **Redis Pipelining** de gui cac lenh cap nhat theo batch, toi thieu gom: `display_quantity`, `sku_status` (neu doi), va `last_inventory_sync_at` tren key `product:sku:{sku_id}`.
   - **Fallback self-healing bat buoc:** neu Pod crash sau DB commit nhung chua flush Redis xong, `PRODUCT_STATE_CHANGED` da nam trong Outbox/Kafka se duoc `product-event-handler-group` tieu thu de tu dong refresh lai `product:sku:{sku_id}` va `product:master:{product_id}`.
6. **Chan Out-of-Order khi update cache cung SKU:**
   - Su dung staleness guard dua tren `last_inventory_sync_at` da luu trong cache.
   - Chi apply cache update neu `incoming.event_timestamp >= cached.last_inventory_sync_at`; neu cu hon thi bo qua, chan stale write.
7. *Luu y quan trong:* Neu Trigger `AUTO_HIDE_OUT_OF_STOCK` dien ra, luong cache dong bo cung phai cap nhat them `product:master:{product_id}` ve `status="HIDDEN"` de tran lech trang thai giua SKU va Product master.

## 7. Markdown Expiry Cronjob (Self-Healing)

**Loai hinh:** Cron Job chay dinh ky (**1 lan/ngay** - luc it nguoi dung nhat, vi du 3:00 Sang).

**Su menh:** Don dep/Backup cac truong hop Product bi ket gia khuyen mai do rot mang hoac loi Kafka Event `MARKDOWN_ENDED` tu Promotion Service. Dam bao Product khong bao gio luu gia cu vinh vien (Self-Healing).

**Chi tiet luong hoat dong:**

> **CANH BAO BAT BUOC (Tranh Deadlock):** CronJobquet rat nhieu dong cung luc. Khi tien hanh cap nhat bang con `product_skus`, worker PHAI tuan thu nguyen tac Lock Hierachy: Lock bang cha `products` truoc de tran loi Deadlock vong tron `AB-BA` voi luong API Sua san pham cua admin (API nay di tu Products -> SKUs).

0. **Distributed lock bat buoc:** truoc khi quet, cron phai acquire lock phan tan `product:jobs:markdown-expiry:lock` (SET NX PX, TTL khuyen nghi 60s va gia han khi job chay lau). Neu khong acquire duoc lock thi instance hien tai phai skip vong chay de tran nhieu pod cung don mot tap du lieu.

1. Quet DB tim cac quy tac da qua han (Bat buoc ORDER BY de chong Deadlock va dung LIMIT de chia Batch):

```sql
SELECT product_id, sku_id, rule_id
FROM product_markdown_applied
WHERE expires_at < NOW()
ORDER BY product_id ASC
LIMIT 100; -- Chunking theo Batch de tran giu Lock qua lau
```

2. Neu co du lieu mo coi (Xu ly Batch theo tung `product_id`):
   - **BAT BUOC CHUNKING**: Cronjob co the lay ra hang chuc nghin records. Tuyet doi khong duoc nhoi tat ca vao chung mot Transaction. Phai xu ly xong mot khoi 100 records va **COMMIT Giao Dien** ngay lap tuc, nham xa Lock cho Connection Pool va tran Block API cua admin.
   - **BAT BUOC LOCK THEO THU TU**: Ben trong moi Transaction khoi, thao tac tren tung `product_id` phai duyet theo thu tu TANG DAN (ASC) de ngua Deadlock cheo. Lock bang CHA `products` truoc: `SELECT 1 FROM products WHERE product_id = :product_id FOR NO KEY UPDATE;`
   - Reset `promotional_price = NULL` o `product_skus`.
   - Cap nhat tang `version` cua bang cha `products`.
   - Delete dong mapping o bang `product_markdown_applied`.
   - Ban Outbox trigger Event `PRODUCT_PRICE_UPDATED`.
   - Xoa Cache lien quan.

