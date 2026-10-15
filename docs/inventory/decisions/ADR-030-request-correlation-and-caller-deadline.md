# ADR-030 — Request ID, trace ID and caller deadline in Inventory logs

## Status

Accepted — 2026-10-15. Applies [contracts ADR-002](../../../contracts/decisions/ADR-002-correlation-and-trace-propagation.md) and
[ADR-003](../../../contracts/decisions/ADR-003-deadline-propagation.md) to Inventory as a receiver. No earlier Inventory ADR changes.

## Context

`IdentityFilter` sets `RequestContext` from `X-Correlation-ID` and `X-Causation-ID`. These are business
identifiers: the correlation ID is stored with commands and receipts and must stay exactly what Order and
Checkout sent. Inventory logs carry neither a request ID nor a trace ID, so a failing Reserve cannot be
matched with the Checkout or Order request that caused it. Inventory also does not know how long Order,
Checkout or the Product Detail BFF will wait for an answer.

## Decision

1. Add `RequestCorrelationFilter`, ordered before `IdentityFilter`. It normalizes `X-Request-Id` (invalid or
   missing becomes the trace ID of a valid `traceparent`, then a UUID; never a `400`), puts `request_id` and
   `trace_id` into the MDC, and returns `X-Request-Id`.
2. `RequestContext` is not touched. `X-Correlation-ID` remains the business flow ID stored with commands;
   `X-Request-Id` is only for logs and the response header. The two are independent and may differ.
3. `logging.pattern.level` shows `[request_id,trace_id]`.
4. A request that outlives `X-Request-Timeout-Ms` increments
   `http.server.caller_deadline_exceeded{route}` and logs a WARN. A command is never abandoned because of the
   header: Reserve, Commit and Release are durable and idempotent, and the caller replays the same command.

## Alternatives considered

- **Reuse `X-Correlation-ID` as the log request ID.** Rejected: Order sends a workflow ID that spans many
  requests, so it cannot identify one request.
- **Cancel an in-flight command at the caller's deadline.** Rejected: a half-applied stock movement is worse
  than finishing work nobody reads; ADR-003 rule 4.4.

## Consequences

Inventory logs correlate with callers by `request_id`. The new counter has one label whose values are route
templates. `/exports/**` calls from Search (25 s) and Order commands (8 s) show up per route.

## Migration impact

Code: `RequestCorrelation` and `RequestCorrelationFilter` (new). Config: `application.yml`
(`logging.pattern.level`). No contract change.

## Verification

`RequestCorrelationTest` and `RequestCorrelationFilterTest`: normalization, MDC cleanup after the request,
returned header, malformed deadline ignored, overrun counted.
