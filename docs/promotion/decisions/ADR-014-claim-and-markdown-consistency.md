---
doc_id: promotion-adr-014
service: promotion
status: accepted
date: 2026-09-11
decision_owners:
  - Promotion Service team
  - Product Service team
supersedes:
  - ADR-004
  - ADR-010
  - ADR-011
evidence:
  - docs/promotion/data.md
  - docs/promotion/workflows.md
  - docs/product/workflows.md
  - contracts/events/product/v1/asyncapi.yaml
---

# ADR-014 — Claim boundary và markdown lifecycle nhất quán

## Status

`Accepted` ở mức thiết kế; chưa implemented. Supersede ADR-004, ADR-010 và ADR-011,
đồng thời giữ các quyết định còn hiệu lực về SPOP + pending rescue, campaign phase dẫn
xuất, Redis schedule tự phục hồi và transactional outbox/CDC.

Amended bởi [ADR-016](./ADR-016-event-scope-and-receipt-driven-finalization.md): markdown
snapshot contract chuyển sang `contracts/events/promotion/v1/` do Promotion sở hữu; không
có coupon fact hay Auth welcome-voucher consumer ở v1.

## Context

Claim kiểm tra ACTIVE trước Lua vẫn có thể `SPOP` sau `campaign.end_time`. Campaign
revision chỉ là một counter trên row nên không thể đại diện cho definition version bất
biến. Markdown outbox key thiếu campaign identity có thể collision. Snapshot `INACTIVE`
lại bắt buộc mang rule/discount dù không còn winner. Product phát tombstone cho Promotion
nhưng Promotion chưa có consumer, còn `Budget exhausted` được liệt kê như event dù chỉ là
Redis observation, không có PostgreSQL transition để outbox atomically.

## Decision

### Immutable definition và campaign lifecycle

- Campaign persist `DRAFT/PUBLISHED/PAUSED/CANCELLED`, `[start_time,end_time)` và
  `revision`; phase `UPCOMING/ACTIVE/ENDED` được dẫn xuất. Redis schedule giữ member có
  campaign revision, worker re-read DB/time, commit outbox trước ZREM, có DB fallback và
  startup rebuild.
- Sau publish, mọi field ảnh hưởng discount, eligibility, validity, product scope hoặc
  budget definition là bất biến. `campaigns.revision` chỉ là optimistic-concurrency,
  cache và schedule fence; nó không phải definition-version history.
- Muốn đổi definition phải clone thành campaign DRAFT mới với campaign/promotion/markdown
  ID mới. Wallet cũ tiếp tục bind promotion cũ và `expired_at` đã snapshot. Pause/cancel
  chỉ đóng claim mới/markdown, không thu hồi voucher đã lưu.

### Limited claim boundary

- Pre-generate claim rows và dùng SPOP + claimed-user + pending Hash/ZSET trên cùng Redis
  hash tag. Thêm `promo:{promotion_id}:claim_gate` chứa campaign revision, open/close epoch
  milliseconds và enabled flag.
- Claim Lua dùng Redis `TIME`, kiểm gate enabled, matching revision và
  `opens_at_ms <= now_ms < closes_at_ms` trước SPOP. Linearization point của claim success
  là Lua acquisition time; pending payload giữ `acquired_at_ms` và campaign revision.
- API hoặc pending worker persist claim/wallet theo acquisition evidence, không từ chối claim hợp lệ chỉ vì
  xử lý sau end time hoặc current campaign revision đã đổi do pause/cancel sau acquisition.
  Worker kiểm pending identity và revision-at-acquisition nhất quán, không yêu cầu revision
  đó bằng current revision. API chỉ trả success sau PostgreSQL commit; nếu chưa xác nhận
  trước HTTP deadline thì trả retryable pending. Sweeper retry cùng guarded DB path đến
  commit; không cần Kafka integration event cho acquisition. Redis loss rebuild pool/gate
  từ PostgreSQL. Redis/DB clock drift phải được đo và alert.
- Publish chỉ mở/rebuild gate sau DB commit. Pause/cancel đóng gate bảo thủ trước DB
  transaction; DB rollback thì rebuild từ truth. Crash có thể false-reject tạm thời nhưng
  không fail-open claim.

