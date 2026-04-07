# Order Service - CQRS Elasticsearch Design (Production Blueprint)

**Liên kết nhanh (Related Documents):**
*   [Database Schema](order_db_design.md)
*   [Events Structure](order_events.md)
*   [Workflows](order_workflow.md)
*   [Workers](order_workers.md)
*   [State Machine & Lifecycle](order_state_machine.md)
*   [Temporal Saga Design](temporal_saga_design.md)

**Cross-Service Reference (Pattern Reuse):**
*   [Search Service - Elasticsearch Schema](../search/search_elasticsearch_schema.md)
*   [Search Service - Workers](../search/search_workers.md)

---

Tài liệu này mô tả read-model Elasticsearch cho Order Service theo CQRS ở mức triển khai production: index settings, field mapping, query DSL, chiến lược đồng bộ event, chống out-of-order, retry/DLQ, và runbook reindex zero-downtime.

> **Nguyên tắc cốt lõi:**
> 1. PostgreSQL vẫn là **source of truth** cho write-model.
> 2. Elasticsearch chỉ phục vụ read path cho search/filter ở tải lớn.
> 3. Read-model được xây theo kiến trúc hybrid **2 index** để cân bằng chính xác và tốc độ.
> 4. Không index PII nhạy cảm (địa chỉ chi tiết, số điện thoại raw).

## 1. Mục tiêu Nghiệp vụ và Query Classes

### 1.1. Hai loại truy vấn bắt buộc

| Query Class | Mục tiêu | Nguồn index chính |
|---|---|---|
| A - Multi-dimensional Filter | Lọc chính xác lịch sử đơn theo buyer/seller/admin + trạng thái + khoảng thời gian + khoảng giá trị | `orders_read` |
| B - Text Search | Tìm theo `snapshot_product_name`, `tracking_number`, `store_name` và vẫn kết hợp filter class A | `order_search_lines` (sau đó hydrate từ `orders_read`) |

### 1.2. SLO nhất quán dữ liệu

- Projection freshness mục tiêu: `p95 <= 5s`, `p99 <= 15s`.
- Dữ liệu có thể lệch tạm thời (eventual consistency), nhưng phải hội tụ bằng retry/replay/self-heal.

---

## 2. Nguồn dữ liệu và Kiến trúc Projection

- **Source of truth:** PostgreSQL của Order Service.
- **Input stream chính:** Kafka topic `t.order.events`.
- **Projection workers:**
	- `order-cqrs-summary-projector` -> ghi `orders_write`.
	- `order-cqrs-line-projector` -> ghi `order_search_lines_write`.
	- `order-cqrs-retry-replayer` -> xử lý `t.order.cqrs.retry`.

> **Lưu ý ownership:** Event sequencing cho CQRS được ràng buộc theo stream ownership (order lifecycle, parcel/shipping, payment/refund), không so sánh thứ tự event chỉ bằng UUID `event_id`.

---

## 3. Danh sách Indices và Alias Strategy

| Physical Index | Read Alias | Write Alias | Granularity | Vai trò |
|---|---|---|---|---|
| `orders_read_v1` | `orders_read` | `orders_write` | 1 document / order | List/filter chính xác theo order-level |
| `order_search_lines_v1` | `order_search_lines` | `order_search_lines_write` | 1 document / order item (gắn `parcel_id`) | Text retrieval + scoring |

### 3.1. Count semantics

- `orders_read`: `hits.total` là tổng đơn.
- `order_search_lines`: `hits.total` là tổng line match, không phải tổng đơn.
- Khi query text, API bắt buộc trả thêm `total_orders` bằng `cardinality(order_id)` để tránh sai lệch pagination theo đơn.

### 3.2. Store name contract

- `store_name` trong read-model là snapshot tại thời điểm đặt hàng.
- Không backfill theo Seller profile mới để giữ tính lịch sử khi đối soát.

---

## 4. Index Settings

### 4.1. Shared analysis settings

```json
{
	"analysis": {
		"analyzer": {
			"vi_text_analyzer": {
				"type": "custom",
				"tokenizer": "icu_tokenizer",
				"filter": ["icu_folding", "lowercase", "vi_stop"]
			},
			"vi_text_search_analyzer": {
				"type": "custom",
				"tokenizer": "icu_tokenizer",
				"filter": ["icu_folding", "lowercase"]
			},
			"edge_prefix_analyzer": {
				"type": "custom",
				"tokenizer": "edge_prefix_tokenizer",
				"filter": ["icu_folding", "lowercase"]
			}
		},
		"tokenizer": {
			"edge_prefix_tokenizer": {
				"type": "edge_ngram",
				"min_gram": 2,
				"max_gram": 20,
				"token_chars": ["letter", "digit"]
			}
		},
		"normalizer": {
			"folding_lc": {
				"type": "custom",
				"filter": ["lowercase", "asciifolding"]
			}
		},
		"filter": {
			"vi_stop": {
				"type": "stop",
				"stopwords": ["và", "của", "là", "cho", "với", "trong", "này", "có", "được", "các"]
			}
		}
	}
}
```

