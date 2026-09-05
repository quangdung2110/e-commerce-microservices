---
doc_id: search-operations
service: search
scope: current
status: proposed
owners:
  - Search Service team
evidence: []
verified_against_commit: null
---

# Search Service — Vận hành

> File này trả lời: “Search hỏng thì phát hiện và xử lý thế nào?”

## 1. SLO và SLI

| SLI | Cách đo | Mục tiêu |
|---|---|---|
| Availability của API đọc | Tỉ lệ response không phải 5xx | TBD |
| Latency search | Phân vị của `http_server_requests_seconds` theo endpoint | TBD |
| Latency suggest | Như trên, tách riêng vì ngân sách chặt hơn nhiều | TBD |
| Freshness catalog | `catalog_indexed_at` trừ CloudEvent `time` | TBD |
| Freshness tồn kho | `stock_indexed_at` trừ `stock_observed_at` | TBD |
| Consumer lag | Lag theo topic và partition | TBD |
| Tỉ lệ lỗi indexing | `search_indexing_total{status="fail"}` chia tổng | TBD |

Ngưỡng cụ thể và error budget để `TBD` cho tới khi có số liệu production. Không
mô tả service là “nhanh” hay “sẵn sàng cao” khi chưa có số đo.

Yêu cầu định lượng duy nhất đã chốt ở mức thiết kế là ngân sách timeout phía
client của Search: 5s cho truy vấn sản phẩm, 2s cho suggest, và mức dài hơn cho
`_update_by_query` diện rộng vì đó là thao tác batch.

## 2. Health endpoints

- **Liveness:** tiến trình còn sống. Không kiểm tra dependency, để một sự cố
  Elasticsearch không gây restart hàng loạt.
- **Readiness:** chỉ `UP` khi kết nối Elasticsearch dùng được và consumer đã
  subscribe xong. Elasticsearch không dùng được nghĩa là instance không phục vụ
  được truy vấn nào, nên phải bị rút khỏi traffic.
- **Startup:** cho phép thời gian khởi động dài hơn khi cluster đang khởi động
  cùng lúc.

Instance phải bị rút khỏi traffic khi mất kết nối Elasticsearch kéo dài, không
phải khi consumer lag cao — lag không ảnh hưởng khả năng phục vụ đọc.

## 3. Configuration

| Nhóm | Nội dung | Bắt buộc | Secret | Cần restart |
|---|---|---|---|---|
| Elasticsearch | URI cluster, credential, connection/socket timeout | Có | Credential: có | Có |
| Kafka | Bootstrap server, credential, `group.id`, `auto.offset.reset`, `max.poll.records`, concurrency, stock-v1 topic | Có | Credential: có | Có |
| Alias | Tên read/write alias cho ba index | Có | Không | Có |
| Ngân sách truy vấn | Timeout cho search, suggest và `_update_by_query` | Có | Không | Không, nếu cấu hình động |
| Throttle | `requests_per_second` cho thao tác diện rộng | Có | Không | Không |
| Pagination | `size` mặc định và tối đa | Có | Không | Không |
| Lịch job | Cron cho rebuild suggestion, recompute count, purge tombstone | Có | Không | Có |
| Suggestion rebuild | `suggestion_min_docs`, ratio tối thiểu với version trước (`0.80`), hot prefix, PIT keep-alive, lease/renewal và retention rollback | Có | Không | Không, nếu cấu hình động |
| Cửa sổ tombstone | Số ngày giữ tombstone trước khi purge | Có | Không | Không |
| CORS | Danh sách origin được phép | Có | Không | Có |

Giá trị mặc định thực tế phải được đọc từ cấu hình runtime khi tài liệu được xác
minh; không ghi giá trị secret hoặc tên secret vào tài liệu này.

## 4. Observability

**Metrics tối thiểu**

| Metric | Loại | Label an toàn |
|---|---|---|
| `http_server_requests_seconds` | histogram | `uri`, `method`, `status` |
| `es_query_duration_seconds` | histogram | `index`, `query_type` |
| `kafka_consumer_lag` | gauge | `topic`, `partition`, `group` |
| `search_indexing_total` | counter | `topic`, `event_type`, `status` |
| `search_indexing_lag_seconds` | histogram | `topic` |
| `search_event_ignored_total` | counter | `topic`, `event_type` |
| `search_document_missing_total` | counter | `topic`, `event_type` |
| `search_payload_invalid_total` | counter | `topic`, `event_type` |
| `search_category_enrichment_miss_total` | counter | `topic`, `event_type` |
| `search_zombie_update_blocked_total` | counter | `topic`, `event_type` |
| `search_job_duration_seconds` | histogram | `job` |
| `search_reindex_state` | gauge | `phase` |
| `search_stock_snapshot_export_total` | counter | `status` |
| `search_stock_snapshot_age_seconds` | gauge | Không có |
| `search_review_snapshot_export_total` | counter | `status` |
| `search_review_snapshot_age_seconds` | gauge | Không có |

