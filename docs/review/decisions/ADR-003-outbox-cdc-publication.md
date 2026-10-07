---
doc_id: review-adr-003
service: review
status: accepted
date: 2026-09-05
decision_owners:
  - Review Service team
supersedes: null
evidence:
  - docs/review/schema/0001_initial_review_schema.sql
---

# ADR-003 — Outbox và Debezium CDC cho việc phát event

## Decision

Mọi business event được insert vào `review_outbox` trong cùng transaction với domain
change. Debezium đọc chỉ bảng này qua publication và publish `t.review.events` với
Kafka key luôn là `product_id`. Topic chứa lifecycle facts, Search-facing rating
snapshots và đúng hai Notification request: first official reply và legal removal.
Reply/takedown chỉ ghi các request này khi eligibility owner không có HMAC deletion
guard; một command có guard vẫn commit domain change nhưng không ghi notification
outbox row. Hai request chỉ có review/product/recipient IDs; không chứa reply content,
legal case, reason hoặc admin identity.

Outbox là append-only CDC source, không phải queue có state theo row. Không có
`published_at` và không đếm row cũ để suy ra backlog. Publication health được đo qua
Kafka Connect, source LSN/replication slot, retained WAL và end-to-end event age.

Outbox partition theo tháng UTC. Initial schema tạo động tháng hiện tại và tháng sau;
scheduler duy trì rolling window. Cleanup chỉ `DROP PARTITION` khi connector/slot đã
đi qua partition và retention hết hạn.

## Envelope và routing

`review_outbox.payload` là **một CloudEvents 1.0 envelope hoàn chỉnh**, không phải
`data` trần. Debezium `EventRouter` chỉ chọn topic, key và header; nó không dựng
business envelope. Vì vậy envelope phải được application ghi trong chính transaction
nghiệp vụ, và row outbox là bản sao nguyên văn của message sẽ lên wire. Đây cũng là
convention Product Service đang chạy (`product_outbox_events.payload`).

Mọi attribute bắt buộc của `contracts/shared/json-schema/v1/cloud-event.schema.json`
đều nằm trong `payload`, nên outbox **không cần cột riêng** cho `subject`,
`dataschema`, `time`, `sequence`, `specversion` hay `datacontenttype`:

| CloudEvents attribute | Nguồn |
|---|---|
| `specversion` | Hằng `1.0` |
| `id` | `event_id`, UUID ngẫu nhiên do producer sinh, không tái sử dụng |
| `type` | `event_type`, ví dụ `com.ecommerce.review.created.v1` |
| `source` | Hằng `/services/review` |
| `subject` | `reviews/{review_id}` cho năm lifecycle fact và hai notification request; `product-rating-aggregates/{product_id}` cho `REVIEW_METRICS_UPDATED` |
| `time` | `now()` của transaction, cùng giá trị mà `created_at` nhận |
| `datacontenttype` | Hằng `application/json` |
| `dataschema` | Đường dẫn JSON Schema của đúng `type` đó |
| `sequence` | Chỉ có ở `REVIEW_METRICS_UPDATED`, bằng `data.review_revision` |
| `correlationid` | `correlation_id` khi request có, vắng mặt khi không |
| `data` | Body theo schema của `type` |

Các cột `event_id`, `event_type`, `partition_key`, `correlation_id` là **projection**
của envelope chứ không phải nguồn sự thật. `ck_outbox_columns_mirror_payload` giữ
chúng không bao giờ lệch khỏi `payload`, và `ck_outbox_payload_is_cloudevent` chặn
row nào không phải envelope hợp lệ. `partition_key = payload->'data'->>'product_id'`
biến quy tắc “Kafka key luôn là `product_id`” của ADR này thành ràng buộc database:
cả tám message type đều mang `data.product_id`.

`aggregate_type` chỉ còn hai giá trị và **không** dùng để route:

| `aggregate_type` | `aggregate_id` | Message type |
|---|---|---|
| `REVIEW` | `review_id` | Năm lifecycle fact và cả hai notification request |
| `REVIEW_METRIC` | `product_id` | `REVIEW_METRICS_UPDATED` |

`ADMIN_REPLY` bị bỏ: `REVIEW_REPLY_NOTIFICATION_REQUESTED` có subject
`reviews/{review_id}`, aggregate của nó là Review chứ không phải reply.

Toàn bộ outbox đi về **đúng một topic** `t.review.events`. Đây là điểm bắt buộc phải
cấu hình tường minh: `EventRouter` mặc định route theo `aggregate_type` và nếu để
mặc định thì Review sẽ ra nhiều topic, phá vỡ ordering theo `product_id` và làm
Search/Notification không tìm thấy message. Replacement là literal, không có
`${routedByValue}`:

