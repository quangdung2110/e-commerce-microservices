# ADR-025 — Request ID, trace ID and caller deadline in Payment logs

Status: Accepted — 2026-10-15. Applies [contracts ADR-002](../../../contracts/decisions/ADR-002-correlation-and-trace-propagation.md) and
[ADR-003](../../../contracts/decisions/ADR-003-deadline-propagation.md) to Payment as a receiver. Refines nothing in ADR-001 to ADR-024.

## Context

Payment controllers read `X-Correlation-Id` (required, 1–128 characters) and echo it. That header carries the
Temporal workflow ID Order sends and is part of the financial correlation of events; it is not a request ID.
Payment has no servlet filter and no `logging` configuration, so its log lines carry neither a request ID nor
a trace ID, and Payment cannot tell how long Order will wait for `initiate`, `cancel` or `refund`.

## Decision

1. Add `RequestCorrelationFilter` in `adapter/in/http`. It normalizes `X-Request-Id` (never a `400`),
   puts `request_id` and `trace_id` in the MDC and returns `X-Request-Id`. It does not read or modify the
   request body, so the Stripe webhook route keeps its exact bytes and signature check.
2. `X-Correlation-Id` handling in the controllers is unchanged: still required on the Order-facing
   operations, still echoed, still copied into financial events.
3. `logging.pattern.level` shows `[request_id,trace_id]`.
4. A request that outlives `X-Request-Timeout-Ms` increments
   `http.server.caller_deadline_exceeded{route}` and logs a WARN. Payment does not cancel a provider call
   because of the header: the outcome of a Stripe call that was started must be recorded, never abandoned
   (ADR-012, ADR-024). Order's 10 s read timeout stays 5 s under the 15 s mesh timeout.
5. The webhook route receives the same filter; Stripe does not send these headers, so Payment mints a UUID.

## Alternatives considered

- **Use `X-Correlation-Id` as the request ID.** Rejected: it is a workflow ID, shared by every request of the
  saga.
- **Pass the deadline to the Stripe client.** Rejected for this change: it would turn a slow provider into
  an unknown outcome. Needs its own ADR with the provider-timeout budget.

## Consequences

Order and Payment logs correlate by `request_id` when Order calls from a request thread, and by
`X-Correlation-Id` when Order calls from a Temporal activity. A WARN plus the counter now shows how often
Payment finishes after Order has stopped waiting; that number decides whether Payment's own deadline needs
tightening.

## Migration impact

Code: `RequestCorrelation` and `RequestCorrelationFilter` (new). Config: `application.yml` (`logging`).
No contract change.

## Verification

`RequestCorrelationTest` and `RequestCorrelationFilterTest` (same cases as the other services). Existing
controller tests and the payment ITs are unchanged.