Không dùng `sku_id`, `product_id`, `category_id`, từ khóa tìm kiếm hay `user_id`
làm label: cardinality không kiểm soát được. Các identifier đó thuộc về log.

**Log có cấu trúc.** Mỗi dòng log mang `request_id` cho đường đọc, hoặc `topic`,
`partition`, `offset`, `event_type`, `event_id` cho đường ghi. Log ở mức INFO
không chứa payload event đầy đủ.

**Tracing.** Hiện chỉ có tương quan bằng `request_id`. Trace phân tán là hạng mục
tương lai, cần khi muốn đo tách bạch thời gian trong Elasticsearch so với thời
gian trong ứng dụng.

## 5. Alerts

| Alert | Điều kiện | Severity | Owner | Runbook |
|---|---|---|---|---|
| Search API lỗi | Tỉ lệ 5xx vượt SLO trong cửa sổ quan sát | High | Search on-call | Dependency Elasticsearch |
| Elasticsearch không khỏe | Cluster không `green`, hoặc readiness fail | High | Search on-call | Elasticsearch outage |
| Consumer lag tăng | Lag vượt ngưỡng và tăng liên tục | High | Search on-call | Consumer lag |
| Freshness kém | Độ trễ indexing vượt ngưỡng | Medium | Search on-call | Consumer lag |
| Payload invalid tăng | `search_payload_invalid_total` tăng đột biến | Medium | Search + producer | Contract drift |
| `document_missing` tăng | Vượt nền bình thường | Medium | Search on-call | Lệch projection |
| Enrichment miss tăng | Vượt nền bình thường | Low | Search on-call | Lệch danh mục |
| Job không chạy | Job hằng ngày không hoàn thành đúng cửa sổ | Medium | Search on-call | Job thất bại |
| Reindex quá hạn | Run vượt giới hạn thời gian hoặc `FAILED` | Medium | Search on-call | Reindex |
| Đĩa Elasticsearch | Vượt ngưỡng cảnh báo dung lượng | High | Platform | Dung lượng cluster |

Ngưỡng cụ thể là `TBD` cho tới khi có nền số liệu.

## 6. Failure modes

| Sự cố | Hành vi |
|---|---|
| Elasticsearch không sẵn sàng | Toàn bộ API đọc trả 503; readiness fail; consumer không commit offset và lag tăng |
| Cluster `yellow` | Vẫn phục vụ; cảnh báo; hoãn reindex |
| Đĩa gần đầy | Elasticsearch có thể chuyển index sang read-only; indexing dừng, đọc còn chạy; phải giải phóng dung lượng trước khi khôi phục ghi |
| Kafka không sẵn sàng | API đọc không ảnh hưởng; dữ liệu ngày càng cũ; consumer tự kết nối lại |
| Consumer lag cao | Dữ liệu cũ; tăng số instance tới trần partition; kiểm tra thao tác diện rộng đang chiếm băng thông ghi |
| Producer đổi schema | `search_payload_invalid_total` tăng; event bị bỏ qua; cần phối hợp với producer rồi reindex |
| Product export API lỗi | Chỉ ảnh hưởng reindex; traffic đọc bình thường |
| Inventory snapshot export lỗi | Reindex không được cutover; giữ alias cũ và phối hợp Inventory |
| Review rating snapshot export lỗi | Reindex không được cutover; giữ alias cũ và phối hợp Review |
| Job singleton kẹt lock | Job bỏ chu kỳ; số đếm và suggestion cũ dần |
| Deploy nhiều version song song | An toàn nếu mapping không đổi; đổi mapping bắt buộc đi qua reindex |

## 7. Degraded behavior

- Suggest lỗi thì chỉ mất dropdown gợi ý; API tìm kiếm chính vẫn phục vụ.
- Indexing dừng thì kết quả vẫn trả về, chỉ là dữ liệu cũ; đây là degrade chấp
  nhận được vì không luồng giao dịch nào phụ thuộc Search.
