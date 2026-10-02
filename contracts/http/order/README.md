# Order HTTP contracts

- [`v1/openapi.yaml`](./v1/openapi.yaml) defines Buyer and Admin reads, lifecycle
  commands, payment-attempt retry, refund-case reads and asynchronous operation reads.
- [`internal/v1/openapi.yaml`](./internal/v1/openapi.yaml) defines Checkout submission
  and Finance/Ops reconciliation workloads.

All Order-owned numeric identifiers cross HTTP/JSON as canonical, unpadded base-10
strings. VND money uses `{amount_minor,currency}` and never floating point. Public
commands require `Idempotency-Key`; Checkout additionally requires that key to equal
`checkout_token`.

Cancellation, Buyer return and Admin pre-dispatch refund return `202` plus an operation
resource. Checkout submission and payment-attempt creation instead wait only until
Payment client data has been durably checkpointed, because their callers require that
data to continue Stripe confirmation; they do not wait for capture or terminal Order
state.

Both contracts are target/proposed. They become verified only after provider and
consumer contract tests run against an Order implementation.

Refund case `SUCCEEDED`/`FAILED` is the financial outcome; optional `scope_finalization`
reports Inventory closure progress separately. Buyer return operation stays running
or in manual review until the matching close/cancel receipt. Admin pre-dispatch has
`NOT_REQUIRED`. `CLOSED_INCOMPLETE` ends the existing Buyer return without payout after
approved incomplete close, or cancellation when no receipt occurred; master is not
`REFUNDED` and lifetime entitlement remains consumed.

Finance/Ops may submit `CLOSE_RETURN_WITHOUT_REFUND` with conditional `return_closure`
identities/reason and evidence. The 30-day timer does not itself close the scope or
authorize Payment. See [Order ADR-013](../../../docs/order/decisions/ADR-013-durable-refund-scope-finalization.md).
The response-enum expansion requires generated-client review before the proposed
contracts are adopted; no implementation is marked verified by this change.