### 4.2. `orders_read_vN` settings

```json
{
	"settings": {
		"number_of_shards": 3,
		"number_of_replicas": 1,
		"refresh_interval": "1s"
	}
}
```

### 4.3. `order_search_lines_vN` settings

```json
{
	"settings": {
		"number_of_shards": 6,
		"number_of_replicas": 1,
		"refresh_interval": "1s"
	}
}
```

> Khi ingest spike (flash sale hoặc backfill), có thể tăng tạm `refresh_interval` lên `3s-5s`, sau đó trả về `1s` để giữ freshness.

---

## 5. Mapping chi tiết

### 5.1. `orders_read_vN` mapping (order-level)

```json
{
	"mappings": {
		"properties": {
			"order_id": { "type": "keyword" },
			"buyer_id": { "type": "keyword" },
			"seller_ids": { "type": "keyword" },
			"tenant_scope": { "type": "keyword" },

			"order_status": { "type": "keyword" },
			"payment_status": { "type": "keyword" },
			"parcel_statuses": { "type": "keyword" },

			"created_at": { "type": "date" },
			"paid_at": { "type": "date" },
			"completed_at": { "type": "date" },
			"cancelled_at": { "type": "date" },
			"refunded_at": { "type": "date" },

			"total_amount": { "type": "scaled_float", "scaling_factor": 100 },
			"total_shipping_fee": { "type": "scaled_float", "scaling_factor": 100 },
			"total_discount": { "type": "scaled_float", "scaling_factor": 100 },
			"final_amount": { "type": "scaled_float", "scaling_factor": 100 },

			"parcel_count": { "type": "short" },
			"item_count": { "type": "integer" },

			"store_names": { "type": "keyword" },
			"store_names_normalized": { "type": "keyword", "normalizer": "folding_lc" },
			"tracking_numbers": { "type": "keyword" },
			"tracking_numbers_normalized": { "type": "keyword", "normalizer": "folding_lc" },

			"shipping_province_code": { "type": "keyword" },
			"shipping_district_code": { "type": "keyword" },

			"is_terminal_order": { "type": "boolean" },
			"is_return_flow": { "type": "boolean" },
			"is_deleted": { "type": "boolean" },

			"order_version": { "type": "long" },
			"payment_event_sequence_id": { "type": "long" },
			"refund_event_sequence_id": { "type": "long" },
			"parcel_guard_map": { "type": "object", "enabled": false },

			"last_event_id": { "type": "keyword" },
			"last_event_timestamp": { "type": "date" },
			"indexed_at": { "type": "date" },
			"projection_version": { "type": "long" }
		}
	}
}
```

**PII boundary:** Không lưu `shipping_address_snapshot.address`, `shipping_address_snapshot.phone`, `shipping_address_snapshot.name` vào index.

### 5.2. Mẫu document `orders_read`

```json
{
	"order_id": "10001",
	"buyer_id": "999",
	"seller_ids": ["s-100", "s-200"],
	"tenant_scope": "marketplace-vn",
	"order_status": "PROCESSING",
	"payment_status": "PAID",
	"parcel_statuses": ["IN_TRANSIT", "PREPARING"],
	"created_at": "2026-02-28T10:00:00Z",
	"paid_at": "2026-02-28T10:05:00Z",
	"final_amount": 995000,
	"total_amount": 1000000,
	"total_shipping_fee": 45000,
	"total_discount": 50000,
	"parcel_count": 2,
	"item_count": 3,
	"store_names": ["Apple Flagship Store", "Seller B"],
	"store_names_normalized": ["apple flagship store", "seller b"],
	"tracking_numbers": ["GHN12345"],
	"tracking_numbers_normalized": ["ghn12345"],
	"shipping_province_code": "HCM",
	"shipping_district_code": "Q1",
	"is_terminal_order": false,
	"is_return_flow": false,
	"is_deleted": false,
	"order_version": 8,
	"payment_event_sequence_id": 20260228100500,
	"refund_event_sequence_id": 0,
	"parcel_guard_map": {
		"2001": { "parcel_version": 5, "shipment_event_sequence_id": 20260301100000 },
		"2002": { "parcel_version": 2, "shipment_event_sequence_id": 20260301103000 }
	},
	"last_event_id": "123e4567-e89b-12d3-a456-426614174003",
	"last_event_timestamp": "2026-02-28T10:05:30Z",
	"indexed_at": "2026-02-28T10:05:31Z",
	"projection_version": 1
}
```