- `product_count` và suggestion cũ là degrade nhẹ, không cần khẩn cấp.
- Elasticsearch mất hoàn toàn thì không có fallback: Search không có store thứ hai
  ([ADR-001](./decisions/ADR-001-elasticsearch-only-store.md)). Frontend phải chịu
  được việc khu vực tìm kiếm không khả dụng.
- Không được phép fallback sang gọi Product hoặc Inventory để dựng kết quả tìm
  kiếm: sẽ đẩy tải đọc của buyer vào write store.

## 8. Scaling và capacity

- API scale theo RPS và latency; stateless nên scale ngang tự do.
- Consumer scale tới trần là tổng số partition của năm topic; vượt trần thì instance
  thừa sẽ nhàn rỗi.
- Nút thắt đã biết: băng thông ghi của Elasticsearch khi có thao tác diện rộng,
  `cardinality` aggregation trên catalog lớn, và `track_total_hits` cho truy vấn
  rộng.
- Job singleton không scale ngang; cần giảm phạm vi hoặc chia lô nếu chậm.
- Sizing cluster theo quy mô catalog, mỗi shard giữ 10–50GB, heap tối đa 31GB, còn
  lại dành cho filesystem cache. Con số cụ thể phải được xác nhận bằng load test
  thay vì suy diễn.

## 9. Deployment và rollback

- Deploy service không kèm đổi mapping là rollout thường và rollback được bằng cách
  quay về image cũ.
- Đổi mapping **không bao giờ** đi cùng deploy: chạy `WF-SEARCH-009`, cutover alias,
  rồi mới rollout code phụ thuộc mapping mới nếu có.
- Code mới phải đọc được document do version cũ ghi và ngược lại trong suốt thời
  gian rollout.
- Rollback dữ liệu là alias swap ngược về index version trước; giữ index cũ tối
  thiểu 24 giờ.
- Đổi `group.id` của consumer sẽ đặt lại vị trí đọc; chỉ làm khi có chủ đích và
  hiểu rõ hệ quả với `auto.offset.reset`.

## 10. Runbooks

Mỗi runbook cần: triệu chứng, bước chẩn đoán chỉ đọc, cách khắc phục, cách xác
nhận đã phục hồi và người escalate. Bộ runbook bắt buộc:

| Runbook | Phạm vi |
|---|---|
| Consumer lag | Phân biệt Kafka chậm, Elasticsearch chậm hay thao tác diện rộng chiếm băng thông |
| Elasticsearch outage | Cluster đỏ, đĩa đầy, index bị chuyển read-only |
| Lệch projection | `document_missing` hoặc enrichment miss tăng; import stock và rating snapshot rồi replay offsets, recompute count, rồi mới reindex catalog |
| Contract drift | `search_payload_invalid_total` tăng sau khi producer deploy |
| Reindex thất bại | Hủy run, dọn index thừa, hoặc rollback alias |
| Job thất bại | Chạy lại thủ công rebuild suggestion, recompute count, purge tombstone |
| Zombie update | `search_zombie_update_blocked_total` tăng bất thường |

Không runbook nào được đánh dấu hoàn chỉnh trong đợt refactor này; nội dung chi
tiết phải viết cùng với vận hành thực tế.

## 11. Workers và scheduled jobs

| Worker/Job | Trigger | Concurrency | Idempotency | Chạy lại | Telemetry |
|---|---|---|---|---|---|
| Indexing worker | Kafka, liên tục | Theo partition | Guard theo nguồn | Bằng offset Kafka | Lag, tỉ lệ indexing, độ trễ |
| Full reindex job | API hoặc lịch bảo trì | Singleton, một run tại một thời điểm | Checkpoint theo chunk | Resume hoặc chạy run mới | Tiến độ, pha, kết quả |
| Suggestions rebuilder | Hằng ngày, giờ thấp điểm | Singleton qua ES lease lock | Build index mới mỗi lần; source alias đổi hoặc validation fail thì không swap | Chạy lại an toàn bất cứ lúc nào | Số document, validation, lock, source generation, version/rollback |
| Category count recompute | Định kỳ | Singleton | Ghi giá trị tuyệt đối | Chạy lại an toàn | Thời lượng, số danh mục |
| Tombstone purger | Hằng ngày, giờ thấp điểm | Singleton | Lọc theo trạng thái hiện tại | Chạy lại an toàn | Số purge, tuổi tombstone |

Mọi job phải có throttle khi chạm Elasticsearch diện rộng và phải hủy được mà
không để lại trạng thái dở dang cần dọn tay. Lịch chạy cụ thể là `TBD` cho tới khi
đọc từ cấu hình runtime.
