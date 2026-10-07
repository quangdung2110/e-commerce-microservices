# ADR-023 — Java/Spring Boot runtime on PostgreSQL with Debezium relay

## Status

Accepted — 2026-10-03. Fixes the implementation choices that ADR-011 and
[architecture.md](../architecture.md) left open. It does not change any domain rule.

## Context

The target design required "one relational primary", a transactional outbox, an
asynchronous availability projector and recovery gates, but left vendor, runtime and
relay mechanics open. The legacy Java implementation follows the replaced design and is
not reused, yet the service catalog still recorded it as implemented. Implementation needs a concrete stack that matches
the repository's operating model.

## Decision

| Concern | Choice |
|---|---|
| Language / build | Java 21, Maven |
| Framework | Spring Boot 3.x (Spring Web, Validation, Actuator); domain model and state guards in plain Java with no framework dependency |
| Authoritative store | PostgreSQL 16; Spring JDBC with explicit SQL for locked aggregates (`SELECT ... FOR UPDATE` in the documented lock order), `READ COMMITTED` |
| Schema migration | Flyway; migrations are the only DDL path |
| Outbox relay | Debezium/Kafka Connect captures `PublishedEvent` (ADR-022) and writes the full structured CloudEvent as Kafka value with the documented key |
| Broker | Kafka topics `t.inventory.stock.events.v1` and `t.inventory.order.events.v1` |
| Background work | Availability projector, warehouse fan-out, readiness publication and the export sequencer run as in-service workers that claim durable work with `FOR UPDATE SKIP LOCKED` leases; the sequencer is a single fenced leader |
| Cache | None in Release 1 |
| Tests | JUnit 5, property tests for bucket transitions, Testcontainers PostgreSQL/Kafka for integration and concurrency tests |
| Location | `java/services/inventory-service`, base package `com.ecommerce.inventory` |

Workers may be deployed as a separate process profile of the same artifact; they never
receive HTTP traffic and never bypass domain commands.

## Alternatives considered

- **Polling relay instead of Debezium.** Less local infrastructure, but a second relay
  mechanism beside Payment/Promotion's established Debezium connectors. Rejected.
- **Go.** Viable, but the repository's transactional/outbox services and their
  operational tooling are Java/Spring. Rejected for consistency.
- **JPA entities for StockPosition.** Implicit flush/locking makes the required lock
  order and conditional bucket updates harder to prove. Rejected for the write path;
  read-only queries may use any Spring data access.

## Consequences

- Local development requires PostgreSQL, Kafka and Kafka Connect, all already in the
  repository compose stack.
- The Debezium connector configuration becomes a versioned service artifact with
  contract tests for the emitted CloudEvent.
- RPO=0/RTO proofs target PostgreSQL synchronous replication or an equivalent
  platform option; that remains an operations gate.

## Migration impact

Update [development.md](../development.md), [architecture.md](../architecture.md) and
the service catalog entry (`runtime: java`, `status: documented`,
`contract_adoption: not_started`) plus the contract adoption table. No legacy code is reused; the legacy service directory is
removed separately.

## Verification

The first implementation milestone builds the skeleton with Flyway baseline, health
endpoints, Testcontainers integration test, and a Debezium connector smoke test that
publishes one sequenced example event matching its JSON Schema.