### 5.3. `order_search_lines_vN` mapping (item-level)

```json
{
	"mappings": {
		"properties": {
			"line_doc_id": { "type": "keyword" },
			"order_id": { "type": "keyword" },
			"parcel_id": { "type": "keyword" },
			"buyer_id": { "type": "keyword" },
			"seller_id": { "type": "keyword" },

			"order_status": { "type": "keyword" },
			"payment_status": { "type": "keyword" },
			"parcel_status": { "type": "keyword" },

			"snapshot_product_name": {
				"type": "text",
				"analyzer": "vi_text_analyzer",
				"search_analyzer": "vi_text_search_analyzer",
				"fields": {
					"keyword": { "type": "keyword" },
					"autocomplete": {
						"type": "text",
						"analyzer": "edge_prefix_analyzer",
						"search_analyzer": "vi_text_search_analyzer"
					}
				}
			},
			"snapshot_product_name_normalized": { "type": "keyword", "normalizer": "folding_lc" },

			"store_name": { "type": "keyword" },
			"store_name_normalized": { "type": "keyword", "normalizer": "folding_lc" },
			"store_name_text": { "type": "text", "analyzer": "vi_text_analyzer", "search_analyzer": "vi_text_search_analyzer" },

			"tracking_number": { "type": "keyword" },
			"tracking_number_normalized": { "type": "keyword", "normalizer": "folding_lc" },

			"product_id": { "type": "keyword" },
			"sku_id": { "type": "keyword" },
			"quantity": { "type": "integer" },
			"line_subtotal": { "type": "scaled_float", "scaling_factor": 100 },

			"created_at": { "type": "date" },
			"paid_at": { "type": "date" },
			"updated_at": { "type": "date" },

			"order_version": { "type": "long" },
			"parcel_version": { "type": "long" },
			"shipment_event_sequence_id": { "type": "long" },

			"is_deleted": { "type": "boolean" },
			"last_event_id": { "type": "keyword" },
			"last_event_timestamp": { "type": "date" },
			"indexed_at": { "type": "date" },
			"projection_version": { "type": "long" }
		}
	}
}
```

### 5.4. Mẫu document `order_search_lines`

```json
{
	"line_doc_id": "10001:2001:prod-001:sku-001",
	"order_id": "10001",
	"parcel_id": "2001",
	"buyer_id": "999",
	"seller_id": "s-100",
	"order_status": "PROCESSING",
	"payment_status": "PAID",
	"parcel_status": "IN_TRANSIT",
	"snapshot_product_name": "Ao thun nam cotton premium",
	"snapshot_product_name_normalized": "ao thun nam cotton premium",
	"store_name": "Apple Flagship Store",
	"store_name_normalized": "apple flagship store",
	"store_name_text": "Apple Flagship Store",
	"tracking_number": "GHN12345",
	"tracking_number_normalized": "ghn12345",
	"product_id": "prod-001",
	"sku_id": "sku-001",
	"quantity": 2,
	"line_subtotal": 500000,
	"created_at": "2026-02-28T10:00:00Z",
	"paid_at": "2026-02-28T10:05:00Z",
	"updated_at": "2026-02-28T10:05:30Z",
	"order_version": 8,
	"parcel_version": 5,
	"shipment_event_sequence_id": 20260301100000,
	"is_deleted": false,
	"last_event_id": "123e4567-e89b-12d3-a456-426614174004",
	"last_event_timestamp": "2026-03-01T10:00:00Z",
	"indexed_at": "2026-03-01T10:00:01Z",
	"projection_version": 1
}
```

---

## 6. Query DSL Patterns

### 6.1. Query Class A - Multi-dimensional filter (chính xác)

```json
GET orders_read/_search
{
	"query": {
		"bool": {
			"filter": [
				{ "term": { "buyer_id": "999" } },
				{ "terms": { "order_status": ["PAID", "PROCESSING", "COMPLETED"] } },
				{ "terms": { "payment_status": ["PAID"] } },
				{ "range": { "created_at": { "gte": "2026-02-01T00:00:00Z", "lt": "2026-03-01T00:00:00Z" } } },
				{ "range": { "final_amount": { "gte": 100000, "lte": 3000000 } } }
			]
		}
	},
	"sort": [
		{ "created_at": "desc" },
		{ "order_id": "desc" }
	],
	"size": 20,
	"search_after": ["2026-02-28T10:00:00Z", "10001"]
}
```

