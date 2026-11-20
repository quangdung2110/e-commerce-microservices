# Isolated Checkout → PAID baseline

Scope/SLO: [workload](../../docs/performance/checkout-payment-10000-workload.md). This harness measures the current system; it does not implement admission control or change service budgets.

Requirements: Docker Desktop with the repository's cached images and Maven/Go build dependencies, Node 24. Dedicated project `checkout-payment-bench`, networks, volumes and localhost ports 35432/38084/38085/38088/38091. No root `.env` or existing database is read. Existing Compose services are never stopped. Backends use an internal network; Gateway/provider and fixture access also use a private bridge for localhost access. The Payment test launcher accepts only the internal provider URL, and no real Stripe credentials are loaded. All identities and secrets are synthetic, in ignored `.generated/`.

```powershell
node loadtest/checkout-bench/prepare.mjs init
node loadtest/checkout-bench/prepare.mjs build
node loadtest/checkout-bench/prepare.mjs infra
node loadtest/checkout-bench/bootstrap.mjs
node loadtest/checkout-bench/seed.mjs 20000
node --test loadtest/checkout-bench/stats.test.mjs loadtest/checkout-bench/recovery.test.mjs loadtest/checkout-bench/preview.test.mjs
node loadtest/checkout-bench/run.mjs name=smoke count=5 rate=1 profile=machine
node loadtest/checkout-bench/reconcile.mjs smoke
node loadtest/checkout-bench/run.mjs name=machine-5 count=600 rate=5 profile=machine
node loadtest/checkout-bench/reconcile.mjs machine-5
node loadtest/checkout-bench/run.mjs name=journey-100 count=100 rate=0 profile=journey
node loadtest/checkout-bench/reconcile.mjs journey-100
node loadtest/checkout-bench/prepare.mjs stop
```

`rate=0` launches a simultaneous cohort. A positive rate is open-loop arrivals/s, count/rate determines the injection period. Every actor runs to PAID or a bounded failure, and continues observing post-payment convergence for up to 15s. All attempts are recorded, including 429/503/timeouts. Sessions reaching PAID after client deadline are separate from client success and must be reconciled. Raw requests contain stage/status/code/latency, never JWT or payment client secret.

Runs consume fresh fixture users using `.generated/cursor.json` because Order-paid events purge Cart. Pass `offset=N` only for unused users. Reusing an existing fixture with a live submission invalidates the workload. Prepare more fixtures before the cursor exhausts. Seed is for fresh isolated volumes; it is not a tool for resetting production or rerunning used carts.

Add `promotion=half` to exercise calculation, reservation and durable confirmation for 50% of buyers. The seed creates a published campaign, wallets and ample budget offline. Without that option, core profile sends no voucher. Fixture preparation may retry cold Product/Cart failures; those requests are outside all timed cohorts.

To grow a fixture pool without touching used carts, e.g. from 20k to 26k, run `node loadtest/checkout-bench/seed.mjs 26000 20001`. Default edge/auth memory limits are 256MiB each. For an explicitly separate resource diagnostic, set `BENCH_EDGE_MEMORY=1g` and `BENCH_AUTH_MEMORY=512m` before `prepare.mjs init`, then recreate only Gateway/gateway-auth. Image IDs, actual limits, OOM state and timing must be reported separately; this is not an application optimization.

Session/request logs are batched to reduce generator filesystem overhead. Journey rows record actual and planned think time separately; event-loop delay, offered-start lateness and full wall-clock journey remain evidence of generator limitations.

For large cohorts, use the Linux load generator on the direct Docker network to avoid the Windows published-port path:

```powershell
node loadtest/checkout-bench/container-run.mjs name=journey-10000-linux count=10000 rate=0 profile=journey promotion=half
node loadtest/checkout-bench/reconcile.mjs journey-10000-linux
```

The controller records the load-generator container's source IP, image, limits and exit state. It has 2 CPU/3GiB, mounts only this bench folder and calls Gateway/provider directly. Host monitoring stays separate. This removes one network/proxy confounder but still shares the physical host and one source IP; it is not the distributed certification gate.

`node loadtest/checkout-bench/repeat.mjs 2 240 3` runs three fresh Linux cohorts at 2 arrivals/s for 120s each, with reconciliation between them. Existing run names are rejected. `arrivals.mjs <run>` derives middleware invocation times from completion logs; this includes upstream queuing and is not a TCP arrival timestamp. `terminal-timing.mjs <run>` exports durable Order terminal timestamps. A delayed audit without unresolved work does not prove the five-minute drain gate.

