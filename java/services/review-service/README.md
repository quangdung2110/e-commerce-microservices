# Review Service

Verified-purchase reviews: entitlements, the public review lifecycle, media metadata,
community moderation, legal takedowns, privacy erasure and the product rating aggregate.

The design of record is [`docs/review`](../../../docs/review). This README says how the
code is arranged and why; it does not restate the design.

## Contracts

| Surface | Contract |
|---|---|
| Public, buyer and admin HTTP | [`contracts/http/review/v1/openapi.yaml`](../../../contracts/http/review/v1/openapi.yaml) |
| PDP summary and rating export | [`contracts/http/review/internal/v1/openapi.yaml`](../../../contracts/http/review/internal/v1/openapi.yaml) |
| Outbound events | [`contracts/events/review/v1/asyncapi.yaml`](../../../contracts/events/review/v1/asyncapi.yaml) |
| Inbound | Order [`ORDER_COMPLETED`](../../../contracts/events/order/v1/asyncapi.yaml), Auth [`USER_DELETED`](../../../contracts/events/auth/v1/asyncapi.yaml) |

## Layout

```
common/       error codes, RFC 9457 problem plumbing, keyset cursors, correlation context
config/       properties, Jackson, Kafka, S3, caller identity, readiness
controller/   the five HTTP surfaces and the exception handler
domain/       plain records and enums; no framework, no annotations
dto/          request and response bodies, one type per contract schema
messaging/    the three Kafka consumers and CloudEvents parsing
outbox/       CloudEvents envelope construction and the eight message types
privacy/      the two keyed derivations: author pseudonym and deletion-guard key
repository/   Spring JDBC; every statement the workflows specify, written out
scheduler/    the five-minute and daily passes
service/      command and query boundaries; transactions live here
storage/      the object-storage port and its S3-compatible adapter
```

## Three things worth knowing before changing anything

**Every outbound event goes through `review_outbox`.** There is no business Kafka
producer, and `ArchitectureTest` fails the build if one appears. `review_outbox.payload`
is a complete CloudEvents envelope that reaches the wire verbatim — Debezium only picks
topic, key and headers — so a missing `subject` or `dataschema` fails the domain
transaction rather than surfacing downstream (ADR-003).

**The privacy subject lock comes before the guard check, always.** Every path that can
persist a direct customer identifier takes `pg_advisory_xact_lock` on the subject first:
the Order consumer, the customer commands, the privacy consumer, and the
notification-writing branch of admin reply and takedown. Checking the guard without the
lock leaves exactly the race the lock exists to close (REVIEW-INV-012).

**No storage call happens inside a database transaction.** Object metadata is verified
before the create or edit transaction opens, and bytes are deleted only after the
transaction that recorded `DELETE_PENDING` has committed. An asset becomes `DELETED`
solely on a storage confirmation, and "object not found" is one of those confirmations
(ADR-011).

## Build and test

```bash
mvn verify
```

Unit tests run under Surefire, integration tests under Failsafe. The integration tests
start PostgreSQL, Kafka and MinIO through Testcontainers, so they need Docker but not
compose. An in-memory database would exercise none of the composite foreign key, partial
indexes, enums, partitioned outbox or triggers this design rests on.

Two tests exist to stop documentation and code drifting apart:

- `SchemaSyncTest` compares `V1__init_review_schema.sql` byte for byte with
  `docs/review/schema/0001_initial_review_schema.sql`.
- `ReviewEventFactoryTest` validates every one of the eight envelopes against the JSON
  Schema of its own type, loaded from `contracts/` rather than copied.

## Running locally

```bash
docker compose up -d postgres kafka kafka-connect minio review-service
curl -X POST http://localhost:8084/connectors -H 'Content-Type: application/json' \
  -d @java/services/review-service/src/main/resources/debezium/review-postgres-connector.json
```

The connector is not optional for the rating aggregate. Review publishes nothing
directly, so until Debezium is registered, lifecycle facts never reach
`t.review.events`, the self-consuming aggregator never runs, and every product keeps a
zero aggregate while reviews accumulate normally. Everything else — creating, editing,
reporting, moderating, taking down — works without it.
