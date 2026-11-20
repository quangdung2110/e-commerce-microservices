---
doc_id: search-adr-021
service: search
status: superseded
date: 2026-10-09
decision_owners:
  - Search Service team
supersedes: null
extends: ADR-011-tombstone-and-purge-window
superseded_by: ADR-022-post-implementation-audit-corrections
evidence:
  - java/services/search-service/src/main/resources/application.yml
  - java/services/search-service/src/main/java/com/ecommerce/search/config/SearchProperties.java
  - java/services/search-service/src/test/java/com/ecommerce/search/config/TombstoneProtectionWindowTest.java
  - docs/inventory/decisions/ADR-028-post-implementation-audit-corrections.md
  - contracts/events/inventory/v1/asyncapi.yaml
  - note/audit-2026-10-07-docs-code-sync.md
---

# ADR-021 — Cửa sổ bảo vệ tombstone là 35 ngày

> **Đã bị thay (2026-10-10).** [ADR-022](./ADR-022-post-implementation-audit-corrections.md) nâng cửa sổ lên 45 ngày vì Kafka
> xóa theo segment (record sống thêm tới `segment.ms`, mặc định 7 ngày, sau `retention.ms`). Quy tắc "cửa sổ dài hơn mọi thứ còn nằm trên
> broker" của ADR-011 và cách tính 30 ngày + biên ở dưới vẫn đúng; chỉ con số 35 ngày là sai. Nội dung gốc giữ nguyên bên dưới.

## Status

`Accepted`. Mở rộng [ADR-011](./ADR-011-tombstone-and-purge-window.md): ADR-011 giữ
nguyên quy tắc "cửa sổ bảo vệ dài hơn retention của mọi topic Search tiêu thụ"; ADR này
chốt con số và cách tính.

## Context

ADR-011 yêu cầu cửa sổ bảo vệ dài hơn retention của mọi topic Search tiêu thụ nhưng không
ghi số. Cấu hình `search.tombstone.protection-window` là `30d` và comment trong
`application.yml` ghi nhầm retention của các topic là 14d.

Retention thật theo contract AsyncAPI (`contracts/events/*/v1/asyncapi.yaml`, `x-retention`):

| Topic Search tiêu thụ | Retention |
|---|---|
| `t.product.events`, `t.category.events`, `t.catalog.control.events` | 14d |
| `t.review.events` | 14d |
| `t.inventory.stock.events.v1` | **30d**, sàn và trần (`retention.ms` đúng 30 ngày — Inventory [ADR-028](../../inventory/decisions/ADR-028-post-implementation-audit-corrections.md)) |

Với cửa sổ 30d bằng đúng retention của topic Inventory, một event tồn kho phát gần cuối
retention vẫn có thể được consume sau khi tombstone đã bị purge. Event availability
không bao giờ `scripted_upsert` (ADR-011), nên không tìm thấy document → không ACK
([ADR-019](./ADR-019-inventory-owned-sku-availability.md)) → partition kẹt. Cửa sổ phải
*dài hơn hẳn*, không chỉ bằng, retention.

## Decision

`search.tombstone.protection-window` = **35 ngày** = retention dài nhất của topic Search
tiêu thụ (30 ngày, `t.inventory.stock.events.v1`) cộng 5 ngày biên cho lệch đồng hồ giữa
`deleted_at` (thời điểm event Product) và thời điểm Search consume, backlog consumer, và
lịch purge hằng ngày (02:30).

- Giá trị mặc định trong `SearchProperties.Tombstone` và `application.yml` cùng là `35d`.
- Quy tắc bất biến: `protection-window > max(retention các topic Search tiêu thụ)`. Khi
  retention của bất kỳ topic nào được tăng, hoặc Search tiêu thụ thêm topic mới, phải nâng
  cửa sổ trong cùng thay đổi và cập nhật bảng trên.
- `TombstoneProtectionWindowTest` kiểm bất đẳng thức với retention dài nhất đã biết (30d)
  cho cả `application.yml` lẫn default của record.

Không thuộc quyết định này: cơ chế purge, gate revoke readiness của Inventory (ADR-019),
retention của bất kỳ topic nào (thuộc service sở hữu topic).

## Alternatives considered

- **Giữ 30d.** Bằng retention → có khoảng hở một event muộn nhất gặp document đã purge.
  Loại.
- **31d–32d.** Đúng bất đẳng thức nhưng biên không đủ cho lệch đồng hồ, backlog và chu kỳ
  purge hằng ngày. Loại.
- **Gỡ tombstone theo offset consumer thay vì thời gian.** Chính xác hơn nhưng cần theo
  dõi offset cho nhiều topic/partition và cộng thêm trạng thái; vượt phạm vi, giữ làm
  hướng cải tiến.

## Consequences

Tích cực: không còn khoảng hở giữa retention và purge; không partition kẹt vì event
Inventory đến muộn.

Tiêu cực: document đã xóa chiếm dung lượng Elasticsearch thêm 5 ngày so với 30d; mọi truy
vấn vẫn phải mang filter `is_deleted = false` lâu hơn. Cửa sổ phụ thuộc retention của topic
thuộc service khác nên phải được xem lại khi retention đổi.

## Migration impact

- Code: đổi default `35d` trong `SearchProperties.Tombstone` và `application.yml`; thêm
  `TombstoneProtectionWindowTest`.
- Dữ liệu: không migration. Tombstone đã tồn tại tự được giữ thêm 5 ngày vì cutoff tính
  theo `now - protection-window`; không tombstone nào bị purge sớm hơn trước.
- Contract: không đổi.
- Rollback: đưa giá trị về `30d` — không khuyến nghị vì quay lại khoảng hở.

## Verification

- `TombstoneProtectionWindowTest` (đọc `application.yml` và default của record).
- `TombstonePurgeIT`: tombstone quá hạn bị purge, tombstone trong cửa sổ không bị purge
  (dùng mốc tuổi 60 ngày, vẫn lớn hơn 35 ngày).
- Nguồn retention: `contracts/events/inventory/v1/asyncapi.yaml` (`x-retention: 30d`,
  `x-retention-bound: floor-and-ceiling`).
