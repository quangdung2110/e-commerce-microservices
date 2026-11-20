---
doc_id: promotion-adr-021
service: promotion
status: accepted
date: 2026-10-15
decision_owners:
  - Promotion Service team
supersedes: null
evidence:
  - java/services/promotion-service/src/main/java/com/platform/promotionservice/service/CampaignScheduleService.java
  - java/services/promotion-service/src/main/java/com/platform/promotionservice/service/MarkdownSnapshotService.java
  - java/services/promotion-service/src/test/java/com/platform/promotionservice/service/CampaignScheduleBatchTest.java
  - java/services/promotion-service/src/test/java/com/platform/promotionservice/integration/CampaignScheduleBatchIntegrationTest.java
---

# ADR-021 — Phát snapshot START/END theo lô nhỏ và kiểm dedup key trước khi lock

## Status

Accepted (implemented). Làm rõ cách thực thi WF-PROMO-002 bước 5–6 của
[ADR-014](./ADR-014-claim-and-markdown-consistency.md) (giữ nguyên các bất biến: advisory lock theo
product, `source_revision` đơn điệu, causal dedup key, outbox commit trước `ZREM`). Không đổi contract
event, schema DB hay Redis keyspace.

## Context

Khi một member START/END đến hạn, worker phát current-state snapshot cho **mọi** product có rule trong
campaign. Cách cũ chạy toàn bộ trong **một** PostgreSQL transaction (`transactionTemplate.execute(process)`):

- Với campaign N product, transaction giữ N advisory lock tới lúc commit; admin markdown mutation và
  consumer Product tombstone của các product đó phải chờ.
- Mỗi product tốn một query tính winner; một product lỗi làm rollback cả N và vòng sau (500 ms) làm lại
  từ đầu, nên một dữ liệu hỏng chặn cả campaign.
- Retry/worker chạy trùng vẫn lock, tính winner và tiêu một giá trị `markdown_source_revision_seq` cho
  mọi product đã phát, rồi mới bị `ON CONFLICT DO NOTHING` bỏ qua ở bước insert cuối.
- Event của mọi product chỉ ra được sau một commit duy nhất, tức dồn thành một cục.

## Decision

1. **Chia lô, mỗi lô một transaction.** `CampaignScheduleService.processDue` đọc rule của campaign bằng
   keyset `id > afterId ORDER BY id` theo `promotion.schedule.emit-batch-size` (mặc định 200, `>= 1`, env
   `PROMOTION_SCHEDULE_EMIT_BATCH_SIZE`). Mỗi lô chạy trong một `TransactionTemplate` riêng; lock product
   chỉ giữ tới commit của lô. Giá trị này là kích thước lô, không phải trần số product.
2. **Kiểm lại campaign ở đầu mỗi lô.** Trong transaction của lô, đọc lại campaign và so
   `revision`/`publication_status` với member. Lệch thì dừng ngay và coi member là lỗi thời (dọn member).
3. **Dedup pre-check theo lô.** `MarkdownSnapshotService.emitBatch` chạy đúng một query
   `dedup_key IN (...)` (unique index) cho cả lô và bỏ qua product đã có key trước khi lấy advisory lock,
   tính winner hay cấp `source_revision`. Bước này chỉ là bộ lọc nhanh không khoá: `INSERT ... ON CONFLICT
   (dedup_key) DO NOTHING` trong `emit` vẫn là hàng rào chính xác, nên hai worker cùng thấy "chưa có" vẫn
   không phát trùng.
4. **Cô lập lô lỗi.** Lô lỗi rollback riêng lô đó, được log/đếm rồi **bỏ qua để các lô sau vẫn chạy**
   (một product hỏng không chặn cả campaign). Nếu còn lô lỗi, member **không** bị `ZREM`; vòng sau chạy lại
   cả campaign, lô đã commit bị pre-check bỏ qua. Chỉ khi mọi lô xong (hoặc member lỗi thời) mới `ZREM`.
5. **Đường admin không đổi.** Publish/pause/cancel vẫn gọi `emitForCampaign` trong cùng transaction với việc
   đổi `publication_status`/`revision` để nguyên tử với trạng thái campaign; không chia lô và không dùng
   pre-check (revision mới nên key không thể tồn tại).
6. **Quan sát.** Counter `promotion.schedule.emit.pages{outcome=committed|failed|stale}` và
   `promotion.schedule.emit.snapshots{result=emitted|already_emitted}`; log WARN khi member `INCOMPLETE`.

## Alternatives considered

- Giữ một transaction: đơn giản và nguyên tử theo campaign, nhưng lock dài, rollback toàn bộ và retry tốn.
- Ghi tiến độ theo lô (checkpoint) để khỏi chạy lại: thêm bảng/trạng thái; dedup key đã đủ idempotent và
  pre-check làm việc chạy lại rẻ.
- Dừng cả campaign khi một lô lỗi: giữ thứ tự nhưng một product hỏng chặn mọi product còn lại.
- Pre-check bằng lock rồi mới kiểm: vẫn tốn lock cho phần đã phát.
- Pre-check từng product một query: thêm N round trip; gom theo lô chỉ tốn một query mỗi lô.
- Chia lô cả đường admin: mất tính nguyên tử giữa đổi trạng thái campaign và snapshot PUBLISH/PAUSE/CANCEL.

## Consequences

Lock ngắn theo lô, một lô lỗi không rollback phần đã phát, retry rẻ và không tiêu sequence cho phần đã có,
event ra đều theo từng commit. Đổi lại:

- Campaign không còn nguyên tử ở bước START/END: trong khoảng ngắn một số product đã đổi trạng thái còn số
  khác chưa. Đây vốn là eventual consistency qua outbox/Kafka và Product chỉ nhận theo `source_revision`
  từng product nên không thêm rủi ro đúng-sai.
