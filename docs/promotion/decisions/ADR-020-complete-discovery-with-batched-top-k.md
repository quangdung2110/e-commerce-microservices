---
doc_id: promotion-adr-020
service: promotion
status: accepted
date: 2026-10-10
decision_owners:
  - Promotion Service team
supersedes: null
evidence:
  - java/services/promotion-service/src/main/java/com/platform/promotionservice/service/DiscoveryService.java
  - java/services/promotion-service/src/test/java/com/platform/promotionservice/integration/DiscoveryIntegrationTest.java
---

# ADR-020 — Discovery đầy đủ bằng keyset, batch read và top K toàn cục

## Status

Accepted (implemented). Mở rộng ADR-002 và ADR-018; xử lý gap phân trang public discovery
được ghi nhận ngoài phạm vi ADR-019. Không thay đổi lịch sử quyết định audit đó.

## Context

Public discovery và Checkout voucher options trước đây chỉ lấy 200 promotion public active
có ID nhỏ nhất, rồi mới loại ownership/evaluate/sort. Promotion ở vị trí 201 trở đi không được
xét dù phù hợp hơn hoặc giảm nhiều hơn. Snapshot/version/runtime cũng được đọc riêng từng voucher;
chỉ bỏ trần candidate sẽ tăng network round trip và bộ nhớ entity của JPA.

## Decision

1. Public candidate query trả scalar ID bằng keyset `id > afterId ORDER BY id ASC`, giữ filter
   enabled, `PUBLIC_SAVE`/`LIMITED_CLAIM`, campaign `PUBLISHED` và `[start,end)`. `NOT EXISTS`
   trên wallet `(user_id,promotion_id)` loại mọi ownership trước batch limit.
2. Wallet lazy-expire như trước, sau đó scan `SAVED` theo wallet ID bằng projection ID/promotion ID/expiry.
   Không giữ entity managed của toàn bộ candidate. Wallet và public dùng cùng `now` của request.
3. `promotion.discovery.batch-size` mặc định 200, validate >= 1, env `PROMOTION_DISCOVERY_BATCH_SIZE`.
   Đây là batch size, không phải candidate cap. Tiếp tục đến hết dữ liệu; cursor tăng theo dòng đã đọc,
   kể cả snapshot thiếu hoặc toàn batch ineligible. Không dừng khi có đủ K voucher eligible.
4. Snapshot đọc version một lần mỗi batch, pipeline GET từng key; chỉ nhận snapshot đúng version/ID
   và JSON parse được. Miss dùng một constructor projection query join promotion–campaign, pipeline
   SET với TTL. Redis lỗi fallback DB; không thêm cache/keyspace hay JVM AST cache.
5. Evaluate rules và discount trước, pipeline HMGET runtime chỉ cho candidate eligible. Hash thiếu
   giữ `NOT_CHECKED`; lỗi pipeline giữ discount và `DEGRADED` fail-open cho candidate eligible;
   exhausted trả `BUDGET_EXHAUSTED`, discount 0. Single calculate/reserve vẫn giữ logic hiện tại.
6. Heap tối đa K (`request.limit`, default 20 cho discovery/suggestion và 50 cho options) giữ kết quả
   trên toàn wallet/public theo eligible trước, discount giảm dần, promotion ID tăng dần. Lọc
   `include_ineligible` trước heap, sort heap khi trả response. Contract không thêm cursor.

## Alternatives considered

- Tăng trần 200: chỉ dời ngưỡng bỏ sót, không bảo đảm top K toàn cục.
- Lấy toàn bộ rồi sort: kết quả đầy đủ nhưng giữ O(N) DTO/entity và sort O(N log N).
- Trả cursor candidate cho client: client phải gọi nhiều lần và tự tổng hợp mới có top K;
  thay contract và trải nghiệm Checkout.
- Dừng scan khi đủ eligible: sai khi batch sau có discount cao hơn.
- SQL sort discount rồi limit trước rule engine: eligibility phụ thuộc AST/context/budget,
  không bảo đảm chọn đúng voucher. Chưa cần thêm rule index/search projection ở quy mô hiện tại.

## Consequences

Không còn bỏ sót do trần 200, giữ ranking/API cũ, network I/O theo batch. Bộ nhớ xử lý
O(B + K), chọn top K O(N log K), vẫn đánh giá N candidate. Latency tăng theo cardinality và AST;
200 là default vận hành, chưa phải giá trị tối ưu đã benchmark. Cache/missing runtime vẫn có
semantics best-effort; cùng `now` không tạo snapshot giao dịch thống nhất giữa DB/Redis và
mọi batch khi publish/save/budget thay đổi đồng thời. Reserve vẫn quyết định cuối.

## Migration impact

Không đổi HTTP/event contract, schema DB hoặc Redis keyspace. Tận dụng promotion/wallet PK,
campaign-window và unique wallet ownership index hiện có; chỉ thêm index sau khi đo query plan
trên dữ liệu thật. Deploy code và cấu hình batch size cùng nhau. Rollback code sẽ khôi phục
hạn chế 200 candidate; không cần rollback dữ liệu.

## Verification

- `DiscoveryIntegrationTest`: PostgreSQL/Redis thật, 401 public candidates; top voucher ở 201/401,
  batch đầu ineligible; ownership mọi status trước paging; tie/ranking wallet-public, wallet >200,
  lazy expiry, options/display, cold/warm/corrupt/versioned snapshot, TTL, budget pipeline và empty result.
- `VoucherEvaluationBatchTest`: batch/single parity, missing/exhausted state, fail-open `DEGRADED`,
  chỉ đọc runtime cho eligible; `PromotionSnapshotBatchTest`: Redis unavailable dùng một batch DB
  projection query, dedup ID và empty batch không I/O.
- `DiscoveryBatchTest`: batch size cấu hình, cursor qua batch mất snapshot và thời gian request cố định.
- Chạy cả suite Promotion gồm HTTP contract, Lua, lifecycle và CDC để kiểm tra hồi quy.
- Kết quả ngày 2026-10-10: `mvn -B clean test '-DargLine=-Dapi.version=1.44'` qua container
  Maven/JDK 21 và Testcontainers PostgreSQL/Redis/Kafka/Debezium: **58 test, 0 failure, 0 error,
  0 skipped**, gồm 13 test mới cho discovery/batch; `BUILD SUCCESS`.
- Chưa có load benchmark chứng minh p99/SLO. Cần đo warm/cold/degraded, số candidate,
  `EXPLAIN (ANALYZE, BUFFERS)` và round trip trước khi tuning batch size/index.
