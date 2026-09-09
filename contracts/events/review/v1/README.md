# Review Events v1

[`asyncapi.yaml`](./asyncapi.yaml) is the wire contract for everything Review
publishes on `t.review.events`: five lifecycle facts, one Search-facing rating
snapshot and exactly two Notification requests. Message bodies are the JSON Schemas
under [`schemas/`](./schemas).

## Transport

Review has no Kafka producer in business code. Every message is written to
`review.review_outbox` in the same PostgreSQL transaction as its domain change and
published by Debezium CDC.

`review_outbox.payload` is a **complete CloudEvents 1.0 envelope**, not a bare `data`
object, and it reaches the wire verbatim. The Debezium `EventRouter` only selects
topic, key and headers — it never builds the business envelope. So `subject`,
`dataschema`, `time`, `sequence`, `specversion` and `datacontenttype` all come from
the payload, and the outbox has no column for them.

The columns `event_id`, `event_type`, `partition_key` and `correlation_id` are
projections of that envelope, kept in step by a database CHECK constraint. They exist
for routing and operator queries; the envelope is the source of truth.

## Topic and key

All eight message types share one topic, `t.review.events`, keyed by `product_id`, so
lifecycle order is preserved per product. The connector **must** pin a static
`route.topic.replacement`: `EventRouter` routes by `aggregate_type` unless told
otherwise, which would split Review across several topics and break that ordering.
`aggregate_type` is metadata only — `REVIEW` (`aggregate_id = review_id`, covering the
lifecycle facts and both notification requests) or `REVIEW_METRIC`
(`aggregate_id = product_id`).

The full connector configuration and the rejected alternatives are in
[ADR-003](../../../../docs/review/decisions/ADR-003-outbox-cdc-publication.md).

## Consumer rules

- Structured mode is normative: read the envelope from the message body. The `ce_*`
  Kafka headers are a convenience mirror for filtering and must never be read in
  place of the envelope.
- Dispatch on the exact `type`. ACK a known message outside your responsibility
  without creating an effect.
- Delivery is at-least-once; deduplicate on CloudEvents `id`.
- `REVIEW_METRICS_UPDATED` is ordered by `data.review_revision` (mirrored in
  `sequence`). Search applies it only when the incoming revision is strictly greater
  than its document revision.
- Both notification requests carry only `review_id`, `product_id` and
  `recipient_user_id` — never reply content, legal case, takedown reason or admin
  identity.
