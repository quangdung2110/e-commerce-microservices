---
doc_id: review-adr-002
service: review
status: accepted
date: 2026-09-05
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
---

# ADR-002 — PostgreSQL là kho duy nhất, không cache

## Status

`Accepted`

## Context

Đánh giá nằm trên trang sản phẩm, nơi có lưu lượng đọc cao nhất của hệ thống. Phản
xạ thông thường là đặt Redis trước đường đọc, hoặc đẩy đánh giá sang Elasticsearch để
tìm kiếm và lọc.

Nhưng dữ liệu đánh giá có ba tính chất khiến cache khó đúng: tác giả phải nhìn thấy
đánh giá của mình ngay sau khi gửi; nội dung bị ẩn phải biến mất ngay, không được nằm
lại trong cache; và điểm số hiển thị sai vì cache cũ là lỗi mà người dùng nhìn thấy
trực tiếp.

Truy vấn thực tế cũng hẹp: lọc theo `product_id`, tùy chọn thêm `sku_id`, số sao,
hoặc có media, sắp xếp theo thời gian. Không có full-text search, không có ranking,
không có facet phức tạp.

## Decision

Review dùng PostgreSQL làm kho duy nhất. Không có Redis, không có cache trong process,
không có Elasticsearch cho đường đọc của Review.

Đường đọc được phục vụ bằng partial index có điều kiện `status = 'APPROVED'`, mỗi
index ứng với một bộ lọc cụ thể; xem
[Data, mục 10](../data.md#10-queryindex-policy). Phân trang dùng keyset trên
`(created_at, id)`, không dùng `OFFSET`.

Khi tải tăng, thứ tự xử lý được quy định trước:

1. Thêm read replica cho đường đọc, với ngoại lệ là đường đọc ngay sau khi ghi của
   chính tác giả phải đi vào primary.
2. Cache ở CDN hoặc API Gateway với TTL ngắn cho response công khai.
3. Chỉ khi hai bước trên không đủ mới xét cache trong service — và khi đó phải viết
   ADR mới thay thế ADR này.

Việc tìm kiếm sản phẩm theo rating không thuộc Review; Search giữ projection riêng.

## Alternatives considered

**Redis cache cho danh sách và aggregate.** Giảm tải database rõ rệt. Nhược điểm: cần
lớp invalidation cho từng thay đổi đánh giá, từng lần ẩn, từng lần cập nhật aggregate;
cần chống cache stampede cho sản phẩm nóng; và mở ra khả năng nội dung đã bị ẩn vẫn
hiển thị vì một khóa cache bị bỏ sót. Với một service chưa từng chạy production và
chưa có số liệu tải, đây là độ phức tạp trả trước cho một vấn đề chưa xác nhận.

**Elasticsearch cho đường đọc của Review.** Đánh đổi tính nhất quán mạnh lấy khả năng
truy vấn mà các bộ lọc hiện tại không cần. Cũng tạo ra hai nguồn sự thật cho cùng một
nội dung.

**Materialized view làm sẵn danh sách.** Vẫn cần refresh và vẫn có độ trễ, nhưng khó
kiểm soát hơn cache tường minh.

## Consequences

**Tích cực.** Không có lớp invalidation nào để sai. Tác giả luôn đọc được đánh giá
của mình. Nội dung bị ẩn biến mất ngay. Ít thành phần hạ tầng hơn, ít chế độ hỏng hơn.
Docker Compose cho phát triển local không cần Redis.

**Tiêu cực.** Mọi lượt xem trang sản phẩm chạm database. Không có degradation từng
phần: PostgreSQL hỏng thì tính năng đánh giá dừng hoàn toàn, không có cache để phục
vụ dữ liệu cũ. Sản phẩm viral tạo hot partition đọc mà không có lớp đệm nào.

**Chi phí vận hành.** Đường đọc phụ thuộc hoàn toàn vào việc index đúng. Một bộ lọc
mới được thêm mà không có index tương ứng sẽ trở thành sự cố, không phải chậm nhẹ.

## Migration impact

Không có gì để migrate. Ảnh hưởng chính là ràng buộc về sau: nếu ai đó thêm Redis vào
cấu hình của Review, đó là dấu hiệu thiết kế đã đi lệch khỏi ADR này, và cần một ADR
mới nói rõ vì sao.

## Verification

Integration test phải kiểm bằng `EXPLAIN` rằng mỗi bộ lọc dùng đúng index dự kiến.
Test hiệu năng phải chứng minh độ trễ trang cuối tương đương trang đầu trên một sản
phẩm có 50.000 đánh giá — nếu không, keyset pagination đã bị cài sai. Test phải chứng
minh đánh giá `HIDDEN_BY_REPORT` không xuất hiện trong bất kỳ response nào.
