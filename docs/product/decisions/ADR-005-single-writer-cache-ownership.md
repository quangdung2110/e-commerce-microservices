---
doc_id: product-adr-005
service: product
status: accepted
date: 2026-08-30
decision_owners:
  - Product Service team
supersedes: null
evidence: []
---

# ADR-005 — Một writer bất đồng bộ duy nhất cho point-read cache

## Status

`Accepted`

## Context

`product:sku:{sku_id}` là dữ liệu mà Cart, Order và Checkout đọc trên đường mua hàng.
Có rất nhiều nguồn có thể muốn cập nhật nó: admin sửa product hoặc SKU, Markdown Worker
tính giá mới, Phase 2 đổi trạng thái tồn kho, Phase 1 cập nhật số lượng, và bản thân
Event Handler khi tiêu thụ event.

Nếu nhiều nguồn cùng ghi bất đồng bộ, thứ tự ghi không xác định và một nguồn có thể ghi
đè kết quả mới hơn của nguồn khác — lỗi này rất khó tái hiện và rất dễ dẫn tới hiển thị
sai giá.

## Decision

Quyền ghi `product:sku:{sku_id}` được phân chia cố định:

| Nguồn | Được ghi | Không được ghi |
|---|---|---|
| API in-band (sau commit, trước response) | Toàn bộ snapshot cho các thay đổi cấp product và SKU do admin thực hiện | — |
| Event Handler Worker (`SKU_CREATED`, `SKU_UPDATED`) | Toàn bộ snapshot, đọc lại từ DB | — |
| Phase 1 Lua CAS | Chỉ field tồn kho: `display_quantity`, `sku_status`, `is_available`, `last_inventory_sync_at` | Field giá, `price_updated_at`, `product_status` |
| Markdown Worker | Chỉ `product:markdown:active:{sku_id}` | `product:sku:{sku_id}` |
| Phase 2 inventory | Không ghi cache | `product:sku:{sku_id}` |

Nói cách khác: **Event Handler là writer bất đồng bộ duy nhất** cho key này; Markdown
Worker và Phase 2 công bố thay đổi bằng cách phát `SKU_UPDATED` chứ không tự ghi cache.
Phase 1 là ngoại lệ có phạm vi hẹp và chỉ được chạm nhóm field tồn kho.

Kèm theo: invalidation là soft (`JSON.SET` đè giá trị mới, không `DEL` hàng loạt); mọi
lần ghi phải qua staleness guard theo mốc thời gian tương ứng; chống stampede bằng
distributed lock `product:lock:fetch:{sku_id}` cộng probabilistic early expiration.

## Alternatives considered

- **Cho mỗi nguồn tự ghi cache ngay sau khi ghi DB**: độ trễ thấp nhất, nhưng đúng là
  kịch bản double-write đã mô tả ở phần Context.
- **Chỉ dùng `DEL` để invalidate**: đơn giản và không bao giờ ghi đè sai, nhưng tạo
  cache stampede trên SKU hot và đẩy tải đột biến xuống PostgreSQL.
- **Bỏ hẳn refresh in-band, chỉ dựa vào Event Handler**: nhất quán tuyệt đối về
  ownership, nhưng admin vừa lưu xong đã đọc lại có thể thấy dữ liệu cũ (mất
  read-your-own-writes).

## Consequences

Tích cực: chỉ có một đường ghi bất đồng bộ nên thứ tự dễ suy luận; staleness guard đủ để
chặn mọi trường hợp ghi đè ngược; admin vẫn thấy ngay thay đổi của mình nhờ nhánh
in-band.

Tiêu cực: thay đổi từ Markdown và Phase 2 chỉ hiện lên cache sau khi đi vòng qua Kafka,
nên có thêm độ trễ; Event Handler trở thành thành phần trọng yếu — nó dừng thì cache
đứng yên cho tới khi TTL hết hạn; nhánh in-band tạo ra một đường ghi thứ hai mà mọi
người phải nhớ.

Ràng buộc mới: nhánh `SKU_CREATED`/`SKU_UPDATED` của Event Handler chỉ được đọc DB và
ghi Redis. Nếu nhánh này ghi DB, nó sẽ sinh outbox → Kafka → chính nó tiêu thụ lại, tạo
vòng lặp vô tận.

## Migration impact

Không thay đổi schema. Ảnh hưởng tới code của Markdown Worker và Phase 2 (bỏ mọi lệnh
ghi point-read cache) và tới Event Handler (trở thành nơi duy nhất xử lý refresh). Cần
metric tách riêng cho từng nguồn ghi để phát hiện vi phạm.

## Verification

Chứng minh bằng: test khẳng định Markdown Worker và Phase 2 không phát lệnh ghi lên key
point-read; test staleness guard với event đến trễ; test vòng lặp self-consume không xảy
ra; và metric cache hit ratio cùng số lần refresh theo nguồn.
