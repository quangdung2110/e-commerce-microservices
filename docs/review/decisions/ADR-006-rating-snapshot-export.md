---
doc_id: review-adr-006
service: review
status: accepted
date: 2026-09-05
decision_owners:
  - Review Service team
  - Search Service team
supersedes: null
evidence:
  - contracts/http/review/internal/v1/openapi.yaml
  - docs/search/decisions/ADR-016-review-rating-snapshot-reindex.md
---

# ADR-006 — Snapshot export rating cho full reindex của Search

## Status

`Accepted`

## Context

Search dựng lại index sản phẩm từ đầu bằng ba pha Build, Catch-up và Cutover. Trước
đây pha Build chỉ import catalog từ Product và tồn kho từ Inventory; rating được kỳ
vọng đến từ việc replay `t.review.events` trong pha Catch-up.

Cách đó không đủ. Replay chỉ mang lại các thay đổi xảy ra trong cửa sổ replay; một sản
phẩm có 500 đánh giá nhưng không có đánh giá mới nào trong hai tuần sẽ không phát event
nào, và index mới sẽ hiển thị nó với rating bằng 0. Đây không phải trường hợp hiếm —
phần lớn sản phẩm ở trạng thái đó.

Vấn đề thứ hai là race: nếu Review export trạng thái hiện tại trong lúc dữ liệu đang
thay đổi, Search không biết phải replay từ đâu để không bỏ sót và không ghi đè.

## Decision

Review cấp một endpoint export nội bộ,
[`GET /internal/v1/reviews/rating-aggregates`](../../../contracts/http/review/internal/v1/openapi.yaml),
chỉ dành cho service identity của Search và không đi qua API Gateway.

Cơ chế:

1. Khi bắt đầu một export session, Review chụp offset hiện tại của **mọi** partition
   `t.review.events` **trước** khi bắt đầu scan.
2. Tập offset này được trả lại **không đổi** ở mọi trang của cùng session, dưới tên
   `replay_from_offsets`.
3. Offset là vị trí đầu tiên Search phải replay, tính **inclusive**.
4. Search import toàn bộ snapshot trước, rồi seek vào các offset đó và replay
   `REVIEW_METRICS_UPDATED`.

Snapshot và event mang cùng một `review_revision` cho cùng một trạng thái
([ADR-004](./ADR-004-review-revision-ordering-guard.md)), nên phần chồng lấn giữa hai
đường được xử lý bằng guard: event có revision không lớn hơn giá trị đã materialize là
no-op.

Nếu Review không mở được scan nhất quán hoặc không chụp được offset, nó trả `503`.
Search **không được** cutover index mới; nó phải hủy hoặc chạy lại run.

Endpoint này không thay thế API aggregate công khai theo từng sản phẩm.

## Alternatives considered

**Chỉ replay event, không có snapshot.** Không cần endpoint mới. Nhưng mọi sản phẩm
không phát event trong cửa sổ replay sẽ mất rating trong index mới — tức là phần lớn
danh mục.

**Kafka topic compacted cho rating.** Compaction giữ lại giá trị cuối cho mỗi key, nên
đọc từ đầu topic sẽ cho trạng thái đầy đủ. Nhược điểm: cần một topic riêng với chính
sách retention khác, và việc compaction chạy khi nào là không xác định, nên "đọc từ
đầu" không bảo đảm có trạng thái mới nhất tại một thời điểm cụ thể.

**Search gọi API aggregate công khai cho từng sản phẩm.** Dùng lại thứ đã có. Nhưng
với hàng trăm nghìn sản phẩm, đó là hàng trăm nghìn request, không có watermark, và
không có cách nào biết trạng thái nào tương ứng với offset nào.

**Search đọc trực tiếp database của Review.** Nhanh nhất, và vi phạm ranh giới sở hữu
dữ liệu.

**Dùng `updated_at` thay cho offset watermark.** Không cần đọc Kafka khi export. Nhưng
timestamp không phải khóa thứ tự, và không nói được chính xác message nào cần replay.

## Consequences

**Tích cực.** Full reindex của Search không mất rating, kể cả cho sản phẩm không có
hoạt động gần đây. Snapshot có thể được lấy trong lúc dữ liệu đang thay đổi mà không
mất mutation: event trước watermark đã nằm trong snapshot, event sau watermark được
replay, và guard revision xử lý phần chồng lấn.

**Tiêu cực — Review trở thành dependency chặn Cutover của Search.** Review export lỗi,
thiếu offset, hoặc trả sai revision đều làm reindex của Search không hoàn thành được.
Đây là một ràng buộc vận hành liên service thật sự, không phải chi tiết kỹ thuật.

**Chi phí thứ hai.** Export cần Review kết nối được Kafka để chụp offset — một
dependency mà đường đọc bình thường không có. Kafka hỏng làm export thất bại ngay cả
khi database hoàn toàn khỏe mạnh.

**Cạm bẫy.** Export không kiểm tra tính đúng đắn của aggregate. Nếu đang có drift chưa
xử lý, Search sẽ materialize số liệu sai. Vì vậy runbook yêu cầu xử lý drift trước khi
cho Search chạy lại reindex.

## Migration impact

Contract đã tồn tại ở trạng thái `proposed`. Phía Search, việc đổi tên mapping
`review_version` thành `review_revision` là thay đổi mapping và chỉ có hiệu lực qua
versioned-index reindex; xem
[Search ADR-016](../../search/decisions/ADR-016-review-rating-snapshot-reindex.md).

## Verification

Contract test phải chứng minh `replay_from_offsets` giống hệt nhau ở mọi trang của một
session. Test tích hợp phải bao phủ: aggregate tồn tại trước Build; mutation xảy ra
trong lúc export và được replay đúng; replay event trùng hoặc cũ là no-op; sản phẩm
chưa có đánh giá; và Review export thất bại thì Search không cutover.
