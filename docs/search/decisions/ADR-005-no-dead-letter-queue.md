---
doc_id: search-adr-005
service: search
status: accepted
date: 2026-09-02
decision_owners:
  - Search Service team
supersedes: null
partially_superseded_by: ADR-019-inventory-owned-sku-availability
evidence: []
---

# ADR-005 — Không dùng dead-letter queue

## Context

Consumer của Search có thể gặp ba loại lỗi: lỗi tạm thời của Elasticsearch, lỗi dữ
liệu không tự hồi phục (payload sai hợp đồng, mapping conflict), và trường hợp
document đích không tồn tại. Thiết kế trước có một topic DLQ kèm consumer xử lý
riêng.

## Decision

> The Inventory missing-document clause below is historical and superseded by
> ADR-019. Search still has no DLQ, but an availability missing document is retried
> without ACK as a readiness contract violation. The Review behavior remains active.

Bỏ hoàn toàn DLQ. Chính sách xử lý lỗi:

- **Lỗi tạm thời** (timeout, 429, 503, mất kết nối): không commit offset, để Kafka
  gửi lại.
- **Lỗi không tự hồi phục**: ghi log ở mức error, tăng metric tương ứng, bỏ qua
  record và ACK.
- **Document không tồn tại cho Review**: ghi metric và bỏ qua; đây có thể là trạng
  thái bình thường. Inventory availability tuân theo ngoại lệ ADR-019.
- **Phục hồi**: full reindex là công cụ phục hồi chính thức cho mọi mất mát
  projection.

## Alternatives considered

- **Giữ DLQ.** Cho phép xử lý lại từng event lỗi, nhưng cần thêm topic, thêm
  consumer, thêm công cụ replay và thêm runbook — trong khi projection có thể được
  dựng lại toàn bộ bằng reindex.
- **Retry vô hạn tại chỗ.** Một record hỏng sẽ chặn cả partition.
- **Bỏ qua im lặng.** Mất khả năng phát hiện contract drift.

## Consequences

Tích cực: đường xử lý event đơn giản, không có hạ tầng phụ, và không có nguy cơ
DLQ phình ra rồi bị bỏ quên.

Tiêu cực: không có cơ chế tự động xử lý lại một event lỗi. Nếu một producer phát
sai schema trong thời gian dài, dữ liệu bị mất phải bù bằng reindex. Vì vậy
metric `search_payload_invalid_total` và `search_document_missing_total` là tín
hiệu vận hành bắt buộc, không phải tùy chọn: chúng là thứ duy nhất thay thế cho
DLQ.

## Migration impact

Không còn topic DLQ và consumer group xử lý DLQ. Cấu hình retry của consumer phải
đủ để chịu sự cố Elasticsearch ngắn hạn.

## Verification

Test bắt buộc: một record hỏng trong batch không chặn các record còn lại; lỗi tạm
thời dẫn tới xử lý lại; mọi nhánh bỏ qua đều tăng đúng metric.
