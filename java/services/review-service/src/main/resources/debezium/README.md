# Review outbox connector

`review-postgres-connector.json` is the Debezium configuration from
[ADR-003](../../../../../../docs/review/decisions/ADR-003-outbox-cdc-publication.md).
It is the only publisher of `t.review.events`; Review has no business Kafka producer.

Register it against the compose `kafka-connect` once PostgreSQL has the Review schema:

```bash
curl -X POST http://localhost:8084/connectors -H 'Content-Type: application/json' -d @java/services/review-service/src/main/resources/debezium/review-postgres-connector.json
```

`OutboxCdcIT` registers *this file* against a Debezium container and asserts every one of
the eight types reaches `t.review.events` unchanged, so a change here is checked rather
than assumed.

Four settings are load-bearing and must not be "tidied up":

- **`route.topic.replacement` is a literal.** `EventRouter` routes by `aggregate_type`
  unless told otherwise, which would split Review across several topics, break ordering
  per `product_id`, and leave Search and Notification listening to a topic nothing
  arrives on.
- **`table.expand.json.payload` is true.** `payload` already holds the complete
  CloudEvents envelope, and it must reach the wire verbatim rather than as an escaped
  JSON string. One topic carries eight different `data` shapes, so schema inference has
  to be exercised for all eight — nullable fields especially.
- **`table.include.list` names the parent table.** The publication was created with
  `publish_via_partition_root = true`, so listing individual monthly partitions would
  both duplicate and break as the scheduler rolls the window forward.
- **There is deliberately no `table.field.event.timestamp`.** `EventRouter` reads that
  field with `getInt64`, and `created_at` is a `TIMESTAMPTZ` that Debezium maps to a
  string in every precision mode because it carries a zone. Pointing the setting at it
  kills the task on its first publish with `Field 'created_at' is not of type INT64`. The
  business timestamp is in `payload.time`, which is what consumers read.

`publication.autocreate.mode` stays `disabled`: the publication belongs to the schema
migration, not to the connector.

Publication health is read from Kafka Connect status, the replication slot LSN, retained
WAL and end-to-end event age at the consumer. The number of old `review_outbox` rows is
**not** backlog — the table has no `published_at`, and retained rows that were published
correctly are old too.