```json
"transforms": "outbox",
"transforms.outbox.type": "io.debezium.transforms.outbox.EventRouter",
"transforms.outbox.table.field.event.id": "event_id",
"transforms.outbox.table.field.event.key": "partition_key",
"transforms.outbox.table.field.event.type": "event_type",
"transforms.outbox.table.field.event.payload": "payload",
"transforms.outbox.table.expand.json.payload": "true",
"transforms.outbox.table.fields.additional.placement":
    "event_type:header:ce_type,aggregate_type:header:ce_aggregate_type,aggregate_id:header:ce_aggregate_id",
"transforms.outbox.route.by.field": "event_type",
"transforms.outbox.route.topic.regex": "(?<routedByValue>.*)",
"transforms.outbox.route.topic.replacement": "t.review.events",

"key.converter": "org.apache.kafka.connect.storage.StringConverter",
"value.converter": "org.apache.kafka.connect.json.JsonConverter",
"value.converter.schemas.enable": "false",

"plugin.name": "pgoutput",
"publication.name": "review_outbox_publication",
"publication.autocreate.mode": "disabled",
"slot.name": "review_outbox_slot",
"table.include.list": "review.review_outbox",
"tombstones.on.delete": "false"
```

`table.include.list` trỏ tới bảng parent vì publication đã khai báo
`publish_via_partition_root = true`; connector không được liệt kê từng partition
tháng. `publication.autocreate.mode` là `disabled` vì publication do schema tạo.

Cấu hình **không** có `table.field.event.timestamp`. `EventRouter` đọc field đó bằng
`getInt64` để đặt timestamp của Kafka record, trong khi `created_at` là `TIMESTAMPTZ` và
Debezium map nó thành `io.debezium.time.ZonedTimestamp`, tức một string — ở cả hai
`time.precision.mode`, vì giá trị mang timezone. Khai báo nó làm connector task chết ngay
lần publish đầu tiên với `DataException: Field 'created_at' is not of type INT64`. Không
mất mát gì: thời điểm nghiệp vụ nằm ở `payload.time`, và structured mode quy định consumer
đọc envelope chứ không đọc record timestamp. Đổi lại, muốn giữ field này thì phải thêm một
cột epoch millis vào outbox — chi phí schema cho một giá trị không consumer nào dùng.

Structured mode (`application/cloudevents+json`, toàn bộ envelope nằm trong message
body) là dạng normative. Các header `ce_*` chỉ là bản sao để filter/debug rẻ tiền;
consumer **không** được đọc chúng thay cho envelope.

## Alternatives đã loại

- `io.debezium.converters.CloudEventsConverter`: sinh envelope *của Debezium*
  (`type` là `io.debezium.connector.postgresql.DataChangeEvent`, `source` là
  connector/database), không phải `com.ecommerce.review.created.v1` với
  `source=/services/review`. Nó mô tả change event của database, không mô tả
  business fact.
- Custom SMT dựng envelope lúc publish: envelope khi đó chỉ tồn tại ngoài database,
  nên không thể validate bằng contract test ở tầng schema, và thêm một thành phần
  phải deploy/nâng cấp cùng Connect.
- `payload` chỉ chứa `data`, còn attribute nằm ở các cột riêng: cần thêm cột cho
  `subject`/`dataschema`/`sequence`, và vẫn phải có SMT ghép lại — tức là gộp nhược
  điểm của cả hai hướng trên.

## Consequences

- Kafka/Connect failure không làm mất review đã commit; downstream chỉ trễ.
- Thiếu partition làm mọi command fail vì mọi command ghi outbox, nên là alert critical.
- PostgreSQL 14 không có global unique constraint trên partitioned parent ngoài
partition key. `event_id` vẫn là UUID CloudEvents do producer sinh và không tái dùng;
nếu cần database-enforced global key, phải đổi partition strategy sau này.
- Envelope bị lặp giữa `payload` và bốn cột projection. Đây là chi phí có chủ ý để
  Debezium route được mà không phải parse JSON; `ck_outbox_columns_mirror_payload`
  trả giá bằng một CHECK trên mỗi insert nhưng loại bỏ hoàn toàn khả năng lệch.
- Envelope là trách nhiệm của application. Ghi thiếu `subject`/`dataschema` không còn
  là bug phát hiện ở downstream mà làm fail chính transaction nghiệp vụ.
- `ck_outbox_payload_within_channel_limit` biến payload quá 16 KiB thành lỗi command
  chứ không phải lỗi publish. Với Review payload đều nhỏ và cố định nên đánh đổi này
  chấp nhận được; nếu sau này có event type body động, phải xem lại ngưỡng.
- `table.expand.json.payload=true` khiến Debezium suy diễn Connect schema từ JSON.
  Một topic mang tám `data` shape khác nhau nên schema inference phải được test cho
  cả tám type, đặc biệt là field nullable.

## Verification

Test domain transaction có đúng outbox, Kafka key đúng product, connector restart
không mất event và scheduler tạo partition đúng. Test một admin reply/takedown có
deletion guard vẫn commit domain change nhưng không ghi notification outbox row. Runbook
không được dùng “old outbox row count” làm publish backlog.

Riêng envelope: validate `payload` đã lưu bằng đúng JSON Schema của `type` đó cho cả
tám message type, khẳng định mọi row chỉ đi về `t.review.events`, và khẳng định
message value nhận được ở consumer bằng đúng `payload` trong database.