- Lô lỗi vĩnh viễn (product hỏng dữ liệu) làm member `INCOMPLETE` và được xử lý lại mỗi chu kỳ loop
  (mặc định 500 ms) cho tới khi sửa dữ liệu; phần còn lại của campaign vẫn hội tụ. Cần alert theo counter
  `outcome=failed` và RB-05.
- Lô lỗi rollback cả lô, kể cả product không lỗi cùng lô; chúng được phát ở vòng retry kế tiếp.
- Trong lô chạy lại, `source_revision` mới của product chưa phát lớn hơn của product đã phát lần trước; vẫn
  đơn điệu theo từng product nên không ảnh hưởng ordering của Product.
- Giữ nguyên khoảng trống `source_revision` khi hai worker thật sự tranh nhau cùng product (pre-check không
  khoá); khoảng trống không có ý nghĩa nghiệp vụ.

## Migration impact

Không migration. Thêm `promotion.schedule.emit-batch-size` (mặc định 200) và hai counter. Triển khai theo
một bước; rollback code quay về một transaction duy nhất, dữ liệu outbox tương thích vì dedup key không đổi.

## Verification

- `CampaignScheduleBatchTest` (unit, mock transaction manager): 450 rule/lô 200 → 3 transaction (200/200/50)
  và một `ZREM`; lô giữa lỗi → rollback đúng một lần, các lô còn lại commit, không `ZREM`; retry hoàn tất rồi
  `ZREM`; campaign đổi revision giữa lô → dừng và dọn member; rule invalidated không vào lô; campaign không
  rule không mở transaction; START sớm hơn `start_time` bị dọn.
- `CampaignScheduleBatchIntegrationTest` (PostgreSQL + Redis thật, lô 3, 7 product): phát đủ 7 ACTIVE;
  trigger PostgreSQL làm một product lỗi → lô trước/sau commit (4 row), member còn trong ZSET; gỡ lỗi rồi
  retry → đủ 7 row, không trùng, sequence chỉ tăng đúng 3 (product của lô lỗi); chạy lại member đã xong không
  thêm row và không tốn sequence; hai worker đồng thời cho đúng một snapshot mỗi product; member revision cũ
  không phát gì.
- Kết quả ngày 2026-10-15: `mvn -B test '-DargLine=-Dapi.version=1.44'` qua container Maven/JDK 21 và
  Testcontainers PostgreSQL/Redis/Kafka/Debezium: **86 test, 0 failure, 0 error, 0 skipped** (12 test mới:
  7 unit + 5 integration), gồm `OutboxCdcIntegrationTest`; `BUILD SUCCESS`.
- Benchmark 10.000 product, 2026-10-15 (`CampaignScheduleBenchmarkTest`, bật bằng `PROMOTION_BENCH=true`;
  PostgreSQL 16 + Redis trong Testcontainers, service chạy trong container Maven khác qua
  `host.docker.internal`, một lần chạy mỗi kịch bản; "cũ" = `emitForCampaign` trong một transaction như trước
  ADR này). Con số tuyệt đối bị phóng đại bởi mỗi product tốn nhiều round trip qua mạng Docker Desktop;
  chỉ dùng để so sánh tương đối, không phải SLO. "Chờ lock" là thời gian một writer khác xin advisory lock
  của một product ngẫu nhiên trong lúc campaign đang phát.

  | Kịch bản | Thời gian | Transaction mở lâu nhất | Advisory lock giữ cùng lúc | Chờ lock tối đa |
  |---|---|---|---|---|
  | Cũ, phát lạnh | 267 s | 267 s | 10.000 | 264 s |
  | Mới lô 50 | 135 s | 1,0 s | 51 | 0,66 s |
  | Mới lô 200 (mặc định) | 123 s | 2,8 s | 200 | 2,1 s |
  | Mới lô 1000 | 142 s | 16,2 s | 1.000 | 15,1 s |
  | Cũ, chạy lại khi đã phát đủ | 258 s, tốn 10.000 giá trị sequence | 258 s | 10.000 | 256 s |
  | Mới lô 200, chạy lại khi đã phát đủ | 0,9 s, 0 sequence | 0,01 s | 1 | 16 ms |
  | Cũ, 1 product lỗi ở ~99% | 290 s, **0** snapshot được phát, rồi retry 287 s (tổng 577 s) | 290 s | 9.900 | 287 s |
  | Mới lô 200, 1 product lỗi ở ~99% | 145 s, 9.800/10.000 snapshot đã phát, rồi retry 3,6 s (tổng 149 s) | 3,8 s | 200 | 3,1 s |

  Nhận xét: lock giữ và thời gian chờ tỉ lệ theo cỡ lô; lô 200 nhanh hơn cách cũ khoảng 2,2 lần ở lần phát
  lạnh và khoảng 3,9 lần để hội tụ khi có một product lỗi muộn (retry chỉ phát đúng lô lỗi, 200 giá trị
  sequence). Lô lớn hơn không nhanh hơn (1000 chậm hơn 200), lô nhỏ hơn chỉ chậm thêm khoảng 10% nhưng giữ
  lock ngắn hơn một nửa; cách cũ chậm hơn có thể do persistence context phình theo 10.000 rule nhưng chưa
  được kiểm chứng riêng. Một lần chạy đầu của kịch bản "1 product lỗi" ghi `longest_open_tx` 4.323 s do đồng
  hồ VM Docker nhảy (tổng thời gian test 5.128 s so với ~720 s tổng các lần đo); số trong bảng lấy từ lần chạy
  lại riêng phần cách mới. Chưa đo tải đồng thời với traffic thật, hay PostgreSQL/mạng gần production.