Real components: Traefik production middleware, gateway-auth JWT verification and Redis revocation, Checkout, Cart, User address read, Product gRPC, Order/Temporal activities, Inventory, Payment, Stripe Java SDK, signed webhook → Payment Kafka inbox → transactional outbox → Debezium → Kafka → Order durable inbox/workflow, Inventory Commit. Synthetic: pre-created login identity/JWKS issuer, provider HTTP server and human delays. Payment is launched by a test-only main class selecting a primary StripeClient whose API base is the internal simulator. Production Java source/configuration is unchanged.

Provider delays (100/200/50ms) are hypotheses, not calibrated live Stripe latency. A shared-host run cannot certify production capacity or distributed 10k-user throughput. One host source IP can hit the production Gateway's generic per-IP rate limit on status reads; preserve and report 429 rather than silently removing the limiter. A distributed source/generator run is a separate certification gate. This harness uses protocol-level sessions, not browser/Stripe Elements or 3DS automation.

`results/<run>/` stores config, artifact hashes, image IDs and resource limits, full session/request samples, provider observations, Docker stats, service metrics, DB observations and reconciliation. Copy a reviewed, redacted evidence bundle into `docs/performance/` for published results. Keep setup pilots separate. A 10k burst peak alone does not meet the 30-minute steady concurrency gate.

`summarize-runs.mjs --compact` derives funnels and sampled resource peaks. `publish.mjs` verifies session/request accounting and scans data for credentials before exporting an allowlisted, compressed evidence bundle. It strips historical checkout tokens and refuses to replace an existing published manifest. Harness snapshots are code, not generated private keys. Preserve original audits before using `cleanup.mjs` to cancel synthetic unpaid orders through the Buyer API; cleanup is not a successful completion.

`node loadtest/checkout-bench/verify.mjs` verifies the published bundle's checksums, decompresses/parses samples, recomputes key statistics from all 15 runs and checks the report's local links without starting any service.

## Submit → PAID with a strict zero-error gate

Use `profile=submit` for this different timed scope. All Preview tokens are prepared at concurrency 2 before releasing Submit. Setup samples are in `setup-requests.jsonl`; they never enter measured requests or concurrency. Each timed session begins at Submit and ends at the first client-visible Order PAID, without synthetic card-entry delays. Every user has a fresh cart/token. Provider create/confirm/retrieve remain simulated at 100/200/50ms; internal services and financial/event processing are real.

```powershell
node loadtest/checkout-bench/container-run.mjs name=submit-burst-10 count=10 profile=submit promotion=half
node loadtest/checkout-bench/reconcile.mjs submit-burst-10
node loadtest/checkout-bench/container-run.mjs name=submit-steady-4 count=480 concurrency=4 duration=120000 profile=submit promotion=half
node loadtest/checkout-bench/reconcile.mjs submit-steady-4
node --test loadtest/checkout-bench/submit-stats.test.mjs loadtest/checkout-bench/server-counters.test.mjs
```

For steady tests, `count` is the preallocated fixture pool, while `sessions` is the actual number submitted. A slot starts a fresh user immediately after the preceding user sees PAID. Requests run to the 180s per-session deadline even after the injection window closes. `fixtureExhausted=true` invalidates a duration test. Concurrency uses half-open intervals, so a replacement at the exact PAID millisecond does not inflate peak CCU.

`strict.strictZeroErrors` requires all sessions PAID and zero measured HTTP >=400, network failures or unreadable responses, including errors recovered by retry. 202 pending is normal protocol. Reconciliation must pass too. The container controller releases the measurement barrier only after taking Java HTTP counter baselines; `server-errors.json` reports 5xx deltas across six Java services. Provider webhook error deltas must also be zero. This is observed zero errors over a defined test, not a guarantee of zero future failures.

`submit-suite.mjs <fresh-prefix> [warm-count]` searches simultaneous cohorts, refines the first failing bracket and runs three confirmations at a candidate. Its `*-suite.json` records every measured result, including failures. Initial cold-state observations must remain visible alongside warm-state tests; do not interpret the first failure as a monotonic capacity ceiling without repeat tests.
