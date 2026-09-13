# Promotion Events v1

Đây là nguồn chuẩn cho Kafka event do Promotion Service sở hữu. Event dùng CloudEvents
1.0 JSON structured; schema wire nằm trong [`schemas/`](schemas/) và topic/message nằm
trong [AsyncAPI](asyncapi.yaml). Contract đang `proposed`: Promotion Service chưa có
runtime, provider/consumer contract test chưa chạy.

## Phạm vi v1

| Hướng | Topic | Message | Đối tác |
|---|---|---|---|
| Phát | `t.promotion.markdown.events` | `com.ecommerce.promotion.product-markdown-snapshot.v1` | Product |
| Nhận | `t.product.events` | `com.ecommerce.catalog.product-deleted.v1` | Product |

Không có message nào khác ở v1. Cụ thể, Promotion **không** phát coupon saved/redeemed/
technical-cancelled, budget-exhausted, reservation, campaign lifecycle hay refund event,
và **không** consume Auth, Order hay Payment event. Lý do: chưa có consumer nào trong
repository đăng ký các fact đó; phát event không có consumer chỉ tạo chi phí topic/schema/
outbox mà không có giá trị. Khi một consumer thật xuất hiện, thêm message type mới là
thay đổi additive theo [`conventions/compatibility.md`](../../../conventions/compatibility.md).

Điều phối nội bộ của Promotion (Redis confirm finalizer, projection repair sau
`cancel-confirmed`, pending-claim rescue) chạy từ `promotion_command_receipts` và Redis
pending evidence, không đi qua Kafka và không thuộc contract này.

## Markdown snapshot

Kafka key là `product_id`; thứ tự chỉ được cam kết trong cùng `product_id`.
`sequence = data.source_revision` là ordering guard monotonic theo product; Product bỏ
snapshot có revision không lớn hơn projection hiện tại. Consumer deduplicate bằng CloudEvent
`id` (UUID, bằng `promotion_outbox_events.event_id`) và phải chịu được delivery trùng.

Hai state có shape tách biệt bằng `oneOf`:

- `ACTIVE`: bắt buộc `promotion_rule_id`, `discount`, `starts_at`, `ends_at`; không có
  `inactive_reason`.
- `INACTIVE`: bắt buộc `inactive_reason` (`NO_ACTIVE_RULE | EXPIRED | ADMIN_DISABLED`);
  không mang rule ID, discount hay interval.

`discount` dùng integer VND (`discount_bps`, `fixed_discount_vnd`, `max_discount_vnd`)
theo convention Product catalog consumer đã chốt tại Product ADR-016. Đây là khác biệt có
chủ ý so với `{amount_minor,currency}` của Promotion HTTP v1; đổi sang Money hoặc đổi
`promotion_rule_id` thành opaque string đều là breaking và phải đi v2.

Outbox dedup key là causal identity
`markdown:{product_id}:campaign:{campaign_id}:rev:{campaign_revision}:trigger:{START|END|PUBLISH|PAUSE|CANCEL}`;
resulting state không thuộc key nên retry cùng cause không thể phát cả `ACTIVE` lẫn
`INACTIVE`. Promotion không phát snapshot cho product đã có terminal tombstone watermark.

Fixture chuẩn:
[`promotion-product-markdown-snapshot.v1.json`](../../../examples/events/promotion-product-markdown-snapshot.v1.json)
và
[`promotion-product-markdown-inactive-snapshot.v1.json`](../../../examples/events/promotion-product-markdown-inactive-snapshot.v1.json).

## Product tombstone consumer

Promotion consume `product-deleted.v1` với guard:

- transaction acquire cùng PostgreSQL advisory lock theo `product_id` với mọi admin markdown
  mutation;
- upsert `catalog_product_tombstones` chỉ tiến theo `catalog_revision` lớn hơn, persist
  watermark kể cả khi chưa có rule;
- invalidate markdown rule có `source_product_revision IS NULL OR < watermark` với reason
  `PRODUCT_DELETED`;
- ack sau commit; không bump campaign revision/claim gate; không phát `INACTIVE` ngược lại
  Product đã xóa.

## Vị trí contract và shim

Schema markdown snapshot trước đây nằm tại
`contracts/events/product/v1/schemas/product-markdown-snapshot.schema.json`. File đó nay chỉ
là `$ref` sang schema ở thư mục này để `dataschema` URI cũ vẫn resolve; Product AsyncAPI
tham chiếu message qua `$ref` tới AsyncAPI của Promotion. Không sửa shim để thay nghĩa.

## Reliability và versioning

Mọi event được insert vào `promotion_outbox_events` trong cùng PostgreSQL transaction với
business state; Debezium relay at-least-once và sở hữu retry/backoff/DLQ. Không direct
produce sau commit. Thêm field optional là tương thích. Thay đổi breaking với payload, Kafka
key, ordering scope hoặc event type phải tạo major version mới và dual-publish nếu Product
chưa migrate.
