---
doc_id: product-detail-bff-adr-002
service: product-detail-bff
status: accepted
date: 2026-09-07
decision_owners:
  - Storefront team
  - Review Service team
---

# ADR-002 — Compose Review read mà không sở hữu domain Review

## Status

`Accepted`.

## Context

PDP cần hiển thị rating aggregate và một số review mới nhất bên cạnh catalog, giá và
availability. Review Service lại sở hữu một bounded context ghi độc lập: eligibility
từ Order, nội dung/media, edit quota, report/moderation, privacy/takedown, aggregate,
outbox và contract snapshot cho Search. Đưa toàn bộ domain đó vào BFF sẽ biến một lớp
composition theo kênh giao diện thành owner của nghiệp vụ, PII và worker liên service.

## Decision

- Review Service tiếp tục là source of truth duy nhất cho review và rating aggregate.
- Sau khi Product trả catalog cùng `product_id`, BFF gọi internal Review PDP read để
  lấy aggregate và một preview hữu hạn. BFF không đọc database Review.
- Review là enrichment optional của PDP. Timeout/lỗi Review không làm mất catalog:
  BFF trả `review_section.status = UNAVAILABLE` và không giả dữ liệu lỗi thành rating 0.
- Trạng thái `AVAILABLE` với aggregate 0 mang nghĩa Product chưa có review.
- BFF không lưu review content/aggregate, không consume Review event và không nhận
  command tạo, sửa, report, reply hay takedown.
- Pagination đầy đủ và mọi command Review đi trực tiếp qua API Gateway tới Review.
- Search tiếp tục consume rating snapshot và gọi Review export khi full reindex;
  Search không phụ thuộc BFF cho dữ liệu Review.

## Alternatives considered

- **Gộp Review domain vào BFF.** Giảm một deployable nhưng không loại bỏ database,
  Kafka consumer, outbox, Debezium, scheduler, object storage và admin/compliance API;
  đồng thời làm Search phụ thuộc một adapter storefront.
- **BFF giữ full Review projection.** Loại live call nhưng nhân bản content, privacy
  và moderation state; recovery phức tạp hơn giá trị nhận được.
- **Dùng Search làm nguồn Review cho PDP.** Search chỉ giữ rating aggregate eventual
  và không sở hữu danh sách/nội dung Review.
- **Frontend luôn gọi Review riêng.** Giữ dependency khỏi BFF nhưng tạo waterfall sau
  khi resolve `product_id`; vẫn là lựa chọn hợp lệ cho pagination/lazy loading.

## Consequences

BFF có thêm một internal call sau Product và phải có timeout/bulkhead/degradation rõ
ràng. Đổi lại domain ownership, security boundary và dependency của Search không bị
đảo ngược. Review có thêm một read contract tối ưu cho PDP nhưng không đổi write model.

## Relationship to existing ADRs

Decision này không thay `review_revision` hay snapshot/export của Review ADR-004/006 và
Search ADR-015/016. Nó thay consumer topology cũ từng giả định Product giữ rating read
model cho PDP: hiện tại Search giữ event projection cho browse, còn BFF đọc Review theo
request cho Product Detail.

## Verification

- Contract test chứng minh BFF phân biệt Product chưa có review với Review unavailable.
- Review timeout/5xx không làm endpoint PDP trả 503 nếu Product vẫn khỏe.
- Không có bảng Review, Review consumer hoặc Review command handler trong BFF.
- Search vẫn chỉ nhận rating từ Review event/export, không gọi BFF.