**Lưu ý:** Bắt buộc dùng `search_after` ở traffic lớn; tránh `from/size` sâu gây chi phí cao.

### 6.2. Query Class B - Text search (exact-first)

### Bước 1: Tìm trên `order_search_lines` + collapse theo `order_id`

```json
GET order_search_lines/_search
{
	"query": {
		"bool": {
			"filter": [
				{ "term": { "buyer_id": "999" } },
				{ "range": { "created_at": { "gte": "2026-02-01T00:00:00Z" } } }
			],
			"should": [
				{ "term": { "tracking_number_normalized": { "value": "ghn12345", "boost": 12 } } },
				{ "prefix": { "tracking_number_normalized": { "value": "ghn12", "boost": 10 } } },
				{ "term": { "store_name_normalized": { "value": "apple flagship store", "boost": 8 } } },
				{ "prefix": { "store_name_normalized": { "value": "apple", "boost": 6 } } },
				{ "match_phrase": { "snapshot_product_name": { "query": "ao thun nam", "boost": 5 } } },
				{ "match": { "snapshot_product_name.autocomplete": { "query": "ao th", "boost": 3 } } }
			],
			"minimum_should_match": 1
		}
	},
	"collapse": {
		"field": "order_id",
		"inner_hits": {
			"name": "matched_lines",
			"size": 3,
			"sort": [
				{ "_score": "desc" },
				{ "updated_at": "desc" }
			],
			"highlight": {
				"fields": {
					"snapshot_product_name": {}
				}
			}
		}
	},
	"aggs": {
		"total_orders": {
			"cardinality": {
				"field": "order_id",
				"precision_threshold": 40000
			}
		}
	},
	"sort": [
		{ "_score": "desc" },
		{ "created_at": "desc" },
		{ "line_doc_id": "asc" }
	],
	"size": 20
}
```

### Bước 2: Fuzzy fallback (chỉ khi bước 1 hit thấp)

```json
GET order_search_lines/_search
{
	"query": {
		"bool": {
			"filter": [
				{ "term": { "buyer_id": "999" } }
			],
			"must": [
				{
					"multi_match": {
						"query": "ao thun cottn",
						"fields": [
							"snapshot_product_name^4",
							"store_name_text^2"
						],
						"fuzziness": "AUTO",
						"operator": "and"
					}
				}
			]
		}
	},
	"size": 20
}
```

### 6.3. Response shaping contract

Khi text search:
1. Query `order_search_lines` để lấy `order_id` + `matched_lines`.
2. `mget`/`terms` query sang `orders_read` để hydrate summary.
3. Trả response gồm:
	 - `total_orders`
	 - `matched_lines_count`
	 - `items[]` (order summary + highlights + match_reason)
	 - `next_cursor`

---

## 7. Projection Semantics, Out-of-Order Guard và Idempotency

### 7.1. Guard matrix theo stream ownership

| Event nhóm | Index bị ảnh hưởng | Guard bắt buộc | Ghi chú |
|---|---|---|---|
| `ORDER_CREATED`, `ORDER_PAID`, `ORDER_CANCELLED`, `ORDER_STATUS_CHANGED`, `ORDER_COMPLETED` | Cả 2 index | `order_version` đơn điệu theo `order_id` | Không apply nếu `incoming.order_version <= current.order_version` |
| `PARCEL_STATUS_CHANGED` | Cả 2 index | `parcel_version` theo `parcel_id` + `shipment_event_sequence_id` | Guard theo từng parcel, không dùng global sequence |
| `ORDER_REFUNDED`, `ORDER_REFUND_FAILED` | `orders_read` (chính) + line status | `refund_event_sequence_id` | Tránh refund stale ghi đè trạng thái mới |
| Payment status derived updates | `orders_read` | `payment_event_sequence_id` | Stream payment tách ownership với order lifecycle |

> **Không dùng** so sánh thứ tự UUID `event_id` theo từ điển để quyết định mới/cũ.

### 7.2. Scripted upsert contract

- Nếu document chưa tồn tại và event không đủ dữ liệu dựng mới -> park `t.order.cqrs.retry` để self-heal snapshot.
- Nếu guard fail (event cũ) -> skip an toàn, vẫn commit offset.
- Nếu guard pass -> apply patch fields, cập nhật `last_event_*`, `indexed_at`, `projection_version`.

