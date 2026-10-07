---
doc_id: search-adr-001
service: search
status: accepted
date: 2026-09-02
decision_owners:
  - Search Service team
supersedes: null
evidence:
  - docs/search/schema
---

# ADR-001 — Elasticsearch là store duy nhất của Search

## Context

Search phục vụ full-text tiếng Việt, filter đa chiều và facet cho toàn bộ traffic
browsing của buyer. PostgreSQL của Product không làm tốt các truy vấn này và cũng
không được phép nhận tải đọc của buyer, vì nó là write store của luồng quản trị
catalog và của point-read giao dịch.

## Decision

Search là read model thuần túy với Elasticsearch là store duy nhất. Search không
có PostgreSQL, không có Redis và không có cache tầng ứng dụng. Mọi dữ liệu trong
index đều là projection có thể tái tạo từ event và từ export API của Product.

Quyết định này bao gồm: không lưu trạng thái nghiệp vụ nào chỉ tồn tại ở Search,
và không thêm store thứ hai để “tăng tốc” trước khi chứng minh được filter cache
và request cache của Elasticsearch không đủ.

## Alternatives considered

- **Thêm Redis cache cho response.** Giảm tải đọc lặp, nhưng thêm một nguồn dữ
  liệu cũ thứ hai, thêm bài toán invalidation trên đúng loại dữ liệu vốn đã
  eventual, và che mất tín hiệu hiệu năng thật của cluster.
- **Đọc trực tiếp PostgreSQL của Product cho một số truy vấn đơn giản.** Vi phạm
  ranh giới service và đẩy tải buyer vào write store.
- **Giữ một bản sao quan hệ trong Search để reconcile.** Tăng chi phí vận hành và
  tạo nguồn chân lý thứ ba mà không giải quyết được vấn đề gốc.

## Consequences

Tích cực: kiến trúc đơn giản, không có bài toán nhất quán giữa hai store, phục hồi
chỉ có một con đường là reindex, và hiệu năng đọc quan sát được trực tiếp trên
cluster.

Tiêu cực: Elasticsearch là điểm hỏng đơn cho Search — mất cluster là mất toàn bộ
chức năng tìm kiếm, không có fallback. Điều này chấp nhận được vì không luồng giao
dịch nào phụ thuộc Search ([`SEARCH-INV-001`](../README.md#7-critical-invariants)).
Chi phí vận hành dồn vào việc chăm sóc cluster: dung lượng, shard sizing và cache.

## Migration impact

Không có migration; đây là mô tả trạng thái hiện hành. Ràng buộc đi kèm: mọi đề
xuất thêm store hoặc cache mới phải đi qua ADR mới thay thế ADR này.

## Verification

Kiểm chứng bằng: cấu hình runtime không có dependency PostgreSQL/Redis, và test
tích hợp chỉ cần Elasticsearch cùng Kafka để chạy toàn bộ luồng.
