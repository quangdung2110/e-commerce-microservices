---
doc_id: product-adr-011
service: product
status: accepted
date: 2026-09-02
decision_owners:
  - Product Service team
supersedes:
  - ADR-004
evidence:
  - docs/product/architecture.md
  - docs/product/operations.md
  - docs/product/workflows.md
---

# ADR-011 — Markdown Consumer là background workload duy nhất

## Status

`Accepted`. Quyết định này thay thế ADR-004: vẫn giữ pessimistic locking và thứ tự
`Product → SKU`, nhưng loại bỏ các giả định inventory cronjob và Redis distributed lock
không còn phù hợp với boundary catalog-only của ADR-009.

## Context

Product chỉ sở hữu catalog và published selling price. Markdown là snapshot Product scope
do Promotion sở hữu rule và schedule. Thiết kế trước giữ scheduled safety check để tự
deactivate projection quá hạn, cùng retention job cho outbox. Điều đó thêm process và
đường ghi nền trong Product, trong khi Product đã cấm Redis, Inventory worker và cache.

Promotion đã là owner của khoảng hiệu lực markdown và có thể phát snapshot `ACTIVE` khi
bắt đầu, `INACTIVE` khi kết thúc. Vì vậy Product không cần tự làm scheduler thứ hai cho
cùng một rule. Outbox retention là data lifecycle vận hành, không phải business workflow.

## Decision

- Markdown Consumer là background workload nghiệp vụ duy nhất của Product. API/gRPC và
  consumer có thể scale thành process riêng; Product không chạy scheduled job.
- Promotion phát snapshot đầy đủ `ACTIVE` tại `starts_at` và `INACTIVE` tại `ends_at`.
  Nếu retry/DLQ làm Product stale, owner Promotion/operations phải replay snapshot mới
  nhất. Đây là trade-off được chấp nhận thay cho Product safety cron.
- Consumer chỉ ack Kafka sau transaction commit. Trong transaction, lock `products` bằng
  `FOR NO KEY UPDATE`, sau đó lock SKU liên quan bằng `FOR UPDATE`; dùng `lock_timeout`
  ngắn và retry/requeue lỗi tạm thời.
- `source_revision` là idempotency/order guard. Snapshot cũ hoặc trùng là no-op. Snapshot
  `ACTIVE` đến sau `ends_at` vẫn advance projection revision nhưng được lưu `INACTIVE` và
  không được hồi sinh giá cũ.
- Product không dùng Redis hoặc distributed lock. Không có job quét nên không cần cơ chế
  single-runner. Data retention của `product_outbox_events` do Platform/DB lifecycle sở
  hữu; Product không polling, publish trực tiếp hay tự xóa outbox.

## Alternatives considered

- **Giữ markdown safety check**: tự lành khi deactivation mất hoặc trễ, nhưng trùng
  ownership schedule với Promotion, thêm scheduler process và thêm write path vào Product.
- **Tính hết hạn động trên mỗi catalog read**: tránh cron, nhưng gRPC/Search snapshot có
  thể không cùng trạng thái giá và thay đổi published price ngoài transaction/outbox.
- **Dùng Redis distributed lock cho scheduler**: không còn hợp lệ vì Product không có
  Redis trong boundary hiện hành.
- **Product tự phát delayed event**: biến Product thành owner schedule thứ hai và tạo thêm
  state/retry path không cần thiết.

## Consequences

Tích cực: topology Product nhỏ hơn, chỉ còn một consumer ghi nền; trách nhiệm schedule
không bị chia đôi; không cần Redis hoặc process scheduler.

Tiêu cực: khi Promotion event bị trễ hoặc bị cô lập trong DLQ, `selling_price_vnd` có thể
stale cho tới khi snapshot được replay. Monitoring consumer lag, DLQ và runbook replay là
critical control, không phải best-effort.

## Migration impact

Gỡ/cấm mọi cấu hình scheduler, job lock và code path safety/retention khỏi deployment
Product khi implementation chuyển sang thiết kế này. Không có thay đổi HTTP/gRPC/Kafka
schema hay Flyway migration do quyết định này. Policy retention tương lai do Platform vận
hành ngoài Product application.

## Verification

- Không có scheduled bean/process Product được deploy; chỉ Markdown Consumer là workload
  nền nghiệp vụ.
- Test consumer cho commit-before-ack, replay, snapshot cũ/trùng, lock timeout retry và
  `ACTIVE` muộn sau `ends_at`.
- E2E test Promotion phát `INACTIVE` làm giá trở về `base_price_vnd` và phát SKU snapshot.
- Drill runbook: đưa event vào DLQ, replay snapshot mới nhất và xác nhận giá/Search phục
  hồi đúng revision.
