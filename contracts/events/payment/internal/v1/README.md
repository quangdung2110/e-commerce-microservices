# Payment internal verified Stripe webhook buffer

This contract is internal to Payment Service. `payment-api` sends one structured
CloudEvent to `t.payment.webhooks.raw`; `payment-worker` receives it in consumer
group `payment-webhook-processor`.

`payment-api` verifies HMAC and the 300-second recency window before producing. An
invalid or stale signature receives HTTP `400` and never enters this topic. The HTTP
body remains base64 encoded so decoding reproduces the exact verified bytes for
deterministic parsing and forensic evidence. `stripe_event_id` is canonical and is
the sole business deduplication identity in `webhook_logs`.

The message `x-example-file` points to
`contracts/examples/events/payment-stripe-webhook-received.v1.json`; its digest is
the SHA-256 of the decoded raw bytes, so the fixture also exercises byte fidelity.

The topic contains confidential verified provider data and is not an integration
contract for other services. After an inbox row exists, retries claim that row
directly with a lease; they do not publish the same delivery back to Kafka.