### Markdown event và Product deletion

- Mọi business event được insert vào append-only outbox cùng PostgreSQL business
  transaction; CDC publish at-least-once sau commit.
- Markdown partition/order scope là product. `source_revision` monotonic theo product dùng
  để bỏ stale event. Outbox `dedup_key` dùng causal identity đầy đủ gồm product, campaign,
  campaign revision và trigger; campaign revision đơn lẻ không global. Resulting state
  không nằm trong key, nên một cause chỉ có thể tạo tối đa một snapshot dù retry tính lại
  state sau khi một cause khác đã xen vào.
- Snapshot `ACTIVE` bắt buộc rule ID, discount và effective interval. Snapshot `INACTIVE`
  là tombstone bắt buộc reason nhưng không mang rule/discount/interval. Product luôn
  advance source revision và reset selling price khi apply tombstone.
- Promotion consume `com.ecommerce.catalog.product-deleted.v1`. Consumer và mọi admin
  markdown mutation serialize bằng cùng PostgreSQL advisory transaction lock theo product.
  Consumer persist terminal `catalog_product_tombstones` watermark dù chưa có rule, guard
  theo `catalog_revision`, đánh dấu markdown rule invalidated với reason `PRODUCT_DELETED`
  và invalidate product-scoped Redis cache sau commit; mutation reject nếu watermark đã có.
  Operational overlay không bump campaign revision hoặc claim gate vì voucher definition
  không đổi. Mutation là naturally idempotent; không dùng final-command receipt. Không phát
  `INACTIVE` ngược lại Product đã xóa.
- `BUDGET_EXHAUSTED` chỉ là synchronous result và telemetry/read-time signal. Không có
  `t.promotion.budget.events` khi chưa có durable PostgreSQL exhaustion transition.

## Alternatives considered

- Chỉ recheck campaign trước Lua: vẫn có TOCTOU ở exact boundary.
- Recheck worker theo processing time: có thể từ chối claim API đã báo success đúng hạn.
- Version table cho mọi published definition: linh hoạt nhưng tăng schema/join/migration;
  clone identity đủ cho yêu cầu hiện tại.
- Bắt `INACTIVE` mang rule cũ: không biểu diễn được product chưa từng có winner và trộn
  current state với historical metadata.
- Bỏ Product tombstone consumer: để dangling rule và scheduler/event retry vô ích.
- Phát budget-exhausted best-effort từ Redis: không có durable fact, dễ duplicate và sai
  khi reservation được release.

## Consequences

Claim không vượt hard end boundary theo Redis clock; API success luôn đã durable và worker
có acquisition evidence để cứu crash window.
Published wallet không bị đổi quyền lợi hồi tố. Markdown dedup không collision giữa
campaign, inactive tombstone có contract hợp lệ và Product deletion dọn logical reference.
Đổi lại cần claim gate/rebuild/clock monitoring, clone workflow, nullable inactive
projection trên Product và một Product-tombstone consumer ở Promotion.

## Migration impact

Thêm claim gate và payload fields; bổ sung invalidation columns cho `markdown_rules` cùng
terminal `catalog_product_tombstones` watermark.
Đổi markdown JSON Schema/fixture và Product projection constraint trước implementation;
nếu v1 đã production phải phát v2. Deploy Product consumer chấp nhận state-discriminated
snapshot trước Promotion producer. Sau đó deploy Promotion product-deleted consumer,
causal dedup writer và claim-gate Lua; rebuild gate/pool. Xóa budget topic khỏi catalog
tài liệu/config chỉ sau khi xác nhận không có consumer thực tế.

## Verification

Test claim tại end−1 ms/end/end+1 ms, stale gate revision, pause/cancel, Redis restart,
clock drift và worker chạy sau end. Test clone definition giữ nguyên wallet cũ. Test hai
campaign cùng revision/product/state, duplicate cùng cause và boundary khác nhau. Contract
test ACTIVE/INACTIVE; Product reset price và bỏ stale revision. Test duplicate/out-of-order
Product tombstone ở cả hai ordering với markdown mutation, no event loop, dangling-rule
cleanup và chứng minh không có budget topic.
