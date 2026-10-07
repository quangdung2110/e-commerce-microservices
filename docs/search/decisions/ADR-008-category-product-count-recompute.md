---
doc_id: search-adr-008
service: search
status: accepted
date: 2026-09-02
decision_owners:
  - Search Service team
supersedes: null
evidence:
  - docs/search/schema/scripts/category_product_count.painless
---

# ADR-008 — `categories.product_count` được tính lại theo lịch, không cộng trừ

## Context

Sidebar điều hướng cần số lượng sản phẩm cho mỗi danh mục. Chạy aggregation trên
index `products` cho mỗi request duyệt danh mục là quá đắt, nên số này được vật
chất hóa trên document danh mục.

Thiết kế trước cộng hoặc trừ 1 cho mỗi event thay đổi trạng thái hiển thị. Cách đó
đòi hỏi biết **trạng thái trước** của product, trong khi event catalog của Product
là snapshot đầy đủ và không nói gì về trạng thái trước. Cộng trừ cũng không
idempotent: một event được xử lý lại sẽ đếm hai lần.

## Decision

> **Đính chính (2026-09-08).** Đoạn dưới ghi sai tên field gom bucket. Field đúng là
> `category_ancestor_ids`, không phải `category_id`. `category_id` chỉ mang một giá trị
> là danh mục được gán, nên gom theo nó thì mọi danh mục không phải lá ra `0`;
> `category_ancestor_ids` mang cả node lẫn tổ tiên nên một lần aggregation cho ra số
> đếm cả nhánh cho mọi cấp. Xem [WF-SEARCH-011](../workflows.md) và
> [Interfaces §3.3](../interfaces.md). Quyết định thực sự của ADR này — ghi giá trị
> tuyệt đối theo lịch thay vì cộng trừ theo event — không đổi.

`product_count` được tính lại định kỳ bằng một job: chạy `terms` aggregation theo
`category_id` trên index `products` với đúng bộ filter hiển thị, kèm `cardinality`
trên `product_id`, rồi ghi **giá trị tuyệt đối** vào document danh mục cùng mốc
thời gian tính toán. Không có thao tác cộng trừ theo event nào chạm vào field này.

API duyệt danh mục trả kèm mốc thời gian đó để client biết số liệu tính lúc nào.

## Alternatives considered

- **Cộng trừ theo event.** Rẻ và gần realtime, nhưng không idempotent, không suy
  ra được từ snapshot event, và sai số tích lũy theo thời gian.
- **Aggregation ngay trong request duyệt danh mục.** Luôn đúng nhưng đắt và đặt
  một truy vấn nặng vào đường đi nóng nhất của trang chủ.
- **Cộng trừ kèm job đối soát định kỳ.** Có cả chi phí phức tạp lẫn chi phí job mà
  không thu được gì so với chỉ chạy job.

## Consequences

Tích cực: tự phục hồi hoàn toàn — mọi sai lệch biến mất sau chu kỳ kế tiếp; thao
tác idempotent; xóa được một nhánh xử lý phức tạp khỏi indexing worker; số đếm
luôn nhất quán với bộ filter mà API search dùng.

Tiêu cực: số đếm cũ tối đa bằng một chu kỳ job, nên vừa tạo sản phẩm thì sidebar
chưa đổi ngay. Đây là đánh đổi chấp nhận được cho một con số điều hướng.
`cardinality` là ước lượng nên vẫn có sai số nhỏ trên danh mục rất lớn.

## Migration impact

Bỏ toàn bộ `_update_by_query` cộng trừ trong indexing worker. Cần thêm job và cấu
hình chu kỳ chạy. Lần chạy đầu tiên tự sửa mọi sai lệch tích lũy từ thiết kế cũ.

## Verification

Test bắt buộc: chạy job hai lần liên tiếp cho cùng kết quả; product nhiều SKU chỉ
đếm một lần; danh mục rỗng về 0; job lỗi giữ nguyên số cũ thay vì ghi số sai; danh mục
cha đếm được sản phẩm chỉ gán ở danh mục con — test bắt buộc này là thứ phân biệt
`category_ancestor_ids` với `category_id` trong đính chính ở trên.
