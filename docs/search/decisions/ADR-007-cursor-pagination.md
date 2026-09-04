---
doc_id: search-adr-007
service: search
status: accepted
date: 2026-09-02
decision_owners:
  - Search Service team
supersedes: null
evidence: []
---

# ADR-007 — Cursor pagination bằng `search_after`

## Context

Phân trang theo offset trên Elasticsearch bị chặn bởi `index.max_result_window`
(mặc định 10.000) và có chi phí tăng tuyến tính theo độ sâu trang, vì mỗi shard
phải sắp xếp và bỏ đi toàn bộ phần đầu. Trang danh sách sản phẩm là nơi buyer cuộn
sâu nhất.

## Decision

Mọi API danh sách của Search dùng cursor pagination dựa trên `search_after`. Cursor
là chuỗi base64 mã hóa sort values của hit cuối cùng cộng với `sku_id`. Mọi sort
kết thúc bằng tie-break `sku_id asc` để cursor xác định duy nhất một vị trí.

Cursor gắn với bộ tham số đã sinh ra nó: đổi từ khóa, filter hoặc sort rồi gửi lại
cursor cũ là `INVALID_CURSOR`. Cursor là chuỗi đục; client không được tự sinh hoặc
tự diễn giải.

## Alternatives considered

- **Offset `page`/`size`.** Quen thuộc với client và cho phép nhảy tới trang bất
  kỳ, nhưng vỡ ở trang sâu và tốn tài nguyên cluster.
- **Point in time kèm `search_after`.** Cho ảnh chụp nhất quán xuyên suốt phiên
  phân trang, nhưng phải giữ tài nguyên PIT trên cluster và quản lý vòng đời của
  nó cho traffic buyer ẩn danh.
- **Scroll API.** Dành cho xuất dữ liệu lô, không dành cho truy vấn tương tác.

## Consequences

Tích cực: chi phí phân trang không tăng theo độ sâu; không có giới hạn 10.000; kết
quả không lặp và không bỏ sót đối với document không đổi.

Tiêu cực: không nhảy được tới trang N và không có tổng số trang; UI phải theo mô
hình “xem thêm” hoặc cuộn vô hạn. Vì không dùng PIT, document được index trong lúc
buyer đang phân trang vẫn có thể xuất hiện hoặc biến mất giữa hai trang. `total`
vẫn được trả bằng `track_total_hits`, và đây là chi phí có chủ ý cần theo dõi khi
catalog lớn lên.

## Migration impact

Client đang dùng offset phải chuyển sang cursor; đây là breaking change ở mức API
và phải đi kèm version path mới nếu còn client cũ.

## Verification

Test bắt buộc: đi hết nhiều trang không lặp và không bỏ sót; cursor hỏng bị từ
chối; cursor dùng lại sau khi đổi filter bị từ chối; mọi sort đều có tie-break.