### 7.3. Commit offset rules

Offset chỉ được commit khi:
1. Bulk write thành công toàn bộ batch, hoặc
2. Event đã được park an toàn vào `t.order.cqrs.retry`/`t.order.cqrs.dlq`.

---

## 8. Retry, DLQ và Self-heal

### 8.1. Retry topology

- **Inline retry ngắn:** tối đa 3 lần cho lỗi ES transient (`429`, timeout, connection reset).
- **Retry topic:** `t.order.cqrs.retry` với backoff tăng dần (30s, 2m, 10m, 30m, ...).
- **DLQ topic:** `t.order.cqrs.dlq` cho contract violation hoặc quá retry budget.

### 8.2. Các lỗi đi thẳng DLQ

- Thiếu `order_id` hoặc thiếu guard key bắt buộc theo event type.
- Payload sai schema nghiêm trọng (ví dụ `parcels` không phải mảng ở `ORDER_CREATED`).
- Dữ liệu vi phạm privacy contract (có PII cấm index) sau bước sanitize.

### 8.3. Self-heal strategy

- Retry replayer có thể gọi snapshot builder (DB read-model query nội bộ) để rebuild full document cho `order_id`.
- Sau rebuild thành công, replay lại event đang treo.
- Nếu vẫn fail do schema mismatch vĩnh viễn -> chuyển DLQ và alert.

---

## 9. Reindex Zero-Downtime (2-index synchronized cutover)

### 9.1. Phase A - Tạo index mới

- Tạo `orders_read_vN+1` và `order_search_lines_vN+1` với mapping mới.

### 9.2. Phase B - Snapshot backfill

- Backfill từ PostgreSQL theo batch (`created_at`, `order_id`) vào write aliases tạm của `vN+1`.
- Không dừng projector hiện tại.

### 9.3. Phase C - Catch-up theo watermark

- Start nhóm consumer catch-up đọc từ watermark Kafka đã chụp lúc bắt đầu backfill.
- Chỉ khi lag về 0 mới cho qua Phase D.

### 9.4. Phase D - Validation gates

- So sánh `doc_count` giữa nguồn và index mới theo nhiều bucket thời gian.
- Sample diff ngẫu nhiên theo `order_id`.
- Smoke tests cho 2 query class (filter + text).
- Kiểm tra privacy (không xuất hiện phone/address raw trong `_source`).

### 9.5. Phase E - Atomic alias cutover

```json
POST _aliases
{
	"actions": [
		{ "remove": { "alias": "orders_read", "index": "orders_read_vN" } },
		{ "add": { "alias": "orders_read", "index": "orders_read_vN+1" } },
		{ "remove": { "alias": "orders_write", "index": "orders_read_vN" } },
		{ "add": { "alias": "orders_write", "index": "orders_read_vN+1", "is_write_index": true } },

		{ "remove": { "alias": "order_search_lines", "index": "order_search_lines_vN" } },
		{ "add": { "alias": "order_search_lines", "index": "order_search_lines_vN+1" } },
		{ "remove": { "alias": "order_search_lines_write", "index": "order_search_lines_vN" } },
		{ "add": { "alias": "order_search_lines_write", "index": "order_search_lines_vN+1", "is_write_index": true } }
	]
}
```

### 9.6. Rollback

- Nếu phát hiện sai lệch sau cutover: alias swap ngược atomically về `vN`.
- Duy trì shadow catch-up ngắn hạn để giảm mất đồng bộ khi rollback.

---

## 10. Observability & Alerting Contracts

Metrics bắt buộc:
- `order_cqrs_projection_lag_seconds` (p95/p99)
- `order_cqrs_projector_bulk_failures_total`
- `order_cqrs_retry_topic_backlog`
- `order_cqrs_dlq_total`
- `order_cqrs_reindex_catchup_lag`
- `order_search_query_latency_ms` (theo class A/B)

Alerts bắt buộc:
- Lag vượt ngưỡng SLO (`>15s` ở p99 trong 5 phút).
- DLQ backlog tăng liên tục.
- Reindex validation fail.
- Alias mismatch giữa read/write alias.

---

## 11. Checklist triển khai

1. Event contract có đủ guard fields theo [Order Events](order_events.md).
2. Workers cập nhật đồng thời cả `orders_read` và `order_search_lines` theo cùng một event.
3. API workflow áp dụng đúng 2 query classes và response contract.
4. Reindex runbook đã được dry-run thành công ở staging.
5. Privacy audit xác nhận không có PII nhạy cảm trong index.
