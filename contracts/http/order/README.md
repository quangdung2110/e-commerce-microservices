# Order HTTP contracts

- [`v1/openapi.yaml`](./v1/openapi.yaml) defines Buyer and Admin reads, lifecycle
  commands, payment-attempt retry, refund-case reads and asynchronous operation reads.
- [`internal/v1/openapi.yaml`](./internal/v1/openapi.yaml) defines Checkout submission
  and Finance/Ops reconciliation workloads.

All Order-owned numeric identifiers cross HTTP/JSON as canonical, unpadded base-10
strings. VND money uses `{amount_minor,currency}` and never floating point. Public
commands require `Idempotency-Key`; Checkout additionally requires that key to equal
`checkout_token`.

Cancellation, Buyer return, Admin pre-dispatch refund and payment-attempt request return
`202` plus an operation resource. Payment-attempt creation is eligible only after
`FAILED`, before the immutable deadline and while both resource holds remain accepted;
the workflow rechecks those guards before it calls Payment. Checkout submission alone
waits until initial Payment client data is durably checkpointed because its caller needs
that data to continue Stripe confirmation; it does not wait for capture.

The open submission response uses server-side async waiters; committed DB checkpoint notifications
resume it without a Checkout callback. Request disconnect/instance loss does not undo durable
acceptance. Recovery always uses the same token; bounded response capacity can return retryable
`503 ORDER_UNAVAILABLE`. Schemas/status codes are unchanged; see
[Order ADR-027](../../../docs/order/decisions/ADR-027-async-submission-checkpoint-waiters.md).

Both contracts are `implemented` by [`java/services/order-service`](../../../java/services/order-service/README.md):
its MockMvc + Testcontainers + in-memory Temporal tests validate every response shape
against these OpenAPI component schemas. They become `verified` only after Checkout,
Gateway/frontend and Finance/Ops consumer tests exist. Since ADR-020 the Checkout item
carries optional `variant_name`, line arrays are capped at 100 (Inventory's Reserve limit),
Finance/Ops decisions require `X-Actor-Id`, and external payout remediation carries
`external_settlement`.

Refund case `SUCCEEDED`/`FAILED` is the financial outcome; optional `scope_finalization`
reports Inventory closure progress separately. Buyer return operation stays running
or in manual review until the matching close/cancel receipt. Admin pre-dispatch has
`NOT_REQUIRED`. `CLOSED_INCOMPLETE` ends the existing Buyer return without payout after
approved incomplete close, or cancellation when no receipt occurred; master is not
`REFUNDED` and lifetime entitlement remains consumed.
`EXTERNALLY_REMEDIATED` instead proves the full locked amount reached the customer
outside the original Payment refund. It may converge an open refund lane to master
`REFUNDED` while preserving provider-truth `payment_status=REFUND_FAILED`; it emits no
provider-refund fact or `ORDER_REFUNDED` notification.

Finance/Ops may submit `CLOSE_RETURN_WITHOUT_REFUND` with conditional `return_closure`
identities/reason and evidence. The 30-day timer does not itself close the scope or
authorize Payment. See [Order ADR-013](../../../docs/order/decisions/ADR-013-durable-refund-scope-finalization.md).
The response-enum expansion requires generated-client review before the proposed
contracts are adopted; no implementation is marked verified by this change.

Reconciliation is one per-order envelope with multiple typed obligations. Every decision
must include `obligation_id`; resolving one obligation never implicitly closes another.
External settlement also requires latest terminal Payment transaction/version evidence
and an exact customer/currency/amount match under the order/case lock.
